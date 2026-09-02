package info.wesite.core.blog;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import info.wesite.core.entity.BlogPost;
import info.wesite.core.mapper.BlogPostMapper;
import info.wesite.core.utils.RandomUtils;

@Service
public class BlogEditorialService {

    static final int MAX_HTML_BYTES = 1_048_576;

    private final BlogPostMapper mapper;
    private final BlogHtmlSanitizer sanitizer;
    private final BlogTimeProvider time;

    public BlogEditorialService(
            BlogPostMapper mapper,
            BlogHtmlSanitizer sanitizer,
            BlogTimeProvider time) {
        this.mapper = mapper;
        this.sanitizer = sanitizer;
        this.time = time;
    }

    @Transactional
    public BlogPost createAiDraft(BlogDraftCommand command) {
        if (command == null) {
            throw new BlogEditorialException("Draft is required");
        }

        validateHtmlSize(command.content());
        String slug = normalizeSlug(command.slug());
        String title = required(command.title(), "Title is required");
        String summary = optional(command.summary());
        String content = sanitizer.sanitize(command.content());
        String category = optional(command.category());
        String tags = optional(command.tags());
        String metaTitle = optional(command.metaTitle());
        String metaDescription = optional(command.metaDescription());

        validateLength(slug, 200, "Slug");
        validateLength(title, 300, "Title");
        validateLength(summary, 600, "Summary");
        validateLength(category, 100, "Category");
        validateLength(tags, 300, "Tags");
        validateLength(metaTitle, 300, "Meta title");
        validateLength(metaDescription, 600, "Meta description");
        if (!sanitizer.hasVisibleContent(content)) {
            throw new BlogEditorialException("Content is required");
        }
        ensureSlugAvailable(slug);

        Date now = time.now();
        BlogPost post = new BlogPost();
        post.setId(RandomUtils.generateId());
        post.setSlug(slug);
        post.setTitle(title);
        post.setSummary(summary);
        post.setContent(content);
        post.setAuthor(null);
        post.setCategory(category);
        post.setTags(tags);
        post.setMetaTitle(metaTitle);
        post.setMetaDescription(metaDescription);
        post.setStatus(BlogPost.POST_STATUS_DRAFT);
        post.setPublishDate(null);
        post.setViewCount(0);
        post.setDeleted(0);
        post.setCreateBy("ai");
        post.setCreateTime(now);
        post.setContentUpdatedAt(now);

        try {
            if (mapper.insert(post) != 1) {
                throw new BlogEditorialException("Failed to create draft");
            }
        } catch (DuplicateKeyException exception) {
            throw new BlogEditorialException("Slug already exists", exception);
        }
        return post;
    }

    public String sanitizePreview(String html) {
        validateHtmlSize(html);
        return sanitizer.sanitize(html);
    }

    private void ensureSlugAvailable(String slug) {
        LambdaQueryWrapper<BlogPost> query = new LambdaQueryWrapper<>();
        query.eq(BlogPost::getSlug, slug);
        if (mapper.selectCount(query) > 0) {
            throw new BlogEditorialException("Slug already exists");
        }
    }

    private static String normalizeSlug(String value) {
        String normalized = StringUtils.trimToEmpty(value)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (normalized.isEmpty()) {
            throw new BlogEditorialException("Slug is required");
        }
        return normalized;
    }

    private static String required(String value, String message) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new BlogEditorialException(message);
        }
        return normalized;
    }

    private static String optional(String value) {
        return StringUtils.trimToNull(value);
    }

    private static void validateLength(String value, int maxCodePoints, String field) {
        if (value != null && value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new BlogEditorialException(field + " is too long");
        }
    }

    private static void validateHtmlSize(String html) {
        if (html != null && html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            throw new BlogEditorialException("Content exceeds 1 MiB");
        }
    }
}
