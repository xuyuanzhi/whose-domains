package info.wesite.web.monitor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two successful monitoring states and derives notification-ready changes.
 */
public final class MonitorChangeDetector {

    private static final Set<Long> EXPIRY_THRESHOLDS = Set.of(30L, 7L, 1L);

    private final Clock clock;

    public MonitorChangeDetector(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public java.util.List<MonitorEventDraft> detect(MonitorState previous, MonitorState current) {
        Instant currentCheckedAt = clock.instant();
        return detect(previous, current, currentCheckedAt.minus(1, ChronoUnit.DAYS), currentCheckedAt);
    }

    public java.util.List<MonitorEventDraft> detect(
        MonitorState previous,
        MonitorState current,
        Instant previousCheckedAt,
        Instant currentCheckedAt) {
        return detect(
            previous,
            current,
            previousCheckedAt,
            currentCheckedAt,
            MonitorSnapshotObservation.allSources(),
            MonitorSnapshotObservation.allSources());
    }

    public java.util.List<MonitorEventDraft> detect(
        MonitorState previous,
        MonitorState current,
        Instant previousCheckedAt,
        Instant currentCheckedAt,
        Set<MonitorCollectorResult.Source> previousObservedSources,
        Set<MonitorCollectorResult.Source> currentObservedSources) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(currentCheckedAt, "currentCheckedAt");
        Set<MonitorCollectorResult.Source> previousObserved = previousObservedSources == null
            ? Set.of()
            : previousObservedSources;
        Set<MonitorCollectorResult.Source> currentObserved = currentObservedSources == null
            ? Set.of()
            : currentObservedSources;
        if (previous == null) {
            java.util.List<MonitorEventDraft> initialEvents = new ArrayList<>();
            if (currentObserved.contains(MonitorCollectorResult.Source.DOMAIN)) {
                detectExpiry(initialEvents, null, current, null, currentCheckedAt, true);
            }
            if (currentObserved.contains(MonitorCollectorResult.Source.SSL)) {
                detectExpiry(initialEvents, null, current, null, currentCheckedAt, false);
            }
            return java.util.List.copyOf(initialEvents);
        }

        java.util.List<MonitorEventDraft> events = new ArrayList<>();
        if (observedOnBoth(
            MonitorCollectorResult.Source.DOMAIN, previousObserved, currentObserved)) {
            detectExpiry(events, previous, current, previousCheckedAt, currentCheckedAt, true);
            detectStatus(events, previous, current);
        }
        if (observedOnBoth(
            MonitorCollectorResult.Source.SSL, previousObserved, currentObserved)) {
            detectExpiry(events, previous, current, previousCheckedAt, currentCheckedAt, false);
        }
        if (observedOnBoth(
            MonitorCollectorResult.Source.DNS, previousObserved, currentObserved)) {
            detectDns(events, previous, current);
        }
        if (observedOnBoth(
            MonitorCollectorResult.Source.WEBSITE, previousObserved, currentObserved)) {
            detectWebsiteAvailability(events, previous, current);
        }
        return java.util.List.copyOf(events);
    }

    private static boolean observedOnBoth(
        MonitorCollectorResult.Source source,
        Set<MonitorCollectorResult.Source> previous,
        Set<MonitorCollectorResult.Source> current) {
        return previous.contains(source) && current.contains(source);
    }

