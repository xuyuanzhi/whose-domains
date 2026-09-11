package info.wesite.admin.blog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import info.wesite.core.blog.*;

class BlogAssistantJobsTest {
    @Test void scanIsBoundedOwnerScopedAndPublishesCountsOnlyAfterCompletion() throws Exception {
        var review = mock(BlogReviewService.class);
        var optimizer = mock(BlogOptimizationService.class);
        var release = new CountDownLatch(1);
        when(review.audit(any())).thenAnswer(call -> {
            assertTrue(release.await(3, TimeUnit.SECONDS));
            java.util.function.BiConsumer<Integer,Integer> progress = call.getArgument(0);
            progress.accept(1, 1);
            return new BlogReviewService.AuditReport(1, List.of(item("a", "Example", "MISSING_SOURCE")));
        });
        var jobs = new BlogAssistantJobs(review, optimizer);
        try {
            var accepted = jobs.scan("owner");
            assertEquals("RUNNING", accepted.status());
            assertThrows(BlogEditorialException.class, () -> jobs.scan("owner"));
            assertThrows(BlogEditorialException.class, () -> jobs.get("another", accepted.id()));
            release.countDown();
            var result = await(jobs, accepted.id());
            assertEquals("SUCCEEDED", result.status());
            var scan = (BlogAssistantJobs.ScanResult) result.result();
            assertEquals(1, scan.scanned());
            assertEquals(1, scan.issues().get("MISSING_SOURCE"));
            assertEquals(1, result.completed());
            verifyNoInteractions(optimizer);
        } finally { release.countDown(); jobs.close(); }
    }
    @Test void databaseFailureIsNotReportedAsSuccessfulScanAndSecretsAreNotReturned() throws Exception {
        var review = mock(BlogReviewService.class);
        when(review.audit(any())).thenThrow(new IllegalStateException("database password=secret"));
        var jobs = new BlogAssistantJobs(review, mock(BlogOptimizationService.class));
        try {
            var result = await(jobs, jobs.scan("owner").id());
            assertEquals("FAILED", result.status());
            assertNull(result.result());
            assertFalse(result.message().contains("secret"));
        } finally { jobs.close(); }
    }
    @Test void anotherTaskDoesNotDiscardCompletedOwnerScopedResults() throws Exception {
        var review = mock(BlogReviewService.class);
        when(review.audit(any())).thenReturn(new BlogReviewService.AuditReport(0, List.of()));
        var jobs = new BlogAssistantJobs(review, mock(BlogOptimizationService.class));
        try {
            String first = jobs.scan("owner").id();
            await(jobs, first);
            String second = jobs.scan("another").id();
            assertEquals("SUCCEEDED", jobs.get("owner", first).status());
            assertThrows(BlogEditorialException.class, () -> jobs.get("another", first));
            assertNotNull(jobs.get("another", second));
        } finally { jobs.close(); }
    }
    @Test void topicalOverlapHintsCatchDnssecPairWithoutCreatingDuplicateBlockers() {
        var first = item("one", "DNSSEC Explained: How It Protects Your Domain from DNS Spoofing and Cache Poisoning", "MISSING_SOURCE");
        var second = item("two", "DNSSEC Signing Explained: How to Protect Your Domain from DNS Spoofing and Cache Poisoning", "MISSING_SOURCE");
        var pairs = BlogAssistantJobs.topics(List.of(first, second));
        assertEquals(1, pairs.size());
        assertEquals("one", pairs.get(0).firstId());
        assertFalse(first.review().hasDuplicate());
    }
    private static BlogAssistantJobs.Snapshot await(BlogAssistantJobs jobs, String id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        BlogAssistantJobs.Snapshot result;
        do { result = jobs.get("owner", id); if (!result.status().equals("RUNNING")) return result; Thread.sleep(10); }
        while (System.nanoTime() < until);
        fail("Background job did not complete"); return null;
    }
    private static BlogReviewService.AuditItem item(String id, String title, String issue) {
        return new BlogReviewService.AuditItem(id, id, title, 1,
            new BlogContentReview.Report(250, List.of(new BlogContentReview.Issue(issue, "Check")), List.of(), List.of()));
    }
}
