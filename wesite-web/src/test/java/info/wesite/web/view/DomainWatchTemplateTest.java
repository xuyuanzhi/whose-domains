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

    @Test
    void signedOutCardSecondaryCopyMeetsNormalTextContrast() throws IOException {
        String template = template();

        assertTrue(template.contains(".watchlist-signin-card p { margin-bottom: 20px; color: #8b96a8; }"));
        assertTrue(template.contains(".watchlist-signin-card small { display: block; margin-top: 16px; color: #8b96a8; }"));
        assertTrue(template.contains("margin: 0 auto 24px; color: #8b96a8; font-size: 14px;"));
        assertTrue(contrastRatio("#8b96a8", composite("#0f1628", "#080c18", 0.85)) >= 4.5);
    }

    private String template() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/views/user/domain-watch.html")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String composite(String foreground, String background, double alpha) {
        int foregroundValue = Integer.parseInt(foreground.substring(1), 16);
        int backgroundValue = Integer.parseInt(background.substring(1), 16);
        int result = 0;
        for (int shift : new int[] {16, 8, 0}) {
            int component = (int) Math.round(((foregroundValue >> shift) & 0xff) * alpha
                    + ((backgroundValue >> shift) & 0xff) * (1 - alpha));
            result = (result << 8) | component;
        }
        return String.format("#%06x", result);
    }

    private double contrastRatio(String first, String second) {
        double firstLuminance = luminance(first);
        double secondLuminance = luminance(second);
        return (Math.max(firstLuminance, secondLuminance) + 0.05)
                / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private double luminance(String hex) {
        int value = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * linear((value >> 16) & 0xff)
                + 0.7152 * linear((value >> 8) & 0xff)
                + 0.0722 * linear(value & 0xff);
    }

    private double linear(int component) {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
