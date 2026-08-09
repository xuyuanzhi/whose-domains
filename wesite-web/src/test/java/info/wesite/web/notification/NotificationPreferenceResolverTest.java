package info.wesite.web.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.NotificationPreference;

class NotificationPreferenceResolverTest {

    private final NotificationPreferenceResolver resolver = new NotificationPreferenceResolver();

    @ParameterizedTest
    @MethodSource("modeRiskContract")
    void resolvesEveryModeAgainstPersistedCanonicalRisk(
            String mode, String risk, NotificationDispatchDecision expected) {
        NotificationPreference preference = NotificationPreference.defaultsFor("user-1");
        preference.setEmailMode(mode);

        assertEquals(expected, resolver.resolve(event("DNS_CHANGED", risk), preference, true));
    }

    static Stream<Arguments> modeRiskContract() {
        return Stream.of(
            Arguments.of(NotificationPreference.MODE_IMMEDIATE, "LOW", NotificationDispatchDecision.IMMEDIATE_EMAIL),
            Arguments.of(NotificationPreference.MODE_DAILY, "LOW", NotificationDispatchDecision.DAILY_DIGEST),
            Arguments.of(NotificationPreference.MODE_WEEKLY, "MEDIUM", NotificationDispatchDecision.WEEKLY_DIGEST),
            Arguments.of(NotificationPreference.MODE_DAILY, "HIGH", NotificationDispatchDecision.IMMEDIATE_EMAIL),
            Arguments.of(NotificationPreference.MODE_WEEKLY, "CRITICAL", NotificationDispatchDecision.IMMEDIATE_EMAIL),
            Arguments.of(NotificationPreference.MODE_IN_APP_ONLY, "CRITICAL", NotificationDispatchDecision.IN_APP_ONLY));
    }

    @ParameterizedTest
    @MethodSource("eventCategories")
    void aDisabledCategoryAlwaysWinsEvenForCriticalEvents(String eventType) {
        NotificationPreference preference = NotificationPreference.defaultsFor("user-1");
        preference.setEmailMode(NotificationPreference.MODE_IMMEDIATE);
        disable(preference, eventType);

        assertEquals(NotificationDispatchDecision.IN_APP_ONLY,
                resolver.resolve(event(eventType, "CRITICAL"), preference, true));
    }

    static Stream<String> eventCategories() {
        return Stream.of("DOMAIN_EXPIRING", "SSL_EXPIRING", "DOMAIN_STATUS_CHANGED", "DNS_CHANGED",
                "WEBSITE_DOWN", "WEBSITE_RECOVERED");
    }

    @Test
    void missingPreferenceUsesDailyForRoutineAndImmediateForHighRisk() {
        assertEquals(NotificationDispatchDecision.DAILY_DIGEST,
                resolver.resolve(event("DNS_CHANGED", "MEDIUM"), null, true));
        assertEquals(NotificationDispatchDecision.IMMEDIATE_EMAIL,
                resolver.resolve(event("DOMAIN_EXPIRING", "HIGH"), null, true));
    }

    @Test
    void unknownTypesRisksAndMissingRecipientsFailClosed() {
        NotificationPreference preference = NotificationPreference.defaultsFor("user-1");
        assertEquals(NotificationDispatchDecision.IN_APP_ONLY,
                resolver.resolve(event("FUTURE_EVENT", "CRITICAL"), preference, true));
        assertEquals(NotificationDispatchDecision.DAILY_DIGEST,
                resolver.resolve(event("DNS_CHANGED", "FUTURE_RISK"), preference, true));
        assertEquals(NotificationDispatchDecision.IN_APP_ONLY,
                resolver.resolve(event("DNS_CHANGED", "CRITICAL"), preference, false));
    }

    private static MonitorEvent event(String type, String risk) {
        MonitorEvent event = new MonitorEvent();
        event.setEventType(type);
        event.setRisk(risk);
        return event;
    }

    private static void disable(NotificationPreference preference, String eventType) {
        switch (eventType) {
            case "DOMAIN_EXPIRING" -> preference.setDomainExpiryEnabled(false);
            case "SSL_EXPIRING" -> preference.setSslExpiryEnabled(false);
            case "DOMAIN_STATUS_CHANGED" -> preference.setDomainStatusEnabled(false);
            case "DNS_CHANGED" -> preference.setDnsChangeEnabled(false);
            case "WEBSITE_DOWN", "WEBSITE_RECOVERED" -> preference.setWebsiteAvailabilityEnabled(false);
            default -> throw new IllegalArgumentException(eventType);
        }
    }
}
