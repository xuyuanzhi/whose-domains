package info.wesite.core.blog;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import info.wesite.core.blog.BlogSanitizationReport.ChangedPost;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.mapper.BlogPostMapper;

@Service
public class BlogSanitizationMaintenanceService {

    private static final String MAINTENANCE_ACTOR = "blog-sanitize";

    private final BlogPostMapper mapper;
    private final BlogHtmlSanitizer sanitizer;
    private final BlogTimeProvider time;
    private final TransactionTemplate transactions;

    public BlogSanitizationMaintenanceService(
            BlogPostMapper mapper,
            BlogHtmlSanitizer sanitizer,
            BlogTimeProvider time,
            TransactionTemplate transactions) {
        this.mapper = mapper;
        this.sanitizer = sanitizer;
        this.time = time;
        this.transactions = transactions;
    }

    public BlogSanitizationReport run(boolean apply, int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Batch size must be between 1 and 1000");
        }

        long scanned = 0;
        String afterId = null;
        List<ChangedPost> changedPosts = new ArrayList<>();

        while (true) {
            List<BlogPost> batch = mapper.selectSanitizationBatch(afterId, batchSize);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            scanned += batch.size();
            List<SanitizedPost> changedBatch = findChangedPosts(batch);
            if (apply && !changedBatch.isEmpty()) {
                applyBatch(changedBatch);
            }
            changedBatch.stream()
                .map(change -> new ChangedPost(change.post().getId(), change.post().getSlug()))
                .forEach(changedPosts::add);

            String nextAfterId = batch.get(batch.size() - 1).getId();
            if (StringUtils.isBlank(nextAfterId)
                    || afterId != null && nextAfterId.compareTo(afterId) <= 0) {
                throw new BlogEditorialException("Sanitization batch did not advance");
            }
            afterId = nextAfterId;
        }

        return new BlogSanitizationReport(scanned, changedPosts);
    }

    private List<SanitizedPost> findChangedPosts(List<BlogPost> batch) {
        List<SanitizedPost> changed = new ArrayList<>();
        for (BlogPost post : batch) {
            String original = StringUtils.defaultString(post.getContent());
            String sanitized = sanitizer.sanitize(original);
            if (!Objects.equals(original, sanitized)) {
                changed.add(new SanitizedPost(post, sanitized));
            }
        }
        return changed;
    }

    private void applyBatch(List<SanitizedPost> changedBatch) {
        Date contentUpdatedAt = time.now();
        transactions.executeWithoutResult(status -> {
            for (SanitizedPost change : changedBatch) {
                int updated = mapper.updateSanitizedContent(
                    change.post().getId(),
                    change.content(),
                    contentUpdatedAt,
                    MAINTENANCE_ACTOR);
                if (updated != 1) {
                    throw new BlogEditorialException(
                        "Failed to sanitize blog post: " + change.post().getId());
                }
            }
        });

        changedBatch.forEach(change -> change.post().setContent(change.content()));
    }

    private record SanitizedPost(BlogPost post, String content) {
    }
}
