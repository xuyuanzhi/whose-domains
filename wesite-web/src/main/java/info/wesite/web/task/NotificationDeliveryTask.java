package info.wesite.web.task;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.BaseEntity;
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
import info.wesite.core.utils.RandomUtils;
import info.wesite.web.notification.NotificationDispatchDecision;

/**
 * Claims queued notification routes and performs SMTP delivery outside the
 * event-publication transaction. In-app records therefore survive any SMTP
 * failure.
 */
@Profile({"prod", "mac"})
@Component
public class NotificationDeliveryTask {

    static final int MAX_ATTEMPTS = 3;
    static final String STATE_SENDING = "SENDING";
    static final String STATE_SENT = "SENT";
    static final String STATE_FAILED = "FAILED";

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryTask.class);
    private static final int BATCH_SIZE = 500;
    private static final String BASE_URL = "https://whose.domains";
    private static final DateTimeFormatter OCCURRED_AT_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);

    private final UserNotificationMapper notificationMapper;
    private final MonitorEventService eventService;
    private final UserService userService;
    private final DomainWatchNotifyLogService logService;
    private final DomainWatchNotifyLogMapper logMapper;
    private final MailSender mailSender;
    private final Clock clock;

    @Autowired
    public NotificationDeliveryTask(
        UserNotificationMapper notificationMapper,
        MonitorEventService eventService,
        UserService userService,
        DomainWatchNotifyLogService logService,
        DomainWatchNotifyLogMapper logMapper,
        Optional<MailSender> mailSender) {
        this(
            notificationMapper,
            eventService,
            userService,
            logService,
            logMapper,
            mailSender.orElse(null),
            Clock.systemUTC());
    }

    NotificationDeliveryTask(
        UserNotificationMapper notificationMapper,
        MonitorEventService eventService,
        UserService userService,
        DomainWatchNotifyLogService logService,
        DomainWatchNotifyLogMapper logMapper,
        MailSender mailSender,
        Clock clock) {
        this.notificationMapper = Objects.requireNonNull(notificationMapper, "notificationMapper");
        this.eventService = Objects.requireNonNull(eventService, "eventService");
        this.userService = Objects.requireNonNull(userService, "userService");
        this.logService = Objects.requireNonNull(logService, "logService");
        this.logMapper = Objects.requireNonNull(logMapper, "logMapper");
        this.mailSender = mailSender;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(cron = "0 */5 * * * ?")
    public void deliverImmediate() {
        deliver(NotificationDispatchDecision.IMMEDIATE_EMAIL, false, "Immediate");
    }

    @Scheduled(cron = "0 0 8 * * ?")
    public void deliverDailyDigest() {
        deliver(NotificationDispatchDecision.DAILY_DIGEST, true, "Daily");
    }

    @Scheduled(cron = "0 0 8 * * MON")
    public void deliverWeeklyDigest() {
        deliver(NotificationDispatchDecision.WEEKLY_DIGEST, true, "Weekly");
    }

    private void deliver(NotificationDispatchDecision mode, boolean digest, String period) {
        if (mailSender == null) {
            log.debug("MailSender is not configured; leaving {} notifications queued", mode);
            return;
        }

        List<UserNotification> candidates = notificationMapper.selectForDelivery(
            mode.name(), MAX_ATTEMPTS, BATCH_SIZE);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        Map<String, List<DeliveryItem>> claimedByUser = new LinkedHashMap<>();
        for (UserNotification notification : candidates) {
            DeliveryItem item = claim(notification, mode);
            if (item != null) {
                claimedByUser.computeIfAbsent(notification.getUserId(), ignored -> new ArrayList<>()).add(item);
            }
        }

        for (List<DeliveryItem> userItems : claimedByUser.values()) {
            if (digest) {
                send(userItems, mode, true, period);
            } else {
                for (DeliveryItem item : userItems) {
                    send(List.of(item), mode, false, period);
                }
            }
        }
    }

    private DeliveryItem claim(UserNotification notification, NotificationDispatchDecision mode) {
        if (notification == null || StringUtils.isBlank(notification.getId())) {
            return null;
        }
        Date claimedAt = now();
        String expectedState = notification.getEmailState();
        if (notificationMapper.claimForDelivery(notification.getId(), expectedState, claimedAt) != 1) {
            return null;
        }

        int attempt = maxAttempt(notification.getId(), mode.name()) + 1;
        if (attempt > MAX_ATTEMPTS) {
            finish(notification.getId(), STATE_FAILED, null);
            return null;
        }

        MonitorEvent event = eventService.getById(notification.getEventId());
        if (event == null) {
            log.warn("Cannot deliver notification {} because event {} is missing",
                notification.getId(), notification.getEventId());
            finish(notification.getId(), STATE_FAILED, null);
            return null;
        }
        return new DeliveryItem(notification, event, attempt);
    }

    private int maxAttempt(String notificationId, String mode) {
        Integer value = logMapper.selectMaxRetryCount(notificationId, mode);
        return value == null ? 0 : Math.max(0, value);
    }

    private void send(
        List<DeliveryItem> items,
        NotificationDispatchDecision mode,
        boolean digest,
        String period) {
        if (items.isEmpty()) {
            return;
        }
        User user = userService.getById(items.get(0).notification().getUserId());
        String email = user == null ? null : user.getEmail();
        Mail mail = StringUtils.isBlank(email) ? null : buildMail(email, items, digest, period);
        MailSendResult result = mail == null
            ? MailSendResult.fail("recipient email unavailable")
            : sendSafely(mail);

        Date completedAt = now();
        for (DeliveryItem item : items) {
            saveAttempt(item, mode, mail, result, completedAt);
            finish(
                item.notification().getId(),
                result.isSuccess() ? STATE_SENT : STATE_FAILED,
                result.isSuccess() ? completedAt : null);
        }
    }

    private MailSendResult sendSafely(Mail mail) {
        try {
            MailSendResult result = mailSender.send(mail);
            return result == null ? MailSendResult.fail("mail sender returned no result") : result;
        } catch (RuntimeException failure) {
            log.error("Notification SMTP delivery failed", failure);
            return MailSendResult.fail(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private Mail buildMail(String email, List<DeliveryItem> items, boolean digest, String period) {
        if (!digest) {
            Map<String, Object> event = eventView(items.get(0));
            return Mail.builder()
                .to(List.of(email))
                .subject("Whose.Domains monitoring alert")
                .templateName("email/monitor-event")
                .templateVariables(Map.of("event", event))
                .plainTextContent(event.get("title") + "\n\n" + event.get("content"))
                .build();
        }

        List<Map<String, Object>> events = items.stream().map(this::eventView).toList();
        String subject = "Whose.Domains " + period.toLowerCase(java.util.Locale.ROOT) + " monitoring digest";
        String plainText = events.stream()
            .map(event -> event.get("title") + ": " + event.get("content"))
            .collect(java.util.stream.Collectors.joining("\n"));
        return Mail.builder()
            .to(List.of(email))
            .subject(subject)
            .templateName("email/monitor-digest")
            .templateVariables(Map.of("period", period, "events", events, "notificationCenterUrl",
                BASE_URL + "/user/notifications"))
            .plainTextContent(plainText)
            .build();
    }

    private Map<String, Object> eventView(DeliveryItem item) {
        UserNotification notification = item.notification();
        MonitorEvent event = item.event();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("title", StringUtils.defaultString(notification.getTitle()));
        view.put("content", StringUtils.defaultString(notification.getContent()));
        view.put("eventType", StringUtils.defaultString(event.getEventType()));
        view.put("occurredAt", event.getOccurredAt() == null
            ? ""
            : OCCURRED_AT_FORMAT.format(event.getOccurredAt().toInstant()));
        view.put("targetUrl", safeTargetUrl(notification.getTargetPath()));
        return Map.copyOf(view);
    }

    private static String safeTargetUrl(String targetPath) {
        if (StringUtils.isBlank(targetPath)
            || !targetPath.startsWith("/")
            || targetPath.startsWith("//")) {
            return BASE_URL + "/user/notifications";
        }
        return BASE_URL + targetPath;
    }

    private void saveAttempt(
        DeliveryItem item,
        NotificationDispatchDecision mode,
        Mail mail,
        MailSendResult result,
        Date completedAt) {
        DomainWatchNotifyLog entry = new DomainWatchNotifyLog();
        entry.setId(RandomUtils.generateId());
        entry.setStatus(BaseEntity.STATUS_ACTIVE);
        entry.setDeleted(0);
        entry.setCreateTime(completedAt);
        entry.setNotificationId(item.notification().getId());
        entry.setEventId(item.event().getId());
        entry.setDeliveryMode(mode.name());
        entry.setSubject(mail == null ? null : mail.getSubject());
        entry.setWatchId(item.event().getWatchId());
        entry.setToEmail(mail == null ? null : mail.getTo().get(0));
        entry.setDomainName(domainName(item.notification().getTargetPath()));
        entry.setDaysLeft(daysLeft(item.event()));
        entry.setSentAt(completedAt);
        entry.setSendStatus(result.isSuccess()
            ? DomainWatchNotifyLog.SEND_STATUS_SUCCESS
            : DomainWatchNotifyLog.SEND_STATUS_FAIL);
        entry.setErrorMsg(result.isSuccess() ? null : result.getErrorMessage());
        entry.setRetryCount(item.attempt());
        try {
            if (!logService.save(entry)) {
                log.error("Failed to save notification delivery attempt for notification {}",
                    item.notification().getId());
            }
        } catch (RuntimeException failure) {
            log.error("Failed to save notification delivery attempt for notification {}",
                item.notification().getId(), failure);
        }
    }

    private static String domainName(String targetPath) {
        String prefix = "/domain/";
        return targetPath != null && targetPath.startsWith(prefix)
            ? targetPath.substring(prefix.length())
            : null;
    }

    private static Integer daysLeft(MonitorEvent event) {
        if (!"DOMAIN_EXPIRING".equals(event.getEventType())
            || event.getOccurredAt() == null
            || StringUtils.isBlank(event.getNewValue())) {
            return null;
        }
        try {
            LocalDate occurred = event.getOccurredAt().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            return Math.toIntExact(ChronoUnit.DAYS.between(occurred, LocalDate.parse(event.getNewValue())));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void finish(String notificationId, String state, Date emailedAt) {
        Date updatedAt = now();
        if (notificationMapper.finishDelivery(notificationId, state, emailedAt, updatedAt) != 1) {
            log.warn("Notification {} left SENDING before it could be marked {}", notificationId, state);
        }
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    private record DeliveryItem(UserNotification notification, MonitorEvent event, int attempt) {
    }
}
