package info.wesite.web.view;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainDetailMonitorUiTest {

    @Test
    void monitorUiShowsSendingStateAndRestoresExistingWatchState() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/views/domain_detail.html")) {
            String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(template.contains("setMonitoringState"));
            assertTrue(template.contains("/api/domain-watch/check/"));
            assertTrue(template.contains("Sending link..."));
            assertTrue(template.contains("document.body.appendChild(modal)"));
            assertTrue(template.contains("Resend in "));
        }
    }
}
