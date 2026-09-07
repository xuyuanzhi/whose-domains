package info.wesite.core.blog;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

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
    private final BlogReviewService review;

    public BlogEditorialService(
            BlogPostMapper mapper,
            BlogHtmlSanitizer sanitizer,
            BlogTimeProvider time,
            BlogReviewService review) {
        this.mapper = mapper;
        this.sanitizer = sanitizer;
        this.time = time;
        this.review = review;
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
        lockEditorialWrites();
        ensureSlugAvailable(slug, null);

        Date now = time.now();
        BlogPost post = new BlogPost();
        post.setId(RandomUtils.generateId());
        post.setSlug(slug);
        post.setTitle(title);
        post.setSummary(summary);
        post.setContent(content);
        post.setAuthor(BlogPost.SITE_AUTHOR);
        post.setAiGenerated(true);
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

        review.requireUnique(post);

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

    @Transactional
    public BlogPost save(BlogEditCommand command, String actorId) {
        String actor = requiredActor(actorId);
        if (command == null) {
            throw new BlogEditorialException("Edit is required");
        }
        lockEditorialWrites();
        BlogPost stored = requireLocked(command.id());
        requireNotArchived(stored);
        NormalizedEdit edit = normalizeAndValidate(command);
        if (Objects.equals(stored.getStatus(), BlogPost.POST_STATUS_PUBLISHED)
                && !Objects.equals(stored.getSlug(), edit.slug())) {
            throw new BlogEditorialException("Published slug cannot be changed");
        }
        ensureSlugAvailable(edit.slug(), stored.getId());

        boolean materialChange = differsFromPersisted(stored, edit);
        preserveAiProvenance(stored);
        applyEditableFields(stored, edit);
        if (Objects.equals(stored.getStatus(), BlogPost.POST_STATUS_PUBLISHED)) {
            validatePublishable(stored, stored.getContent());
            review.requirePublishable(stored);
        }
        Date now = time.now();
        if (materialChange) {
            stored.setContentUpdatedAt(now);
        }
        setUpdateAudit(stored, actor, now);
        update(stored);
        return stored;
    }

    @Transactional
    public BlogPost publish(String id, String actorId) {
        String actor = requiredActor(actorId);
        lockEditorialWrites();
        BlogPost stored = requireLocked(id);
        requireNotArchived(stored);
        validateHtmlSize(stored.getContent());
        String sanitized = sanitizer.sanitize(stored.getContent());
        validatePublishable(stored, sanitized);
        review.requirePublishable(stored);
        preserveAiProvenance(stored);

        Date now = time.now();
        if (!Objects.equals(sanitized, stored.getContent())) {
            stored.setContentUpdatedAt(now);
        }
        stored.setContent(sanitized);
        if (stored.getPublishDate() == null) {
            stored.setPublishDate(now);
        }
        if (stored.getContentUpdatedAt() == null) {
            stored.setContentUpdatedAt(now);
        }
        stored.setStatus(BlogPost.POST_STATUS_PUBLISHED);
        setUpdateAudit(stored, actor, now);
        update(stored);
        return stored;
    }

    @Transactional
    public BlogPost unpublish(String id, String actorId) {
        String actor = requiredActor(actorId);
        BlogPost stored = requireLocked(id);
        if (!Objects.equals(stored.getStatus(), BlogPost.POST_STATUS_PUBLISHED)) {
            throw new BlogEditorialException("Only published posts can be unpublished");
        }

        Date now = time.now();
        stored.setStatus(BlogPost.POST_STATUS_DRAFT);
        setUpdateAudit(stored, actor, now);
        update(stored);
        return stored;
    }

    private BlogPost requireLocked(String id) {
        String normalizedId = required(id, "Post ID is required");
        BlogPost stored = mapper.selectByIdForUpdate(normalizedId);
        if (stored == null) {
            throw new BlogEditorialException("Post not found");
        }
        return stored;
    }

    @Transactional
    public BlogPost archive(String id, String actorId) {
        String actor = requiredActor(actorId);
        lockEditorialWrites();
        BlogPost stored = requireLocked(id);
        if (!Objects.equals(stored.getStatus(), BlogPost.POST_STATUS_DRAFT)
                && !Objects.equals(stored.getStatus(), BlogPost.POST_STATUS_PUBLISHED)) {
            throw new BlogEditorialException("Only drafts or published posts can be archived");
        }
        preserveAiProvenance(stored);
        stored.setStatus(BlogPost.POST_STATUS_ARCHIVED);
        setUpdateAudit(stored, actor, time.now());
        update(stored);
        return stored;
    }

    @Transactional
    public BlogPost restore(String id, String actorId) {
        String actor = requiredActor(actorId);
        lockEditorialWrites();
        BlogPost stored = requireLocked(id);
        if (!Objects.equals(stored.getStatus(), BlogPost.POST_STATUS_ARCHIVED)) {
            throw new BlogEditorialException("Only archived posts can be restored");
        }
        preserveAiProvenance(stored);
        stored.setStatus(BlogPost.POST_STATUS_DRAFT);
        setUpdateAudit(stored, actor, time.now());
        update(stored);
        return stored;
    }

    private static void requireNotArchived(BlogPost post) {
        if (Objects.equals(post.getStatus(), BlogPost.POST_STATUS_ARCHIVED)) {
            throw new BlogEditorialException("Restore the archived post to a draft before editing or publishing");
        }
    }

    private static void preserveAiProvenance(BlogPost post) {
        if (post.isAiAssisted()) post.setAiGenerated(true);
    }

    @Transactional(readOnly = true)
    public BlogContentReview.Report review(BlogEditCommand command) {
        if (command == null) throw new BlogEditorialException("Edit is required");
        BlogPost candidate = new BlogPost();
        candidate.setId(required(command.id(), "Post ID is required"));
        applyEditableFields(candidate, normalizeAndValidate(command));
        return review.review(candidate);
    }

    private void lockEditorialWrites() {
        if (!Integer.valueOf(1).equals(mapper.lockEditorialWrites())) {
            throw new BlogEditorialException("Blog editorial lock missing; apply the blog content review migration first");
        }
    }

    private NormalizedEdit normalizeAndValidate(BlogEditCommand command) {
        validateHtmlSize(command.content());
        NormalizedEdit edit = new NormalizedEdit(
            normalizeSlug(command.slug()),
            required(command.title(), "Title is required"),
            optional(command.summary()),
            sanitizer.sanitize(command.content()),
            optional(command.author()),
            optional(command.category()),
            optional(command.tags()),
            optional(command.metaTitle()),
            optional(command.metaDescription()));

        validateLength(edit.slug(), 200, "Slug");
        validateLength(edit.title(), 300, "Title");
        validateLength(edit.summary(), 600, "Summary");
        validateLength(edit.author(), 100, "Author");
        validateLength(edit.category(), 100, "Category");
        validateLength(edit.tags(), 300, "Tags");
        validateLength(edit.metaTitle(), 300, "Meta title");
        validateLength(edit.metaDescription(), 600, "Meta description");
        return edit;
    }

    private void validatePublishable(BlogPost stored, String sanitizedContent) {
        String slug = normalizeSlug(stored.getSlug());
        if (!Objects.equals(slug, stored.getSlug())) {
            throw new BlogEditorialException("Slug must be normalized before publication");
        }
        String title = required(stored.getTitle(), "Title is required");
        String summary = required(stored.getSummary(), "Summary is required");
        String metaDescription = required(stored.getMetaDescription(), "Meta description is required");
        validateLength(slug, 200, "Slug");
        validateLength(title, 300, "Title");
        validateLength(summary, 600, "Summary");
        validateLength(stored.getAuthor(), 100, "Author");
        validateLength(stored.getCategory(), 100, "Category");
        validateLength(stored.getTags(), 300, "Tags");
        validateLength(stored.getMetaTitle(), 300, "Meta title");
        validateLength(metaDescription, 600, "Meta description");
        if (!sanitizer.hasVisibleContent(sanitizedContent)) {
            throw new BlogEditorialException("Content is required");
        }
    }

    private static boolean differsFromPersisted(BlogPost stored, NormalizedEdit edit) {
        return !Objects.equals(stored.getSlug(), edit.slug())
            || !Objects.equals(stored.getTitle(), edit.title())
            || !Objects.equals(stored.getSummary(), edit.summary())
            || !Objects.equals(stored.getContent(), edit.content())
            || !Objects.equals(stored.getAuthor(), edit.author())
            || !Objects.equals(stored.getCategory(), edit.category())
            || !Objects.equals(stored.getTags(), edit.tags())
            || !Objects.equals(stored.getMetaTitle(), edit.metaTitle())
            || !Objects.equals(stored.getMetaDescription(), edit.metaDescription());
    }

    private static void applyEditableFields(BlogPost stored, NormalizedEdit edit) {
        stored.setSlug(edit.slug());
        stored.setTitle(edit.title());
        stored.setSummary(edit.summary());
        stored.setContent(edit.content());
        stored.setAuthor(edit.author());
        stored.setCategory(edit.category());
        stored.setTags(edit.tags());
        stored.setMetaTitle(edit.metaTitle());
        stored.setMetaDescription(edit.metaDescription());
    }

    private void update(BlogPost stored) {
        try {
            if (mapper.updateById(stored) != 1) {
                throw new BlogEditorialException("Failed to update post");
            }
        } catch (DuplicateKeyException exception) {
            throw new BlogEditorialException("Slug already exists", exception);
        }
    }

    private static void setUpdateAudit(BlogPost stored, String actor, Date now) {
        stored.setUpdateBy(actor);
        stored.setUpdateTime(now);
    }

    private static String requiredActor(String actorId) {
        String actor = required(actorId, "Actor ID is required");
        validateLength(actor, 64, "Actor ID");
        return actor;
    }

    private void ensureSlugAvailable(String slug, String excludedId) {
        LambdaQueryWrapper<BlogPost> query = new LambdaQueryWrapper<>();
        query.eq(BlogPost::getSlug, slug);
        query.ne(excludedId != null, BlogPost::getId, excludedId);
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

    private record NormalizedEdit(
            String slug,
            String title,
            String summary,
            String content,
            String author,
            String category,
            String tags,
            String metaTitle,
            String metaDescription) {
    }
}
