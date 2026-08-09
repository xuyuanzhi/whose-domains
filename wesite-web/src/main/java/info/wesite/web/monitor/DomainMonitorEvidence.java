package info.wesite.web.monitor;

import java.time.LocalDate;
import java.util.Date;
import java.util.Set;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.entity.MonitorSnapshot;

/** Established-baseline evidence rendered on an authenticated domain detail page. */
public record DomainMonitorEvidence(
    Date checkedAt,
    boolean sslObserved,
    LocalDate sslExpiry,
    boolean websiteObserved,
    Boolean websiteAvailable,
    Integer websiteFailureCount) {

    public static DomainMonitorEvidence from(MonitorSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        MonitorState state = JSON.parseObject(snapshot.getStateJson(), MonitorState.class);
        if (state == null) {
            throw new IllegalArgumentException("snapshot state is required");
        }
        Set<MonitorCollectorResult.Source> establishedSources =
            MonitorSnapshotObservation.establishedSources(snapshot);
        boolean sslObserved = establishedSources.contains(MonitorCollectorResult.Source.SSL);
        boolean websiteObserved = establishedSources.contains(MonitorCollectorResult.Source.WEBSITE);
        return new DomainMonitorEvidence(
            snapshot.getCheckedAt(),
            sslObserved,
            sslObserved ? state.sslExpiry() : null,
            websiteObserved,
            websiteObserved ? state.websiteAvailable() : null,
            websiteObserved ? state.websiteFailureCount() : null);
    }
}
