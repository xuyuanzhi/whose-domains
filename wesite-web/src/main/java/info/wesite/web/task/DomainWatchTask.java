package info.wesite.web.task;

import java.time.LocalDate;
import java.time.ZoneId;
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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.web.monitor.MonitorEventPublisher;
import info.wesite.web.monitor.MonitorState;

/**
 * Refreshes domain expiry data and routes successful checks through the unified
 * monitoring event pipeline. Email policy and SMTP delivery live downstream.
 */
@Profile({"prod", "mac"})
@Component
@EnableScheduling
public class DomainWatchTask {

    private static final Logger log = LoggerFactory.getLogger(DomainWatchTask.class);

    private final DomainWatchService domainWatchService;
    private final DomainService domainService;
    private final MonitorEventPublisher eventPublisher;

    @Autowired
    public DomainWatchTask(
        DomainWatchService domainWatchService,
        DomainService domainService,
        MonitorEventPublisher eventPublisher) {
        this.domainWatchService = domainWatchService;
        this.domainService = domainService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void checkDomainExpiry() {
        log.info("Domain expiry monitoring refresh started");
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
                        throw new IllegalStateException("Failed to persist refreshed domain watch " + watch.getId());
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
        log.info("Domain expiry monitoring refresh completed; updated={}", updated);
    }

    private RefreshResult refreshWatchInfo(DomainWatch watch) {
        if (watch.getLastCheckTime() != null
            && watch.getLastCheckTime().after(DateUtils.addHours(new Date(), -24))) {
            return RefreshResult.skipped();
        }

        Domain domain = watch.getDomainId() != null
            ? domainService.getById(watch.getDomainId())
            : domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, watch.getDomainName()));

        boolean changed = false;
        if (domain != null) {
            if (!java.util.Objects.equals(domain.getRegistrar(), watch.getRegistrar())
                && domain.getRegistrar() != null) {
                watch.setRegistrar(domain.getRegistrar());
                changed = true;
            }
            if (!java.util.Objects.equals(domain.getRegistExpiryDateText(), watch.getExpiryDateText())
                && domain.getRegistExpiryDateText() != null) {
                watch.setExpiryDateText(domain.getRegistExpiryDateText());
                watch.setExpiryDate(domain.getExpiryDate());
                changed = true;
            } else if (watch.getExpiryDate() == null && domain.getExpiryDate() != null) {
                watch.setExpiryDate(domain.getExpiryDate());
                changed = true;
            }
            if (watch.getDomainId() == null) {
                watch.setDomainId(domain.getId());
                changed = true;
            }
        }

        if (domain == null) {
            return new RefreshResult(true, false, changed, null);
        }
        return new RefreshResult(true, true, changed, monitorState(watch, domain));
    }

    private static MonitorState monitorState(DomainWatch watch, Domain domain) {
        return new MonitorState(
            watch.getDomainName(),
            statuses(domain.getDomainStatus()),
            expiryDate(watch),
            null,
            Map.of(),
            true,
            0);
    }

    private static LocalDate expiryDate(DomainWatch watch) {
        String value = watch.getExpiryDateText();
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
        return watch.getExpiryDate() == null
            ? null
            : watch.getExpiryDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
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
