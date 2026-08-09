package info.wesite.web.view;

import com.alibaba.fastjson2.JSON;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.web.monitor.DomainMonitorEvidence;
import info.wesite.web.monitor.MonitorSnapshotObservation;
import info.wesite.web.monitor.MonitorState;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainDetailMonitorUiTest {

    @Test
    void monitorUiShowsSendingStateAndRestoresExistingWatchState() throws IOException {
        String template = template();

        assertTrue(template.contains("setMonitoringState"));
        assertTrue(template.contains("/api/domain-watch/check/"));
        assertTrue(template.contains("Sending link..."));
        assertTrue(template.contains("document.body.appendChild(modal)"));
        assertTrue(template.contains("Resend in "));
    }

    @Test
    void monitorDialogSeparatesGoogleAndEmailLoginMethods() throws IOException {
        String template = template();

        int googleSection = template.indexOf("id=\"monitorGoogleSection\" class=\"auth-method monitor-google-section\" th:if=\"${_googleLoginEnabled}\"");
        int googleLogin = template.indexOf("id=\"monitorGoogleLogin\"", googleSection);
        int googleMessage = template.indexOf("id=\"monitorGoogleMessage\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\"", googleLogin);
        int divider = template.indexOf("class=\"auth-login-divider monitor-login-divider\" th:if=\"${_googleLoginEnabled}\"", googleMessage);
        int emailSection = template.indexOf("class=\"auth-method monitor-email-section\"", divider);
        int emailInput = template.indexOf("id=\"monitorEmail\"", emailSection);
        int emailMessage = template.indexOf("id=\"monitorEmailMessage\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\"", emailInput);

        assertTrue(googleSection >= 0);
        assertTrue(googleLogin > googleSection);
        assertTrue(template.contains("class=\"auth-google-icon\""));
        assertTrue(googleMessage > googleLogin);
        assertTrue(divider > googleMessage);
        assertTrue(emailSection > divider);
        assertTrue(emailMessage > emailInput);
        assertFalse(template.contains("id=\"monitorMessage\""));
    }

    @Test
    void monitorEmailControlsUseDedicatedModalStyles() throws IOException {
        String template = template();
        String stylesheet = stylesheet();

        assertTrue(template.contains("id=\"monitorEmail\" class=\"monitor-email-input\""));
        assertTrue(template.contains("id=\"sendMonitorLink\" class=\"monitor-email-submit\""));
        assertTrue(stylesheet.contains(".monitor-email-section .monitor-email-input {"));
        assertTrue(stylesheet.contains(".monitor-email-section .monitor-email-submit {"));
        assertTrue(stylesheet.contains(".monitor-email-section .monitor-email-input:focus"));
        assertTrue(stylesheet.contains(".monitor-email-section .monitor-email-submit:focus-visible"));
    }

    @Test
    void monitorLoginUsesValidatedReturnTargetsWithoutLocalStorage() throws IOException {
        String template = template();

        assertTrue(template.contains("/login/google?returnTo="));
        assertTrue(template.contains("JSON.stringify({email: value, returnTo: monitorReturnTo()})"));
        assertFalse(template.contains("pendingDomainWatch"));
        assertFalse(template.contains("localStorage"));
    }

    @Test
    void monitorDialogHasPersistentAccessibleNamesAndDescription() throws IOException {
        String template = template();

        assertTrue(template.contains("role=\"dialog\" aria-modal=\"true\""));
        assertTrue(template.contains("aria-labelledby=\"monitorModalTitle\""));
        assertTrue(template.contains("aria-describedby=\"monitorModalDescription\""));
        assertTrue(template.contains("id=\"monitorModalTitle\""));
        assertTrue(template.contains("id=\"monitorModalDescription\""));
        assertTrue(template.contains("id=\"monitorEmail\" class=\"monitor-email-input\" type=\"email\" autocomplete=\"email\" aria-label=\"Email address\""));
        assertTrue(template.contains("Sign in to start expiry alerts for"));
        assertFalse(template.contains("We'll email a secure sign-in link"));
    }

    @Test
    void domainDetailExposesStableSslAndWebsiteEvidenceSections() throws IOException {
        String template = template();

        assertTrue(template.contains("id=\"ssl-evidence\""));
        assertTrue(template.contains("SSL certificate evidence"));
        assertTrue(template.contains("id=\"website-availability-evidence\""));
        assertTrue(template.contains("Website availability evidence"));
        assertTrue(template.contains("Current SSL observation"));
        assertTrue(template.contains("Current website observation"));
        assertTrue(template.contains("sslLastSuccessAt"));
        assertTrue(template.contains("websiteLastSuccessAt"));
    }

    @Test
    void staleEvidenceRendersOldSourceTimesWithoutClaimingTodayWasObserved() {
        MonitorSnapshot snapshot = snapshot(
            "DNS,DOMAIN,SSL,WEBSITE",
            "DNS",
            Instant.parse("2026-08-08T03:00:00Z"),
            Instant.parse("2026-08-08T04:00:00Z"));

        String html = renderEvidence(DomainMonitorEvidence.from(snapshot));

        assertTrue(html.contains("Stale - latest SSL check failed"), html);
        assertTrue(html.contains("Stale - latest website check failed"), html);
        assertTrue(html.contains("Last known certificate expiry"), html);
        assertTrue(html.contains("Last known HTTP reachability"), html);
        assertTrue(html.contains("2026-08-08T03:00:00Z"), html);
        assertTrue(html.contains("2026-08-08T04:00:00Z"), html);
        assertFalse(html.contains("2026-08-09T08:00:00Z"), html);
        assertFalse(html.contains(">Observed at<"), html);
    }

    @Test
    void currentEvidenceRendersObservedAtWithoutAStaleWarning() {
        MonitorSnapshot snapshot = snapshot(
            "SSL,WEBSITE",
            "SSL,WEBSITE",
            Instant.parse("2026-08-09T08:00:00Z"),
            Instant.parse("2026-08-09T08:00:00Z"));

        String html = renderEvidence(DomainMonitorEvidence.from(snapshot));

        assertTrue(html.contains("Current SSL observation"), html);
        assertTrue(html.contains("Current website observation"), html);
        assertTrue(html.contains(">Observed at<"), html);
        assertTrue(html.contains("2026-08-09T08:00:00Z"), html);
        assertFalse(html.contains("Stale -"), html);
    }

    @Test
    void neverObservedSourcesRenderUnavailableInsteadOfRetainedState() {
        MonitorSnapshot snapshot = snapshot("DNS,DOMAIN", "DNS", null, null);

        String html = renderEvidence(DomainMonitorEvidence.from(snapshot));

        assertTrue(html.contains("SSL evidence unavailable"), html);
        assertTrue(html.contains("Website evidence unavailable"), html);
        assertFalse(html.contains("2026-08-16"), html);
        assertFalse(html.contains("Reachable"), html);
    }

    private static MonitorSnapshot snapshot(
            String establishedSources,
            String currentSources,
            Instant sslLastSuccess,
            Instant websiteLastSuccess) {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setCheckedAt(Date.from(Instant.parse("2026-08-09T08:00:00Z")));
        snapshot.setSchemaVersion(MonitorSnapshotObservation.CURRENT_SCHEMA_VERSION);
        snapshot.setObservedSources(establishedSources);
        snapshot.setCurrentObservedSources(currentSources);
        snapshot.setSslLastSuccessAt(sslLastSuccess == null ? null : Date.from(sslLastSuccess));
        snapshot.setWebsiteLastSuccessAt(
            websiteLastSuccess == null ? null : Date.from(websiteLastSuccess));
        snapshot.setStateJson(JSON.toJSONString(new MonitorState(
            "example.com",
            Set.of("ok"),
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2026, 8, 16),
            Map.of(),
            true,
            0)));
        return snapshot;
    }

    private static String renderEvidence(DomainMonitorEvidence evidence) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/views/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebContext context = new WebContext(
            JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(request, response),
            Locale.ROOT);
        context.setVariable("monitorEvidence", evidence);
        context.setVariable("domain", Map.of("name", "example.com"));
        return engine.process(
            "domain_detail",
            Set.of("#ssl-evidence", "#website-availability-evidence"),
            context);
    }

    private String template() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/views/domain_detail.html")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String stylesheet() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/static/style/common.css")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
