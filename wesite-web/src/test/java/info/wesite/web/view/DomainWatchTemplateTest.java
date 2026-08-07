package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DomainWatchTemplateTest {
    @Test
    void signedOutStateIsASingleFocusedSignInCard() throws IOException {
        String template = template();
        assertTrue(template.contains("class=\"watchlist-signin-card\""));
        assertTrue(template.contains("Sign in to view your Watchlist"));
        assertTrue(template.contains("Expiry alerts"));
        assertTrue(template.contains("Up to 50 domains"));
        assertTrue(template.contains("onclick=\"openAuthModal(this)\""));
        assertFalse(template.contains("id=\"quickWatchDomain\""));
        assertFalse(template.contains("id=\"quickWatchEmail\""));
        assertFalse(template.contains("requestQuickWatch"));
        assertFalse(template.contains("addPendingWatch"));
        assertFalse(template.contains("pendingDomainWatch"));
        assertFalse(template.contains("Create Account"));
    }

    private String template() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/views/user/domain-watch.html")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
