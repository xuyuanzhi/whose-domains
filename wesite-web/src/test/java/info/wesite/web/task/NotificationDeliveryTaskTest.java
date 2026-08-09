package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import java.util.Optional;
import java.util.stream.IntStream;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mail.Mail;
import info.wesite.core.mail.MailSendResult;
import info.wesite.core.mail.MailSender;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.UserService;
import info.wesite.web.notification.DeliveryAttemptDetails;
import info.wesite.web.notification.DeliveryBatchClaim;
import info.wesite.web.notification.NotificationDeliveryCoordinator;

class NotificationDeliveryTaskTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-09T00:05:00Z"), ZoneOffset.UTC);

    private UserNotificationMapper notificationMapper;
    private MonitorEventService eventService;
    private UserService userService;
    private NotificationDeliveryCoordinator coordinator;
    private MailSender mailSender;
    private NotificationDeliveryTask task;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(UserNotificationMapper.class);
        eventService = mock(MonitorEventService.class);
        userService = mock(UserService.class);
        coordinator = mock(NotificationDeliveryCoordinator.class);
        mailSender = mock(MailSender.class);
        when(coordinator.retryNext(anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(Optional.empty());
        when(notificationMapper.selectImmediateCandidateIds(anyString(), eq(500))).thenReturn(List.of());
        when(notificationMapper.selectDigestCandidateUserIds(
            anyString(), any(Date.class), anyString(), eq(100))).thenReturn(List.of());
        when(mailSender.send(any(Mail.class))).thenReturn(MailSendResult.ok());
        task = new NotificationDeliveryTask(
            notificationMapper, eventService, userService, coordinator, mailSender, CLOCK);
    }

    @Test
    void durableStartCompletesBeforeImmediateSmtpBegins() {
        DeliveryBatchClaim claim = claim("batch-1", "notification-1", 1);
        when(notificationMapper.selectImmediateCandidateIds("", 500))
            .thenReturn(List.of("notification-1"), List.of());
        when(coordinator.startImmediate(eq("notification-1"), any(Instant.class), any(Instant.class)))
            .thenReturn(Optional.of(claim));
        when(notificationMapper.selectBatchMembers("batch-1"))
            .thenReturn(List.of(notification("notification-1", "event-1")));
        when(eventService.getById("event-1")).thenReturn(event("event-1"));
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        task.deliverImmediate();

        InOrder order = inOrder(coordinator, mailSender);
        order.verify(coordinator).startImmediate(eq("notification-1"), any(Instant.class), any(Instant.class));
        order.verify(mailSender).send(any(Mail.class));
        order.verify(coordinator).complete(eq(claim), eq(true), any(DeliveryAttemptDetails.class),
            any(Instant.class), any(Instant.class));
    }

    @Test
    void failedDurableStartNeverCallsSmtp() {
        when(notificationMapper.selectImmediateCandidateIds("", 500))
            .thenReturn(List.of("notification-1"), List.of());
        when(coordinator.startImmediate(eq("notification-1"), any(Instant.class), any(Instant.class)))
            .thenThrow(new IllegalStateException("pending log insert failed"));

        task.deliverImmediate();

        verify(mailSender, never()).send(any(Mail.class));
    }

    @Test
    void dependencyFailureAfterClaimIsRecordedAsAFailedAttempt() {
        DeliveryBatchClaim claim = claim("batch-1", "notification-1", 1);
        when(notificationMapper.selectImmediateCandidateIds("", 500))
            .thenReturn(List.of("notification-1"), List.of());
        when(coordinator.startImmediate(eq("notification-1"), any(Instant.class), any(Instant.class)))
            .thenReturn(Optional.of(claim));
        when(notificationMapper.selectBatchMembers("batch-1"))
            .thenThrow(new IllegalStateException("notification lookup failed"));

        task.deliverImmediate();

        ArgumentCaptor<DeliveryAttemptDetails> details = ArgumentCaptor.forClass(DeliveryAttemptDetails.class);
        verify(coordinator).complete(eq(claim), eq(false), details.capture(),
            any(Instant.class), any(Instant.class));
        assertTrue(details.getValue().errorMessage().contains("notification lookup failed"));
        verify(mailSender, never()).send(any(Mail.class));
    }

    @Test
    void dailyDigestContainsAll501AtomicallyAssignedEvents() {
        DeliveryBatchClaim claim = new DeliveryBatchClaim(
            "batch-1", "user-1", "DAILY_DIGEST", "2026-08-09", 1, "claim-1");
        List<UserNotification> notifications = IntStream.rangeClosed(1, 501)
            .mapToObj(index -> notification("notification-" + index, "event-" + index))
            .toList();
        when(notificationMapper.selectDigestCandidateUserIds(
            eq("DAILY_DIGEST"), any(Date.class), eq(""), eq(100)))
            .thenReturn(List.of("user-1"));
        when(coordinator.startDigest(eq("user-1"), eq("DAILY_DIGEST"), eq("2026-08-09"),
            any(Instant.class), any(Instant.class), any(Instant.class))).thenReturn(Optional.of(claim));
        when(notificationMapper.selectBatchMembers("batch-1")).thenReturn(notifications);
        when(eventService.getById(anyString())).thenAnswer(invocation -> event(invocation.getArgument(0)));
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        task.deliverDailyDigest();

        ArgumentCaptor<Mail> mail = ArgumentCaptor.forClass(Mail.class);
        verify(mailSender).send(mail.capture());
        assertEquals(501, events(mail.getValue()).size());
    }

    @Test
    void immediateQueueDrainsPastTheFirst500CandidatePage() {
        List<String> firstPage = IntStream.rangeClosed(1, 500).mapToObj(index -> "n" + index).toList();
        when(notificationMapper.selectImmediateCandidateIds("", 500)).thenReturn(firstPage);
        when(notificationMapper.selectImmediateCandidateIds("n500", 500)).thenReturn(List.of("n501"));
        when(coordinator.startImmediate(anyString(), any(Instant.class), any(Instant.class)))
            .thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                return Optional.of(claim("batch-" + id, id, 1));
            });
        when(notificationMapper.selectBatchMembers(anyString())).thenAnswer(invocation -> {
            String batchId = invocation.getArgument(0);
            String id = batchId.substring("batch-".length());
            return List.of(notification(id, "event-" + id));
        });
        when(eventService.getById(anyString())).thenAnswer(invocation -> event(invocation.getArgument(0)));
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        task.deliverImmediate();

        verify(notificationMapper).selectImmediateCandidateIds("", 500);
        verify(notificationMapper).selectImmediateCandidateIds("n500", 500);
        verify(mailSender, times(501)).send(any(Mail.class));
    }

    @Test
    void thirdRetryIsDeliveredOnceAndNoFourthAttemptIsRequestedByTheTask() {
        DeliveryBatchClaim third = claim("batch-1", "notification-1", 3);
        when(coordinator.retryNext(eq("IMMEDIATE_EMAIL"), any(Instant.class), any(Instant.class)))
            .thenReturn(Optional.of(third), Optional.empty());
        when(notificationMapper.selectBatchMembers("batch-1"))
            .thenReturn(List.of(notification("notification-1", "event-1")));
        when(eventService.getById("event-1")).thenReturn(event("event-1"));
        when(userService.getById("user-1")).thenReturn(user("person@example.com"));

        task.deliverImmediate();

        verify(mailSender).send(any(Mail.class));
        verify(coordinator, times(2)).retryNext(
            eq("IMMEDIATE_EMAIL"), any(Instant.class), any(Instant.class));
        verify(coordinator).complete(eq(third), eq(true), any(), any(), any());
    }

    @Test
    void digestSqlClaimsOneUserWindowWithoutALimitOrImmediateItems() throws Exception {
        String assignment = sql(UserNotificationMapper.class.getMethod(
            "assignDigestToBatch", String.class, String.class, String.class, Date.class, Date.class)
            .getAnnotation(Update.class).value());
        assertTrue(assignment.contains("USER_ID = #{USERID}"));
        assertTrue(assignment.contains("EMAIL_MODE = #{EMAILMODE}"));
        assertTrue(assignment.contains("EMAIL_STATE = 'QUEUED'"));
        assertTrue(assignment.contains("DELIVERY_BATCH_ID IS NULL"));
        assertFalse(assignment.contains("LIMIT"));

        String members = sql(UserNotificationMapper.class.getMethod(
            "selectBatchMembers", String.class).getAnnotation(Select.class).value());
        assertFalse(members.contains("LIMIT"));

        String users = sql(UserNotificationMapper.class.getMethod(
            "selectDigestCandidateUserIds", String.class, Date.class, String.class, int.class)
            .getAnnotation(Select.class).value());
        assertTrue(users.contains("SELECT DISTINCT USER_ID"));
        assertTrue(users.contains("USER_ID > #{AFTERUSERID}"));
    }

    @Test
    void schedulesMatchImmediateDailyAndWeeklyDeliveryWindows() throws Exception {
        assertEquals("0 */5 * * * ?", cron("deliverImmediate"));
        assertEquals("0 0 8 * * ?", cron("deliverDailyDigest"));
        assertEquals("0 0 8 * * MON", cron("deliverWeeklyDigest"));
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

    private static DeliveryBatchClaim claim(String batchId, String notificationId, int attempt) {
        return new DeliveryBatchClaim(
            batchId, "user-1", "IMMEDIATE_EMAIL", notificationId, attempt, "claim-" + attempt);
    }

    private static UserNotification notification(String id, String eventId) {
        UserNotification notification = new UserNotification();
        notification.setId(id);
        notification.setEventId(eventId);
        notification.setUserId("user-1");
        notification.setEmailMode("DAILY_DIGEST");
        notification.setEmailState("CLAIMED");
        notification.setTitle("A <changed> title");
        notification.setContent("Old <value> -> new & value");
        notification.setTargetPath("/domain/example.com");
        notification.setCreateTime(Date.from(Instant.parse("2026-08-09T00:00:00Z")));
        return notification;
    }

    private static MonitorEvent event(String id) {
        MonitorEvent event = new MonitorEvent();
        event.setId(id);
        event.setWatchId("watch-1");
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
