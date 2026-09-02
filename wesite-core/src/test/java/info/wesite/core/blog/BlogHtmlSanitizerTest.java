package info.wesite.core.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class BlogHtmlSanitizerTest {

    private final BlogHtmlSanitizer sanitizer = new BlogHtmlSanitizer();

    @Test
    void preservesArticleMarkupAndRelativeLinks() {
        String safe = sanitizer.sanitize("""
            <h2>DNS</h2><p>Use <a href='/tools/dns-analyzer' title='DNS'>this</a></p>
            <table><thead><tr><th>Type</th></tr></thead><tbody><tr><td>A</td></tr></tbody></table>
            """);
        Document document = Jsoup.parseBodyFragment(safe);
        Element link = document.selectFirst("a");

        assertNotNull(link);
        assertEquals("/tools/dns-analyzer", link.attr("href"));
        assertEquals("DNS", link.attr("title"));
        assertEquals(8, document.select("h2,table,thead,tbody,tr,th,td").size());
    }

    @Test
    void permitsOnlyApprovedLinkProtocols() {
        String safe = sanitizer.sanitize("""
            <a id='https' href='https://example.com'>https</a>
            <a id='http' href='http://example.com'>http</a>
            <a id='mail' href='mailto:team@example.com'>mail</a>
            <a id='data' href='data:text/html,bad'>data</a>
            <a id='file' href='file:///tmp/bad'>file</a>
            <a id='js' href='javascript:alert(1)'>js</a>
            <a id='vb' href='vbscript:alert(1)'>vb</a>
            """);
        Document document = Jsoup.parseBodyFragment(safe);

        assertEquals("https://example.com", document.select("a").get(0).attr("href"));
        assertEquals("http://example.com", document.select("a").get(1).attr("href"));
        assertEquals("mailto:team@example.com", document.select("a").get(2).attr("href"));
        assertFalse(document.select("a").get(3).hasAttr("href"));
        assertFalse(document.select("a").get(4).hasAttr("href"));
        assertFalse(document.select("a").get(5).hasAttr("href"));
        assertFalse(document.select("a").get(6).hasAttr("href"));
    }

    @Test
    void removesExecutableMarkupAndUnapprovedAttributes() {
        String safe = sanitizer.sanitize("""
            <p onclick='alert(1)' style='color:red'>ok</p>
            <script>alert(1)</script><iframe src='https://example.com'></iframe>
            <img src='x' onerror='alert(1)'>
            """);
        Document document = Jsoup.parseBodyFragment(safe);
        Element paragraph = document.selectFirst("p");

        assertNotNull(paragraph);
        assertEquals("ok", paragraph.text());
        assertEquals(0, paragraph.attributesSize());
        assertTrue(document.select("script,iframe,img").isEmpty());
    }

    @Test
    void blankOrStructurallyEmptyHtmlHasNoVisibleContent() {
        assertEquals("", sanitizer.sanitize(null));
        assertEquals("", sanitizer.sanitize("   \n\t"));
        assertFalse(sanitizer.hasVisibleContent("<p><br></p><table><tr><td>&nbsp;</td></tr></table>"));
        assertTrue(sanitizer.hasVisibleContent("<blockquote>Useful</blockquote>"));
    }
}
