package info.wesite.web.task;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mail.Mail;
import info.wesite.core.mail.MailSendResult;
import info.wesite.core.mail.MailSender;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.mapper.model.NotificationDigestRecipientRow;
import info.wesite.core.service.MonitorEventService;
import info.wesite.web.notification.DeliveryAttemptDetails;
import info.wesite.web.notification.DeliveryBatchClaim;
import info.wesite.web.notification.NotificationDeliveryCoordinator;
import info.wesite.web.notification.NotificationDispatchDecision;

/** Delivers only attempts that already have a durable batch lease and pending audit log. */
@Profile({"prod", "mac"})
@Component
public class NotificationDeliveryTask {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryTask.class);
    private static final int IMMEDIATE_PAGE_SIZE = 500;
    private static final int DIGEST_USER_PAGE_SIZE = 100;
    private static final long LEASE_SECONDS = 30 * 60;
    private static final String BASE_URL = "https://whose.domains";
    private static final DateTimeFormatter OCCURRED_AT_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);

    private final UserNotificationMapper notificationMapper;
    private final MonitorEventService eventService;
    private final NotificationDeliveryCoordinator coordinator;
    private final MailSender mailSender;
    private final Clock clock;

    @Autowired
    public NotificationDeliveryTask(
        UserNotificationMapper notificationMapper,
        MonitorEventService eventService,
        NotificationDeliveryCoordinator coordinator,
        Optional<MailSender> mailSender) {
        this(
            notificationMapper,
            eventService,
            coordinator,
            mailSender.orElse(null),
            Clock.systemUTC());
    }

    NotificationDeliveryTask(
        UserNotificationMapper notificationMapper,
        MonitorEventService eventService,
        NotificationDeliveryCoordinator coordinator,
        MailSender mailSender,
        Clock clock) {
        this.notificationMapper = Objects.requireNonNull(notificationMapper, "notificationMapper");
        this.eventService = Objects.requireNonNull(eventService, "eventService");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.mailSender = mailSender;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void deliverImmediate() {
        deliver(NotificationDispatchDecision.IMMEDIATE_EMAIL, false, "Immediate");
    }

    public void deliverDailyDigest() {
        deliver(NotificationDispatchDecision.DAILY_DIGEST, true, "Daily");
    }

    public void deliverWeeklyDigest() {
        deliver(NotificationDispatchDecision.WEEKLY_DIGEST, true, "Weekly");
    }

    /** Recovers digest leases and retries durable failures without claiming a new digest window. */
    public void recoverDigestDeliveries() {
        if (mailSender == null) {
            return;
        }
        recoverAndRetry(NotificationDispatchDecision.DAILY_DIGEST, true, "Daily");
        recoverAndRetry(NotificationDispatchDecision.WEEKLY_DIGEST, true, "Weekly");
    }

    private void deliver(NotificationDispatchDecision mode, boolean digest, String period) {
        if (mailSender == null) {
            log.debug("MailSender is not configured; leaving {} notifications queued", mode);
            return;
        }

        recoverAndRetry(mode, digest, period);
        if (digest) {
            startDigestBatches(mode, period);
        } else {
            startImmediateBatches(period);
        }
    }

    private void recoverAndRetry(NotificationDispatchDecision mode, boolean digest, String period) {
        try {
            coordinator.finalizeExpiredCancelled(mode.name(), clock.instant());
            coordinator.finalizeExpiredExhausted(mode.name(), clock.instant());
        } catch (RuntimeException failure) {
            log.error("Failed to finalize exhausted {} notification leases", mode, failure);
            return;
        }

        while (true) {
            Optional<DeliveryBatchClaim> retry;
            Instant now = clock.instant();
            try {
                retry = coordinator.retryNext(mode.name(), now, leaseUntil(now));
            } catch (RuntimeException failure) {
                log.error("Failed to start a durable {} retry", mode, failure);
                return;
            }
            if (retry.isEmpty()) {
                return;
            }
            deliverClaimSafely(retry.orElseThrow(), digest, period);
        }
    }

    private void startImmediateBatches(String period) {
        String afterId = "";
        while (true) {
            List<String> ids = notificationMapper.selectImmediateCandidateIds(afterId, IMMEDIATE_PAGE_SIZE);
            if (ids == null || ids.isEmpty()) {
                return;
            }
            for (String id : ids) {
                Instant now = clock.instant();
                try {
                    coordinator.startImmediate(id, now, leaseUntil(now))
                        .ifPresent(claim -> deliverClaimSafely(claim, false, period));
                } catch (RuntimeException failure) {
                    log.error("Failed to durably start immediate notification {}", id, failure);
                }
            }
            afterId = ids.get(ids.size() - 1);
            if (ids.size() < IMMEDIATE_PAGE_SIZE) {
                return;
            }
        }
    }

    private void startDigestBatches(NotificationDispatchDecision mode, String period) {
        Instant cutoff = clock.instant();
        String windowKey = windowKey(mode, cutoff);
        String afterUserId = "";
        String afterRecipientEmail = "";
        while (true) {
            List<NotificationDigestRecipientRow> candidates = notificationMapper.selectDigestCandidates(
                mode.name(), Date.from(cutoff), afterUserId, afterRecipientEmail, DIGEST_USER_PAGE_SIZE);
            if (candidates == null || candidates.isEmpty()) {
                return;
            }
            for (NotificationDigestRecipientRow candidate : candidates) {
                Instant now = clock.instant();
                try {
                    coordinator.startDigest(
                        candidate.getUserId(), mode.name(), windowKey, candidate.getRecipientEmail(),
                        cutoff, now, leaseUntil(now))
                        .ifPresent(claim -> deliverClaimSafely(claim, true, period));
                } catch (RuntimeException failure) {
                    log.error("Failed to durably start {} digest for user {}", period,
                        candidate.getUserId(), failure);
                }
            }
            NotificationDigestRecipientRow last = candidates.get(candidates.size() - 1);
            afterUserId = last.getUserId();
            afterRecipientEmail = last.getRecipientEmail();
            if (candidates.size() < DIGEST_USER_PAGE_SIZE) {
                return;
            }
        }
    }

    private void deliverClaimSafely(DeliveryBatchClaim claim, boolean digest, String period) {
        DeliveryOutcome outcome;
        try {
            outcome = attemptDelivery(claim, digest, period);
        } catch (RuntimeException failure) {
            outcome = DeliveryOutcome.failed(errorMessage(failure));
        }

        Instant completedAt = clock.instant();
        try {
            coordinator.complete(
                claim,
                outcome.success(),
                outcome.details(),
                completedAt,
                completedAt.plus(5L * Math.max(1, claim.attempt()), ChronoUnit.MINUTES));
        } catch (RuntimeException persistenceFailure) {
            log.error("Delivery batch {} remains leased because completion persistence failed",
                claim.batchId(), persistenceFailure);
        }
    }

    private DeliveryOutcome attemptDelivery(DeliveryBatchClaim claim, boolean digest, String period) {
        List<UserNotification> notifications = notificationMapper.selectBatchMembers(claim.batchId());
        if (notifications == null || notifications.isEmpty()) {
            throw new IllegalStateException("delivery batch has no notification members");
        }

        List<String> eventIds = notifications.stream()
            .map(UserNotification::getEventId)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        List<MonitorEvent> loaded = eventIds.isEmpty() ? List.of() : eventService.listByIds(eventIds);
        Map<String, MonitorEvent> eventsById = loaded == null
            ? Map.of()
            : loaded.stream().collect(java.util.stream.Collectors.toMap(
                MonitorEvent::getId, java.util.function.Function.identity(), (first, ignored) -> first));
        List<DeliveryItem> items = new ArrayList<>(notifications.size());
        for (UserNotification notification : notifications) {
            MonitorEvent event = eventsById.get(notification.getEventId());
            if (event == null) {
                throw new IllegalStateException("monitor event is missing: " + notification.getEventId());
            }
            items.add(new DeliveryItem(notification, event));
        }

        String email = claim.recipientEmail();
        if (StringUtils.isBlank(email)) {
            return DeliveryOutcome.failed("recipient email unavailable");
        }

        Mail mail = buildMail(email, items, digest, period);
        MailSendResult result = sendSafely(mail);
        DeliveryItem auditItem = digest ? null : items.get(0);
        DeliveryAttemptDetails details = details(
            auditItem,
            email,
            mail.getSubject(),
            result.isSuccess() ? null : result.getErrorMessage());
        return new DeliveryOutcome(result.isSuccess(), details);
    }

    private MailSendResult sendSafely(Mail mail) {
        try {
            MailSendResult result = mailSender.send(mail);
            return result == null ? MailSendResult.fail("mail sender returned no result") : result;
        } catch (RuntimeException failure) {
            return MailSendResult.fail(errorMessage(failure));
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
        String subject = "Whose.Domains " + period.toLowerCase(Locale.ROOT) + " monitoring digest";
        String plainText = events.stream()
            .map(event -> event.get("title") + ": " + event.get("content"))
            .collect(java.util.stream.Collectors.joining("\n"));
        return Mail.builder()
            .to(List.of(email))
            .subject(subject)
            .templateName("email/monitor-digest")
            .templateVariables(Map.of(
                "period", period,
                "events", events,
                "notificationCenterUrl", BASE_URL + "/user/notifications"))
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

    private static DeliveryAttemptDetails details(
        DeliveryItem item,
        String email,
        String subject,
        String error) {
        if (item == null) {
            return new DeliveryAttemptDetails(null, null, null, email, null, null, subject, error);
        }
        return new DeliveryAttemptDetails(
            item.notification().getId(),
            item.event().getId(),
            item.event().getWatchId(),
            email,
            domainName(item.notification().getTargetPath()),
            daysLeft(item.event()),
            subject,
            error);
    }

    private static String safeTargetUrl(String targetPath) {
        if (StringUtils.isBlank(targetPath)
            || !targetPath.startsWith("/")
            || targetPath.startsWith("//")) {
            return BASE_URL + "/user/notifications";
        }
        return BASE_URL + targetPath;
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

    private static String windowKey(NotificationDispatchDecision mode, Instant cutoff) {
        LocalDate date = cutoff.atZone(ZoneOffset.UTC).toLocalDate();
        if (mode == NotificationDispatchDecision.DAILY_DIGEST) {
            return date.toString();
        }
        WeekFields iso = WeekFields.ISO;
        return "%04d-W%02d".formatted(
            date.get(iso.weekBasedYear()),
            date.get(iso.weekOfWeekBasedYear()));
    }

    private static Instant leaseUntil(Instant now) {
        return now.plusSeconds(LEASE_SECONDS);
    }

    private static String errorMessage(RuntimeException failure) {
        String message = StringUtils.defaultIfBlank(failure.getMessage(), "no detail");
        return failure.getClass().getSimpleName() + ": " + message;
    }

    private record DeliveryItem(UserNotification notification, MonitorEvent event) {
    }

    private record DeliveryOutcome(boolean success, DeliveryAttemptDetails details) {
        private static DeliveryOutcome failed(String error) {
            return new DeliveryOutcome(
                false,
                new DeliveryAttemptDetails(null, null, null, null, null, null, null, error));
        }
    }
}
