package info.wesite.web.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MonitorChangeDetectorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-09T10:15:30Z"), ZoneOffset.UTC);
    private final MonitorChangeDetector detector = new MonitorChangeDetector(CLOCK);

    @Test
    void domainExpiryThresholdsProduceExpectedRisks() {
        for (ExpiryCase expiryCase : List.of(
            new ExpiryCase(30, MonitorRisk.LOW),
            new ExpiryCase(7, MonitorRisk.HIGH),
            new ExpiryCase(1, MonitorRisk.CRITICAL))) {
            LocalDate expiry = today().plusDays(expiryCase.daysRemaining());

            List<MonitorEventDraft> events = detector.detect(
                state().domainExpiry(expiry.plusDays(1)).build(),
                state().domainExpiry(expiry).build());

            MonitorEventDraft event = onlyEventOfType(events, MonitorEventType.DOMAIN_EXPIRING);
            assertEquals(expiryCase.risk(), event.risk());
            assertEquals("domainExpiry:" + expiryCase.daysRemaining(), event.field());
            assertEquals(expiry.toString(), event.newValue());
        }
    }

    @Test
    void sslExpiryThresholdsProduceHighRiskEvents() {
        for (int daysRemaining : List.of(30, 7, 1)) {
            LocalDate expiry = today().plusDays(daysRemaining);

            List<MonitorEventDraft> events = detector.detect(
                state().sslExpiry(expiry.plusDays(1)).build(),
                state().sslExpiry(expiry).build());

            MonitorEventDraft event = onlyEventOfType(events, MonitorEventType.SSL_EXPIRING);
            assertEquals(MonitorRisk.HIGH, event.risk());
            assertEquals("sslExpiry:" + daysRemaining, event.field());
            assertEquals(expiry.toString(), event.newValue());
        }
    }

    @Test
    void enteringHoldStatusCreatesCriticalStatusEvent() {
        List<MonitorEventDraft> events = detector.detect(
            state().statuses("ok").build(),
            state().statuses("ok", "clientHold").build());

        MonitorEventDraft event = onlyEventOfType(events, MonitorEventType.DOMAIN_STATUS_CHANGED);
        assertEquals(MonitorRisk.CRITICAL, event.risk());
        assertEquals("domainStatuses", event.field());
        assertEquals("ok", event.oldValue());
        assertEquals("clientHold,ok", event.newValue());
    }

    @Test
    void changedDnsRecordSetCreatesDnsEventWithCanonicalValues() {
        List<MonitorEventDraft> events = detector.detect(
            state().dns(Map.of("a", Set.of("2.2.2.2", "1.1.1.1"))).build(),
            state().dns(Map.of("A", Set.of("3.3.3.3", "1.1.1.1"))).build());

        MonitorEventDraft event = onlyEventOfType(events, MonitorEventType.DNS_CHANGED);
        assertEquals(MonitorRisk.MEDIUM, event.risk());
        assertEquals("DNS:A", event.field());
        assertEquals("1.1.1.1,2.2.2.2", event.oldValue());
        assertEquals("1.1.1.1,3.3.3.3", event.newValue());
    }

    @Test
    void firstConsecutiveWebsiteFailureDoesNotCreateEvent() {
        List<MonitorEventDraft> events = detector.detect(
            state().website(true, 0).build(),
            state().website(false, 1).build());

        assertFalse(events.stream().anyMatch(event -> event.type() == MonitorEventType.WEBSITE_DOWN));
    }

    @Test
    void secondConsecutiveWebsiteFailureCreatesDownEvent() {
        List<MonitorEventDraft> events = detector.detect(
            state().website(false, 1).build(),
            state().website(false, 2).build());

        MonitorEventDraft event = onlyEventOfType(events, MonitorEventType.WEBSITE_DOWN);
        assertEquals(MonitorRisk.CRITICAL, event.risk());
        assertEquals("websiteAvailable", event.field());
        assertEquals("true", event.oldValue());
        assertEquals("false", event.newValue());
    }

    @Test
    void repeatedConfirmedWebsiteFailureDoesNotCreateDuplicateDownEvent() {
        List<MonitorEventDraft> events = detector.detect(
            state().website(false, 2).build(),
            state().website(false, 3).build());

        assertFalse(events.stream().anyMatch(event -> event.type() == MonitorEventType.WEBSITE_DOWN));
    }

    @Test
    void confirmedWebsiteRecoveryCreatesMediumRiskEvent() {
        List<MonitorEventDraft> events = detector.detect(
            state().website(false, 2).build(),
            state().website(true, 0).build());

        MonitorEventDraft event = onlyEventOfType(events, MonitorEventType.WEBSITE_RECOVERED);
        assertEquals(MonitorRisk.MEDIUM, event.risk());
        assertEquals("websiteAvailable", event.field());
        assertEquals("false", event.oldValue());
        assertEquals("true", event.newValue());
    }

    private static MonitorEventDraft onlyEventOfType(List<MonitorEventDraft> events, MonitorEventType type) {
        List<MonitorEventDraft> matching = events.stream().filter(event -> event.type() == type).toList();
        assertEquals(1, matching.size());
        return matching.get(0);
    }

    private static LocalDate today() {
        return LocalDate.now(CLOCK);
    }

    private static StateBuilder state() {
        return new StateBuilder();
    }

    private record ExpiryCase(int daysRemaining, MonitorRisk risk) {
    }

    private static final class StateBuilder {
        private Set<String> statuses = Set.of("ok");
        private LocalDate domainExpiry = today().plusDays(90);
        private LocalDate sslExpiry = today().plusDays(90);
        private Map<String, Set<String>> dnsRecords = Map.of();
        private boolean websiteAvailable = true;
        private int websiteFailureCount;

        StateBuilder statuses(String... values) {
            statuses = Set.of(values);
            return this;
        }

        StateBuilder domainExpiry(LocalDate value) {
            domainExpiry = value;
            return this;
        }

        StateBuilder sslExpiry(LocalDate value) {
            sslExpiry = value;
            return this;
        }

        StateBuilder dns(Map<String, Set<String>> value) {
            dnsRecords = value;
            return this;
        }

        StateBuilder website(boolean available, int failures) {
            websiteAvailable = available;
            websiteFailureCount = failures;
            return this;
        }

        MonitorState build() {
            return new MonitorState(
                "example.com", statuses, domainExpiry, sslExpiry, dnsRecords, websiteAvailable, websiteFailureCount);
        }
    }
}
