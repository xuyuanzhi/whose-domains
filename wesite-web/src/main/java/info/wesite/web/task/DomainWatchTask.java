package info.wesite.web.task;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.mapper.DomainWatchMapper;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.web.monitor.DnsMonitorCollector;
import info.wesite.web.monitor.DomainMonitorCollector;
import info.wesite.web.monitor.MonitorCollectorResult;
import info.wesite.web.monitor.MonitorDeadline;
import info.wesite.web.monitor.MonitorEventPublisher;
import info.wesite.web.monitor.MonitorSnapshotObservation;
import info.wesite.web.monitor.MonitorState;
import info.wesite.web.monitor.SslMonitorCollector;
import info.wesite.web.monitor.WebsiteMonitorCollector;

/**
 * Runs the first-release monitoring probes and publishes one merged result per
 * due watch. A failed source never replaces its most recent successful value.
 */
@Profile({"prod", "mac"})
@Component
@EnableScheduling
public class DomainWatchTask {

    private static final Logger log = LoggerFactory.getLogger(DomainWatchTask.class);
    private static final Duration WATCH_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration COLLECTOR_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration SCAN_LEASE = Duration.ofMinutes(5);

    private final DomainWatchService domainWatchService;
    private final DomainService domainService;
    private final DomainWatchMapper domainWatchMapper;
    private final MonitorSnapshotService monitorSnapshotService;
    private final MonitorEventPublisher eventPublisher;
    private final DomainMonitorCollector domainCollector;
    private final DnsMonitorCollector dnsCollector;
    private final SslMonitorCollector sslCollector;
    private final WebsiteMonitorCollector websiteCollector;

    @Autowired
    public DomainWatchTask(
        DomainWatchService domainWatchService,
        DomainService domainService,
        DomainWatchMapper domainWatchMapper,
        MonitorSnapshotService monitorSnapshotService,
        MonitorEventPublisher eventPublisher,
        DomainMonitorCollector domainCollector,
        DnsMonitorCollector dnsCollector,
        SslMonitorCollector sslCollector,
        WebsiteMonitorCollector websiteCollector) {
        this.domainWatchService = domainWatchService;
        this.domainService = domainService;
        this.domainWatchMapper = domainWatchMapper;
        this.monitorSnapshotService = monitorSnapshotService;
        this.eventPublisher = eventPublisher;
        this.domainCollector = domainCollector;
        this.dnsCollector = dnsCollector;
        this.sslCollector = sslCollector;
        this.websiteCollector = websiteCollector;
    }

    DomainWatchTask(
        DomainWatchService domainWatchService,
        DomainService domainService,
        MonitorSnapshotService monitorSnapshotService,
        MonitorEventPublisher eventPublisher,
        DomainMonitorCollector domainCollector,
        DnsMonitorCollector dnsCollector,
        SslMonitorCollector sslCollector,
        WebsiteMonitorCollector websiteCollector) {
        this(domainWatchService, domainService, null, monitorSnapshotService, eventPublisher,
            domainCollector, dnsCollector, sslCollector, websiteCollector);
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void checkDomainExpiry() {
        log.info("Domain monitoring refresh started");
        int pageSize = 100;
        int updated = 0;
        String lastId = null;
        Map<String, ProbeBundle> probeCache = new HashMap<>();
        while (true) {
            java.util.List<DomainWatch> watches = domainWatchService.page(
                new Page<>(1, pageSize),
                Wrappers.<DomainWatch>lambdaQuery()
                    .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE)
                    .gt(StringUtils.isNotBlank(lastId), DomainWatch::getId, lastId)
                    .orderByAsc(DomainWatch::getId)).getRecords();
            if (watches == null || watches.isEmpty()) {
                break;
            }
            lastId = watches.get(watches.size() - 1).getId();

            for (DomainWatch watch : watches) {
                String claimToken = claimScan(watch);
                if (claimToken == null) {
                    continue;
                }
                try {
                    DomainWatch claimedWatch = reloadClaimedWatch(watch);
                    if (claimedWatch == null) {
                        releaseScan(watch, claimToken);
                        continue;
                    }
                    RefreshResult result = refreshWatchInfo(claimedWatch, probeCache);
                    if (!result.checked()) {
                        releaseScan(claimedWatch, claimToken);
                        continue;
                    }

                    eventPublisher.publish(
                        claimedWatch, result.state(), result.succeeded(), result.successfulSources());
                    Date checkedAt = new Date();
                    claimedWatch.setLastCheckTime(checkedAt);
                    claimedWatch.setUpdateTime(checkedAt);
                    if (!completeScan(claimedWatch, claimToken)) {
                        throw new IllegalStateException(
                            "Failed to persist refreshed domain watch " + claimedWatch.getId());
                    }
                    if (result.changed()) {
                        updated++;
                    }
                } catch (RuntimeException failure) {
                    releaseScan(watch, claimToken);
                    log.error("Failed to refresh monitored domain {}", watch.getDomainName(), failure);
                }
            }

            if (watches.size() < pageSize) {
                break;
            }
        }
        log.info("Domain monitoring refresh completed; updated={}", updated);
    }

