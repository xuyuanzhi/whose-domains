package info.wesite.web.monitor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.utils.RandomUtils;

/**
 * Persists monitoring snapshots and turns changes between successful checks into
 * idempotent events and in-app notifications.
 */
@Service
public class MonitorEventPublisher {

    private static final String EMAIL_PENDING = "pending";

    private final MonitorSnapshotService snapshotService;
    private final MonitorEventService eventService;
    private final UserNotificationService notificationService;
    private final MonitorChangeDetector detector;
    private final Clock clock;

    @Autowired
    public MonitorEventPublisher(
        MonitorSnapshotService snapshotService,
        MonitorEventService eventService,
        UserNotificationService notificationService) {
        this(snapshotService, eventService, notificationService, Clock.systemUTC());
    }

    private MonitorEventPublisher(
        MonitorSnapshotService snapshotService,
        MonitorEventService eventService,
        UserNotificationService notificationService,
        Clock clock) {
        this(snapshotService, eventService, notificationService, new MonitorChangeDetector(clock), clock);
    }

    MonitorEventPublisher(
        MonitorSnapshotService snapshotService,
        MonitorEventService eventService,
        UserNotificationService notificationService,
        MonitorChangeDetector detector,
        Clock clock) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.eventService = Objects.requireNonNull(eventService, "eventService");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public List<MonitorEvent> publish(DomainWatch watch, MonitorState current, boolean checkSucceeded) {
        Objects.requireNonNull(watch, "watch");
        Date checkedAt = Date.from(clock.instant());

        if (!checkSucceeded) {
            snapshotService.save(snapshot(watch.getId(), current, checkedAt, BaseEntity.STATUS_INACTIVE));
            return List.of();
        }

        Objects.requireNonNull(current, "current");
        MonitorSnapshot previousSnapshot = latestSuccessfulSnapshot(watch.getId());
        MonitorState previous = previousSnapshot == null
            ? null
            : JSON.parseObject(previousSnapshot.getStateJson(), MonitorState.class);
        MonitorSnapshot currentSnapshot = snapshot(
            watch.getId(), current, checkedAt, BaseEntity.STATUS_ACTIVE);

        List<MonitorEvent> published = new ArrayList<>();
        for (MonitorEventDraft draft : detector.detect(previous, current)) {
            MonitorEvent event = publishEvent(watch, currentSnapshot, draft, checkedAt);
            publishNotification(watch, event, draft, checkedAt);
            published.add(event);
        }

        snapshotService.save(currentSnapshot);
        return List.copyOf(published);
    }

    private MonitorSnapshot latestSuccessfulSnapshot(String watchId) {
        return snapshotService.getOne(
            Wrappers.<MonitorSnapshot>lambdaQuery()
                .eq(MonitorSnapshot::getWatchId, watchId)
                .eq(MonitorSnapshot::getStatus, BaseEntity.STATUS_ACTIVE)
                .orderByDesc(MonitorSnapshot::getCheckedAt)
                .last("LIMIT 1"));
    }

    private MonitorEvent publishEvent(
        DomainWatch watch,
        MonitorSnapshot snapshot,
        MonitorEventDraft draft,
        Date occurredAt) {
        String fingerprint = MonitorFingerprint.of(watch.getId(), draft);
        MonitorEvent event = new MonitorEvent();
        initialize(event, occurredAt);
        event.setWatchId(watch.getId());
        event.setSnapshotId(snapshot.getId());
        event.setFingerprint(fingerprint);
        event.setEventType(draft.type().name());
        event.setOldValue(draft.oldValue());
        event.setNewValue(draft.newValue());
        event.setOccurredAt(occurredAt);

        try {
            eventService.save(event);
            return event;
        } catch (DuplicateKeyException duplicate) {
            MonitorEvent winner = eventService.getOne(
                Wrappers.<MonitorEvent>lambdaQuery()
                    .eq(MonitorEvent::getWatchId, watch.getId())
                    .eq(MonitorEvent::getFingerprint, fingerprint));
            if (winner == null) {
                throw duplicate;
            }
            return winner;
        }
    }

    private void publishNotification(
        DomainWatch watch,
        MonitorEvent event,
        MonitorEventDraft draft,
        Date createdAt) {
        UserNotification notification = new UserNotification();
        initialize(notification, createdAt);
        notification.setUserId(watch.getUserId());
        notification.setEventId(event.getId());
        notification.setTitle(title(draft.type()));
        notification.setContent(content(draft));
        notification.setTargetPath(internalDomainTarget(watch.getDomainName()));
        notification.setEmailState(EMAIL_PENDING);

        try {
            notificationService.save(notification);
        } catch (DuplicateKeyException duplicate) {
            UserNotification winner = notificationService.getOne(
                Wrappers.<UserNotification>lambdaQuery()
                    .eq(UserNotification::getUserId, watch.getUserId())
                    .eq(UserNotification::getEventId, event.getId()));
            if (winner == null) {
                throw duplicate;
            }
        }
    }

    private static MonitorSnapshot snapshot(
        String watchId,
        MonitorState state,
        Date checkedAt,
        int status) {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        initialize(snapshot, checkedAt);
        snapshot.setStatus(status);
        snapshot.setWatchId(watchId);
        snapshot.setCheckedAt(checkedAt);
        snapshot.setStateJson(JSON.toJSONString(state));
        return snapshot;
    }

    private static void initialize(BaseEntity entity, Date createdAt) {
        entity.setId(RandomUtils.generateId());
        entity.setStatus(BaseEntity.STATUS_ACTIVE);
        entity.setDeleted(0);
        entity.setCreateTime(createdAt);
    }

    private static String internalDomainTarget(String domainName) {
        String domain = domainName == null ? "" : domainName.trim();
        return "/domain/" + UriUtils.encodePathSegment(domain, StandardCharsets.UTF_8);
    }

    private static String title(MonitorEventType type) {
        return switch (type) {
            case DOMAIN_EXPIRING -> "Domain expiration reminder";
            case SSL_EXPIRING -> "SSL certificate expiration reminder";
            case DOMAIN_STATUS_CHANGED -> "Domain status changed";
            case DNS_CHANGED -> "DNS records changed";
            case WEBSITE_DOWN -> "Website unavailable";
            case WEBSITE_RECOVERED -> "Website recovered";
        };
    }

    private static String content(MonitorEventDraft draft) {
        return draft.field() + ": " + draft.oldValue() + " -> " + draft.newValue();
    }
}
