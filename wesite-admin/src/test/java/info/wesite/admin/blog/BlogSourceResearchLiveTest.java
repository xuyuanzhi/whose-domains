package info.wesite.admin.blog;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in public documentation fetch; no article or credentials sent. */
@EnabledIfEnvironmentVariable(named = "BLOG_SOURCE_LIVE_TEST", matches = "1")
class BlogSourceResearchLiveTest {
    @Test void readsOfficialRfcPageAsEvidence() throws Exception {
        var research = new BlogSourceResearch(org.mockito.Mockito.mock(BlogAiClient.class));
        var result = research.fetch("https://www.rfc-editor.org/rfc/rfc1035.html");
        assertTrue(result.text().length() >= 100);
        assertTrue(result.text().contains("TTL"), () -> result.title() + " length=" + result.text().length() + " " + result.text().substring(0, Math.min(500, result.text().length())));
        assertFalse(result.text().contains("<script"));
        assertTrue(BlogSourceResearch.excerpt(result.text(), "DNS TTL").contains("TTL"));
    }
}