    private String claimScan(DomainWatch watch) {
        if (domainWatchMapper == null) {
            return "test-no-db-lease";
        }
        Date now = new Date();
        String token = RandomUtils.generateId();
        return domainWatchMapper.claimScan(
            watch.getId(), token, Date.from(now.toInstant().plus(SCAN_LEASE)), now) == 1
            ? token
            : null;
    }

    private DomainWatch reloadClaimedWatch(DomainWatch pageValue) {
        if (domainWatchMapper == null) {
            return pageValue;
        }
        DomainWatch current = domainWatchService.getById(pageValue.getId());
        return current != null && Integer.valueOf(DomainWatch.STATUS_ACTIVE).equals(current.getStatus())
            ? current
            : null;
    }

    private boolean completeScan(DomainWatch watch, String claimToken) {
        return domainWatchMapper == null
            ? domainWatchService.updateById(watch)
            : domainWatchMapper.completeScan(watch, claimToken) == 1;
    }

    private void releaseScan(DomainWatch watch, String claimToken) {
        if (domainWatchMapper != null) {
            domainWatchMapper.releaseScan(watch.getId(), claimToken);
        }
    }

    private RefreshResult refreshWatchInfo(
        DomainWatch watch,
        Map<String, ProbeBundle> probeCache) {
        if (watch.getLastCheckTime() != null
            && watch.getLastCheckTime().after(DateUtils.addHours(new Date(), -24))
            && hasSuccessfulSnapshot(watch.getId())) {
            return RefreshResult.skipped();
        }

        PriorSnapshot prior = latestSuccessfulSnapshot(watch.getId());
        MonitorState previous = prior == null ? null : prior.state();
        String cacheKey = canonicalDomain(watch.getDomainName());
        ProbeBundle probes = probeCache.computeIfAbsent(cacheKey, ignored -> collectProbes(watch));
        Domain domain = probes.domain();
        MonitorCollectorResult domainResult = probes.domainResult();
        MonitorCollectorResult dnsResult = probes.dnsResult();
        MonitorCollectorResult sslResult = probes.sslResult();
        int previousWebsiteFailures = previous == null ? 0 : previous.websiteFailureCount();
        MonitorCollectorResult websiteResult = rebaseWebsite(
            probes.websiteResult(), previousWebsiteFailures);

        java.util.List<MonitorCollectorResult> results = java.util.List.of(
            domainResult, dnsResult, sslResult, websiteResult);
        results.stream()
            .filter(result -> !result.successful())
            .forEach(result -> log.warn(
                "Monitor source failed domain={} source={} kind={} reason={}",
                watch.getDomainName(),
                result.source(),
                result.failureKind(),
                result.failureMessage()));

        MonitorState base = previous == null ? fallbackState(watch, domain) : previous;
        MonitorState merged = merge(base, domainResult, dnsResult, sslResult, websiteResult);
        boolean anySucceeded = results.stream().anyMatch(MonitorCollectorResult::successful);
        boolean succeeded = anySucceeded;
        boolean changed = domainResult.successful() && applyDomainResult(watch, domain, merged);
        Set<MonitorCollectorResult.Source> successfulSources = successfulSources(results);
        return new RefreshResult(true, succeeded, changed, merged, successfulSources);
    }

