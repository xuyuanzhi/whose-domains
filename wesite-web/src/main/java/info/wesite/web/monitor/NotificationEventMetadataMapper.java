package info.wesite.web.monitor;

import org.apache.commons.lang3.StringUtils;

import info.wesite.core.entity.MonitorEvent;

/**
 * Exposes immutable presentation metadata persisted with the event.
 * Legacy rows without authoritative risk/source values are explicitly marked unknown.
 */
public final class NotificationEventMetadataMapper {

    private NotificationEventMetadataMapper() {
    }

    public static Metadata map(MonitorEvent event, String domain) {
        MonitorEventType type = eventType(event);
        if (type == null) {
            return null;
        }
        return new Metadata(
            type.name(),
            canonicalRiskName(event),
            StringUtils.defaultString(domain),
            StringUtils.defaultIfBlank(event.getSource(), "UNKNOWN"));
    }

    public static MonitorRisk canonicalRisk(MonitorEvent event) {
        if (event == null || StringUtils.isBlank(event.getRisk())) {
            return null;
        }
        try {
            return MonitorRisk.valueOf(event.getRisk());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    private static String canonicalRiskName(MonitorEvent event) {
        MonitorRisk risk = canonicalRisk(event);
        return risk == null ? "UNKNOWN" : risk.name();
    }

    public record Metadata(String eventType, String risk, String domain, String source) {
    }
}
