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
        BlogHtmlSanitizer sanitizer = new BlogHtmlSanitizer();
        service = new BlogEditorialService(mapper, sanitizer, time, new BlogReviewService(mapper, sanitizer));
        org.mockito.Mockito.lenient().when(mapper.lockEditorialWrites()).thenReturn(1);
    }

    @Test
    void editingLegacyBylinePreservesAiDisclosureWithoutRewritingCreationAudit() {
        BlogPost stored = publishablePost("legacy", BlogPost.POST_STATUS_DRAFT);
        stored.setAuthor("James Chen");
        when(mapper.selectByIdForUpdate("legacy")).thenReturn(stored);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.updateById(any(BlogPost.class))).thenReturn(1);
        when(time.now()).thenReturn(new Date());
        BlogPost saved = service.save(new BlogEditCommand("legacy", stored.getSlug(), stored.getTitle(),
            stored.getSummary(), stored.getContent(), "Whose.Domains", null, null, null,
            stored.getMetaDescription()), "admin-1");
        org.junit.jupiter.api.Assertions.assertTrue(saved.isAiAssisted());
        assertEquals(Boolean.TRUE, saved.getAiGenerated());
        assertNull(saved.getCreateBy());
    }

    @Test
    void publicationRejectsAnArticleWithNoEvidenceOrPracticalDetail() {
        BlogPost stored = publishablePost("thin-post", BlogPost.POST_STATUS_DRAFT);
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
        org.mockito.Mockito.lenient().when(time.now()).thenReturn(new Date());
        org.mockito.Mockito.lenient().when(mapper.updateById(any(BlogPost.class))).thenReturn(1);

        assertThrows(BlogEditorialException.class, () -> service.publish(stored.getId(), "admin-1"));
        verify(mapper, never()).updateById(any(BlogPost.class));
    }

    @Test
    void createsSanitizedSiteAuthoredDraftWithAuditAndEditorialTime() {
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
        assertEquals("Whose.Domains", result.getAuthor());
        assertNull(result.getPublishDate());
        assertEquals("ai", result.getCreateBy());
        assertEquals(now, result.getCreateTime());
        assertEquals(now, result.getContentUpdatedAt());
        assertFalse(result.getContent().contains("script"));
    }

    @Test
    void rejectsCopiedBodyEvenWhenGeneratedSlugAndTitleAreDifferent() {
        BlogPost existing = publishablePost("older", BlogPost.POST_STATUS_PUBLISHED);
        existing.setContent("<p>Copied material</p>");
        when(mapper.selectCount(any())).thenReturn(0L);
        when(time.now()).thenReturn(new Date());
        when(mapper.selectReviewBatch(null, 100)).thenReturn(java.util.List.of(existing));

        assertThrows(BlogEditorialException.class, () -> service.createAiDraft(
            draft("new-url", "New title", "<p><strong>Copied</strong> material!</p>")));
        verify(mapper, never()).insert(any(BlogPost.class));
        var order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).lockEditorialWrites();
        order.verify(mapper).selectCount(any());
        order.verify(mapper).selectReviewBatch(null, 100);
    }

    @Test
    void draftCanBeEditedToResolveDuplicateButCannotBePublishedUntilResolved() {
        BlogPost stored = publishablePost("draft", BlogPost.POST_STATUS_DRAFT);
        BlogPost existing = publishablePost("older", BlogPost.POST_STATUS_PUBLISHED);
        stored.setContent(BlogContentReviewTest.completeArticle());
        existing.setContent(stored.getContent());
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.updateById(any(BlogPost.class))).thenReturn(1);
        when(time.now()).thenReturn(new Date());
        assertDoesNotThrowSave(stored);
        when(mapper.selectReviewBatch(null, 100)).thenReturn(java.util.List.of(existing));
        assertThrows(BlogEditorialException.class, () -> service.publish(stored.getId(), "admin-1"));
        assertEquals(BlogPost.POST_STATUS_DRAFT, stored.getStatus());
    }

    private void assertDoesNotThrowSave(BlogPost stored) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.save(edit(stored, stored.getSlug(), stored.getTitle()), "admin-1"));
    }

    @Test
    void editingPublishedContentCannotBypassQualityChecks() {
        BlogPost stored = publishablePost("public", BlogPost.POST_STATUS_PUBLISHED);
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
        when(mapper.selectCount(any())).thenReturn(0L);
        assertThrows(BlogEditorialException.class, () -> service.save(edit(stored, stored.getSlug(), "Updated title"), "admin-1"));
        verify(mapper, never()).updateById(any(BlogPost.class));
    }

    @Test
    void missingDatabaseLockPreventsAnyPublicationReadOrWrite() {
        when(mapper.lockEditorialWrites()).thenReturn(null);
        assertThrows(BlogEditorialException.class, () -> service.publish("post", "admin-1"));
        verify(mapper, never()).selectByIdForUpdate(any());
        verify(mapper, never()).updateById(any(BlogPost.class));
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

    @Test
    void publishedPostRejectsSlugChange() {
        BlogPost stored = publishablePost("post-1", BlogPost.POST_STATUS_PUBLISHED);
        stored.setSlug("fixed-slug");
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);

        BlogEditCommand edit = edit(stored, "changed-slug", stored.getTitle());

        assertThrows(BlogEditorialException.class, () -> service.save(edit, "admin-1"));
        verify(mapper, never()).updateById(any(BlogPost.class));
    }

    @Test
    void normalizedNoOpSavePreservesEditorialTimestamp() {
        Date editedAt = new Date(1_790_000_000_000L);
        Date auditNow = new Date(1_800_000_000_000L);
        BlogPost stored = publishablePost("post-2", BlogPost.POST_STATUS_DRAFT);
        stored.setContentUpdatedAt(editedAt);
        stored.setCategory(null);
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.updateById(any(BlogPost.class))).thenReturn(1);
        when(time.now()).thenReturn(auditNow);

        BlogPost result = service.save(new BlogEditCommand(
            stored.getId(), " Publish Me ", " Publish Me ", " Summary ", "<p>Body</p>",
            "   ", "  ", null, null, " Description "), " admin-1 ");

        assertEquals(editedAt, result.getContentUpdatedAt());
        assertEquals("publish-me", result.getSlug());
        assertNull(result.getAuthor());
        assertNull(result.getCategory());
        assertEquals("admin-1", result.getUpdateBy());
        assertEquals(auditNow, result.getUpdateTime());
        assertEquals(BlogPost.POST_STATUS_DRAFT, result.getStatus());
    }

    @Test
    void materialSaveAdvancesEditorialTimestampAndPreservesPublicationState() {
        Date publishedAt = new Date(1_780_000_000_000L);
        Date editedAt = new Date(1_790_000_000_000L);
        Date now = new Date(1_800_000_000_000L);
        BlogPost stored = publishablePost("post-3", BlogPost.POST_STATUS_DRAFT);
        stored.setPublishDate(publishedAt);
        stored.setContentUpdatedAt(editedAt);
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.updateById(any(BlogPost.class))).thenReturn(1);
        when(time.now()).thenReturn(now);

        BlogPost result = service.save(edit(stored, stored.getSlug(), "Revised title"), "admin-1");

        assertEquals("Revised title", result.getTitle());
        assertEquals(now, result.getContentUpdatedAt());
        assertEquals(now, result.getUpdateTime());
        assertEquals(publishedAt, result.getPublishDate());
        assertEquals(BlogPost.POST_STATUS_DRAFT, result.getStatus());
    }

    @Test
    void firstPublishSetsDateAndRepublishPreservesIt() {
        Date firstNow = new Date(1_800_000_000_000L);
        Date secondNow = new Date(1_800_000_100_000L);
        BlogPost stored = publishablePost("post-4", BlogPost.POST_STATUS_DRAFT);
        stored.setContent(BlogContentReviewTest.completeArticle());
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
        when(mapper.updateById(any(BlogPost.class))).thenReturn(1);
        when(time.now()).thenReturn(firstNow, secondNow);

        BlogPost first = service.publish(stored.getId(), "admin-1");
        Date originalPublishDate = first.getPublishDate();
        BlogPost second = service.publish(stored.getId(), "admin-1");

        assertEquals(firstNow, originalPublishDate);
        assertEquals(firstNow, first.getContentUpdatedAt());
        assertEquals(originalPublishDate, second.getPublishDate());
        assertEquals(BlogPost.POST_STATUS_PUBLISHED, second.getStatus());
    }

    @Test
    void publishResanitizesStoredHtmlAndAdvancesEditorialTimestamp() {
        Date oldEditorialTime = new Date(1_790_000_000_000L);
        Date now = new Date(1_800_000_000_000L);
        BlogPost stored = publishablePost("post-5", BlogPost.POST_STATUS_DRAFT);
        stored.setContent(BlogContentReviewTest.completeArticle() + "<script>alert(1)</script>");
        stored.setContentUpdatedAt(oldEditorialTime);
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
        when(mapper.updateById(any(BlogPost.class))).thenReturn(1);
        when(time.now()).thenReturn(now);

        BlogPost result = service.publish(stored.getId(), "admin-1");

        assertEquals(BlogContentReviewTest.completeArticle(), result.getContent());
        assertEquals(now, result.getContentUpdatedAt());
    }

    @ParameterizedTest(name = "publish rejects missing {0}")
    @MethodSource("unpublishablePosts")
    void publishRejectsIncompletePosts(String field, BlogPost stored) {
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);

        assertThrows(BlogEditorialException.class,
            () -> service.publish(stored.getId(), "admin-1"));

        verify(mapper, never()).updateById(any(BlogPost.class));
    }

    @Test
    void unpublishPreservesPublicationAndEditorialDates() {
        Date publishedAt = new Date(1_790_000_000_000L);
        Date editedAt = new Date(1_795_000_000_000L);
        Date now = new Date(1_800_000_000_000L);
        BlogPost stored = publishablePost("post-6", BlogPost.POST_STATUS_PUBLISHED);
        stored.setPublishDate(publishedAt);
        stored.setContentUpdatedAt(editedAt);
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
        when(mapper.updateById(any(BlogPost.class))).thenReturn(1);
        when(time.now()).thenReturn(now);

        BlogPost result = service.unpublish(stored.getId(), "admin-1");

        assertEquals(BlogPost.POST_STATUS_DRAFT, result.getStatus());
        assertEquals(publishedAt, result.getPublishDate());
        assertEquals(editedAt, result.getContentUpdatedAt());
        assertEquals(now, result.getUpdateTime());
    }

    @Test
    void unpublishRejectsDraftAndBlankActor() {
        BlogPost stored = publishablePost("post-7", BlogPost.POST_STATUS_DRAFT);
        when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);

        assertThrows(BlogEditorialException.class,
            () -> service.unpublish(stored.getId(), "admin-1"));
        assertThrows(BlogEditorialException.class,
            () -> service.unpublish(stored.getId(), "   "));
        verify(mapper, never()).updateById(any(BlogPost.class));
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

    private static Stream<Arguments> unpublishablePosts() {
        BlogPost missingSlug = publishablePost("missing-slug", BlogPost.POST_STATUS_DRAFT);
        missingSlug.setSlug(null);
        BlogPost missingTitle = publishablePost("missing-title", BlogPost.POST_STATUS_DRAFT);
        missingTitle.setTitle(" ");
        BlogPost missingSummary = publishablePost("missing-summary", BlogPost.POST_STATUS_DRAFT);
        missingSummary.setSummary(null);
        BlogPost missingContent = publishablePost("missing-content", BlogPost.POST_STATUS_DRAFT);
        missingContent.setContent("<script>alert(1)</script>");
        BlogPost missingDescription = publishablePost("missing-description", BlogPost.POST_STATUS_DRAFT);
        missingDescription.setMetaDescription(null);
        return Stream.of(
            Arguments.of("slug", missingSlug),
            Arguments.of("title", missingTitle),
            Arguments.of("summary", missingSummary),
            Arguments.of("content", missingContent),
            Arguments.of("meta description", missingDescription)
        );
    }

    private static BlogEditCommand edit(BlogPost stored, String slug, String title) {
        return new BlogEditCommand(
            stored.getId(), slug, title, stored.getSummary(), stored.getContent(),
            stored.getAuthor(), stored.getCategory(), stored.getTags(),
            stored.getMetaTitle(), stored.getMetaDescription());
    }

    private static BlogPost publishablePost(String id, int status) {
        BlogPost post = new BlogPost();
        post.setId(id);
        post.setSlug("publish-me");
        post.setTitle("Publish Me");
        post.setSummary("Summary");
        post.setContent("<p>Body</p>");
        post.setMetaDescription("Description");
        post.setStatus(status);
        post.setViewCount(0);
        post.setDeleted(0);
        return post;
    }

    private static BlogDraftCommand draft(String slug, String title, String content) {
        return new BlogDraftCommand(slug, title, null, content, null, null, null, null);
    }
}
