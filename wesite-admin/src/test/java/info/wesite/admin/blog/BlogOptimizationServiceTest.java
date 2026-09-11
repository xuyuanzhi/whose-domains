package info.wesite.admin.blog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.wesite.core.blog.*;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.service.BlogPostService;

class BlogOptimizationServiceTest {
    BlogAiClient ai = mock(BlogAiClient.class);
    BlogEditorialService editorial = mock(BlogEditorialService.class);
    BlogPostService posts = mock(BlogPostService.class);
    BlogSourceResearch research = mock(BlogSourceResearch.class);
    BlogOptimizationService service = new BlogOptimizationService(ai, editorial, posts, research);
    BlogPost stored;
    @BeforeEach void setup() throws Exception {
        when(research.research(any(), any())).thenReturn(new BlogSourceResearch.Result(List.of(), List.of()));
        stored = new BlogPost(); stored.setId("p1"); stored.setStatus(1);
        when(posts.getById("p1")).thenReturn(stored);
        when(ai.isConfigured()).thenReturn(true);
        when(editorial.review(any())).thenReturn(new BlogContentReview.Report(250, List.of(), List.of(), List.of()));
        when(editorial.sanitizePreview(any())).thenAnswer(call -> new BlogHtmlSanitizer().sanitize(call.getArgument(0)));
    }
    BlogOptimizationService.Request request() {
        return new BlogOptimizationService.Request(new BlogAdminModels.SaveRequest("p1", "keep-url", "Original",
            "Summary", "<p>Original body</p>", "Named editor", "dns", "dns", "Meta", "Description"),
            "Add steps", "https://example.org/reference supplied by editor");
    }
    @Test void proposalSanitizesModelHtmlPreservesIdentityAndDoesNotSaveOrPublish() throws Exception {
        when(ai.complete(any(), any())).thenReturn(new ObjectMapper().writeValueAsString(Map.of(
            "title", "Improved", "summary", "Better summary", "content", "<h2>Steps</h2><p>Details</p><script>alert(1)</script>",
            "metaTitle", "Better meta", "metaDescription", "Better description", "notes", "Check sources", "slug", "hijacked", "citations", List.of())));
        var result = service.optimize(request());
        assertEquals("keep-url", result.article().slug());
        assertEquals("Named editor", result.article().author());
        assertEquals("Improved", result.article().title());
        assertFalse(result.article().content().contains("script"));
        assertEquals(1, stored.getStatus());
        verify(posts).getById("p1"); verifyNoMoreInteractions(posts);
        verify(editorial, never()).save(any(), any());
        verify(editorial, never()).save(any(), any(), anyBoolean());
        verify(editorial, never()).publish(any(), any());
        verify(ai).complete(contains("untrusted"), contains("Add steps"));
    }
    @Test void archivedArticleAndMissingKeyAreRejectedBeforeAiCall() throws Exception {
        stored.setStatus(2);
        assertThrows(BlogEditorialException.class, () -> service.optimize(request()));
        stored.setStatus(0); when(ai.isConfigured()).thenReturn(false);
        assertThrows(BlogEditorialException.class, () -> service.optimize(request()));
        verify(ai, never()).complete(any(), any());
    }
    @Test void automaticallyAddsReadSourceAndItsClaimToArticle() throws Exception {
        String url = "https://www.rfc-editor.org/rfc/rfc1035.html";
        when(research.research(any(), any())).thenReturn(new BlogSourceResearch.Result(
            List.of(new BlogSourceResearch.Source(url, "DNS", "TTL controls cache lifetime")), List.of()));
        when(ai.complete(any(), any())).thenReturn(new ObjectMapper().writeValueAsString(Map.of(
            "title", "Improved", "summary", "Summary", "content", "<p>Cache explanation</p>",
            "metaTitle", "Meta", "metaDescription", "Description", "notes", "补充缓存依据",
            "citations", List.of(Map.of("url", url, "claim", "TTL 控制缓存期限")))));
        var result = service.optimize(request());
        assertTrue(result.article().content().contains(url));
        assertTrue(result.article().content().contains("TTL 控制缓存期限"));
        verify(ai).complete(anyString(), contains("TTL controls cache lifetime"));
        verify(editorial, never()).save(any(), any());
    }
    @Test void repeatedOptimizationMergesReferencesWithoutDiscardingDistinctClaims() throws Exception {
        String url = "https://www.rfc-editor.org/rfc/rfc1035.html";
        when(research.research(any(), any())).thenReturn(new BlogSourceResearch.Result(
            List.of(new BlogSourceResearch.Source(url, "DNS", "TTL controls caching; DNS records have a type")), List.of()));
        String body = "<p>DNS explanation</p>";
        for (int round = 0; round < 2; round++) {
            when(ai.complete(any(), any())).thenReturn(new ObjectMapper().writeValueAsString(Map.of(
                "title", "DNS", "summary", "Summary", "content", body,
                "metaTitle", "Meta", "metaDescription", "Description", "notes", "Preserve useful sources",
                "citations", List.of(Map.of("url", url, "claim", "TTL controls caching"),
                    Map.of("url", url, "claim", "DNS records have a type")))));
            var old = request().article();
            var input = new BlogAdminModels.SaveRequest(old.id(), old.slug(), old.title(), old.summary(), body,
                old.author(), old.category(), old.tags(), old.metaTitle(), old.metaDescription());
            body = service.optimize(new BlogOptimizationService.Request(input, "", "")).article().content();
            var document = org.jsoup.Jsoup.parseBodyFragment(body);
            assertEquals(1, document.select("h2").size());
            assertEquals(2, document.select("li").size());
            assertTrue(document.text().contains("TTL controls caching"));
            assertTrue(document.text().contains("DNS records have a type"));
        }
    }
    @Test void mergesExistingReferenceListsAndPreservesUnrelatedContent() throws Exception {
        String url = "https://www.rfc-editor.org/rfc/rfc1035.html";
        when(research.research(any(), any())).thenReturn(new BlogSourceResearch.Result(
            List.of(new BlogSourceResearch.Source(url, "DNS", "TTL controls caching")), List.of()));
        String entry = "<li>TTL controls caching — <a href=\"" + url + "\">" + url + "</a></li>";
        String body = "<h2>Steps</h2><ul><li>Keep this step</li></ul>"
            + "<h2>References</h2><ul>" + entry + "</ul><p>Keep this paragraph</p>"
            + "<h3>参考资料</h3><ul>" + entry + "<li>Keep editorial note</li></ul>";
        when(ai.complete(any(), any())).thenReturn(new ObjectMapper().writeValueAsString(Map.of(
            "title", "DNS", "summary", "Summary", "content", body,
            "metaTitle", "Meta", "metaDescription", "Description", "notes", "Merge sources",
            "citations", List.of(Map.of("url", url, "claim", "TTL controls caching")))));
        var document = org.jsoup.Jsoup.parseBodyFragment(service.optimize(request()).article().content());
        assertEquals(2, document.select("h2, h3").size());
        assertEquals(2, document.select("ul").size());
        assertEquals(1, document.select("a[href]").size());
        assertTrue(document.text().contains("Keep this step"));
        assertTrue(document.text().contains("Keep this paragraph"));
        assertTrue(document.text().contains("Keep editorial note"));
    }
    @Test void rejectsFabricatedCitationEvenIfModelClaimsItWasRead() throws Exception {
        when(ai.complete(any(), any())).thenReturn(new ObjectMapper().writeValueAsString(Map.of(
            "title", "Improved", "summary", "Summary", "content", "<p>Body</p>",
            "metaTitle", "Meta", "metaDescription", "Description", "notes", "Claims",
            "citations", List.of(Map.of("url", "https://invented.example/fake", "claim", "Unsupported")))));
        assertThrows(BlogEditorialException.class, () -> service.optimize(request()));
    }
    @Test void rejectsUnfetchedLinksWithMixedCaseAndWhitespace() throws Exception {
        for (String href : List.of("HTTPS://invented.example/fake", "  hTtPs://invented.example/fake",
                "ht&#9;tps://invented.example/fake", "//invented.example/fake",
                "https://invented.example/fake&#x2028;", "https://invented.example/fake&#x85;",
                "https://invented.example/fake&#x2029;")) {
            when(ai.complete(any(), any())).thenReturn(new ObjectMapper().writeValueAsString(Map.of(
                "title", "Improved", "summary", "Summary", "content", "<p><a href=\"" + href + "\">Source</a></p>",
                "metaTitle", "Meta", "metaDescription", "Description", "notes", "Claims", "citations", List.of())));
            assertThrows(BlogEditorialException.class, () -> service.optimize(request()), href);
        }
    }
    @Test void malformedOrIncompleteAiOutputCannotBecomeAnEditableProposal() throws Exception {
        when(ai.complete(any(), any())).thenReturn("not json", "{\"title\":\"Incomplete\"}");
        assertThrows(BlogEditorialException.class, () -> service.optimize(request()));
        assertThrows(BlogEditorialException.class, () -> service.optimize(request()));
        verify(editorial, never()).save(any(), any());
    }
    @Test void oversizedInputRejectedBeforeDatabaseAndAi() {
        var input = request().article();
        var request = new BlogOptimizationService.Request(new BlogAdminModels.SaveRequest(input.id(), input.slug(), input.title(),
            input.summary(), "x".repeat(60001), input.author(), input.category(), input.tags(), input.metaTitle(), input.metaDescription()), "", "");
        assertThrows(BlogEditorialException.class, () -> service.validate(request));
        verifyNoInteractions(posts);
    }
    @Test void responseSubscriberRejectsOversizedBodyWithoutBufferingIt() {
        var body = new BlogAiClient.LimitedBody();
        var subscription = mock(java.util.concurrent.Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(java.nio.ByteBuffer.allocate(512 * 1024 + 1)));
        verify(subscription).cancel();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
