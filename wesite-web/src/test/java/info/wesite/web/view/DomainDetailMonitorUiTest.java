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

        assertTrue(template.contains("id=\"monitorGoogleLogin\""));
        assertTrue(template.contains("class=\"auth-google-icon\""));
        assertTrue(template.contains("id=\"monitorGoogleMessage\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
        assertTrue(template.contains("th:if=\"${_googleLoginEnabled}\""));
        assertTrue(template.contains("class=\"auth-login-divider monitor-login-divider\""));
        assertTrue(template.contains("id=\"monitorEmailMessage\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
        assertFalse(template.contains("id=\"monitorMessage\""));
    }

    private String template() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/views/domain_detail.html")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
