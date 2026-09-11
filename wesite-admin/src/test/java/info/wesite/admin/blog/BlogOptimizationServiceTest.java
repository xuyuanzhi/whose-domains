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
    BlogOptimizationService service = new BlogOptimizationService(ai, editorial, posts);
    BlogPost stored;
    @BeforeEach void setup() {
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
            "metaTitle", "Better meta", "metaDescription", "Better description", "notes", "Check sources", "slug", "hijacked")));
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
