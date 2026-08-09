package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.entity.MonitorSnapshot;

class DomainMonitorEvidenceTest {

    @Test
    void cumulativeHistoryIsNotReportedAsACurrentSourceObservation() {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setCheckedAt(Date.from(Instant.parse("2026-08-09T08:00:00Z")));
        snapshot.setSchemaVersion(MonitorSnapshotObservation.CURRENT_SCHEMA_VERSION);
        snapshot.setObservedSources("DNS,DOMAIN,SSL,WEBSITE");
        snapshot.setCurrentObservedSources("DNS");
        snapshot.setSslLastSuccessAt(Date.from(Instant.parse("2026-08-08T03:00:00Z")));
        snapshot.setWebsiteLastSuccessAt(Date.from(Instant.parse("2026-08-08T04:00:00Z")));
        snapshot.setStateJson(JSON.toJSONString(new MonitorState(
            "example.com", Set.of("ok"), LocalDate.of(2027, 1, 1),
            LocalDate.of(2026, 8, 16), Map.of(), false, 2)));

        DomainMonitorEvidence evidence = DomainMonitorEvidence.from(snapshot);

        assertFalse(evidence.sslObserved());
        assertTrue(evidence.sslHasHistory());
        assertEquals(
            Date.from(Instant.parse("2026-08-08T03:00:00Z")),
            evidence.sslLastSuccessAt());
        assertEquals(LocalDate.of(2026, 8, 16), evidence.sslExpiry());
        assertFalse(evidence.websiteObserved());
        assertTrue(evidence.websiteHasHistory());
        assertEquals(
            Date.from(Instant.parse("2026-08-08T04:00:00Z")),
            evidence.websiteLastSuccessAt());
        assertEquals(false, evidence.websiteAvailable());
        assertEquals(2, evidence.websiteFailureCount());
    }

    @Test
    void currentSuccessfulSourcesUseTheirOwnCurrentSuccessTimes() {
        Date checkedAt = Date.from(Instant.parse("2026-08-09T08:00:00Z"));
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setCheckedAt(checkedAt);
        snapshot.setSchemaVersion(MonitorSnapshotObservation.CURRENT_SCHEMA_VERSION);
        snapshot.setObservedSources("SSL,WEBSITE");
        snapshot.setCurrentObservedSources("SSL,WEBSITE");
        snapshot.setSslLastSuccessAt(checkedAt);
        snapshot.setWebsiteLastSuccessAt(checkedAt);
        snapshot.setStateJson(JSON.toJSONString(new MonitorState(
            "example.com", Set.of(), null, LocalDate.of(2026, 9, 1),
            Map.of(), true, 0)));

        DomainMonitorEvidence evidence = DomainMonitorEvidence.from(snapshot);

        assertTrue(evidence.sslObserved());
        assertTrue(evidence.sslHasHistory());
        assertEquals(checkedAt, evidence.sslLastSuccessAt());
        assertTrue(evidence.websiteObserved());
        assertTrue(evidence.websiteHasHistory());
        assertEquals(checkedAt, evidence.websiteLastSuccessAt());
        assertEquals(true, evidence.websiteAvailable());
    }

    @Test
    void neverObservedSourcesExposeNoHistoryOrRetainedValues() {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setCheckedAt(Date.from(Instant.parse("2026-08-09T08:00:00Z")));
        snapshot.setSchemaVersion(MonitorSnapshotObservation.CURRENT_SCHEMA_VERSION);
        snapshot.setObservedSources("DNS,DOMAIN");
        snapshot.setCurrentObservedSources("DNS");
        snapshot.setDnsLastSuccessAt(snapshot.getCheckedAt());
        snapshot.setStateJson(JSON.toJSONString(new MonitorState(
            "example.com", Set.of("ok"), LocalDate.of(2027, 1, 1),
            LocalDate.of(2026, 8, 16), Map.of(), true, 0)));

        DomainMonitorEvidence evidence = DomainMonitorEvidence.from(snapshot);

        assertFalse(evidence.sslObserved());
        assertFalse(evidence.sslHasHistory());
        assertNull(evidence.sslLastSuccessAt());
        assertNull(evidence.sslExpiry());
        assertFalse(evidence.websiteObserved());
        assertFalse(evidence.websiteHasHistory());
        assertNull(evidence.websiteLastSuccessAt());
        assertNull(evidence.websiteAvailable());
        assertNull(evidence.websiteFailureCount());
    }

    @Test
    void legacySnapshotDoesNotInferPerSourceFreshnessFromCheckedAt() {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setCheckedAt(Date.from(Instant.parse("2026-08-09T08:00:00Z")));
        snapshot.setSchemaVersion(MonitorSnapshotObservation.ESTABLISHED_SOURCES_SCHEMA_VERSION);
        snapshot.setObservedSources("SSL,WEBSITE");
        snapshot.setStateJson(JSON.toJSONString(new MonitorState(
            "example.com", Set.of(), null, LocalDate.of(2026, 8, 16),
            Map.of(), true, 0)));

        DomainMonitorEvidence evidence = DomainMonitorEvidence.from(snapshot);

        assertFalse(evidence.sslObserved());
        assertTrue(evidence.sslHasHistory());
        assertNull(evidence.sslLastSuccessAt());
        assertFalse(evidence.websiteObserved());
        assertTrue(evidence.websiteHasHistory());
        assertNull(evidence.websiteLastSuccessAt());
    }
}
