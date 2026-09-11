package info.wesite.admin.blog;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import info.wesite.core.blog.*;

/** One finite background operation per Admin process; no periodic scheduler. */
@Service
public class BlogAssistantJobs {
    private final BlogReviewService review;
    private final BlogOptimizationService optimizer;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), r -> { Thread t = new Thread(r, "blog-assistant"); t.setDaemon(true); return t; });
    private Job current;
    private final Map<String, Job> retained = new LinkedHashMap<>();
    private static final int MAX_RETAINED = 10;
    private static final long RETENTION_MS = 30 * 60 * 1000L;
    public BlogAssistantJobs(BlogReviewService review, BlogOptimizationService optimizer) {
        this.review = review; this.optimizer = optimizer;
    }
    public record Snapshot(String id, String kind, String status, String message, int completed, int total,
            String startedAt, Object result) {}
    public record ScanResult(int scanned, int affected, Map<String, Integer> issues,
            List<BlogReviewService.AuditItem> findings, List<TopicCandidate> topics) {}
    public record TopicCandidate(String firstId, String firstTitle, String secondId, String secondTitle, double similarity) {}
    private static class Job {
        String id = UUID.randomUUID().toString(), owner, kind, status = "RUNNING", message = "正在准备";
        int completed, total;
        Instant started = Instant.now();
        Instant finished;
        Object result;
    }
    public synchronized Snapshot scan(String owner) {
        Job job = begin(owner, "SCAN");
        submit(job, () -> {
            var report = review.audit((done, total) -> update(job, "正在检查文章", done, total));
            Map<String, Integer> issues = new TreeMap<>();
            report.findings().forEach(f -> f.review().blockers().forEach(i -> issues.merge(i.code(), 1, Integer::sum)));
            return new ScanResult(report.scanned(), report.findings().size(), Map.copyOf(issues), report.findings(),
                topics(report.findings()));
        });
        return snapshot(job);
    }
    public synchronized Snapshot optimize(String owner, BlogOptimizationService.Request request) {
        // Validate before accepting a paid AI operation.
        optimizer.validate(request);
        Job job = begin(owner, "OPTIMIZE");
        submit(job, () -> {
            update(job, "正在查找并读取官方资料、生成优化稿和复检，最多约 6 分钟", 0, 1);
            try { return optimizer.optimize(request); }
            catch (BlogEditorialException e) { throw e; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BlogEditorialException("任务已中断，请重试。"); }
            catch (Exception e) { throw new BlogEditorialException("AI 优化失败或超时，请检查服务配置后重试。"); }
        });
        return snapshot(job);
    }
    public synchronized Snapshot get(String owner, String id) {
        expire();
        Job job = retained.get(id);
        if (job == null || !job.owner.equals(owner))
            throw new BlogEditorialException("任务不存在或已过期，服务重启后请重新操作。");
        return snapshot(job);
    }
    private Job begin(String owner, String kind) {
        if (owner == null || owner.isBlank()) throw new BlogEditorialException("请先登录管理员账号。");
        if (current != null && "RUNNING".equals(current.status)) throw new BlogEditorialException("已有扫描或优化任务运行中，请等待完成后再操作。");
        expire();
        if (retained.size() >= MAX_RETAINED)
            throw new BlogEditorialException("暂存任务已达上限，请等待旧结果过期后重试（完成后保留30分钟）。");
        Job job = new Job(); job.owner = owner; job.kind = kind; current = job;
        retained.put(job.id, job); return job;
    }
    private void expire() {
        Instant cutoff = Instant.now().minusMillis(RETENTION_MS);
        retained.values().removeIf(job -> job.finished != null && job.finished.isBefore(cutoff));
    }
    private void submit(Job job, Supplier<Object> work) {
        try {
            executor.execute(() -> {
                try {
                    Object result = work.get();
                    synchronized (this) { job.result = result; job.status = "SUCCEEDED"; job.message = "已完成"; job.completed = job.total; job.finished = Instant.now(); }
                } catch (Exception e) {
                    synchronized (this) { job.status = "FAILED"; job.message = e instanceof BlogEditorialException ? e.getMessage() : "扫描失败，请检查数据库连接、迁移和文章容量。"; job.finished = Instant.now(); }
                }
            });
        } catch (RejectedExecutionException e) {
            job.status = "FAILED"; job.message = "后台服务正在关闭，请稍后重试。";
            job.finished = Instant.now();
            throw new BlogEditorialException(job.message);
        }
    }
    private synchronized void update(Job job, String message, int done, int total) {
        job.message = message; job.completed = done; job.total = total;
    }
    private Snapshot snapshot(Job job) {
        return new Snapshot(job.id, job.kind, job.status, job.message, job.completed, job.total, job.started.toString(), job.result);
    }
    // Editorial hints only, deliberately separate from publication duplicate blockers.
    // This analysis concerns findings; clean articles omitted from the audit are not included.
    static List<TopicCandidate> topics(List<BlogReviewService.AuditItem> items) {
        List<TopicCandidate> result = new ArrayList<>();
        List<Set<String>> titles = items.stream().map(i -> tokens(i.title())).toList();
        for (int a = 0; a < items.size(); a++) for (int b = a + 1; b < items.size(); b++) {
            var left = titles.get(a); var right = titles.get(b);
            long common = left.stream().filter(right::contains).count();
            double similarity = (double) common / Math.max(1, left.size() + right.size() - common);
            if (common >= 4 && similarity >= .5) {
                var first = items.get(a); var second = items.get(b);
                result.add(new TopicCandidate(first.id(), first.title(), second.id(), second.title(), similarity));
                result.sort(Comparator.comparingDouble(TopicCandidate::similarity).reversed());
                if (result.size() > 100) result.remove(result.size() - 1);
            }
        }
        return List.copyOf(result);
    }
    private static Set<String> tokens(String title) {
        Set<String> words = new HashSet<>(Arrays.asList(java.text.Normalizer.normalize(title == null ? "" : title,
            java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        words.removeAll(Set.of("", "how", "to", "the", "a", "an", "and", "or", "of", "for", "your", "in", "is", "it", "what", "why", "with", "by", "from", "explained", "guide", "use"));
        words.removeIf(w -> w.matches("20\\d\\d"));
        return words;
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
