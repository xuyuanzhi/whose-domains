package info.wesite.web.monitor;

import java.time.LocalDate;
import java.util.Date;
import java.util.Set;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.entity.MonitorSnapshot;

/** Per-source established history and current-scan freshness for the domain detail page. */
public record DomainMonitorEvidence(
    boolean sslObserved,
    boolean sslHasHistory,
    Date sslLastSuccessAt,
    LocalDate sslExpiry,
    boolean websiteObserved,
    boolean websiteHasHistory,
    Date websiteLastSuccessAt,
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
        Set<MonitorCollectorResult.Source> currentSuccessfulSources =
            MonitorSnapshotObservation.currentSuccessfulSources(snapshot);
        boolean sslHasHistory = establishedSources.contains(MonitorCollectorResult.Source.SSL);
        boolean websiteHasHistory = establishedSources.contains(MonitorCollectorResult.Source.WEBSITE);
        Date sslLastSuccessAt = copy(snapshot.getSslLastSuccessAt());
        Date websiteLastSuccessAt = copy(snapshot.getWebsiteLastSuccessAt());
        boolean sslObserved = currentSuccessfulSources.contains(MonitorCollectorResult.Source.SSL)
            && sslLastSuccessAt != null;
        boolean websiteObserved = currentSuccessfulSources.contains(
            MonitorCollectorResult.Source.WEBSITE) && websiteLastSuccessAt != null;
        return new DomainMonitorEvidence(
            sslObserved,
            sslHasHistory,
            sslHasHistory ? sslLastSuccessAt : null,
            sslHasHistory ? state.sslExpiry() : null,
            websiteObserved,
            websiteHasHistory,
            websiteHasHistory ? websiteLastSuccessAt : null,
            websiteHasHistory ? state.websiteAvailable() : null,
            websiteHasHistory ? state.websiteFailureCount() : null);
    }

    private static Date copy(Date value) {
        return value == null ? null : new Date(value.getTime());
    }
}
