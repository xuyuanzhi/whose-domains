package info.wesite.core.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import info.wesite.core.entity.BlogPost;
import info.wesite.core.mapper.BlogPostMapper;

@ExtendWith(MockitoExtension.class)
class BlogEditorialServiceTest {

    @Mock
    private BlogPostMapper mapper;

    @Mock
    private BlogTimeProvider time;

    private BlogEditorialService service;

    @BeforeEach
    void setUp() {
        service = new BlogEditorialService(mapper, new BlogHtmlSanitizer(), time);
    }

    @Test
    void createsSanitizedUnownedDraftWithAuditAndEditorialTime() {
        Date now = new Date(1_800_000_000_000L);
        when(time.now()).thenReturn(now);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(BlogPost.class))).thenReturn(1);

        BlogPost result = service.createAiDraft(new BlogDraftCommand(
            " DNS Guide ", " DNS Guide ", " summary ",
            "<p>safe</p><script>alert(1)</script>",
            " dns ", " dns,security ", " DNS Guide ", " description "));

        ArgumentCaptor<BlogPost> inserted = ArgumentCaptor.forClass(BlogPost.class);
        verify(mapper).insert(inserted.capture());
        assertSame(result, inserted.getValue());
        assertEquals(32, result.getId().length());
        assertEquals("dns-guide", result.getSlug());
        assertEquals("DNS Guide", result.getTitle());
        assertEquals("summary", result.getSummary());
        assertEquals("dns", result.getCategory());
        assertEquals("dns,security", result.getTags());
        assertEquals(BlogPost.POST_STATUS_DRAFT, result.getStatus());
        assertEquals(0, result.getViewCount());
        assertEquals(0, result.getDeleted());
        assertNull(result.getAuthor());
        assertNull(result.getPublishDate());
        assertEquals("ai", result.getCreateBy());
        assertEquals(now, result.getCreateTime());
        assertEquals(now, result.getContentUpdatedAt());
        assertFalse(result.getContent().contains("script"));
    }

    @ParameterizedTest(name = "rejects overlong {0}")
    @MethodSource("overlongDraftFields")
    void rejectsEveryOverlongDatabaseFieldBeforePersistence(
            String field,
            BlogDraftCommand command) {
        assertThrows(BlogEditorialException.class, () -> service.createAiDraft(command));
        verify(mapper, never()).insert(any(BlogPost.class));
    }

    @Test
    void countsUnicodeCodePointsRatherThanUtf16CodeUnits() {
        Date now = new Date(1_800_000_000_000L);
        String title = "😀".repeat(300);
        when(time.now()).thenReturn(now);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(BlogPost.class))).thenReturn(1);

        BlogPost post = service.createAiDraft(draft("unicode", title, "<p>body</p>"));

        assertEquals(300, post.getTitle().codePointCount(0, post.getTitle().length()));
    }

    @Test
    void rejectsEmptyNormalizedSlugBeforePersistence() {
        assertThrows(BlogEditorialException.class,
            () -> service.createAiDraft(draft(" --- ", "Title", "<p>body</p>")));
        verify(mapper, never()).insert(any(BlogPost.class));
    }

    @Test
    void rejectsDuplicateSlugBeforePersistence() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThrows(BlogEditorialException.class,
            () -> service.createAiDraft(draft("duplicate", "Title", "<p>body</p>")));

        verify(mapper, never()).insert(any(BlogPost.class));
    }

    @Test
    void convertsInsertTimeSlugCollisionToEditorialError() {
        DuplicateKeyException databaseError = new DuplicateKeyException("duplicate slug");
        when(time.now()).thenReturn(new Date(1_800_000_000_000L));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(BlogPost.class))).thenThrow(databaseError);

        BlogEditorialException error = assertThrows(BlogEditorialException.class,
            () -> service.createAiDraft(draft("race", "Title", "<p>body</p>")));

        assertSame(databaseError, error.getCause());
    }

    @Test
    void rejectsContentThatIsEmptyAfterSanitization() {
        assertThrows(BlogEditorialException.class,
            () -> service.createAiDraft(draft("empty", "Title", "<script>alert(1)</script>")));
        verify(mapper, never()).insert(any(BlogPost.class));
    }

    @Test
    void rejectsHtmlSourceLargerThanOneMebibyte() {
        String oversized = "你".repeat(349_526);
        assertEquals(1_048_578, oversized.getBytes(StandardCharsets.UTF_8).length);

        assertThrows(BlogEditorialException.class,
            () -> service.createAiDraft(draft("large", "Title", oversized)));

        verify(mapper, never()).insert(any(BlogPost.class));
    }

    @Test
    void previewSanitizesFragmentsAndAllowsEmptyInput() {
        assertEquals("", service.sanitizePreview(null));
        assertEquals("<p>safe</p>",
            service.sanitizePreview("<script>alert(1)</script><p>safe</p>"));
    }

    @Test
    void previewRejectsOversizedSource() {
        String oversized = "a".repeat(1_048_577);
        assertThrows(BlogEditorialException.class, () -> service.sanitizePreview(oversized));
    }

    private static Stream<Arguments> overlongDraftFields() {
        return Stream.of(
            Arguments.of("slug", draft("a".repeat(201), "Title", "<p>body</p>")),
            Arguments.of("title", new BlogDraftCommand(
                "slug", "a".repeat(301), null, "<p>body</p>", null, null, null, null)),
            Arguments.of("summary", new BlogDraftCommand(
                "slug", "Title", "a".repeat(601), "<p>body</p>", null, null, null, null)),
            Arguments.of("category", new BlogDraftCommand(
                "slug", "Title", null, "<p>body</p>", "a".repeat(101), null, null, null)),
            Arguments.of("tags", new BlogDraftCommand(
                "slug", "Title", null, "<p>body</p>", null, "a".repeat(301), null, null)),
            Arguments.of("meta title", new BlogDraftCommand(
                "slug", "Title", null, "<p>body</p>", null, null, "a".repeat(301), null)),
            Arguments.of("meta description", new BlogDraftCommand(
                "slug", "Title", null, "<p>body</p>", null, null, null, "a".repeat(601)))
        );
    }

    private static BlogDraftCommand draft(String slug, String title, String content) {
        return new BlogDraftCommand(slug, title, null, content, null, null, null, null);
    }
}
