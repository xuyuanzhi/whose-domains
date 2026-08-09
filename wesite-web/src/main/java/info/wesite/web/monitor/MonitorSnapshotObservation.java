package info.wesite.web.monitor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import info.wesite.core.entity.MonitorSnapshot;

/**
 * Versioned source provenance and freshness stored beside a monitoring state snapshot.
 * The compatibility column {@code OBSERVED_SOURCES} is cumulative and means that a
 * source has ever established a reliable baseline. {@code CURRENT_OBSERVED_SOURCES}
 * is intentionally scan-local and contains only sources successful in that scan.
 */
public final class MonitorSnapshotObservation {

    public static final int ESTABLISHED_SOURCES_SCHEMA_VERSION = 2;
    public static final int SOURCE_FRESHNESS_SCHEMA_VERSION = 3;
    public static final int CURRENT_SCHEMA_VERSION = SOURCE_FRESHNESS_SCHEMA_VERSION;

    private MonitorSnapshotObservation() {
    }

    public static Set<MonitorCollectorResult.Source> establishedSources(MonitorSnapshot snapshot) {
        if (snapshot == null) {
            return Set.of();
        }
        if (snapshot.getSchemaVersion() == null
            || snapshot.getSchemaVersion() < ESTABLISHED_SOURCES_SCHEMA_VERSION) {
            // Legacy DomainWatchTask populated DOMAIN facts and synthetic placeholders
            // for every other source.
            return Set.of(MonitorCollectorResult.Source.DOMAIN);
        }
        return deserialize(snapshot.getObservedSources());
    }

    public static Set<MonitorCollectorResult.Source> currentSuccessfulSources(
        MonitorSnapshot snapshot) {
        if (snapshot == null
            || snapshot.getSchemaVersion() == null
            || snapshot.getSchemaVersion() < SOURCE_FRESHNESS_SCHEMA_VERSION) {
            return Set.of();
        }
        return deserialize(snapshot.getCurrentObservedSources());
    }

    private static Set<MonitorCollectorResult.Source> deserialize(String serializedSources) {
        if (StringUtils.isBlank(serializedSources)) {
            return Set.of();
        }

        EnumSet<MonitorCollectorResult.Source> result = EnumSet.noneOf(
            MonitorCollectorResult.Source.class);
        for (String value : serializedSources.split(",")) {
            try {
                result.add(MonitorCollectorResult.Source.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Unknown future values are not treated as observations by this version.
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public static String serialize(Set<MonitorCollectorResult.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return sources.stream()
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new))
            .stream()
            .collect(Collectors.joining(","));
    }

    public static Set<MonitorCollectorResult.Source> allSources() {
        return Collections.unmodifiableSet(EnumSet.allOf(MonitorCollectorResult.Source.class));
    }
}
