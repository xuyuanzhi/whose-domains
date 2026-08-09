package info.wesite.web.task;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Date;
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
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.web.monitor.DnsMonitorCollector;
import info.wesite.web.monitor.DomainMonitorCollector;
import info.wesite.web.monitor.MonitorCollectorResult;
import info.wesite.web.monitor.MonitorEventPublisher;
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

    private final DomainWatchService domainWatchService;
    private final DomainService domainService;
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
        MonitorSnapshotService monitorSnapshotService,
        MonitorEventPublisher eventPublisher,
        DomainMonitorCollector domainCollector,
        DnsMonitorCollector dnsCollector,
        SslMonitorCollector sslCollector,
        WebsiteMonitorCollector websiteCollector) {
        this.domainWatchService = domainWatchService;
        this.domainService = domainService;
        this.monitorSnapshotService = monitorSnapshotService;
        this.eventPublisher = eventPublisher;
        this.domainCollector = domainCollector;
        this.dnsCollector = dnsCollector;
        this.sslCollector = sslCollector;
        this.websiteCollector = websiteCollector;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void checkDomainExpiry() {
        log.info("Domain monitoring refresh started");
        int pageNumber = 1;
        int pageSize = 100;
        int updated = 0;
        while (true) {
            java.util.List<DomainWatch> watches = domainWatchService.page(
                new Page<>(pageNumber, pageSize),
                Wrappers.<DomainWatch>lambdaQuery()
                    .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE)
                    .orderByAsc(DomainWatch::getId)).getRecords();
            if (watches == null || watches.isEmpty()) {
                break;
            }

            for (DomainWatch watch : watches) {
                try {
                    RefreshResult result = refreshWatchInfo(watch);
                    if (!result.checked()) {
                        continue;
                    }

                    eventPublisher.publish(watch, result.state(), result.succeeded());
                    Date checkedAt = new Date();
                    watch.setLastCheckTime(checkedAt);
                    watch.setUpdateTime(checkedAt);
                    if (!domainWatchService.updateById(watch)) {
                        throw new IllegalStateException(
                            "Failed to persist refreshed domain watch " + watch.getId());
                    }
                    if (result.changed()) {
                        updated++;
                    }
                } catch (RuntimeException failure) {
                    log.error("Failed to refresh monitored domain {}", watch.getDomainName(), failure);
                }
            }

            if (watches.size() < pageSize) {
                break;
            }
            pageNumber++;
        }
        log.info("Domain monitoring refresh completed; updated={}", updated);
    }

    private RefreshResult refreshWatchInfo(DomainWatch watch) {
        if (watch.getLastCheckTime() != null
            && watch.getLastCheckTime().after(DateUtils.addHours(new Date(), -24))
            && hasSuccessfulSnapshot(watch.getId())) {
            return RefreshResult.skipped();
        }

        MonitorState previous = latestSuccessfulState(watch.getId());
        Domain domain = findDomain(watch);
        MonitorCollectorResult domainResult = domainCollector.collect(domain);
        MonitorCollectorResult dnsResult = dnsCollector.collect(watch.getDomainName());
        MonitorCollectorResult sslResult = sslCollector.collect(watch.getDomainName());
        int previousWebsiteFailures = previous == null ? 0 : previous.websiteFailureCount();
        MonitorCollectorResult websiteResult = websiteCollector.collect(
            watch.getDomainName(), previousWebsiteFailures);

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
        boolean allSucceeded = results.stream().allMatch(MonitorCollectorResult::successful);
        boolean anySucceeded = results.stream().anyMatch(MonitorCollectorResult::successful);
        boolean succeeded = previous == null ? allSucceeded : anySucceeded;
        boolean changed = domainResult.successful() && applyDomainResult(watch, domain, merged);
        return new RefreshResult(true, succeeded, changed, merged);
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

    private MonitorState latestSuccessfulState(String watchId) {
        MonitorSnapshot snapshot = monitorSnapshotService.getOne(
            Wrappers.<MonitorSnapshot>lambdaQuery()
                .eq(MonitorSnapshot::getWatchId, watchId)
                .eq(MonitorSnapshot::getStatus, BaseEntity.STATUS_ACTIVE)
                .orderByDesc(MonitorSnapshot::getCheckedAt)
                .last("LIMIT 1"));
        if (snapshot == null || StringUtils.isBlank(snapshot.getStateJson())) {
            return null;
        }
        return JSON.parseObject(snapshot.getStateJson(), MonitorState.class);
    }

    private static MonitorState merge(
        MonitorState base,
        MonitorCollectorResult domain,
        MonitorCollectorResult dns,
        MonitorCollectorResult ssl,
        MonitorCollectorResult website) {
        MonitorState domainState = domain.successful() ? domain.state() : base;
        MonitorState dnsState = dns.successful() ? dns.state() : base;
        MonitorState sslState = ssl.successful() ? ssl.state() : base;
        MonitorState websiteState = website.successful() ? website.state() : base;
        return new MonitorState(
            base.domain(),
            domainState.domainStatuses(),
            domainState.domainExpiry(),
            sslState.sslExpiry(),
            dnsState.dnsRecords(),
            websiteState.websiteAvailable(),
            websiteState.websiteFailureCount());
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

    private record RefreshResult(boolean checked, boolean succeeded, boolean changed, MonitorState state) {
        private static RefreshResult skipped() {
            return new RefreshResult(false, false, false, null);
        }
    }
}
