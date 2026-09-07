package info.wesite.core.blog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.mapper.BlogPostMapper;

class BlogReviewServiceTest {
    private final BlogPostMapper mapper = mock(BlogPostMapper.class);
    private final BlogReviewService review = new BlogReviewService(mapper, new BlogHtmlSanitizer());

    @Test
    void searchesBeyondTheFirstBatchAndIncludesDraftsInReadOnlyAudit() {
        BlogPost first = post("1", "First title");
        BlogPost second = post("2", "A new title");
        first.setStatus(1); second.setStatus(0);
        when(mapper.selectReviewBatch(null, 100)).thenReturn(List.of(first));
        when(mapper.selectReviewBatch("1", 100)).thenReturn(List.of(second));
        var audit = review.audit();
        assertEquals(2, audit.scanned());
        assertEquals(2, audit.findings().size());
        assertEquals("2", audit.findings().get(0).review().matches().get(0).id());
        assertEquals("1", audit.findings().get(1).review().matches().get(0).id());
        verify(mapper, never()).insert(any(BlogPost.class));
        verify(mapper, never()).updateById(any(BlogPost.class));
        verify(mapper, never()).lockEditorialWrites();
    }

    @Test
    void incompleteDraftCanBeUniqueButCannotBePublished() {
        BlogPost post = post("1", "Unique title");
        post.setContent("<p>Incomplete draft.</p>");
        assertDoesNotThrow(() -> review.requireUnique(post));
        assertThrows(BlogEditorialException.class, () -> review.requirePublishable(post));
    }

    @Test
    void scanFailsClosedIfPaginationStopsAdvancing() {
        when(mapper.selectReviewBatch(any(), org.mockito.ArgumentMatchers.eq(100)))
            .thenReturn(List.of(post("1", "Title")));
        assertThrows(BlogEditorialException.class, review::audit);
    }

    @Test
    void topicPreflightNormalizesTitlesAcrossTheCorpus() {
        when(mapper.selectReviewBatch(null, 100)).thenReturn(List.of(post("1", "DNS: Monitoring!")));
        assertTrue(review.topicExists("  dns monitoring "));
        assertFalse(review.topicExists("TLS certificate chains"));
    }

    private static BlogPost post(String id, String title) {
        BlogPost post = new BlogPost(); post.setId(id); post.setTitle(title); post.setSlug("post-" + id);
        post.setContent(BlogContentReviewTest.completeArticle()); post.setSummary("Summary"); post.setMetaDescription("Description");
        return post;
    }
}
