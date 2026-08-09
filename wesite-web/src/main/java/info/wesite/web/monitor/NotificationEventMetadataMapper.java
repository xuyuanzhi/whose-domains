package info.wesite.web.monitor;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import info.wesite.core.entity.MonitorEvent;

/**
 * Reconstructs stable presentation metadata from persisted event facts.
 * MonitorEvent currently does not persist the draft risk or collector source,
 * so every API consumer must use this mapper rather than notification copy.
 */
public final class NotificationEventMetadataMapper {

    private NotificationEventMetadataMapper() {
    }

    public static Metadata map(MonitorEvent event, String domain) {
        MonitorEventType type = eventType(event);
        if (type == null) {
            return null;
        }
        return new Metadata(type.name(), risk(event, type).name(), StringUtils.defaultString(domain), source(type));
    }

    public static MonitorRisk risk(MonitorEvent event) {
        MonitorEventType type = eventType(event);
        return type == null ? MonitorRisk.LOW : risk(event, type);
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

    private static MonitorRisk risk(MonitorEvent event, MonitorEventType type) {
        return switch (type) {
            case DOMAIN_EXPIRING -> domainExpiryRisk(event);
            case SSL_EXPIRING -> MonitorRisk.HIGH;
            case DOMAIN_STATUS_CHANGED -> enteredHoldStatus(event.getNewValue())
                    ? MonitorRisk.CRITICAL : MonitorRisk.MEDIUM;
            case DNS_CHANGED, WEBSITE_RECOVERED -> MonitorRisk.MEDIUM;
            case WEBSITE_DOWN -> MonitorRisk.CRITICAL;
        };
    }

    private static MonitorRisk domainExpiryRisk(MonitorEvent event) {
        if (event.getOccurredAt() == null || StringUtils.isBlank(event.getNewValue())) {
            return MonitorRisk.LOW;
        }
        try {
            LocalDate occurred = event.getOccurredAt().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            long days = ChronoUnit.DAYS.between(occurred, LocalDate.parse(event.getNewValue()));
            if (days <= 1) return MonitorRisk.CRITICAL;
            if (days <= 7) return MonitorRisk.HIGH;
            return MonitorRisk.LOW;
        } catch (java.time.format.DateTimeParseException ignored) {
            return MonitorRisk.LOW;
        }
    }

    private static boolean enteredHoldStatus(String statuses) {
        if (StringUtils.isBlank(statuses)) {
            return false;
        }
        for (String status : statuses.split(",")) {
            if (status.trim().toLowerCase(Locale.ROOT).endsWith("hold")) {
                return true;
            }
        }
        return false;
    }

    private static String source(MonitorEventType type) {
        return switch (type) {
            case DOMAIN_EXPIRING, DOMAIN_STATUS_CHANGED -> "WHOIS/RDAP";
            case SSL_EXPIRING -> "TLS";
            case DNS_CHANGED -> "DNS";
            case WEBSITE_DOWN, WEBSITE_RECOVERED -> "HTTP";
        };
    }

    public record Metadata(String eventType, String risk, String domain, String source) {
    }
}
