package info.wesite.web.notification;

import org.apache.commons.lang3.StringUtils;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.web.monitor.MonitorEventType;
import info.wesite.web.monitor.MonitorRisk;
import info.wesite.web.monitor.NotificationEventMetadataMapper;

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
            return defaultDecision(event);
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

    private static NotificationDispatchDecision defaultDecision(MonitorEvent event) {
        return isHighRisk(event)
            ? NotificationDispatchDecision.IMMEDIATE_EMAIL
            : NotificationDispatchDecision.DAILY_DIGEST;
    }

    private static boolean isHighRisk(MonitorEvent event) {
        MonitorRisk risk = NotificationEventMetadataMapper.canonicalRisk(event);
        return risk == MonitorRisk.HIGH || risk == MonitorRisk.CRITICAL;
    }
}
