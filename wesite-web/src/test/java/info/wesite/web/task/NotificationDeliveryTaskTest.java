package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import info.wesite.core.entity.DomainWatchNotifyLog;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mail.Mail;
import info.wesite.core.mail.MailSendResult;
import info.wesite.core.mail.MailSender;
import info.wesite.core.mapper.DomainWatchNotifyLogMapper;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.service.DomainWatchNotifyLogService;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.UserService;
import info.wesite.web.notification.NotificationDispatchDecision;

class NotificationDeliveryTaskTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-09T00:05:00Z"), ZoneOffset.UTC);

    private UserNotificationMapper notificationMapper;
    private MonitorEventService eventService;
    private UserService userService;
    private DomainWatchNotifyLogService logService;
    private DomainWatchNotifyLogMapper logMapper;
    private MailSender mailSender;
    private NotificationDeliveryTask task;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(UserNotificationMapper.class);
        eventService = mock(MonitorEventService.class);
        userService = mock(UserService.class);
        logService = mock(DomainWatchNotifyLogService.class);
        logMapper = mock(DomainWatchNotifyLogMapper.class);
        mailSender = mock(MailSender.class);

        when(notificationMapper.claimForDelivery(anyString(), anyString(), any(Date.class))).thenReturn(1);
        when(notificationMapper.finishDelivery(anyString(), anyString(), any(), any(Date.class))).thenReturn(1);
        when(logMapper.selectMaxRetryCount(anyString(), anyString())).thenReturn(0);
        when(logService.save(any(DomainWatchNotifyLog.class))).thenReturn(true);
        when(mailSender.send(any(Mail.class))).thenReturn(MailSendResult.ok());

        task = new NotificationDeliveryTask(
            notificationMapper,
            eventService,
            userService,
            logService,
            logMapper,
            mailSender,
            CLOCK);
    }

    @Test
    void immediateQueueSendsOneEventMailAndMarksTheNotificationSent() {
        UserNotification notification = notification(
            "notification-1", "event-1", "user-1", NotificationDispatchDecision.IMMEDIATE_EMAIL.name());
        stubQueue(NotificationDispatchDecision.IMMEDIATE_EMAIL, List.of(notification));
        when(eventService.getById("event-1")).thenReturn(event("event-1", "watch-1"));
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        task.deliverImmediate();

        ArgumentCaptor<Mail> mail = ArgumentCaptor.forClass(Mail.class);
        verify(mailSender).send(mail.capture());
        assertEquals(List.of("person@example.com"), mail.getValue().getTo());
        assertEquals("email/monitor-event", mail.getValue().getTemplateName());
        verify(notificationMapper).finishDelivery(
            eq("notification-1"), eq(NotificationDeliveryTask.STATE_SENT), any(Date.class), any(Date.class));

        ArgumentCaptor<DomainWatchNotifyLog> log = ArgumentCaptor.forClass(DomainWatchNotifyLog.class);
        verify(logService).save(log.capture());
        assertEquals("notification-1", log.getValue().getNotificationId());
        assertEquals(1, log.getValue().getRetryCount());
        assertEquals(DomainWatchNotifyLog.SEND_STATUS_SUCCESS, log.getValue().getSendStatus());
    }

    @Test
    void atomicClaimLoserDoesNotSendTheSameImmediateNotification() {
        UserNotification notification = notification(
            "notification-1", "event-1", "user-1", NotificationDispatchDecision.IMMEDIATE_EMAIL.name());
        stubQueue(NotificationDispatchDecision.IMMEDIATE_EMAIL, List.of(notification));
        when(notificationMapper.claimForDelivery(
            eq("notification-1"), eq(NotificationDispatchDecision.IMMEDIATE_EMAIL.name()), any(Date.class)))
            .thenReturn(0);

        task.deliverImmediate();

        verify(mailSender, never()).send(any(Mail.class));
        verify(logService, never()).save(any(DomainWatchNotifyLog.class));
    }

    @Test
    void dailyDigestGroupsQueuedEventsByUserIntoOneMail() {
        UserNotification first = notification(
            "notification-1", "event-1", "user-1", NotificationDispatchDecision.DAILY_DIGEST.name());
        UserNotification second = notification(
            "notification-2", "event-2", "user-1", NotificationDispatchDecision.DAILY_DIGEST.name());
        stubQueue(NotificationDispatchDecision.DAILY_DIGEST, List.of(first, second));
        when(eventService.getById("event-1")).thenReturn(event("event-1", "watch-1"));
        when(eventService.getById("event-2")).thenReturn(event("event-2", "watch-2"));
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        task.deliverDailyDigest();

        ArgumentCaptor<Mail> mail = ArgumentCaptor.forClass(Mail.class);
        verify(mailSender).send(mail.capture());
        assertEquals("email/monitor-digest", mail.getValue().getTemplateName());
        assertEquals(2, events(mail.getValue()).size());
        verify(notificationMapper, times(2)).finishDelivery(
            anyString(), eq(NotificationDeliveryTask.STATE_SENT), any(Date.class), any(Date.class));
    }

    @Test
    void weeklyDigestCreatesOneMailPerUser() {
        UserNotification first = notification(
            "notification-1", "event-1", "user-1", NotificationDispatchDecision.WEEKLY_DIGEST.name());
        UserNotification second = notification(
            "notification-2", "event-2", "user-1", NotificationDispatchDecision.WEEKLY_DIGEST.name());
        UserNotification third = notification(
            "notification-3", "event-3", "user-2", NotificationDispatchDecision.WEEKLY_DIGEST.name());
        stubQueue(NotificationDispatchDecision.WEEKLY_DIGEST, List.of(first, second, third));
        when(eventService.getById(anyString())).thenAnswer(invocation ->
            event(invocation.getArgument(0), "watch-" + invocation.getArgument(0)));
        when(userService.getById("user-1")).thenReturn(user("one@example.com"));
        when(userService.getById("user-2")).thenReturn(user("two@example.com"));

        task.deliverWeeklyDigest();

        ArgumentCaptor<Mail> mails = ArgumentCaptor.forClass(Mail.class);
        verify(mailSender, times(2)).send(mails.capture());
        assertEquals(List.of(1, 2), mails.getAllValues().stream()
            .map(mail -> events(mail).size())
            .sorted()
            .toList());
    }

    @Test
    void smtpFailureKeepsTheInAppRecordAndMakesDeliveryRetryable() {
        UserNotification notification = notification(
            "notification-1", "event-1", "user-1", NotificationDispatchDecision.IMMEDIATE_EMAIL.name());
        notification.setReadAt(Date.from(Instant.parse("2026-08-08T10:00:00Z")));
        stubQueue(NotificationDispatchDecision.IMMEDIATE_EMAIL, List.of(notification));
        when(eventService.getById("event-1")).thenReturn(event("event-1", "watch-1"));
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));
        when(mailSender.send(any(Mail.class))).thenReturn(MailSendResult.fail("smtp unavailable"));

        task.deliverImmediate();

        verify(notificationMapper).finishDelivery(
            eq("notification-1"), eq(NotificationDeliveryTask.STATE_FAILED), isNull(), any(Date.class));
        assertEquals("A <changed> title", notification.getTitle());
        assertNotNull(notification.getReadAt());
        ArgumentCaptor<DomainWatchNotifyLog> log = ArgumentCaptor.forClass(DomainWatchNotifyLog.class);
        verify(logService).save(log.capture());
        assertEquals(DomainWatchNotifyLog.SEND_STATUS_FAIL, log.getValue().getSendStatus());
        assertEquals("smtp unavailable", log.getValue().getErrorMsg());
    }

    @Test
    void exhaustedNotificationDoesNotMakeAFourthDeliveryAttempt() {
        UserNotification notification = notification(
            "notification-1", "event-1", "user-1", NotificationDeliveryTask.STATE_FAILED);
        stubQueue(NotificationDispatchDecision.IMMEDIATE_EMAIL, List.of(notification));
        when(logMapper.selectMaxRetryCount(
            "notification-1", NotificationDispatchDecision.IMMEDIATE_EMAIL.name())).thenReturn(3);

        task.deliverImmediate();

        verify(mailSender, never()).send(any(Mail.class));
        verify(logService, never()).save(any(DomainWatchNotifyLog.class));
        verify(notificationMapper).finishDelivery(
            eq("notification-1"), eq(NotificationDeliveryTask.STATE_FAILED), isNull(), any(Date.class));
    }

    @Test
    void schedulesMatchImmediateDailyAndWeeklyDeliveryWindows() throws Exception {
        assertEquals("0 */5 * * * ?", cron("deliverImmediate"));
        assertEquals("0 0 8 * * ?", cron("deliverDailyDigest"));
        assertEquals("0 0 8 * * MON", cron("deliverWeeklyDigest"));
    }

    @Test
    void deliverySqlFiltersByRouteAndAttemptLimitAndClaimsWithCompareAndSet() throws Exception {
        String selection = sql(UserNotificationMapper.class.getMethod(
            "selectForDelivery", String.class, int.class, int.class).getAnnotation(Select.class).value());
        assertTrue(selection.contains("N.EMAIL_STATE = #{DELIVERYMODE}"));
        assertTrue(selection.contains("N.EMAIL_STATE = 'FAILED'"));
        assertTrue(selection.contains("ROUTE_LOG.DELIVERY_MODE = #{DELIVERYMODE}"));
        assertTrue(selection.contains("< #{MAXATTEMPTS}"));
        assertFalse(selection.contains("N.EMAIL_STATE = 'SENT'"));

        String claim = sql(UserNotificationMapper.class.getMethod(
            "claimForDelivery", String.class, String.class, Date.class).getAnnotation(Update.class).value());
        assertTrue(claim.contains("SET EMAIL_STATE = 'SENDING'"));
        assertTrue(claim.contains("WHERE ID = #{ID} AND EMAIL_STATE = #{EXPECTEDSTATE}"));
    }

    @Test
    void templatesEscapeEventValuesInsteadOfRenderingInjectedMarkup() {
        SpringTemplateEngine engine = templateEngine();
        Context eventContext = new Context();
        eventContext.setVariable("event", Map.of(
            "title", "<script>alert(1)</script>",
            "content", "<img src=x onerror=alert(2)>",
            "eventType", "DNS_CHANGED",
            "occurredAt", "2026-08-09 00:00 UTC",
            "targetUrl", "https://whose.domains"));

        String eventHtml = engine.process("email/monitor-event", eventContext);

        assertFalse(eventHtml.contains("<script>alert(1)</script>"));
        assertFalse(eventHtml.contains("<img src=x onerror=alert(2)>"));
        assertTrue(eventHtml.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));

        Context digestContext = new Context();
        digestContext.setVariable("events", List.of(Map.of(
            "title", "<script>alert(3)</script>",
            "content", "safe",
            "eventType", "DNS_CHANGED",
            "occurredAt", "2026-08-09 00:00 UTC",
            "targetUrl", "https://whose.domains")));
        digestContext.setVariable("period", "Daily");

        String digestHtml = engine.process("email/monitor-digest", digestContext);

        assertFalse(digestHtml.contains("<script>alert(3)</script>"));
        assertTrue(digestHtml.contains("&lt;script&gt;alert(3)&lt;/script&gt;"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Mail mail) {
        return (List<Map<String, Object>>) mail.getTemplateVariables().get("events");
    }

    private void stubQueue(NotificationDispatchDecision mode, List<UserNotification> notifications) {
        when(notificationMapper.selectForDelivery(mode.name(), NotificationDeliveryTask.MAX_ATTEMPTS, 500))
            .thenReturn(notifications);
    }

    private static UserNotification notification(String id, String eventId, String userId, String emailState) {
        UserNotification notification = new UserNotification();
        notification.setId(id);
        notification.setEventId(eventId);
        notification.setUserId(userId);
        notification.setEmailState(emailState);
        notification.setTitle("A <changed> title");
        notification.setContent("Old <value> -> new & value");
        notification.setTargetPath("/domain/example.com");
        notification.setCreateTime(Date.from(Instant.parse("2026-08-09T00:00:00Z")));
        return notification;
    }

    private static MonitorEvent event(String id, String watchId) {
        MonitorEvent event = new MonitorEvent();
        event.setId(id);
        event.setWatchId(watchId);
        event.setEventType("DNS_CHANGED");
        event.setOldValue("<old>");
        event.setNewValue("<new>");
        event.setOccurredAt(Date.from(Instant.parse("2026-08-09T00:00:00Z")));
        return event;
    }

    private static User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private static String cron(String methodName) throws Exception {
        Method method = NotificationDeliveryTask.class.getMethod(methodName);
        return method.getAnnotation(Scheduled.class).cron();
    }

    private static SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static String sql(String[] value) {
        return String.join(" ", value)
            .replaceAll("\\s+", " ")
            .trim()
            .toUpperCase(java.util.Locale.ROOT);
    }
}
