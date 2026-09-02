package info.wesite.core.blog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import info.wesite.core.blog.BlogSanitizationReport.ChangedPost;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.mapper.BlogPostMapper;

class BlogSanitizationMaintenanceServiceTest {

    private BlogPostMapper mapper;
    private BlogTimeProvider time;
    private PlatformTransactionManager transactionManager;
    private BlogSanitizationMaintenanceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(BlogPostMapper.class);
        time = mock(BlogTimeProvider.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new BlogSanitizationMaintenanceService(
            mapper, new BlogHtmlSanitizer(), time, new TransactionTemplate(transactionManager));
    }

    @Test
    void dryRunReportsChangesWithoutUpdatingRows() {
        BlogPost unsafe = post("unsafe-id", "unsafe-slug", "<p>safe</p><script>x()</script>");
        when(mapper.selectSanitizationBatch(null, 100)).thenReturn(List.of(unsafe));
        when(mapper.selectSanitizationBatch("unsafe-id", 100)).thenReturn(List.of());

        BlogSanitizationReport report = service.run(false, 100);

        assertEquals(1, report.scanned());
        assertEquals(List.of(new ChangedPost("unsafe-id", "unsafe-slug")), report.changedPosts());
        verify(mapper, never()).updateSanitizedContent(anyString(), anyString(), any(), anyString());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void applyUpdatesOnlyChangedRowsAndAdvancesEditorialTime() {
        Date now = new Date(1_800_000_000_000L);
        BlogPost safe = post("safe-id", "safe", "<p>safe</p>");
        BlogPost unsafe = post("unsafe-id", "unsafe", "<p>safe</p><script>x()</script>");
        when(time.now()).thenReturn(now);
        when(mapper.selectSanitizationBatch(null, 100)).thenReturn(List.of(safe, unsafe));
        when(mapper.selectSanitizationBatch("unsafe-id", 100)).thenReturn(List.of());
        when(mapper.updateSanitizedContent(anyString(), anyString(), any(), anyString())).thenReturn(1);

        BlogSanitizationReport report = service.run(true, 100);

        assertEquals(2, report.scanned());
        assertEquals(1, report.changed());
        verify(mapper).updateSanitizedContent("unsafe-id", "<p>safe</p>", now, "blog-sanitize");
        verify(mapper, never()).updateSanitizedContent(eq("safe-id"), anyString(), any(), anyString());
        verify(transactionManager).commit(any());
    }

    @Test
    void repeatedApplyIsIdempotent() {
        BlogPost unsafe = post("unsafe-id", "unsafe", "<p>safe</p><script>x()</script>");
        Date now = new Date(1_800_000_000_000L);
        when(time.now()).thenReturn(now);
        when(mapper.selectSanitizationBatch(null, 100)).thenReturn(List.of(unsafe));
        when(mapper.selectSanitizationBatch("unsafe-id", 100)).thenReturn(List.of());
        when(mapper.updateSanitizedContent(anyString(), anyString(), any(), anyString())).thenReturn(1);

        BlogSanitizationReport first = service.run(true, 100);
        BlogSanitizationReport second = service.run(true, 100);

        assertEquals(1, first.changed());
        assertEquals(0, second.changed());
        verify(mapper, times(1)).updateSanitizedContent(
            "unsafe-id", "<p>safe</p>", now, "blog-sanitize");
    }

    @Test
    void applyCommitsChangedBatchesIndependently() {
        Date firstTime = new Date(1_800_000_000_000L);
        Date secondTime = new Date(1_800_000_100_000L);
        BlogPost first = post("a", "first", "<p>one</p><script>x()</script>");
        BlogPost second = post("b", "second", "<p>two</p><script>y()</script>");
        when(time.now()).thenReturn(firstTime, secondTime);
        when(mapper.selectSanitizationBatch(null, 1)).thenReturn(List.of(first));
        when(mapper.selectSanitizationBatch("a", 1)).thenReturn(List.of(second));
        when(mapper.selectSanitizationBatch("b", 1)).thenReturn(List.of());
        when(mapper.updateSanitizedContent(anyString(), anyString(), any(), anyString()))
            .thenReturn(1);

        BlogSanitizationReport report = service.run(true, 1);

        assertEquals(2, report.changed());
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(2)).commit(any());
        verify(mapper).updateSanitizedContent("a", "<p>one</p>", firstTime, "blog-sanitize");
        verify(mapper).updateSanitizedContent("b", "<p>two</p>", secondTime, "blog-sanitize");
    }

    @Test
    void rejectsUnsafeBatchSizes() {
        assertThrows(IllegalArgumentException.class, () -> service.run(false, 0));
        assertThrows(IllegalArgumentException.class, () -> service.run(false, 1001));
        verify(mapper, never()).selectSanitizationBatch(any(), any(Integer.class));
    }

    @Test
    void failedRowUpdateRollsBackTheCurrentBatch() {
        BlogPost unsafe = post("unsafe-id", "unsafe", "<p>safe</p><script>x()</script>");
        when(time.now()).thenReturn(new Date(1_800_000_000_000L));
        when(mapper.selectSanitizationBatch(null, 100)).thenReturn(List.of(unsafe));
        when(mapper.updateSanitizedContent(anyString(), anyString(), any(), anyString())).thenReturn(0);

        assertThrows(BlogEditorialException.class, () -> service.run(true, 100));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    private static BlogPost post(String id, String slug, String content) {
        BlogPost post = new BlogPost();
        post.setId(id);
        post.setSlug(slug);
        post.setContent(content);
        return post;
    }
}
