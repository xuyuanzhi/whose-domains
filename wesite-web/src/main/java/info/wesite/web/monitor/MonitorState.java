package info.wesite.web.monitor;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A successful, canonicalized result of monitoring one domain.
 */
public record MonitorState(
    String domain,
    Set<String> domainStatuses,
    LocalDate domainExpiry,
    LocalDate sslExpiry,
    Map<String, Set<String>> dnsRecords,
    boolean websiteAvailable,
    int websiteFailureCount) {

    public MonitorState {
        domain = canonicalDomain(domain);
        domainStatuses = canonicalSet(domainStatuses);
        dnsRecords = canonicalDnsRecords(dnsRecords);
        websiteFailureCount = Math.max(0, websiteFailureCount);
    }

    private static String canonicalDomain(String value) {
        return canonicalString(value).toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> canonicalDnsRecords(Map<String, Set<String>> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }

        Map<String, Set<String>> normalized = new TreeMap<>();
        value.forEach((recordType, records) -> {
            String normalizedType = canonicalString(recordType).toUpperCase(Locale.ROOT);
            if (!normalizedType.isEmpty()) {
                normalized.put(normalizedType, canonicalSet(records));
            }
        });
        return Collections.unmodifiableMap(normalized);
    }

    private static Set<String> canonicalSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }

        Set<String> normalized = new TreeSet<>();
        for (String value : values) {
            String canonical = canonicalString(value);
            if (!canonical.isEmpty()) {
                normalized.add(canonical);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String canonicalString(String value) {
        return value == null ? "" : value.trim();
    }
}
