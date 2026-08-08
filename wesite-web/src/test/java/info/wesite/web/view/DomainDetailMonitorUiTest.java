package info.wesite.web.view;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