    private void detectExpiry(
        java.util.List<MonitorEventDraft> events,
        MonitorState previous,
        MonitorState current,
        Instant previousCheckedAt,
        Instant currentCheckedAt,
        boolean domainExpiry) {
        LocalDate currentExpiry = domainExpiry ? current.domainExpiry() : current.sslExpiry();
        if (currentExpiry == null) {
            return;
        }

        LocalDate currentScanDate = currentCheckedAt.atZone(clock.getZone()).toLocalDate();
        long currentDaysRemaining = ChronoUnit.DAYS.between(currentScanDate, currentExpiry);
        LocalDate previousExpiry = previous == null
            ? null
            : domainExpiry ? previous.domainExpiry() : previous.sslExpiry();
        Long previousDaysRemaining = previousCheckedAt == null || previousExpiry == null
            ? null
            : ChronoUnit.DAYS.between(
                previousCheckedAt.atZone(clock.getZone()).toLocalDate(), previousExpiry);

        for (long threshold : EXPIRY_THRESHOLDS.stream().sorted(java.util.Comparator.reverseOrder()).toList()) {
            boolean crossed = previousDaysRemaining == null
                ? currentDaysRemaining == threshold
                : previousDaysRemaining > threshold && currentDaysRemaining <= threshold;
            if (!crossed) {
                continue;
            }
            MonitorEventType type = domainExpiry ? MonitorEventType.DOMAIN_EXPIRING : MonitorEventType.SSL_EXPIRING;
            MonitorRisk risk = domainExpiry ? domainExpiryRisk(threshold) : MonitorRisk.HIGH;
            String field = (domainExpiry ? "domainExpiry:" : "sslExpiry:") + threshold;
            events.add(new MonitorEventDraft(
                type,
                risk,
                current.domain(),
                field,
                dateValue(previousExpiry),
                currentExpiry.toString()));
        }
    }

    private static MonitorRisk domainExpiryRisk(long daysRemaining) {
        if (daysRemaining == 1) {
            return MonitorRisk.CRITICAL;
        }
        if (daysRemaining == 7) {
            return MonitorRisk.HIGH;
        }
        return MonitorRisk.LOW;
    }

    private static void detectStatus(
        java.util.List<MonitorEventDraft> events,
        MonitorState previous,
        MonitorState current) {
        if (previous.domainStatuses().equals(current.domainStatuses())) {
            return;
        }

        MonitorRisk risk = enteredHoldStatus(previous.domainStatuses(), current.domainStatuses())
            ? MonitorRisk.CRITICAL
            : MonitorRisk.MEDIUM;
        events.add(new MonitorEventDraft(
            MonitorEventType.DOMAIN_STATUS_CHANGED,
            risk,
            current.domain(),
            "domainStatuses",
            join(previous.domainStatuses()),
            join(current.domainStatuses())));
    }

    private static boolean enteredHoldStatus(Set<String> previous, Set<String> current) {
        return current.stream()
            .filter(status -> !previous.contains(status))
            .anyMatch(status -> status.toLowerCase(java.util.Locale.ROOT).endsWith("hold"));
    }

    private static void detectDns(
        java.util.List<MonitorEventDraft> events,
        MonitorState previous,
        MonitorState current) {
        Set<String> recordTypes = new TreeSet<>();
        recordTypes.addAll(previous.dnsRecords().keySet());
        recordTypes.addAll(current.dnsRecords().keySet());
        for (String recordType : recordTypes) {
            Set<String> previousRecords = previous.dnsRecords().getOrDefault(recordType, Set.of());
            Set<String> currentRecords = current.dnsRecords().getOrDefault(recordType, Set.of());
            if (!previousRecords.equals(currentRecords)) {
                events.add(MonitorEventDraft.dns(current.domain(), recordType, previousRecords, currentRecords));
            }
        }
    }

    private static void detectWebsiteAvailability(
        java.util.List<MonitorEventDraft> events,
        MonitorState previous,
        MonitorState current) {
        if (!current.websiteAvailable()
            && !previous.websiteAvailable()
            && previous.websiteFailureCount() < 2
            && current.websiteFailureCount() >= 2) {
            events.add(new MonitorEventDraft(
                MonitorEventType.WEBSITE_DOWN,
                MonitorRisk.CRITICAL,
                current.domain(),
                "websiteAvailable",
                "true",
                "false"));
        }

        if (current.websiteAvailable()
            && !previous.websiteAvailable()
            && previous.websiteFailureCount() >= 2) {
            events.add(new MonitorEventDraft(
                MonitorEventType.WEBSITE_RECOVERED,
                MonitorRisk.MEDIUM,
                current.domain(),
                "websiteAvailable",
                "false",
                "true"));
        }
    }

    private static String dateValue(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private static String join(Set<String> values) {
        return String.join(",", new TreeSet<>(values));
    }
}
