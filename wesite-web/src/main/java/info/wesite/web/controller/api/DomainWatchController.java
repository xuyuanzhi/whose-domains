package info.wesite.web.controller.api;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.AccessControl.Level;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.monitor.NotificationEventMetadataMapper;
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
    private MonitorEventService monitorEventService;

    @Autowired
    private MonitorSnapshotService monitorSnapshotService;

    @Autowired
    private UserNotificationService notificationService;

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
        Set<String> watchIds = new HashSet<>();
        for (DomainWatch watch : watches) {
            watchIds.add(watch.getId());
        }
        List<MonitorEvent> events = monitorEventService.list(
                Wrappers.<MonitorEvent>lambdaQuery()
                        .in(MonitorEvent::getWatchId, watchIds)
                        .orderByDesc(MonitorEvent::getOccurredAt));
        Map<String, MonitorEvent> latestEvents = latestEvents(events, watchIds);
        Map<String, Date> latestSuccessfulChecks = latestSuccessfulChecks(watchIds);
        Map<String, Long> unreadCounts = unreadCounts(userId, events, watchIds);
        List<DomainWatchSummary> summaries = watches.stream()
                .map(watch -> summary(watch, latestEvents.get(watch.getId()),
                        unreadCounts.getOrDefault(watch.getId(), 0L),
                        latestSuccessfulChecks.get(watch.getId())))
                .toList();

        return ResponseJson.success(summaries);
    }

    private Map<String, MonitorEvent> latestEvents(List<MonitorEvent> events, Set<String> watchIds) {
        Map<String, MonitorEvent> latest = new HashMap<>();
        for (MonitorEvent event : events) {
            if (!watchIds.contains(event.getWatchId())) {
                continue;
            }
            MonitorEvent existing = latest.get(event.getWatchId());
            if (existing == null || isAfter(event.getOccurredAt(), existing.getOccurredAt())) {
                latest.put(event.getWatchId(), event);
            }
        }
        return latest;
    }

    private Map<String, Date> latestSuccessfulChecks(Set<String> watchIds) {
        List<MonitorSnapshot> snapshots = monitorSnapshotService.list(
                Wrappers.<MonitorSnapshot>lambdaQuery()
                        .in(MonitorSnapshot::getWatchId, watchIds)
                        .eq(MonitorSnapshot::getStatus, info.wesite.core.entity.BaseEntity.STATUS_ACTIVE)
                        .orderByDesc(MonitorSnapshot::getCheckedAt));
        Map<String, Date> latest = new HashMap<>();
        for (MonitorSnapshot snapshot : snapshots) {
            if (!watchIds.contains(snapshot.getWatchId())
                    || snapshot.getStatus() == null
                    || snapshot.getStatus() != info.wesite.core.entity.BaseEntity.STATUS_ACTIVE) {
                continue;
            }
            Date existing = latest.get(snapshot.getWatchId());
            if (existing == null || isAfter(snapshot.getCheckedAt(), existing)) {
                latest.put(snapshot.getWatchId(), snapshot.getCheckedAt());
            }
        }
        return latest;
    }

    private Map<String, Long> unreadCounts(String userId, List<MonitorEvent> events, Set<String> watchIds) {
        Map<String, MonitorEvent> eventsById = new HashMap<>();
        for (MonitorEvent event : events) {
            if (watchIds.contains(event.getWatchId()) && event.getId() != null) {
                eventsById.put(event.getId(), event);
            }
        }
        if (eventsById.isEmpty()) {
            return Map.of();
        }
        List<UserNotification> notifications = notificationService.list(
                Wrappers.<UserNotification>lambdaQuery()
                        .eq(UserNotification::getUserId, userId)
                        .isNull(UserNotification::getReadAt)
                        .in(UserNotification::getEventId, eventsById.keySet()));
        Map<String, Long> counts = new HashMap<>();
        for (UserNotification notification : notifications) {
            if (!userId.equals(notification.getUserId()) || notification.getReadAt() != null) {
                continue;
            }
            MonitorEvent event = eventsById.get(notification.getEventId());
            if (event != null) {
                counts.merge(event.getWatchId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static DomainWatchSummary summary(DomainWatch watch, MonitorEvent latestEvent,
            long unreadCount, Date lastSuccessfulCheck) {
        if (latestEvent == null) {
            return new DomainWatchSummary(watch, "UNKNOWN", unreadCount, lastSuccessfulCheck,
                    "No monitoring events yet");
        }
        return new DomainWatchSummary(watch, persistedRisk(latestEvent), unreadCount,
                lastSuccessfulCheck, eventSummary(latestEvent));
    }

    private static String persistedRisk(MonitorEvent event) {
        var risk = NotificationEventMetadataMapper.canonicalRisk(event);
        return risk == null ? "UNKNOWN" : risk.name();
    }

    private static String eventSummary(MonitorEvent event) {
        if (StringUtils.isBlank(event.getEventType())) {
            return "Monitoring event";
        }
        return StringUtils.isBlank(event.getNewValue())
                ? event.getEventType()
                : event.getEventType() + ": " + event.getNewValue();
    }

    private static boolean isAfter(Date candidate, Date existing) {
        return candidate != null && (existing == null || candidate.after(existing));
    }

    @Operation(summary = "添加域名监控")
    @PostMapping("/watch")
    public ResponseJson<DomainWatch> watchDomain(@RequestBody DomainWatch param) {
        String userId = UserHolder.get().getId();

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

        DomainWatch watch = new DomainWatch();
        watch.setId(RandomUtils.generateId());
        watch.setUserId(userId);
        watch.setDomainName(domainName);
        watch.setNotifyType(param.getNotifyType() != null ? param.getNotifyType() : DomainWatch.NOTIFY_BOTH);
        watch.setNotifyEmail(StringUtils.trimToNull(param.getNotifyEmail()));
        watch.setRemark(param.getRemark());
        watch.setCreateBy(userId);
        watch.setCreateTime(new Date());

        if (domain != null) {
            watch.setDomainId(domain.getId());
            watch.setRegistrar(domain.getRegistrar());
            watch.setExpiryDateText(domain.getRegistExpiryDateText());
            watch.setExpiryDate(domain.getExpiryDate());
        }

        watch.setLastCheckTime(new Date());

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
    public ResponseJson<String> unwatchDomain(@PathVariable("id") String id) {
        String userId = UserHolder.get().getId();

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
            return ResponseJson.success("Domain watch removed.", null);
        } else {
            return ResponseJson.failure("Failed to remove domain watch.");
        }
    }

    @Operation(summary = "更新域名监控设置")
    @PutMapping("/update/{id}")
    public ResponseJson<DomainWatch> updateWatch(@PathVariable("id") String id, @RequestBody DomainWatch param) {
        String userId = UserHolder.get().getId();

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
        if (param.getRemark() != null) {
            watch.setRemark(param.getRemark());
        }
        watch.setUpdateBy(userId);
        watch.setUpdateTime(new Date());

        if (domainWatchService.updateById(watch)) {
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
}
