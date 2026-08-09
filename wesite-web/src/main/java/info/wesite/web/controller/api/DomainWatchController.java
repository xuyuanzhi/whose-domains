package info.wesite.web.controller.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.AccessControl.Level;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.mapper.DomainWatchSummaryMapper;
import info.wesite.core.mapper.model.DomainWatchLatestCheckRow;
import info.wesite.core.mapper.model.DomainWatchLatestEventRow;
import info.wesite.core.mapper.model.DomainWatchUnreadCountRow;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.monitor.MonitorRisk;
import info.wesite.web.notification.NotificationEmailAddress;
import info.wesite.web.notification.NotificationCancellationService;
import info.wesite.web.notification.NotificationPolicyLock;
import info.wesite.web.retention.RetentionReportingClockConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Domain Watch API - 域名监控")
@RestController
@RequestMapping("/api/domain-watch")
@AccessControl(level = Level.SESSION)
public class DomainWatchController {

    private static final Logger log = LoggerFactory.getLogger(DomainWatchController.class);

    private static final int MAX_WATCH_PER_USER = 50;
    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";

    @Autowired
    private DomainWatchService domainWatchService;

    @Autowired
    private DomainService domainService;

    @Autowired
    private DomainWatchSummaryMapper domainWatchSummaryMapper;

    @Autowired
    private NotificationCancellationService cancellationService;

    @Autowired
    private NotificationPolicyLock policyLock;

    @Autowired
    @Qualifier(RetentionReportingClockConfiguration.BEAN_NAME)
    private Clock retentionReportingClock;

    @Operation(summary = "获取用户的域名监控列表")
    @GetMapping("/list")
    public ResponseJson<DomainWatchSummary> listWatches() {
        String userId = UserHolder.get().getId();

        List<DomainWatch> watches = domainWatchService.list(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE)
                        .orderByAsc(DomainWatch::getExpiryDate));
        if (watches.isEmpty()) {
            return ResponseJson.success(List.of());
        }
        List<String> watchIds = watches.stream().map(DomainWatch::getId).toList();
        Map<String, DomainWatchLatestEventRow> latestEvents = latestEvents(watchIds);
        Map<String, Date> latestSuccessfulChecks = latestSuccessfulChecks(watchIds);
        Map<String, Long> unreadCounts = unreadCounts(userId, watchIds);
        List<DomainWatchSummary> summaries = watches.stream()
                .map(watch -> summary(watch, latestEvents.get(watch.getId()),
                        unreadCounts.getOrDefault(watch.getId(), 0L),
                        latestSuccessfulChecks.get(watch.getId())))
                .toList();

