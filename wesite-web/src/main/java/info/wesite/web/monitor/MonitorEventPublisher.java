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
import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mapper.MonitorEventMapper;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.web.notification.NotificationDispatcher;

/**
 * Persists monitoring snapshots and turns changes between successful checks into
 * idempotent events and in-app notifications.
 */
@Service
public class MonitorEventPublisher {

    private final MonitorSnapshotService snapshotService;
    private final MonitorEventService eventService;
    private final UserNotificationService notificationService;
    private final MonitorEventMapper eventMapper;
    private final UserNotificationMapper notificationMapper;
    private final NotificationDispatcher dispatcher;
    private final MonitorChangeDetector detector;
    private final Clock clock;

    @Autowired
    public MonitorEventPublisher(
        MonitorSnapshotService snapshotService,
        MonitorEventService eventService,
        UserNotificationService notificationService,
        MonitorEventMapper eventMapper,
        UserNotificationMapper notificationMapper,
        NotificationDispatcher dispatcher) {
        this(
            snapshotService,
            eventService,
            notificationService,
            eventMapper,
            notificationMapper,
            dispatcher,
            Clock.systemUTC());
    }

    private MonitorEventPublisher(
        MonitorSnapshotService snapshotService,
        MonitorEventService eventService,
        UserNotificationService notificationService,
        MonitorEventMapper eventMapper,
        UserNotificationMapper notificationMapper,
        NotificationDispatcher dispatcher,
        Clock clock) {
        this(
            snapshotService,
            eventService,
            notificationService,
            eventMapper,
            notificationMapper,
            dispatcher,
            new MonitorChangeDetector(clock),
            clock);
    }

    MonitorEventPublisher(
        MonitorSnapshotService snapshotService,
        MonitorEventService eventService,
        UserNotificationService notificationService,
        MonitorEventMapper eventMapper,
        UserNotificationMapper notificationMapper,
        MonitorChangeDetector detector,
        Clock clock) {
        this(
            snapshotService,
            eventService,
            notificationService,
            eventMapper,
            notificationMapper,
            null,
            detector,
            clock);
    }

    MonitorEventPublisher(
        MonitorSnapshotService snapshotService,
        MonitorEventService eventService,
        UserNotificationService notificationService,
        MonitorEventMapper eventMapper,
        UserNotificationMapper notificationMapper,
        NotificationDispatcher dispatcher,
        MonitorChangeDetector detector,
        Clock clock) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.eventService = Objects.requireNonNull(eventService, "eventService");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.notificationMapper = Objects.requireNonNull(notificationMapper, "notificationMapper");
        this.dispatcher = dispatcher;
        this.detector = Objects.requireNonNull(detector, "detector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public List<MonitorEvent> publish(DomainWatch watch, MonitorState current, boolean checkSucceeded) {
        Objects.requireNonNull(watch, "watch");
        Date checkedAt = Date.from(clock.instant());

        if (!checkSucceeded) {
            requireSaved(
                snapshotService.save(snapshot(watch.getId(), current, checkedAt, BaseEntity.STATUS_INACTIVE)),
                "failed-check diagnostic snapshot");
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
        for (MonitorEventDraft draft : detector.detect(
            previous,
            current,
            previousSnapshot == null || previousSnapshot.getCheckedAt() == null
                ? null
                : previousSnapshot.getCheckedAt().toInstant(),
            checkedAt.toInstant())) {
            MonitorEvent event = publishEvent(watch, currentSnapshot, draft, checkedAt);
            publishNotification(watch, event, draft, checkedAt);
            published.add(event);
        }

        requireSaved(snapshotService.save(currentSnapshot), "successful snapshot");
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
            requireSaved(eventService.save(event), "monitor event");
            return event;
        } catch (DuplicateKeyException duplicate) {
            MonitorEvent winner = eventMapper.selectByIdentityForUpdate(watch.getId(), fingerprint);
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
        notification.setEmailState(UserNotification.EMAIL_STATE_QUEUED);

        UserNotification persisted;
        boolean needsDispatch;
        try {
            requireSaved(notificationService.save(notification), "user notification");
            persisted = notification;
            needsDispatch = true;
        } catch (DuplicateKeyException duplicate) {
            UserNotification winner = notificationMapper.selectByIdentityForUpdate(
                watch.getUserId(), event.getId());
            if (winner == null) {
                throw duplicate;
            }
            persisted = winner;
            needsDispatch = StringUtils.isBlank(winner.getEmailMode())
                && (UserNotification.EMAIL_STATE_QUEUED.equalsIgnoreCase(winner.getEmailState())
                    || "pending".equalsIgnoreCase(winner.getEmailState()));
        }

        if (needsDispatch && dispatcher != null) {
            dispatcher.dispatch(event, persisted);
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

    private static void requireSaved(boolean saved, String recordType) {
        if (!saved) {
            throw new IllegalStateException("Failed to save " + recordType);
        }
    }
}
