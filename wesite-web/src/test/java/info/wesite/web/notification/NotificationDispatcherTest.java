package info.wesite.web.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.service.NotificationPreferenceService;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.service.UserService;

class NotificationDispatcherTest {

    private NotificationPreferenceService preferenceService;
    private UserService userService;
    private UserNotificationService notificationService;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        preferenceService = mock(NotificationPreferenceService.class);
        userService = mock(UserService.class);
        notificationService = mock(UserNotificationService.class);
        when(notificationService.updateById(any(UserNotification.class))).thenReturn(true);
        dispatcher = new NotificationDispatcher(preferenceService, userService, notificationService);
    }

    @ParameterizedTest
    @MethodSource("explicitModes")
    void explicitEmailModeIsPersistedForEnabledCategory(String mode, NotificationDispatchDecision expected) {
        MonitorEvent event = event("DNS_CHANGED", "2026-09-08");
        UserNotification notification = notification();
        when(preferenceService.getOne(any())).thenReturn(preference(mode));
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        NotificationDispatchDecision decision = dispatcher.dispatch(event, notification);

        assertEquals(expected, decision);
        assertEquals(expected.name(), notification.getEmailMode());
        assertEquals(expected == NotificationDispatchDecision.IN_APP_ONLY ? "IN_APP_ONLY" : "QUEUED",
            notification.getEmailState());
        assertEquals(0, notification.getEmailAttemptCount());
        assertEquals(null, notification.getEmailClaimToken());
        assertEquals(null, notification.getEmailClaimUntil());
        assertEquals(null, notification.getDeliveryBatchId());
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
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        NotificationDispatchDecision decision = dispatcher.dispatch(event, notification);

        assertEquals(NotificationDispatchDecision.IN_APP_ONLY, decision);
        assertEquals("IN_APP_ONLY", notification.getEmailState());
        assertEquals("IN_APP_ONLY", notification.getEmailMode());
    }

    @Test
    void missingPreferenceUsesDailyDigestForOrdinaryChanges() {
        MonitorEvent event = event("DNS_CHANGED", "192.0.2.2");
        UserNotification notification = notification();
        when(preferenceService.getOne(any())).thenReturn(null);
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        NotificationDispatchDecision decision = dispatcher.dispatch(event, notification);

        assertEquals(NotificationDispatchDecision.DAILY_DIGEST, decision);
        assertEquals("DAILY_DIGEST", notification.getEmailMode());
        assertEquals("QUEUED", notification.getEmailState());
    }

    @Test
    void missingEmailKeepsNotificationInAppOnly() {
        MonitorEvent event = event("WEBSITE_DOWN", "false");
        UserNotification notification = notification();
        when(preferenceService.getOne(any())).thenReturn(preference(NotificationPreference.MODE_IMMEDIATE));
        when(userService.getById("user-1")).thenReturn(user("  "));

        NotificationDispatchDecision decision = dispatcher.dispatch(event, notification);

        assertEquals(NotificationDispatchDecision.IN_APP_ONLY, decision);
        assertEquals("IN_APP_ONLY", notification.getEmailState());
        assertEquals("IN_APP_ONLY", notification.getEmailMode());
    }

    @Test
    void missingPreferenceUsesImmediateEmailForHighRiskEvent() {
        MonitorEvent event = event("WEBSITE_DOWN", "false");
        UserNotification notification = notification();
        when(preferenceService.getOne(any())).thenReturn(null);
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        NotificationDispatchDecision decision = dispatcher.dispatch(event, notification);

        assertEquals(NotificationDispatchDecision.IMMEDIATE_EMAIL, decision);
        assertEquals("IMMEDIATE_EMAIL", notification.getEmailMode());
        assertEquals("QUEUED", notification.getEmailState());
    }

    private static MonitorEvent event(String eventType, String newValue) {
        MonitorEvent event = new MonitorEvent();
        event.setEventType(eventType);
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

    private static User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private static NotificationPreference preference(String mode) {
        NotificationPreference preference = NotificationPreference.defaultsFor("user-1");
        preference.setEmailMode(mode);
        return preference;
    }
}
