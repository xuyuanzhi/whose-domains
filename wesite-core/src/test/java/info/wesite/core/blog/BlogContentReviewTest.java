package info.wesite.core.blog;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import info.wesite.core.entity.BlogPost;

class BlogContentReviewTest {
    private final BlogContentReview policy = new BlogContentReview();
    private final BlogHtmlSanitizer sanitizer = new BlogHtmlSanitizer();

    @Test
    void detectsCopiedProseDespiteDifferentMarkupAndTitle() {
        var first = facts("1", "DNS checks", "<p>Check the DNS records &amp; expiry.</p>");
        var second = facts("2", "Different guide", "<div>CHECK the <b>DNS</b> records &amp; expiry!</div>");
        var report = policy.review(second, List.of(first));
        assertTrue(report.hasDuplicate());
        assertEquals("SAME_CONTENT", report.matches().get(0).kind());
    }

    @Test
    void normalizedTitlesCannotBeReusedEvenWithDifferentBody() {
        var first = facts("1", "  ＤＮＳ: Monitoring! ", "<p>Original</p>");
        var second = facts("2", "dns monitoring", "<p>Replacement</p>");
        assertEquals("SAME_TITLE", policy.review(second, List.of(first)).matches().get(0).kind());
    }

    @Test
    void ignoresSelfAndEmptyBodiesButFindsNearCopies() {
        String body = completeArticle();
        var first = facts("1", "A guide", body);
        assertFalse(policy.review(first, List.of(first)).hasDuplicate());
        var revised = facts("2", "New subject", body.replace("record150", "changed150"));
        assertEquals("NEAR_CONTENT", policy.review(revised, List.of(first)).matches().get(0).kind());
        assertFalse(policy.review(facts("3", "Empty one", ""), List.of(facts("4", "Empty two", ""))).hasDuplicate());
    }

    @Test
    void similarTitleIsOnlyAReviewWarningForIndependentContent() {
        var first = facts("1", "How to monitor DNS records", "<p>Original</p>");
        var second = facts("2", "How to monitor DNS records daily", "<p>New content</p>");
        var report = policy.review(second, List.of(first));
        assertFalse(report.hasDuplicate());
        assertEquals("SIMILAR_TITLE", report.matches().get(0).kind());
    }

    @Test
    void completeArticlePassesMechanicalChecksButStillRequiresHumanVerification() {
        var report = policy.review(facts("1", "Guide", completeArticle()), List.of());
        assertTrue(report.isPublishable(), report.blockers().toString());
        assertTrue(report.warnings().stream().anyMatch(i -> i.code().equals("HUMAN_REVIEW")));
    }

    @Test
    void detectsMissingEvidenceExamplesLimitationsAndProse() {
        var report = policy.review(facts("1", "Guide", "<p>A short article.</p>"), List.of());
        var codes = report.blockers().stream().map(BlogContentReview.Issue::code).toList();
        assertTrue(codes.containsAll(List.of("THIN_CONTENT", "MISSING_SOURCE", "MISSING_EXAMPLE", "MISSING_LIMITATIONS", "MISSING_STRUCTURE")));
    }

    @Test
    void codePaddingAndInternalOrUnsafeLinksCannotSatisfyTheBaseline() {
        for (String link : List.of("/tools/dns-analyzer", "https://whose.domains/tools", "https://www.whose.domains/", "javascript:alert(1)", "https://user@reference.org/docs")) {
            String html = "<h2>Steps</h2><pre>" + "padding ".repeat(300)
                + "</pre><h2>Limitations</h2><a href=\"" + link + "\">Source</a>";
            var report = policy.review(facts("1", "Guide", html), List.of());
            assertTrue(report.blockers().stream().anyMatch(i -> i.code().equals("THIN_CONTENT")));
            assertTrue(report.blockers().stream().anyMatch(i -> i.code().equals("MISSING_SOURCE")), link);
        }
    }

    private BlogContentReview.Facts facts(String id, String title, String html) {
        BlogPost post = new BlogPost();
        post.setId(id); post.setSlug("post-" + id); post.setTitle(title);
        post.setSummary("Summary"); post.setMetaDescription("Description"); post.setContent(html);
        return policy.describe(post, sanitizer.sanitize(html));
    }

    // Varied tokens make near-copy tests sensitive to a localized edit instead of repeated filler.
    static String completeArticle() {
        StringBuilder html = new StringBuilder("<h2>Inspect DNS records</h2><p>");
        for (int i = 0; i < 240; i++) html.append("record").append(i).append(' ');
        return html.append("</p><ol><li>Query example.com and compare the result.</li></ol>")
            .append("<h2>Limitations</h2><p>Cached records can differ until the TTL expires.</p>")
            .append("<a href=\"https://www.rfc-editor.org/rfc/rfc1035\">DNS reference</a>").toString();
    }
}
