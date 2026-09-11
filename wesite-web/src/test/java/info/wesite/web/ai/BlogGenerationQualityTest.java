package info.wesite.web.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import info.wesite.core.blog.*;
import info.wesite.core.entity.BlogPost;

class BlogGenerationQualityTest {
    DeepSeekClient ai = mock(DeepSeekClient.class);
    info.wesite.core.mapper.BlogPostMapper mapper = mock(info.wesite.core.mapper.BlogPostMapper.class);
    BlogHtmlSanitizer sanitizer = new BlogHtmlSanitizer();
    BlogEditorialService editorial = spy(new BlogEditorialService(mapper, sanitizer, mock(BlogTimeProvider.class), new BlogReviewService(mapper, sanitizer)));
    BlogGenerationQuality quality = new BlogGenerationQuality(ai, editorial);
    String article;
    static final String APPROVED = "{\"approved\":true,\"issues\":[],\"rationale\":\"Scoped explanation with labelled steps and evidence\"}";
    @BeforeEach void setup() throws Exception {
        article = new String(getClass().getResourceAsStream("/blog-quality/dns-cache.txt").readAllBytes(), StandardCharsets.UTF_8);
        when(ai.chat(contains("independent technical editorial reviewer"), anyString())).thenReturn(APPROVED);
    }
    BlogDraftCommand generate() throws Exception {
        return quality.generate("Why DNS cache answers differ", "dns-cache", "dns", "dns,ttl", "writer", "Write a scoped guide", List.of("Inspecting DNS records"));
    }
    @Test void completeSampleIsReviewedAgainstEvidenceAndExistingTopicsWithoutSaving() throws Exception {
        when(ai.chat(eq("writer"), anyString())).thenReturn(article);
        var result = generate();
        assertTrue(result.content().contains("rfc1035.html"));
        verify(ai).chat(contains("independent technical editorial reviewer"), contains("Inspecting DNS records"));
        verify(ai).chat(eq("writer"), contains("Editorial evidence"));
        verify(editorial, never()).createAiDraft(any());
    }
    @Test void missingFieldsAndThinProseAreRepairedBeforeReturning() throws Exception {
        when(ai.chat(eq("writer"), anyString())).thenReturn("===CONTENT===\n<p>Partial</p>", article);
        assertNotNull(generate());
        verify(ai, times(2)).chat(eq("writer"), anyString());
        verify(ai).chat(eq("writer"), contains("OUTPUT_SECTIONS_MISSING"));
    }
    @Test void unsupportedCitationNeverPassesEvenWhenReviewerWouldApprove() throws Exception {
        when(ai.chat(eq("writer"), anyString())).thenReturn(article.replace("https://www.rfc-editor.org/rfc/rfc1035.html", "https://invented.example/fake"));
        var error = assertThrows(BlogEditorialException.class, this::generate);
        assertTrue(error.getMessage().contains("UNSUPPORTED_SOURCE"));
        verify(ai, times(3)).chat(eq("writer"), anyString());
        verify(ai, never()).chat(contains("independent technical editorial reviewer"), anyString());
    }
    @Test void semanticRecyclingOrFillerMustBeRevisedAndCanExhaustBudget() throws Exception {
        when(ai.chat(eq("writer"), anyString())).thenReturn(article);
        when(ai.chat(contains("independent technical editorial reviewer"), anyString()))
            .thenReturn("{\"approved\":false,\"issues\":[\"Same reader problem as existing article; no new diagnostic value\"],\"rationale\":\"Reworded title\"}");
        var error = assertThrows(BlogEditorialException.class, this::generate);
        assertTrue(error.getMessage().contains("after 3 attempts"));
        verify(ai, times(3)).chat(eq("writer"), anyString());
        verify(ai, times(3)).chat(contains("independent technical editorial reviewer"), anyString());
    }
    @Test void inconsistentReviewerVerdictFailsClosed() throws Exception {
        when(ai.chat(eq("writer"), anyString())).thenReturn(article);
        when(ai.chat(contains("independent technical editorial reviewer"), anyString()))
            .thenReturn("{\"approved\":true,\"issues\":[\"Unsupported claim\"],\"rationale\":\"Bad\"}");
        assertTrue(assertThrows(BlogEditorialException.class, this::generate).getMessage().contains("SEMANTIC_REVIEW_INVALID"));
    }
    @Test void transportFailureDoesNotRetryPaidRequests() throws Exception {
        when(ai.chat(eq("writer"), anyString())).thenThrow(new java.io.IOException("truncated"));
        assertThrows(java.io.IOException.class, this::generate);
        verify(ai, times(1)).chat(eq("writer"), anyString());
    }
    @Test void rejectsRepeatedMarkersAndHtmlMetadata() {
        assertThrows(BlogEditorialException.class, () -> quality.parse(article + "\n===SUMMARY===\nagain", "t", "s", "dns", "dns"));
        assertThrows(BlogEditorialException.class, () -> quality.parse(article.replace("===SUMMARY===\n", "===SUMMARY===\n<b>Bad</b>"), "t", "s", "dns", "dns"));
    }
    @Test void thinDraftCannotSkipMechanicalReview() throws Exception {
        when(ai.chat(eq("writer"), anyString())).thenReturn("===SUMMARY===\nSummary\n===CONTENT===\n<p>Too short.</p>\n===META_DESCRIPTION===\nDescription");
        assertTrue(assertThrows(BlogEditorialException.class, this::generate).getMessage().contains("THIN_CONTENT"));
        verify(ai, never()).chat(contains("independent technical editorial reviewer"), anyString());
    }
    @Test void semanticFeedbackIsUsedAndRecheckedAfterRevision() throws Exception {
        when(ai.chat(eq("writer"), anyString())).thenReturn(article);
        when(ai.chat(contains("independent technical editorial reviewer"), anyString()))
            .thenReturn("{\"approved\":false,\"issues\":[\"Explain expected outcomes\"],\"rationale\":\"Missing interpretation\"}", APPROVED);
        assertNotNull(generate());
        verify(ai).chat(eq("writer"), contains("Explain expected outcomes"));
        verify(ai, times(2)).chat(contains("independent technical editorial reviewer"), anyString());
    }
    @Test void evidenceMustBeLocalAndBounded(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(quality, "evidenceLocation", "https://example.org/data");
        assertThrows(java.io.IOException.class, quality::evidence);
        var file = directory.resolve("evidence.txt");
        java.nio.file.Files.writeString(file, "x".repeat(60001));
        org.springframework.test.util.ReflectionTestUtils.setField(quality, "evidenceLocation", file.toUri().toString());
        assertThrows(java.io.IOException.class, quality::evidence);
        java.nio.file.Files.writeString(file, "No sources");
        assertThrows(java.io.IOException.class, quality::evidence);
        java.nio.file.Files.writeString(file, "Source: https://example.org/reference\nScope: scoped fact\nEvidence: editor-verified fact");
        assertTrue(quality.evidence().contains("editor-verified fact"));
    }
    @Test void unsavedCandidateStillChecksEveryPersistedArticle() throws Exception {
        var existing = new BlogPost();
        existing.setId("existing"); existing.setTitle("Why DNS cache answers differ");
        existing.setContent("<p>A different existing article about the same title</p>");
        when(mapper.selectReviewBatch(null, 100)).thenReturn(List.of(existing));
        when(ai.chat(eq("writer"), anyString())).thenReturn(article);
        assertTrue(assertThrows(BlogEditorialException.class, this::generate).getMessage().contains("DUPLICATE"));
        verify(ai, never()).chat(contains("independent technical editorial reviewer"), anyString());
        verify(editorial, never()).createAiDraft(any());
    }
}
