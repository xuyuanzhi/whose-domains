package info.wesite.web.monitor;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A normalized event before it is assigned a persistent identifier.
 */
public record MonitorEventDraft(
    MonitorEventType type,
    MonitorRisk risk,
    String domain,
    String field,
    String oldValue,
    String newValue) {

    public MonitorEventDraft {
        type = Objects.requireNonNull(type, "type");
        risk = Objects.requireNonNull(risk, "risk");
        domain = canonicalDomain(domain);
        field = canonicalString(field);
        oldValue = canonicalString(oldValue);
        newValue = canonicalString(newValue);
    }

    public static MonitorEventDraft dns(
        String domain,
        String recordType,
        Collection<String> oldValues,
        Collection<String> newValues) {
        return new MonitorEventDraft(
            MonitorEventType.DNS_CHANGED,
            MonitorRisk.MEDIUM,
            domain,
            "DNS:" + canonicalString(recordType).toUpperCase(Locale.ROOT),
            canonicalDnsValues(oldValues),
            canonicalDnsValues(newValues));
    }

    private static String canonicalDomain(String value) {
        return canonicalString(value).toLowerCase(Locale.ROOT);
    }

    private static String canonicalDnsValues(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            String canonical = canonicalString(value);
            if (!canonical.isEmpty()) {
                normalized.add(canonical);
            }
        }
        return String.join(",", normalized);
    }

    private static String canonicalString(String value) {
        return value == null ? "" : value.trim();
    }
}
