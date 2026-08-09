package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.core.service.UserNotificationService;

class MonitorEventPublisherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-09T12:34:56Z"), ZoneOffset.UTC);

    private MonitorSnapshotService snapshotService;
    private MonitorEventService eventService;
    private UserNotificationService notificationService;
    private MonitorChangeDetector detector;
    private MonitorEventPublisher publisher;

    @BeforeEach
    void setUp() {
        snapshotService = mock(MonitorSnapshotService.class);
        eventService = mock(MonitorEventService.class);
        notificationService = mock(UserNotificationService.class);
        detector = mock(MonitorChangeDetector.class);
        publisher = new MonitorEventPublisher(
            snapshotService, eventService, notificationService, detector, CLOCK);
    }

    @Test
    void failedCheckSavesOnlyAnInactiveDiagnosticSnapshot() {
        DomainWatch watch = watch();
        MonitorState failedState = state(Set.of("serverHold"));

        publisher.publish(watch, failedState, false);

        ArgumentCaptor<MonitorSnapshot> snapshot = ArgumentCaptor.forClass(MonitorSnapshot.class);
        verify(snapshotService).save(snapshot.capture());
        assertEquals(BaseEntity.STATUS_INACTIVE, snapshot.getValue().getStatus());
        assertEquals(JSON.toJSONString(failedState), snapshot.getValue().getStateJson());
        verify(snapshotService, never()).getOne(any());
        verifyNoInteractions(detector, eventService, notificationService);
    }

    @Test
    void duplicateEventReloadsWinnerAndUsesItForTheNotification() {
        DomainWatch watch = watch();
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        MonitorEventDraft draft = statusDraft();
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(previous, current)).thenReturn(List.of(draft));
        when(eventService.save(any(MonitorEvent.class)))
            .thenThrow(new DuplicateKeyException("UK_MONITOR_EVENT_WATCH_FINGERPRINT"));
        MonitorEvent winner = new MonitorEvent();
        winner.setId("winning-event");
        when(eventService.getOne(any())).thenReturn(winner);

        List<MonitorEvent> published = publisher.publish(watch, current, true);

        assertEquals(1, published.size());
        assertSame(winner, published.get(0));
        verify(eventService).getOne(any());
        ArgumentCaptor<UserNotification> notification = ArgumentCaptor.forClass(UserNotification.class);
        verify(notificationService).save(notification.capture());
        assertEquals("winning-event", notification.getValue().getEventId());
    }

    @Test
    void everyDetectedEventCreatesOneNotificationWithAnInternalDomainTarget() {
        DomainWatch watch = watch();
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        List<MonitorEventDraft> drafts = List.of(
            statusDraft(),
            MonitorEventDraft.dns("example.com", "A", Set.of("192.0.2.1"), Set.of("192.0.2.2")));
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(previous, current)).thenReturn(drafts);

        publisher.publish(watch, current, true);

        ArgumentCaptor<UserNotification> notifications = ArgumentCaptor.forClass(UserNotification.class);
        verify(notificationService, times(2)).save(notifications.capture());
        assertEquals(2, notifications.getAllValues().stream()
            .map(UserNotification::getEventId)
            .distinct()
            .count());
        assertEquals(List.of("/domain/example.com", "/domain/example.com"), notifications.getAllValues().stream()
            .map(UserNotification::getTargetPath)
            .toList());

        InOrder order = inOrder(detector, eventService, notificationService, snapshotService);
        order.verify(snapshotService).getOne(any());
        order.verify(detector).detect(previous, current);
        order.verify(eventService).save(any(MonitorEvent.class));
        order.verify(notificationService).save(any(UserNotification.class));
        order.verify(eventService).save(any(MonitorEvent.class));
        order.verify(notificationService).save(any(UserNotification.class));
        order.verify(snapshotService).save(any(MonitorSnapshot.class));
    }

    @Test
    void duplicateNotificationIsAcceptedAsTheConcurrentWinner() {
        DomainWatch watch = watch();
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(previous, current)).thenReturn(List.of(statusDraft()));
        when(notificationService.save(any(UserNotification.class)))
            .thenThrow(new DuplicateKeyException("UK_USER_NOTIFICATION_USER_EVENT"));
        UserNotification winner = new UserNotification();
        winner.setId("winning-notification");
        when(notificationService.getOne(any())).thenReturn(winner);

        List<MonitorEvent> published = publisher.publish(watch, current, true);

        assertEquals(1, published.size());
        verify(notificationService).save(any(UserNotification.class));
        verify(notificationService).getOne(any());
        verify(snapshotService).save(any(MonitorSnapshot.class));
    }

    private static DomainWatch watch() {
        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setUserId("user-1");
        watch.setDomainName("example.com");
        return watch;
    }

    private static MonitorState state(Set<String> statuses) {
        return new MonitorState(
            "example.com",
            statuses,
            LocalDate.of(2026, 9, 8),
            LocalDate.of(2026, 9, 8),
            Map.of("A", Set.of("192.0.2.1")),
            true,
            0);
    }

    private static MonitorSnapshot snapshot(String id, MonitorState state) {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setId(id);
        snapshot.setStatus(BaseEntity.STATUS_ACTIVE);
        snapshot.setStateJson(JSON.toJSONString(state));
        return snapshot;
    }

    private static MonitorEventDraft statusDraft() {
        return new MonitorEventDraft(
            MonitorEventType.DOMAIN_STATUS_CHANGED,
            MonitorRisk.CRITICAL,
            "example.com",
            "domainStatuses",
            "ok",
            "clientHold");
    }
}
