package info.wesite.core.blog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import info.wesite.core.entity.BlogPost;
import info.wesite.core.mapper.BlogPostMapper;

@Service
public class BlogReviewService {
    private final BlogPostMapper mapper;
    private final BlogHtmlSanitizer sanitizer;
    private final BlogContentReview policy = new BlogContentReview();

    public BlogReviewService(BlogPostMapper mapper, BlogHtmlSanitizer sanitizer) {
        this.mapper = mapper;
        this.sanitizer = sanitizer;
    }

    public BlogContentReview.Report review(BlogPost post) {
        return policy.review(describe(post), corpus());
    }

    @Transactional(readOnly = true)
    public BlogContentReview.Report reviewById(String id) {
        if (StringUtils.isBlank(id)) throw new BlogEditorialException("Post ID is required");
        BlogPost post = mapper.selectById(id.trim());
        if (post == null) throw new BlogEditorialException("Post not found");
        return review(post);
    }

    public void requirePublishable(BlogPost post) {
        BlogContentReview.Report report = review(post);
        if (!report.isPublishable()) {
            throw new BlogEditorialException(report.blockers().stream()
                .map(BlogContentReview.Issue::message).collect(java.util.stream.Collectors.joining(" ")));
        }
    }

    public void requireUnique(BlogPost post) {
        BlogContentReview.Report report = review(post);
        if (report.hasDuplicate()) {
            throw new BlogEditorialException("Duplicate article: inspect the blog review report and update the existing article");
        }
    }

    public boolean topicExists(String title) {
        String normalized = BlogContentReview.normalize(title);
        return corpus().stream().anyMatch(f -> !normalized.isEmpty() && normalized.equals(f.title()));
    }

    @Transactional(readOnly = true)
    public AuditReport audit() {
        List<BlogContentReview.Facts> corpus = corpus();
        List<AuditItem> findings = new ArrayList<>();
        for (BlogContentReview.Facts facts : corpus) {
            BlogContentReview.Report report = policy.review(facts, corpus);
            if (!report.isPublishable() || !report.matches().isEmpty()) {
                BlogPost post = facts.post();
                findings.add(new AuditItem(post.getId(), post.getSlug(), post.getTitle(), post.getStatus(), report));
            }
        }
        return new AuditReport(corpus.size(), List.copyOf(findings));
    }

    private BlogContentReview.Facts describe(BlogPost post) {
        String html = StringUtils.defaultString(post.getContent());
        if (html.getBytes(StandardCharsets.UTF_8).length > BlogEditorialService.MAX_HTML_BYTES) {
            throw new BlogEditorialException("Article exceeds review size limit: " + post.getId());
        }
        return policy.describe(post, sanitizer.sanitize(html));
    }

    private List<BlogContentReview.Facts> corpus() {
        List<BlogContentReview.Facts> result = new ArrayList<>();
        String afterId = null;
        long bytes = 0;
        while (true) {
            List<BlogPost> batch = mapper.selectReviewBatch(afterId, 100);
            if (batch.isEmpty()) break;
            for (BlogPost post : batch) {
                bytes += StringUtils.defaultString(post.getContent()).getBytes(StandardCharsets.UTF_8).length;
                if (result.size() >= 4000 || bytes > 32L * 1024 * 1024) {
                    throw new BlogEditorialException("Blog corpus exceeds review capacity; no partial review is allowed");
                }
                result.add(describe(post));
            }
            String next = batch.get(batch.size() - 1).getId();
            if (StringUtils.isBlank(next) || next.equals(afterId)) {
                throw new BlogEditorialException("Blog review scan did not advance");
            }
            afterId = next;
        }
        return result;
    }

    public record AuditItem(String id, String slug, String title, Integer status, BlogContentReview.Report review) {}
    public record AuditReport(int scanned, List<AuditItem> findings) {}
}
