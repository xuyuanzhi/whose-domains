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
    void exposesOnlyCollectorSourcesThatTheSuccessfulSnapshotActuallyObserved() {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setCheckedAt(Date.from(Instant.parse("2026-08-09T08:00:00Z")));
        snapshot.setSchemaVersion(MonitorSnapshotObservation.CURRENT_SCHEMA_VERSION);
        snapshot.setObservedSources("DOMAIN,SSL");
        snapshot.setStateJson(JSON.toJSONString(new MonitorState(
            "example.com", Set.of("ok"), LocalDate.of(2027, 1, 1),
            LocalDate.of(2026, 8, 16), Map.of(), false, 2)));

        DomainMonitorEvidence evidence = DomainMonitorEvidence.from(snapshot);

        assertTrue(evidence.sslObserved());
        assertEquals(LocalDate.of(2026, 8, 16), evidence.sslExpiry());
        assertFalse(evidence.websiteObserved());
        assertNull(evidence.websiteAvailable());
        assertNull(evidence.websiteFailureCount());
        assertEquals(snapshot.getCheckedAt(), evidence.checkedAt());
    }
}
