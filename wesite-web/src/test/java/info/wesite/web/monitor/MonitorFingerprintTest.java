package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MonitorFingerprintTest {

    @Test
    void dnsOrderDoesNotChangeFingerprint() {
        MonitorEventDraft a = MonitorEventDraft.dns(
            "example.com", "A", List.of("2.2.2.2", "1.1.1.1", "2.2.2.2"), List.of("3.3.3.3"));
        MonitorEventDraft b = MonitorEventDraft.dns(
            "example.com", "A", List.of("1.1.1.1", "2.2.2.2"), List.of("3.3.3.3"));

        assertEquals(MonitorFingerprint.of("w1", a), MonitorFingerprint.of("w1", b));
        assertEquals("1d92013999a6b62563d92c01bbaf10dbddf803382a5ace45466552320d2b6e6d",
            MonitorFingerprint.of("w1", a));
    }

    @Test
    void canonicalizesDomainStatusesAndDnsCollections() {
        MonitorState state = new MonitorState(
            " Example.COM ",
            Set.of(" clientHold ", "clientHold", " OK "),
            LocalDate.of(2027, 1, 2),
            LocalDate.of(2026, 12, 3),
            Map.of(" a ", Set.of(" 2.2.2.2 ", "1.1.1.1")),
            true,
            0);

        assertEquals("example.com", state.domain());
        assertEquals(Set.of("OK", "clientHold"), state.domainStatuses());
        assertEquals(Map.of("A", Set.of("1.1.1.1", "2.2.2.2")), state.dnsRecords());
    }

    @Test
    void missingValuesMatchTheirEmptyCanonicalRepresentation() {
        MonitorEventDraft missing = new MonitorEventDraft(
            MonitorEventType.DOMAIN_STATUS_CHANGED, MonitorRisk.HIGH, "EXAMPLE.COM", " status ", null, null);
        MonitorEventDraft empty = new MonitorEventDraft(
            MonitorEventType.DOMAIN_STATUS_CHANGED, MonitorRisk.HIGH, "example.com", "status", "", "");

        assertEquals(MonitorFingerprint.of("w1", missing), MonitorFingerprint.of("w1", empty));
    }

    @Test
    void changedCanonicalFieldProducesDifferentFingerprint() {
        MonitorEventDraft before = MonitorEventDraft.dns(
            "example.com", "A", List.of("1.1.1.1"), List.of("2.2.2.2"));
        MonitorEventDraft after = MonitorEventDraft.dns(
            "example.com", "A", List.of("1.1.1.1"), List.of("3.3.3.3"));

        assertNotEquals(MonitorFingerprint.of("w1", before), MonitorFingerprint.of("w1", after));
    }
}