    private ProbeBundle collectProbes(DomainWatch watch) {
        MonitorDeadline watchDeadline = MonitorDeadline.after(WATCH_TIMEOUT);
        Domain domain = findDomain(watch);
        MonitorCollectorResult domainResult = collectSafely(
            MonitorCollectorResult.Source.DOMAIN,
            () -> domainCollector.collect(domain, watchDeadline.bounded(COLLECTOR_TIMEOUT)));
        MonitorCollectorResult dnsResult = collectSafely(
            MonitorCollectorResult.Source.DNS,
            () -> dnsCollector.collect(
                watch.getDomainName(), watchDeadline.bounded(COLLECTOR_TIMEOUT)));
        MonitorCollectorResult sslResult = collectSafely(
            MonitorCollectorResult.Source.SSL,
            () -> sslCollector.collect(
                watch.getDomainName(), watchDeadline.bounded(COLLECTOR_TIMEOUT)));
        MonitorCollectorResult websiteResult = collectSafely(
            MonitorCollectorResult.Source.WEBSITE,
            () -> websiteCollector.collect(
                watch.getDomainName(), 0, watchDeadline.bounded(COLLECTOR_TIMEOUT)));
        return new ProbeBundle(
            domain, domainResult, dnsResult, sslResult, websiteResult);
    }

    private static MonitorCollectorResult collectSafely(
        MonitorCollectorResult.Source source,
        CollectorCall call) {
        try {
            MonitorCollectorResult result = call.collect();
            return result == null
                ? MonitorCollectorResult.failure(
                    source, MonitorCollectorResult.FailureKind.LOOKUP_ERROR,
                    "Monitor collector returned no result")
                : result;
        } catch (java.net.SocketTimeoutException timeout) {
            return MonitorCollectorResult.failure(
                source, MonitorCollectorResult.FailureKind.TIMEOUT,
                StringUtils.defaultIfBlank(timeout.getMessage(), "Monitoring deadline exceeded"));
        } catch (Exception failure) {
            return MonitorCollectorResult.failure(
                source, MonitorCollectorResult.FailureKind.LOOKUP_ERROR,
                StringUtils.defaultIfBlank(failure.getMessage(), failure.getClass().getSimpleName()));
        }
    }

    private Domain findDomain(DomainWatch watch) {
        Domain domain = watch.getDomainId() != null
            ? domainService.getById(watch.getDomainId())
            : domainService.getOne(
                Wrappers.<Domain>lambdaQuery().eq(Domain::getName, watch.getDomainName()));
        if (domain != null) {
            return domain;
        }

        Domain seed = new Domain();
        seed.setName(watch.getDomainName());
        seed.setRegistrar(watch.getRegistrar());
        seed.setRegistExpiryDateText(watch.getExpiryDateText());
        return seed;
    }

    private boolean hasSuccessfulSnapshot(String watchId) {
        return monitorSnapshotService.count(
            Wrappers.<MonitorSnapshot>lambdaQuery()
                .eq(MonitorSnapshot::getWatchId, watchId)
                .eq(MonitorSnapshot::getStatus, BaseEntity.STATUS_ACTIVE)) > 0;
    }

    private PriorSnapshot latestSuccessfulSnapshot(String watchId) {
        MonitorSnapshot snapshot = monitorSnapshotService.getOne(
            Wrappers.<MonitorSnapshot>lambdaQuery()
                .eq(MonitorSnapshot::getWatchId, watchId)
                .eq(MonitorSnapshot::getStatus, BaseEntity.STATUS_ACTIVE)
                .orderByDesc(MonitorSnapshot::getCheckedAt)
                .orderByDesc(MonitorSnapshot::getId)
                .last("LIMIT 1"));
        if (snapshot == null || StringUtils.isBlank(snapshot.getStateJson())) {
            return null;
        }
        return new PriorSnapshot(
            JSON.parseObject(snapshot.getStateJson(), MonitorState.class),
            MonitorSnapshotObservation.establishedSources(snapshot));
    }

    private static MonitorState merge(
        MonitorState base,
        MonitorCollectorResult domain,
        MonitorCollectorResult dns,
        MonitorCollectorResult ssl,
        MonitorCollectorResult website) {
        MonitorState dnsState = dns.successful() ? dns.state() : base;
        MonitorState sslState = ssl.successful() ? ssl.state() : base;
        MonitorState websiteState = website.successful() ? website.state() : base;
        Set<String> domainStatuses = base.domainStatuses();
        LocalDate domainExpiry = base.domainExpiry();
        if (domain.successful()) {
            if (!domain.state().domainStatuses().isEmpty()) {
                domainStatuses = domain.state().domainStatuses();
            }
            if (domain.state().domainExpiry() != null) {
                domainExpiry = domain.state().domainExpiry();
            }
        }
        return new MonitorState(
            base.domain(),
            domainStatuses,
            domainExpiry,
            sslState.sslExpiry(),
            dnsState.dnsRecords(),
            websiteState.websiteAvailable(),
            websiteState.websiteFailureCount());
    }

