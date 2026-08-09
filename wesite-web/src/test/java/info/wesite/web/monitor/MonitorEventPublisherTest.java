package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
        when(detector.detect(eq(previous), eq(current), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(draft));
        when(eventService.save(any(MonitorEvent.class)))
            .thenThrow(new DuplicateKeyException("UK_MONITOR_EVENT_WATCH_FINGERPRINT"));
        MonitorEvent winner = new MonitorEvent();
        winner.setId("winning-event");
        when(eventMapper.selectByIdentityForUpdate(
            "watch-1", MonitorFingerprint.of("watch-1", draft, "after:previous")))
            .thenReturn(winner);

        List<MonitorEvent> published = publisher.publish(watch, current, true);

        assertEquals(1, published.size());
        assertSame(winner, published.get(0));
        verify(eventMapper).selectByIdentityForUpdate(
            "watch-1", MonitorFingerprint.of("watch-1", draft, "after:previous"));
        ArgumentCaptor<UserNotification> notification = ArgumentCaptor.forClass(UserNotification.class);
        verify(notificationService).save(notification.capture());
        assertEquals("winning-event", notification.getValue().getEventId());
    }

    @Test
    void repeatedTransitionAfterRecoveryBelongsToANewEpisode() {
        DomainWatch watch = watch();
        MonitorState up = state(Set.of("ok"));
        MonitorState down = state(Set.of("serverHold"));
        MonitorEventDraft entered = new MonitorEventDraft(
            MonitorEventType.DOMAIN_STATUS_CHANGED, MonitorRisk.CRITICAL,
            "example.com", "domainStatuses", "ok", "serverHold");
        MonitorEventDraft recovered = new MonitorEventDraft(
            MonitorEventType.DOMAIN_STATUS_CHANGED, MonitorRisk.MEDIUM,
            "example.com", "domainStatuses", "serverHold", "ok");
        when(snapshotService.getOne(any())).thenReturn(
            snapshot("up-1", up), snapshot("down-1", down), snapshot("up-2", up));
        when(detector.detect(eq(up), eq(down), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(entered));
        when(detector.detect(eq(down), eq(up), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(recovered));

        publisher.publish(watch, down, true);
        publisher.publish(watch, up, true);
        publisher.publish(watch, down, true);

        ArgumentCaptor<MonitorEvent> events = ArgumentCaptor.forClass(MonitorEvent.class);
        verify(eventService, times(3)).save(events.capture());
        assertNotEquals(
            events.getAllValues().get(0).getFingerprint(),
            events.getAllValues().get(2).getFingerprint());
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
        when(detector.detect(eq(previous), eq(current), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(drafts);

        publisher.publish(watch, current, true);

        ArgumentCaptor<UserNotification> notifications = ArgumentCaptor.forClass(UserNotification.class);
        verify(notificationService, times(2)).save(notifications.capture());
        assertEquals(2, notifications.getAllValues().stream()
            .map(UserNotification::getEventId)
            .distinct()
            .count());
        assertEquals(List.of("/domain/example.com#domain-information", "/domain/example.com#dns-records"), notifications.getAllValues().stream()
            .map(UserNotification::getTargetPath)
            .toList());

        InOrder order = inOrder(detector, eventService, notificationService, dispatcher, snapshotService);
        order.verify(snapshotService).getOne(any());
        order.verify(detector).detect(eq(previous), eq(current), nullable(Instant.class), nullable(Instant.class));
        order.verify(eventService).save(any(MonitorEvent.class));
        order.verify(notificationService).save(any(UserNotification.class));
        order.verify(dispatcher).dispatch(
            any(MonitorEvent.class), any(UserNotification.class), eq(watch), nullable(Integer.class));
        order.verify(eventService).save(any(MonitorEvent.class));
        order.verify(notificationService).save(any(UserNotification.class));
        order.verify(dispatcher).dispatch(
            any(MonitorEvent.class), any(UserNotification.class), eq(watch), nullable(Integer.class));
        order.verify(snapshotService).save(any(MonitorSnapshot.class));
    }

    @Test
    void sslAndWebsiteEventsDeepLinkToTheirOwnEvidenceSections() {
        DomainWatch watch = watch();
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        List<MonitorEventDraft> drafts = List.of(
            new MonitorEventDraft(
                MonitorEventType.SSL_EXPIRING, MonitorRisk.HIGH,
                "example.com", "sslExpiry:7", "2027-01-01", "2026-08-16"),
            new MonitorEventDraft(
                MonitorEventType.WEBSITE_DOWN, MonitorRisk.CRITICAL,
                "example.com", "websiteAvailable", "true", "false"),
            new MonitorEventDraft(
                MonitorEventType.WEBSITE_RECOVERED, MonitorRisk.MEDIUM,
                "example.com", "websiteAvailable", "false", "true"));
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(eq(previous), eq(current), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(drafts);

        publisher.publish(watch, current, true);

        ArgumentCaptor<UserNotification> notifications = ArgumentCaptor.forClass(UserNotification.class);
        verify(notificationService, times(3)).save(notifications.capture());
        assertEquals(List.of(
            "/domain/example.com#ssl-evidence",
            "/domain/example.com#website-availability-evidence",
            "/domain/example.com#website-availability-evidence"),
            notifications.getAllValues().stream().map(UserNotification::getTargetPath).toList());
    }

    @Test
    void duplicateNotificationIsAcceptedAsTheConcurrentWinner() {
        DomainWatch watch = watch();
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(eq(previous), eq(current), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(statusDraft()));
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
        verify(dispatcher).dispatch(published.get(0), winner, watch, null);
        verify(snapshotService).save(any(MonitorSnapshot.class));
    }

    @Test
    void duplicateNotificationThatWasAlreadyRoutedIsNotDispatchedAgain() {
        DomainWatch watch = watch();
        MonitorState previous = state(Set.of("ok"));
        MonitorState current = state(Set.of("clientHold"));
        when(snapshotService.getOne(any())).thenReturn(snapshot("previous", previous));
        when(detector.detect(eq(previous), eq(current), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(statusDraft()));
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
        when(detector.detect(eq(previous), eq(current), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(statusDraft()));
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
        when(detector.detect(eq(previous), eq(current), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(statusDraft()));
        when(notificationService.save(any(UserNotification.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> publisher.publish(watch(), current, true));

        verify(snapshotService, never()).save(any(MonitorSnapshot.class));
    }

    @Test
    void successfulSnapshotSaveFalseAbortsPublication() {
        MonitorState current = state(Set.of("ok"));
        when(detector.detect(eq(null), eq(current), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of());
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

    @Test
    void firstScanAtExpiryThresholdUsesRealDetectorAndStableFingerprint() {
        detector = new MonitorChangeDetector(CLOCK);
        publisher = new MonitorEventPublisher(
            snapshotService,
            eventService,
            notificationService,
            eventMapper,
            notificationMapper,
            dispatcher,
            detector,
            CLOCK);
        DomainWatch watch = watch();
        MonitorState current = new MonitorState(
            "example.com",
            Set.of("ok"),
            LocalDate.of(2026, 8, 16),
            LocalDate.of(2026, 11, 1),
            Map.of(),
            true,
            0);
        AtomicReference<MonitorEvent> firstEvent = new AtomicReference<>();
        AtomicReference<UserNotification> firstNotification = new AtomicReference<>();
        when(eventService.save(any(MonitorEvent.class))).thenAnswer(invocation -> {
            MonitorEvent candidate = invocation.getArgument(0);
            if (firstEvent.compareAndSet(null, candidate)) {
                return true;
            }
            assertEquals(firstEvent.get().getFingerprint(), candidate.getFingerprint());
            throw new DuplicateKeyException("UK_MONITOR_EVENT_WATCH_FINGERPRINT");
        });
        when(eventMapper.selectByIdentityForUpdate(eq("watch-1"), any(String.class)))
            .thenAnswer(invocation -> firstEvent.get());
        when(notificationService.save(any(UserNotification.class))).thenAnswer(invocation -> {
            UserNotification candidate = invocation.getArgument(0);
            if (firstNotification.compareAndSet(null, candidate)) {
                return true;
            }
            throw new DuplicateKeyException("UK_USER_NOTIFICATION_USER_EVENT");
        });
        when(notificationMapper.selectByIdentityForUpdate(eq("user-1"), any(String.class)))
            .thenAnswer(invocation -> firstNotification.get());

        List<MonitorEvent> first = publisher.publish(watch, current, true);
        firstNotification.get().setEmailState("SENT");
        List<MonitorEvent> replay = publisher.publish(watch, current, true);

        assertEquals(1, first.size());
        assertSame(first.get(0), replay.get(0));
        assertEquals("DOMAIN_EXPIRING", first.get(0).getEventType());
        assertTrue(first.get(0).getFingerprint() != null && !first.get(0).getFingerprint().isBlank());
        verify(dispatcher, times(1)).dispatch(
            any(MonitorEvent.class), any(UserNotification.class), any(DomainWatch.class), eq(7));
    }

    @Test
    void oneScanCrossingAllExpiryThresholdsPreservesEachDraftRisk() {
        detector = new MonitorChangeDetector(CLOCK);
        publisher = new MonitorEventPublisher(
            snapshotService,
            eventService,
            notificationService,
            eventMapper,
            notificationMapper,
            dispatcher,
            detector,
            CLOCK);
        MonitorState previous = expiryState(LocalDate.of(2026, 8, 10));
        MonitorState current = expiryState(LocalDate.of(2026, 8, 10));
        MonitorSnapshot previousSnapshot = snapshot("previous", previous);
        previousSnapshot.setCheckedAt(Date.from(CLOCK.instant().minusSeconds(31L * 86_400L)));
        when(snapshotService.getOne(any())).thenReturn(previousSnapshot);

        List<MonitorEvent> events = publisher.publish(watch(), current, true);

        assertEquals(List.of("LOW", "HIGH", "CRITICAL"), events.stream()
            .map(MonitorEvent::getRisk)
            .toList());
        assertTrue(events.stream().allMatch(event -> "WHOIS/RDAP".equals(event.getSource())));
    }

    @Test
    void holdEntryAndExitPreserveTheDraftRiskAtBothBoundaries() {
        MonitorState beforeHold = state(Set.of("ok"));
        MonitorState duringHold = state(Set.of("clientHold"));
        MonitorState afterHold = state(Set.of("ok"));
        MonitorEventDraft entered = statusDraft();
        MonitorEventDraft exited = new MonitorEventDraft(
            MonitorEventType.DOMAIN_STATUS_CHANGED,
            MonitorRisk.MEDIUM,
            "example.com",
            "domainStatuses",
            "clientHold",
            "ok");
        when(snapshotService.getOne(any())).thenReturn(
            snapshot("before-hold", beforeHold),
            snapshot("during-hold", duringHold));
        when(detector.detect(eq(beforeHold), eq(duringHold), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(entered));
        when(detector.detect(eq(duringHold), eq(afterHold), nullable(Instant.class), nullable(Instant.class)))
            .thenReturn(List.of(exited));

        MonitorEvent entryEvent = publisher.publish(watch(), duringHold, true).get(0);
        MonitorEvent exitEvent = publisher.publish(watch(), afterHold, true).get(0);

        assertEquals("CRITICAL", entryEvent.getRisk());
        assertEquals("MEDIUM", exitEvent.getRisk());
    }

    @Test
    void legacySnapshotDoesNotBaselinePreviouslyUnobservedSources() {
        detector = new MonitorChangeDetector(CLOCK);
        publisher = new MonitorEventPublisher(
            snapshotService,
            eventService,
            notificationService,
            eventMapper,
            notificationMapper,
            dispatcher,
            detector,
            CLOCK);
        MonitorState legacy = new MonitorState(
            "example.com", Set.of("ok"), LocalDate.of(2027, 1, 1), null,
            Map.of(), true, 0);
        MonitorState upgraded = new MonitorState(
            "example.com", Set.of("ok"), LocalDate.of(2027, 1, 1), LocalDate.of(2026, 8, 16),
            Map.of("A", Set.of("203.0.113.8")), false, 2);
        MonitorSnapshot legacySnapshot = snapshot("legacy", legacy);
        legacySnapshot.setSchemaVersion(null);
        legacySnapshot.setObservedSources(null);
        when(snapshotService.getOne(any())).thenReturn(legacySnapshot);

        List<MonitorEvent> events = publisher.publish(
            watch(),
            upgraded,
            true,
            Set.of(
                MonitorCollectorResult.Source.DOMAIN,
                MonitorCollectorResult.Source.DNS,
                MonitorCollectorResult.Source.SSL,
                MonitorCollectorResult.Source.WEBSITE));

        assertTrue(events.isEmpty());
        ArgumentCaptor<MonitorSnapshot> saved = ArgumentCaptor.forClass(MonitorSnapshot.class);
        verify(snapshotService).save(saved.capture());
        assertEquals(2, saved.getValue().getSchemaVersion());
        assertEquals("DNS,DOMAIN,SSL,WEBSITE", saved.getValue().getObservedSources());
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

    private static MonitorState expiryState(LocalDate domainExpiry) {
        return new MonitorState(
            "example.com",
            Set.of("ok"),
            domainExpiry,
            LocalDate.of(2027, 8, 9),
            Map.of(),
            true,
            0);
    }

    private static MonitorSnapshot snapshot(String id, MonitorState state) {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setId(id);
        snapshot.setStatus(BaseEntity.STATUS_ACTIVE);
        snapshot.setStateJson(JSON.toJSONString(state));
        snapshot.setCheckedAt(Date.from(CLOCK.instant().minusSeconds(86_400)));
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
