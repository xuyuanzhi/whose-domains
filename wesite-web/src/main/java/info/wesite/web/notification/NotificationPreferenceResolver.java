package info.wesite.web.notification;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.web.monitor.MonitorEventType;

/**
 * Resolves a notification's delivery route without performing delivery.
 */
public class NotificationPreferenceResolver {

    public NotificationDispatchDecision resolve(
        MonitorEvent event,
        NotificationPreference preference,
        boolean hasEmail) {
        MonitorEventType eventType = eventType(event);
        if (!hasEmail || eventType == null) {
            return NotificationDispatchDecision.IN_APP_ONLY;
        }

        if (preference != null && !isCategoryEnabled(preference, eventType)) {
            return NotificationDispatchDecision.IN_APP_ONLY;
        }

        if (preference == null || StringUtils.isBlank(preference.getEmailMode())) {
            return defaultDecision(event, eventType);
        }

        return switch (preference.getEmailMode()) {
            case NotificationPreference.MODE_IMMEDIATE -> NotificationDispatchDecision.IMMEDIATE_EMAIL;
            case NotificationPreference.MODE_DAILY -> NotificationDispatchDecision.DAILY_DIGEST;
            case NotificationPreference.MODE_WEEKLY -> NotificationDispatchDecision.WEEKLY_DIGEST;
            case NotificationPreference.MODE_IN_APP_ONLY -> NotificationDispatchDecision.IN_APP_ONLY;
            default -> NotificationDispatchDecision.IN_APP_ONLY;
        };
    }

    private static MonitorEventType eventType(MonitorEvent event) {
        if (event == null || StringUtils.isBlank(event.getEventType())) {
            return null;
        }
        try {
            return MonitorEventType.valueOf(event.getEventType());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isCategoryEnabled(NotificationPreference preference, MonitorEventType eventType) {
        return switch (eventType) {
            case DOMAIN_EXPIRING -> Boolean.TRUE.equals(preference.getDomainExpiryEnabled());
            case SSL_EXPIRING -> Boolean.TRUE.equals(preference.getSslExpiryEnabled());
            case DOMAIN_STATUS_CHANGED -> Boolean.TRUE.equals(preference.getDomainStatusEnabled());
            case DNS_CHANGED -> Boolean.TRUE.equals(preference.getDnsChangeEnabled());
            case WEBSITE_DOWN, WEBSITE_RECOVERED -> Boolean.TRUE.equals(preference.getWebsiteAvailabilityEnabled());
        };
    }

    private static NotificationDispatchDecision defaultDecision(MonitorEvent event, MonitorEventType eventType) {
        return isHighRisk(event, eventType)
            ? NotificationDispatchDecision.IMMEDIATE_EMAIL
            : NotificationDispatchDecision.DAILY_DIGEST;
    }

    private static boolean isHighRisk(MonitorEvent event, MonitorEventType eventType) {
        return switch (eventType) {
            case SSL_EXPIRING, WEBSITE_DOWN -> true;
            case DOMAIN_STATUS_CHANGED -> enteredHoldStatus(event.getNewValue());
            case DOMAIN_EXPIRING -> expiresWithinSevenDays(event);
            case DNS_CHANGED, WEBSITE_RECOVERED -> false;
        };
    }

    private static boolean enteredHoldStatus(String statusValues) {
        if (StringUtils.isBlank(statusValues)) {
            return false;
        }
        for (String status : statusValues.split(",")) {
            if (status.trim().toLowerCase(Locale.ROOT).endsWith("hold")) {
                return true;
            }
        }
        return false;
    }

    private static boolean expiresWithinSevenDays(MonitorEvent event) {
        if (event.getOccurredAt() == null || StringUtils.isBlank(event.getNewValue())) {
            return false;
        }
        try {
            LocalDate eventDate = event.getOccurredAt().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate expiryDate = LocalDate.parse(event.getNewValue());
            return ChronoUnit.DAYS.between(eventDate, expiryDate) <= 7;
        } catch (java.time.format.DateTimeParseException ignored) {
            return false;
        }
    }
}