    private static MonitorCollectorResult rebaseWebsite(
        MonitorCollectorResult raw,
        int previousFailureCount) {
        if (!raw.successful()) {
            return raw;
        }
        boolean available = raw.state().websiteAvailable();
        int normalized = Math.max(0, previousFailureCount);
        int count = available
            ? 0
            : normalized == Integer.MAX_VALUE ? normalized : normalized + 1;
        return MonitorCollectorResult.success(
            MonitorCollectorResult.Source.WEBSITE,
            new MonitorState(
                raw.state().domain(), Set.of(), null, null, Map.of(), available, count));
    }

    private static Set<MonitorCollectorResult.Source> successfulSources(
        List<MonitorCollectorResult> results) {
        EnumSet<MonitorCollectorResult.Source> successful = EnumSet.noneOf(
            MonitorCollectorResult.Source.class);
        results.stream()
            .filter(MonitorCollectorResult::successful)
            .map(MonitorCollectorResult::source)
            .forEach(successful::add);
        return Set.copyOf(successful);
    }

    private static String canonicalDomain(String value) {
        String result = StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static MonitorState fallbackState(DomainWatch watch, Domain domain) {
        return new MonitorState(
            watch.getDomainName(),
            domain == null ? Set.of() : statuses(domain.getDomainStatus()),
            expiryDate(watch, domain),
            null,
            Map.of(),
            true,
            0);
    }

    private static boolean applyDomainResult(
        DomainWatch watch,
        Domain domain,
        MonitorState merged) {
        boolean changed = false;
        if (domain != null && StringUtils.isNotBlank(domain.getRegistrar())
            && !java.util.Objects.equals(domain.getRegistrar(), watch.getRegistrar())) {
            watch.setRegistrar(domain.getRegistrar());
            changed = true;
        }
        if (domain != null && StringUtils.isNotBlank(domain.getRegistExpiryDateText())
            && !java.util.Objects.equals(domain.getRegistExpiryDateText(), watch.getExpiryDateText())) {
            watch.setExpiryDateText(domain.getRegistExpiryDateText());
            watch.setExpiryDate(domain.getExpiryDate());
            changed = true;
        } else if (merged.domainExpiry() != null && watch.getExpiryDate() == null) {
            watch.setExpiryDate(Date.from(
                merged.domainExpiry().atStartOfDay().toInstant(ZoneOffset.UTC)));
            changed = true;
        }
        if (domain != null && watch.getDomainId() == null && domain.getId() != null) {
            watch.setDomainId(domain.getId());
            changed = true;
        }
        return changed;
    }

    private static LocalDate expiryDate(DomainWatch watch, Domain domain) {
        String value = watch.getExpiryDateText();
        if (StringUtils.isBlank(value) && domain != null) {
            value = domain.getRegistExpiryDateText();
        }
        if (StringUtils.isNotBlank(value)) {
            try {
                if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
                    return LocalDate.parse(value.substring(0, 10));
                }
                if (value.length() >= 8 && value.substring(0, 8).chars().allMatch(Character::isDigit)) {
                    return LocalDate.parse(value.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
                }
            } catch (DateTimeParseException ignored) {
                // Fall through to the already parsed legacy Date value.
            }
        }
        Date parsed = watch.getExpiryDate();
        if (parsed == null && domain != null) {
            parsed = domain.getExpiryDate();
        }
        return parsed == null
            ? null
            : parsed.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static Set<String> statuses(String rawStatuses) {
        if (StringUtils.isBlank(rawStatuses)) {
            return Set.of();
        }
        return Arrays.stream(rawStatuses.split("[,\\s]+"))
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toUnmodifiableSet());
    }

    @FunctionalInterface
    private interface CollectorCall {
        MonitorCollectorResult collect() throws Exception;
    }

    private record PriorSnapshot(
        MonitorState state,
        Set<MonitorCollectorResult.Source> establishedSources) {
    }

    private record ProbeBundle(
        Domain domain,
        MonitorCollectorResult domainResult,
        MonitorCollectorResult dnsResult,
        MonitorCollectorResult sslResult,
        MonitorCollectorResult websiteResult) {
    }

    private record RefreshResult(
        boolean checked,
        boolean succeeded,
        boolean changed,
        MonitorState state,
        Set<MonitorCollectorResult.Source> successfulSources) {
        private static RefreshResult skipped() {
            return new RefreshResult(false, false, false, null, Set.of());
        }
    }
}
