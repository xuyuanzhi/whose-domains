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
 * Versioned cumulative baseline provenance stored beside a monitoring state snapshot.
 * The database column retains its compatibility name {@code OBSERVED_SOURCES}, but
 * represents sources that have ever established a reliable successful baseline.
 */
public final class MonitorSnapshotObservation {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    private MonitorSnapshotObservation() {
    }

    public static Set<MonitorCollectorResult.Source> establishedSources(MonitorSnapshot snapshot) {
        if (snapshot == null) {
            return Set.of();
        }
        if (snapshot.getSchemaVersion() == null
            || snapshot.getSchemaVersion() < CURRENT_SCHEMA_VERSION) {
            // Legacy DomainWatchTask populated DOMAIN facts and synthetic placeholders
            // for every other source.
            return Set.of(MonitorCollectorResult.Source.DOMAIN);
        }
        if (StringUtils.isBlank(snapshot.getObservedSources())) {
            return Set.of();
        }

        EnumSet<MonitorCollectorResult.Source> result = EnumSet.noneOf(
            MonitorCollectorResult.Source.class);
        for (String value : snapshot.getObservedSources().split(",")) {
            try {
                result.add(MonitorCollectorResult.Source.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Unknown future values are not treated as observations by this version.
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public static String serialize(Set<MonitorCollectorResult.Source> establishedSources) {
        if (establishedSources == null || establishedSources.isEmpty()) {
            return "";
        }
        return establishedSources.stream()
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new))
            .stream()
            .collect(Collectors.joining(","));
    }

    public static Set<MonitorCollectorResult.Source> allSources() {
        return Collections.unmodifiableSet(EnumSet.allOf(MonitorCollectorResult.Source.class));
    }
}
