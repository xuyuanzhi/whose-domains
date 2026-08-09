package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.apache.ibatis.annotations.Select;
import org.springframework.dao.DuplicateKeyException;

import com.alibaba.fastjson2.JSON;

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
import info.wesite.web.notification.NotificationDispatcher;

class MonitorEventPublisherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-09T12:34:56Z"), ZoneOffset.UTC);

    private MonitorSnapshotService snapshotService;
    private MonitorEventService eventService;
    private UserNotificationService notificationService;
    private MonitorEventMapper eventMapper;
    private UserNotificationMapper notificationMapper;
    private MonitorChangeDetector detector;
    private NotificationDispatcher dispatcher;
    private MonitorEventPublisher publisher;

    @BeforeEach
    void setUp() {
        snapshotService = mock(MonitorSnapshotService.class);
        eventService = mock(MonitorEventService.class);
        notificationService = mock(UserNotificationService.class);
        eventMapper = mock(MonitorEventMapper.class);
        notificationMapper = mock(UserNotificationMapper.class);
        detector = mock(MonitorChangeDetector.class);
        dispatcher = mock(NotificationDispatcher.class);
        when(snapshotService.save(any(MonitorSnapshot.class))).thenReturn(true);
        when(eventService.save(any(MonitorEvent.class))).thenReturn(true);
        when(notificationService.save(any(UserNotification.class))).thenReturn(true);
        publisher = new MonitorEventPublisher(
            snapshotService,
            eventService,
            notificationService,
            eventMapper,
            notificationMapper,
            dispatcher,
            detector,
            CLOCK);
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
        verifyNoInteractions(detector, eventService, notificationService, dispatcher);
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
        when(eventMapper.selectByIdentityForUpdate("watch-1", MonitorFingerprint.of("watch-1", draft)))
            .thenReturn(winner);

        List<MonitorEvent> published = publisher.publish(watch, current, true);

        assertEquals(1, published.size());
        assertSame(winner, published.get(0));
        verify(eventMapper).selectByIdentityForUpdate("watch-1", MonitorFingerprint.of("watch-1", draft));
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

        InOrder order = inOrder(detector, eventService, notificationService, dispatcher, snapshotService);
        order.verify(snapshotService).getOne(any());
        order.verify(detector).detect(previous, current);
        order.verify(eventService).save(any(MonitorEvent.class));
        order.verify(notificationService).save(any(UserNotification.class));
        order.verify(dispatcher).dispatch(any(MonitorEvent.class), any(UserNotification.class));
        order.verify(eventService).save(any(MonitorEvent.class));
        order.verify(notificationService).save(any(UserNotification.class));
        order.verify(dispatcher).dispatch(any(MonitorEvent.class), any(UserNotification.class));
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
        winner.setEmailState("pending");
        when(notificationMapper.selectByIdentityForUpdate(org.mockito.ArgumentMatchers.eq("user-1"), any(String.class)))
            .thenReturn(winner);

        List<MonitorEvent> published = publisher.publish(watch, current, true);

        assertEquals(1, published.size());
        verify(notificationService).save(any(UserNotification.class));
        verify(notificationMapper).selectByIdentityForUpdate("user-1", published.get(0).getId());
        verify(dispatcher).dispatch(published.get(0), winner);
        verify(snapshotService).save(any(MonitorSnapshot.class));
    }

    @Test
    void duplicateNotificationThatWasAlreadyRoutedIsNotDispatchedAgain() {
        DomainWatch watch = watch();
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(previous, current)).thenReturn(List.of(statusDraft()));
        when(notificationService.save(any(UserNotification.class)))
            .thenThrow(new DuplicateKeyException("UK_USER_NOTIFICATION_USER_EVENT"));
        UserNotification winner = new UserNotification();
        winner.setId("already-routed-notification");
        winner.setEmailState("SENT");
        when(notificationMapper.selectByIdentityForUpdate(org.mockito.ArgumentMatchers.eq("user-1"), any(String.class)))
            .thenReturn(winner);

        publisher.publish(watch, current, true);

        verify(notificationMapper).selectByIdentityForUpdate(any(String.class), any(String.class));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void failedDiagnosticSnapshotSaveFalseAbortsPublication() {
        when(snapshotService.save(any(MonitorSnapshot.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> publisher.publish(watch(), state(Set.of("error")), false));

        verifyNoInteractions(detector, eventService, notificationService);
    }

    @Test
    void eventSaveFalseAbortsBeforeNotificationAndSnapshot() {
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(previous, current)).thenReturn(List.of(statusDraft()));
        when(eventService.save(any(MonitorEvent.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> publisher.publish(watch(), current, true));

        verifyNoInteractions(notificationService);
        verifyNoInteractions(dispatcher);
        verify(snapshotService, never()).save(any(MonitorSnapshot.class));
    }

    @Test
    void notificationSaveFalseAbortsBeforeSnapshot() {
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(previous, current)).thenReturn(List.of(statusDraft()));
        when(notificationService.save(any(UserNotification.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> publisher.publish(watch(), current, true));

        verify(snapshotService, never()).save(any(MonitorSnapshot.class));
    }

    @Test
    void successfulSnapshotSaveFalseAbortsPublication() {
        MonitorState current = state(Set.of("ok"));
        when(detector.detect(null, current)).thenReturn(List.of());
        when(snapshotService.save(any(MonitorSnapshot.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> publisher.publish(watch(), current, true));
    }

    @Test
    void duplicateWinnerQueriesAreLockingReadsOverTheCompleteUniqueKeys() throws Exception {
        String eventSql = sql(MonitorEventMapper.class.getMethod(
            "selectByIdentityForUpdate", String.class, String.class));
        assertTrue(eventSql.contains("WATCH_ID = #{WATCHID}"));
        assertTrue(eventSql.contains("FINGERPRINT = #{FINGERPRINT}"));
        assertTrue(eventSql.endsWith("FOR UPDATE"));

        String notificationSql = sql(UserNotificationMapper.class.getMethod(
            "selectByIdentityForUpdate", String.class, String.class));
        assertTrue(notificationSql.contains("USER_ID = #{USERID}"));
        assertTrue(notificationSql.contains("EVENT_ID = #{EVENTID}"));
        assertTrue(notificationSql.endsWith("FOR UPDATE"));
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

    private static String sql(java.lang.reflect.Method method) {
        return String.join(" ", method.getAnnotation(Select.class).value())
            .replaceAll("\\s+", " ")
            .trim()
            .toUpperCase(java.util.Locale.ROOT);
    }
}