        return ResponseJson.success(summaries);
    }

    private Map<String, DomainWatchLatestEventRow> latestEvents(List<String> watchIds) {
        Map<String, DomainWatchLatestEventRow> latest = new HashMap<>();
        for (DomainWatchLatestEventRow event : domainWatchSummaryMapper.selectLatestEvents(watchIds)) {
            latest.put(event.getWatchId(), event);
        }
        return latest;
    }

    private Map<String, Date> latestSuccessfulChecks(List<String> watchIds) {
        Map<String, Date> latest = new HashMap<>();
        for (DomainWatchLatestCheckRow check : domainWatchSummaryMapper.selectLatestSuccessfulChecks(watchIds)) {
            latest.put(check.getWatchId(), check.getLastSuccessfulCheck());
        }
        return latest;
    }

    private Map<String, Long> unreadCounts(String userId, List<String> watchIds) {
        Map<String, Long> counts = new HashMap<>();
        for (DomainWatchUnreadCountRow count : domainWatchSummaryMapper.selectUnreadCounts(userId, watchIds)) {
            counts.put(count.getWatchId(), count.getUnreadCount());
        }
        return counts;
    }

    private static DomainWatchSummary summary(DomainWatch watch, DomainWatchLatestEventRow latestEvent,
            long unreadCount, Date lastSuccessfulCheck) {
        if (latestEvent == null) {
            return new DomainWatchSummary(watch, "UNKNOWN", unreadCount, lastSuccessfulCheck,
                    "No monitoring events yet");
        }
        return new DomainWatchSummary(watch, persistedRisk(latestEvent), unreadCount,
                lastSuccessfulCheck, eventSummary(latestEvent));
    }

    private static String persistedRisk(DomainWatchLatestEventRow event) {
        try {
            return MonitorRisk.valueOf(event.getLatestEventRisk()).name();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return "UNKNOWN";
        }
    }

    private static String eventSummary(DomainWatchLatestEventRow event) {
        if (StringUtils.isBlank(event.getLatestEventType())) {
            return "Monitoring event";
        }
        return StringUtils.isBlank(event.getLatestEventValue())
                ? event.getLatestEventType()
                : event.getLatestEventType() + ": " + event.getLatestEventValue();
    }

    @Operation(summary = "添加域名监控")
    @PostMapping("/watch")
    public ResponseJson<DomainWatch> watchDomain(@RequestBody DomainWatch param) {
        String userId = UserHolder.get().getId();

        if (!validNotifyType(param.getNotifyType())) {
            return ResponseJson.failure("Invalid notification threshold selection.");
        }
        if (StringUtils.isNotBlank(param.getNotifyEmail())
                && !NotificationEmailAddress.isValid(param.getNotifyEmail())) {
            return ResponseJson.failure("Invalid notification email address.");
        }

        // 验证域名
        if (StringUtils.isBlank(param.getDomainName())) {
            return ResponseJson.failure("Domain name is required.");
        }

        String domainName = param.getDomainName().toLowerCase().trim();
        if (!domainName.matches(DOMAIN_REGEX)) {
            return ResponseJson.failure("Invalid domain name format.");
        }

        // 检查是否已存在
        DomainWatch existing = domainWatchService.getOne(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getDomainName, domainName));
        if (existing != null) {
            if (existing.getStatus() == DomainWatch.STATUS_ACTIVE) {
                return ResponseJson.success("Domain is already being monitored.", existing);
            } else {
                // 重新激活
                existing.setStatus(DomainWatch.STATUS_ACTIVE);
                existing.setNotifyType(param.getNotifyType() != null ? param.getNotifyType() : DomainWatch.NOTIFY_BOTH);
                existing.setNotifyEmail(NotificationEmailAddress.normalize(param.getNotifyEmail()).orElse(null));
                existing.setRemark(param.getRemark());
                existing.setUpdateBy(userId);
                existing.setUpdateTime(new Date());
                domainWatchService.updateById(existing);
                return ResponseJson.success("Domain watch reactivated.", existing);
            }
        }

        // 检查上限
        long count = domainWatchService.count(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE));
        if (count >= MAX_WATCH_PER_USER) {
            return ResponseJson.failure("You can watch up to " + MAX_WATCH_PER_USER + " domains.");
        }

        // 尝试从数据库获取域名信息
        Domain domain = domainService.getOne(
                Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));

        Instant createdAt = retentionReportingClock.instant();
        DomainWatch watch = new DomainWatch();
        watch.setId(RandomUtils.generateId());
        watch.setUserId(userId);
        watch.setWatchCreatedOn(createdAt.atZone(retentionReportingClock.getZone()).toLocalDate());
        watch.setDomainName(domainName);
        watch.setNotifyType(param.getNotifyType() != null ? param.getNotifyType() : DomainWatch.NOTIFY_BOTH);
        watch.setNotifyEmail(NotificationEmailAddress.normalize(param.getNotifyEmail()).orElse(null));
        watch.setRemark(param.getRemark());
        watch.setCreateBy(userId);
        watch.setCreateTime(Date.from(createdAt));

        if (domain != null) {
            watch.setDomainId(domain.getId());
            watch.setRegistrar(domain.getRegistrar());
            watch.setExpiryDateText(domain.getRegistExpiryDateText());
            watch.setExpiryDate(domain.getExpiryDate());
        }

        watch.setLastCheckTime(Date.from(createdAt));

        try {
            if (domainWatchService.save(watch)) {
                return ResponseJson.success("Domain watch added successfully.", watch);
            }
        } catch (DuplicateKeyException exception) {
            DomainWatch concurrentWinner = domainWatchService.getOne(
                    Wrappers.<DomainWatch>lambdaQuery()
                            .eq(DomainWatch::getUserId, userId)
                            .eq(DomainWatch::getDomainName, domainName));
            if (concurrentWinner != null && concurrentWinner.getStatus() == DomainWatch.STATUS_ACTIVE) {
                return ResponseJson.success("Domain is already being monitored.", concurrentWinner);
            }
            throw exception;
        }
        return ResponseJson.failure("Failed to add domain watch.");
    }

    @Operation(summary = "取消域名监控")
    @DeleteMapping("/unwatch/{id}")
    @Transactional
    public ResponseJson<String> unwatchDomain(@PathVariable("id") String id) {
        String userId = UserHolder.get().getId();
        policyLock.lockUser(userId);

        DomainWatch watch = domainWatchService.getOne(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getId, id)
                        .eq(DomainWatch::getUserId, userId));

        if (watch == null) {
            return ResponseJson.failure("Watch record not found.");
        }

        watch.setStatus(DomainWatch.STATUS_INACTIVE);
        watch.setUpdateBy(userId);
        watch.setUpdateTime(new Date());

        if (domainWatchService.updateById(watch)) {
            cancellationService.cancelForWatch(userId, watch.getId(), Instant.now());
            return ResponseJson.success("Domain watch removed.", null);
        } else {
            return ResponseJson.failure("Failed to remove domain watch.");
        }
    }

    @Operation(summary = "更新域名监控设置")
    @PutMapping("/update/{id}")
    @Transactional
    public ResponseJson<DomainWatch> updateWatch(@PathVariable("id") String id, @RequestBody DomainWatch param) {
        String userId = UserHolder.get().getId();
        policyLock.lockUser(userId);

        if (!validNotifyType(param.getNotifyType())) {
            return ResponseJson.failure("Invalid notification threshold selection.");
        }
        if (StringUtils.isNotBlank(param.getNotifyEmail())
                && !NotificationEmailAddress.isValid(param.getNotifyEmail())) {
            return ResponseJson.failure("Invalid notification email address.");
        }

        DomainWatch watch = domainWatchService.getOne(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getId, id)
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE));

        if (watch == null) {
            return ResponseJson.failure("Watch record not found.");
        }

        if (param.getNotifyType() != null) {
            watch.setNotifyType(param.getNotifyType());
        }
        watch.setNotifyEmail(NotificationEmailAddress.normalize(param.getNotifyEmail()).orElse(null));
        if (param.getRemark() != null) {
            watch.setRemark(param.getRemark());
        }
        watch.setUpdateBy(userId);
        watch.setUpdateTime(new Date());

        if (domainWatchService.updateById(watch)) {
            // A route or recipient edit is a delivery-policy boundary. Existing
            // unclaimed work keeps its in-app record but must not use stale settings.
            cancellationService.cancelForWatch(userId, watch.getId(), Instant.now());
            return ResponseJson.success("Domain watch updated.", watch);
        } else {
            return ResponseJson.failure("Failed to update domain watch.");
        }
    }

    @Operation(summary = "检查域名是否已被当前用户关注")
    @GetMapping("/check/{domainName}")
    public ResponseJson<Boolean> checkWatch(@PathVariable("domainName") String domainName) {
        String userId = UserHolder.get().getId();
        domainName = domainName.toLowerCase().trim();

        DomainWatch watch = domainWatchService.getOne(
                Wrappers.<DomainWatch>lambdaQuery()
                        .eq(DomainWatch::getUserId, userId)
                        .eq(DomainWatch::getDomainName, domainName)
                        .eq(DomainWatch::getStatus, DomainWatch.STATUS_ACTIVE));

        return ResponseJson.success(watch != null);
    }

    private static boolean validNotifyType(Integer notifyType) {
        return notifyType == null || notifyType == DomainWatch.NOTIFY_NONE
                || notifyType == DomainWatch.NOTIFY_7_DAYS
                || notifyType == DomainWatch.NOTIFY_30_DAYS
                || notifyType == DomainWatch.NOTIFY_BOTH;
    }
}
