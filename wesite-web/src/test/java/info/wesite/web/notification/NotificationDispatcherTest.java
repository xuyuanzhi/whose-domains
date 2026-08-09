package info.wesite.web.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mapper.DomainWatchMapper;
import info.wesite.core.service.NotificationPreferenceService;
import info.wesite.core.service.UserNotificationService;

class NotificationDispatcherTest {

    private NotificationPreferenceService preferenceService;
    private UserNotificationService notificationService;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        preferenceService = mock(NotificationPreferenceService.class);
        notificationService = mock(UserNotificationService.class);
        when(notificationService.updateById(any(UserNotification.class))).thenReturn(true);
        dispatcher = new NotificationDispatcher(preferenceService, notificationService);
    }

    @ParameterizedTest
    @MethodSource("explicitModes")
    void explicitEmailModeIsPersistedForEnabledCategory(String mode, NotificationDispatchDecision expected) {
        MonitorEvent event = event("DNS_CHANGED", "2026-09-08");
        UserNotification notification = notification();
        when(preferenceService.getOne(any())).thenReturn(preference(mode));
        NotificationDispatchDecision decision = dispatcher.dispatch(
            event, notification, watch(DomainWatch.NOTIFY_BOTH, "watch@example.com"), null);

        assertEquals(expected, decision);
        assertEquals(expected.name(), notification.getEmailMode());
        assertEquals(expected == NotificationDispatchDecision.IN_APP_ONLY ? "IN_APP_ONLY" : "QUEUED",
            notification.getEmailState());
        assertEquals(0, notification.getEmailAttemptCount());
        assertEquals(null, notification.getEmailClaimToken());
        assertEquals(null, notification.getEmailClaimUntil());
        assertEquals(null, notification.getDeliveryBatchId());
        assertEquals(expected == NotificationDispatchDecision.IN_APP_ONLY ? null : "watch@example.com",
            notification.getRecipientEmail());
        ArgumentCaptor<UserNotification> persisted = ArgumentCaptor.forClass(UserNotification.class);
        verify(notificationService).updateById(persisted.capture());
        assertEquals(expected.name(), persisted.getValue().getEmailMode());
    }

    private static Stream<Arguments> explicitModes() {
        return Stream.of(
            Arguments.of(NotificationPreference.MODE_IMMEDIATE, NotificationDispatchDecision.IMMEDIATE_EMAIL),
            Arguments.of(NotificationPreference.MODE_DAILY, NotificationDispatchDecision.DAILY_DIGEST),
            Arguments.of(NotificationPreference.MODE_WEEKLY, NotificationDispatchDecision.WEEKLY_DIGEST),
            Arguments.of(NotificationPreference.MODE_IN_APP_ONLY, NotificationDispatchDecision.IN_APP_ONLY));
    }

    @Test
    void disabledEventCategoryKeepsNotificationInAppOnly() {
        MonitorEvent event = event("DNS_CHANGED", "2026-09-08");
        UserNotification notification = notification();
        NotificationPreference preference = preference(NotificationPreference.MODE_IMMEDIATE);
        preference.setDnsChangeEnabled(false);
        when(preferenceService.getOne(any())).thenReturn(preference);
        NotificationDispatchDecision decision = dispatcher.dispatch(
            event, notification, watch(DomainWatch.NOTIFY_BOTH, "watch@example.com"), null);

        assertEquals(NotificationDispatchDecision.IN_APP_ONLY, decision);
        assertEquals("IN_APP_ONLY", notification.getEmailState());
        assertEquals("IN_APP_ONLY", notification.getEmailMode());
    }

    @Test
    void missingPreferenceUsesDailyDigestForOrdinaryChanges() {
        MonitorEvent event = event("DNS_CHANGED", "192.0.2.2");
        UserNotification notification = notification();
        when(preferenceService.getOne(any())).thenReturn(null);
        NotificationDispatchDecision decision = dispatcher.dispatch(
            event, notification, watch(DomainWatch.NOTIFY_BOTH, "watch@example.com"), null);

        assertEquals(NotificationDispatchDecision.DAILY_DIGEST, decision);
        assertEquals("DAILY_DIGEST", notification.getEmailMode());
        assertEquals("QUEUED", notification.getEmailState());
    }

    @Test
    void missingEmailKeepsNotificationInAppOnly() {
        MonitorEvent event = event("WEBSITE_DOWN", "false");
        UserNotification notification = notification();
        when(preferenceService.getOne(any())).thenReturn(preference(NotificationPreference.MODE_IMMEDIATE));
        NotificationDispatchDecision decision = dispatcher.dispatch(
            event, notification, watch(DomainWatch.NOTIFY_BOTH, null), null);

        assertEquals(NotificationDispatchDecision.IN_APP_ONLY, decision);
        assertEquals("IN_APP_ONLY", notification.getEmailState());
        assertEquals("IN_APP_ONLY", notification.getEmailMode());
    }

    @Test
    void missingPreferenceUsesTheDailyRoutineDefaultButEscalatesCanonicalCriticalRisk() {
        MonitorEvent event = event("WEBSITE_DOWN", "false");
        UserNotification notification = notification();
        when(preferenceService.getOne(any())).thenReturn(null);
        NotificationDispatchDecision decision = dispatcher.dispatch(
            event, notification, watch(DomainWatch.NOTIFY_BOTH, "watch@example.com"), null);

        assertEquals(NotificationDispatchDecision.IMMEDIATE_EMAIL, decision);
        assertEquals("IMMEDIATE_EMAIL", notification.getEmailMode());
        assertEquals("QUEUED", notification.getEmailState());
    }

    @Test
    void notifyNoneAndInvalidWatchEmailNeverFallBackToTheAccountEmail() {
        when(preferenceService.getOne(any())).thenReturn(preference(NotificationPreference.MODE_IMMEDIATE));
        UserNotification none = notification();
        UserNotification invalid = notification();
        assertEquals(NotificationDispatchDecision.IN_APP_ONLY,
            dispatcher.dispatch(event("DNS_CHANGED", "192.0.2.2"), none,
                watch(DomainWatch.NOTIFY_NONE, "watch@example.com"), null));
        assertEquals(NotificationDispatchDecision.IN_APP_ONLY,
            dispatcher.dispatch(event("DNS_CHANGED", "192.0.2.2"), invalid,
                watch(DomainWatch.NOTIFY_BOTH, "bad\r\nBcc: attacker@example.com"), null));
        assertEquals(null, none.getRecipientEmail());
        assertEquals(null, invalid.getRecipientEmail());
    }

    @Test
    void dispatchLocksAndUsesTheCurrentWatchSettingsInsteadOfAStaleScanCopy() {
        DomainWatchMapper watchMapper = mock(DomainWatchMapper.class);
        DomainWatch current = watch(DomainWatch.NOTIFY_NONE, "current@example.com");
        current.setStatus(DomainWatch.STATUS_ACTIVE);
        current.setDeleted(0);
        when(watchMapper.selectByIdForUpdate("watch-1")).thenReturn(current);
        when(preferenceService.getOne(any())).thenReturn(preference(NotificationPreference.MODE_IMMEDIATE));
        NotificationDispatcher lockedDispatcher = new NotificationDispatcher(
            preferenceService, notificationService, watchMapper, new NotificationPreferenceResolver());

        UserNotification notification = notification();
        NotificationDispatchDecision decision = lockedDispatcher.dispatch(
            event("DNS_CHANGED", "192.0.2.2"), notification,
            watch(DomainWatch.NOTIFY_BOTH, "stale@example.com"), null);

        assertEquals(NotificationDispatchDecision.IN_APP_ONLY, decision);
        assertEquals(null, notification.getRecipientEmail());
        verify(watchMapper).selectByIdForUpdate("watch-1");
    }

    @Test
    void dispatchLocksTheUserPolicyBeforeTheWatchAndUsesTheCurrentPreference() {
        DomainWatchMapper watchMapper = mock(DomainWatchMapper.class);
        NotificationPolicyLock policyLock = mock(NotificationPolicyLock.class);
        DomainWatch current = watch(DomainWatch.NOTIFY_BOTH, "current@example.com");
        current.setStatus(DomainWatch.STATUS_ACTIVE);
        current.setDeleted(0);
        when(policyLock.lockCurrent("user-1"))
            .thenReturn(preference(NotificationPreference.MODE_IN_APP_ONLY));
        when(watchMapper.selectByIdForUpdate("watch-1")).thenReturn(current);
        NotificationDispatcher lockedDispatcher = new NotificationDispatcher(
            preferenceService, notificationService, watchMapper,
            new NotificationPreferenceResolver(), policyLock);

        UserNotification notification = notification();
        NotificationDispatchDecision decision = lockedDispatcher.dispatch(
            event("DNS_CHANGED", "192.0.2.2"), notification,
            watch(DomainWatch.NOTIFY_BOTH, "stale@example.com"), null);

        assertEquals(NotificationDispatchDecision.IN_APP_ONLY, decision);
        InOrder order = inOrder(policyLock, watchMapper);
        order.verify(policyLock).lockCurrent("user-1");
        order.verify(watchMapper).selectByIdForUpdate("watch-1");
    }

    @Test
    void legacyExpiryThresholdSelectionIsAppliedBeforeGlobalCadence() {
        when(preferenceService.getOne(any())).thenReturn(preference(NotificationPreference.MODE_IMMEDIATE));
        MonitorEvent lateSevenDayCrossing = event("DOMAIN_EXPIRING", "2026-09-07");
        MonitorEvent lateThirtyDayCrossing = event("DOMAIN_EXPIRING", "2026-09-29");

        assertEquals(NotificationDispatchDecision.IMMEDIATE_EMAIL,
            dispatcher.dispatch(lateSevenDayCrossing, notification(),
                watch(DomainWatch.NOTIFY_7_DAYS, "watch@example.com"), 7));
        assertEquals(NotificationDispatchDecision.IN_APP_ONLY,
            dispatcher.dispatch(lateThirtyDayCrossing, notification(),
                watch(DomainWatch.NOTIFY_7_DAYS, "watch@example.com"), 30));
        assertEquals(NotificationDispatchDecision.IN_APP_ONLY,
            dispatcher.dispatch(lateSevenDayCrossing, notification(),
                watch(DomainWatch.NOTIFY_30_DAYS, "watch@example.com"), 7));
        assertEquals(NotificationDispatchDecision.IMMEDIATE_EMAIL,
            dispatcher.dispatch(lateThirtyDayCrossing, notification(),
                watch(DomainWatch.NOTIFY_30_DAYS, "watch@example.com"), 30));
        assertEquals(NotificationDispatchDecision.IN_APP_ONLY,
            dispatcher.dispatch(lateSevenDayCrossing, notification(),
                watch(DomainWatch.NOTIFY_BOTH, "watch@example.com"), 1));
    }

    private static MonitorEvent event(String eventType, String newValue) {
        MonitorEvent event = new MonitorEvent();
        event.setEventType(eventType);
        event.setRisk("WEBSITE_DOWN".equals(eventType) ? "CRITICAL" : "MEDIUM");
        event.setNewValue(newValue);
        event.setOccurredAt(Date.from(Instant.parse("2026-09-01T00:00:00Z")));
        return event;
    }

    private static UserNotification notification() {
        UserNotification notification = new UserNotification();
        notification.setId("notification-1");
        notification.setUserId("user-1");
        return notification;
    }

    private static NotificationPreference preference(String mode) {
        NotificationPreference preference = NotificationPreference.defaultsFor("user-1");
        preference.setEmailMode(mode);
        return preference;
    }

    private static DomainWatch watch(int notifyType, String notifyEmail) {
        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setNotifyType(notifyType);
        watch.setNotifyEmail(notifyEmail);
        return watch;
    }
}
