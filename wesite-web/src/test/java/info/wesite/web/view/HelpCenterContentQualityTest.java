package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HelpCenterContentQualityTest {

    @Test
    void domainLockBestPracticesUseValidCheckMarks() throws Exception {
        String html;
        try (var stream = getClass().getResourceAsStream(
            "/views/info/domain-lock-and-transfer-protection.html")) {
            html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertFalse(html.contains("锟?"));
        assertFalse(html.contains("�"));
        assertEquals(8, occurrences(html, "&#10004;"));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int start = 0;
        while ((start = text.indexOf(token, start)) >= 0) {
            count++;
            start += token.length();
        }
        return count;
    }
}
