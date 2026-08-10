package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CanonicalToolRoutesTest {

    @ParameterizedTest
    @CsvSource({
            "/tools/domain_analyzer,/tools/domain-analyzer",
            "/tools/dns_analyzer,/tools/dns-analyzer",
            "/tools/ssl_checker,/tools/ssl-checker",
            "/tools/competitor_analysis,/tools/competitor-analysis"
    })
    void resolvesEachLegacyPath(String legacy, String canonical) {
        assertEquals(canonical, CanonicalToolRoutes.canonicalFor(legacy).orElseThrow());
    }

    @Test
    void rewritesOnlyRelativeInternalHrefValuesAndPreservesSuffixes() {
        String html = "<a href=\"/tools/dns_analyzer?d=example.com#records\">DNS</a>"
                + "<a href='/tools/ssl_checker'>SSL</a>"
                + "<a href='https://example.com/tools/dns_analyzer'>external</a>";

        String normalized = CanonicalToolRoutes.canonicalizeInternalLinks(html);

        assertTrue(normalized.contains("/tools/dns-analyzer?d=example.com#records"));
        assertTrue(normalized.contains("href='/tools/ssl-checker'"));
        assertTrue(normalized.contains("https://example.com/tools/dns_analyzer"));
        assertFalse(CanonicalToolRoutes.containsLegacyInternalLink(normalized));
    }

    @Test
    void detectsLegacyInternalLinksWithoutTreatingExternalUrlsAsInternal() {
        assertTrue(CanonicalToolRoutes.containsLegacyInternalLink(
                "<a href = \"/tools/domain_analyzer\">Analyze</a>"));
        assertFalse(CanonicalToolRoutes.containsLegacyInternalLink(
                "<a href=\"https://example.com/tools/domain_analyzer\">External</a>"));
    }
}
