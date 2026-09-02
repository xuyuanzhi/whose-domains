package info.wesite.admin.controller;

import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.admin.blog.BlogAdminModels.DetailResponse;
import info.wesite.admin.blog.BlogAdminModels.IdRequest;
import info.wesite.admin.blog.BlogAdminModels.ListItemResponse;
import info.wesite.admin.blog.BlogAdminModels.ListRequest;
import info.wesite.admin.blog.BlogAdminModels.PreviewRequest;
import info.wesite.admin.blog.BlogAdminModels.PreviewResponse;
import info.wesite.admin.blog.BlogAdminModels.SaveRequest;
import info.wesite.core.blog.BlogEditCommand;
import info.wesite.core.blog.BlogEditorialException;
import info.wesite.core.blog.BlogEditorialService;
import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.entity.User;
import info.wesite.core.service.BlogPostService;
import info.wesite.core.view.ResponseJson;

@RestController
@RequestMapping("/blog")
@AccessControl(level = AccessControl.Level.SESSION)
public class BlogAdminController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlogAdminController.class);
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_KEYWORD_CODE_POINTS = 200;
    private static final String GENERIC_ERROR = "Unable to complete the blog operation";

    private final BlogPostService posts;
    private final BlogEditorialService editorial;

    public BlogAdminController(BlogPostService posts, BlogEditorialService editorial) {
        this.posts = posts;
        this.editorial = editorial;
    }

    @PostMapping("/list")
    public ResponseJson<?> list(@RequestBody(required = false) ListRequest request) {
        try {
            ListRequest value = request == null ? new ListRequest(null, null, null, null) : request;
            int pageNumber = value.page() == null ? DEFAULT_PAGE : value.page();
            int limit = value.limit() == null ? DEFAULT_LIMIT : value.limit();
            String keyword = StringUtils.trimToNull(value.keyword());
            validateListRequest(pageNumber, limit, keyword, value.status());

            LambdaQueryWrapper<BlogPost> query = Wrappers.lambdaQuery();
            if (keyword != null) {
                query.and(nested -> nested.like(BlogPost::getTitle, keyword)
                    .or().like(BlogPost::getSlug, keyword)
                    .or().like(BlogPost::getSummary, keyword));
            }
            if (value.status() != null) {
                query.eq(BlogPost::getStatus, value.status());
            }
            query.orderByDesc(BlogPost::getPublishDate)
                .orderByDesc(BlogPost::getCreateTime);

            Page<BlogPost> result = posts.page(Page.of(pageNumber, limit), query);
            List<ListItemResponse> items = result.getRecords().stream()
                .map(BlogAdminController::toListItem)
                .toList();
            return ResponseJson.success(items, result.getTotal());
        } catch (BlogEditorialException exception) {
            return ResponseJson.failure(exception.getMessage());
        } catch (Exception exception) {
            return unexpected("list", exception);
        }
    }

    @PostMapping("/detail")
    public ResponseJson<?> detail(@RequestBody IdRequest request) {
        try {
            String id = requireId(request);
            BlogPost post = posts.getById(id);
            if (post == null) {
                return ResponseJson.failure("Post not found");
            }
            return ResponseJson.success(toDetail(post));
        } catch (BlogEditorialException exception) {
            return ResponseJson.failure(exception.getMessage());
        } catch (Exception exception) {
            return unexpected("detail", exception);
        }
    }

    @PostMapping("/save")
    public ResponseJson<?> save(@RequestBody SaveRequest request) {
        return editorialOperation("save", () -> {
            if (request == null) {
                throw new BlogEditorialException("Edit is required");
            }
            BlogEditCommand command = new BlogEditCommand(
                request.id(), request.slug(), request.title(), request.summary(), request.content(),
                request.author(), request.category(), request.tags(), request.metaTitle(),
                request.metaDescription());
            editorial.save(command, currentActorId());
            return ResponseJson.success();
        });
    }

    @PostMapping("/preview")
    public ResponseJson<?> preview(@RequestBody PreviewRequest request) {
        return editorialOperation("preview", () -> {
            if (request == null) {
                throw new BlogEditorialException("Preview is required");
            }
            return ResponseJson.success(new PreviewResponse(editorial.sanitizePreview(request.content())));
        });
    }

    @PostMapping("/publish")
    public ResponseJson<?> publish(@RequestBody IdRequest request) {
        return editorialOperation("publish", () -> {
            editorial.publish(requireId(request), currentActorId());
            return ResponseJson.success();
        });
    }

    @PostMapping("/unpublish")
    public ResponseJson<?> unpublish(@RequestBody IdRequest request) {
        return editorialOperation("unpublish", () -> {
            editorial.unpublish(requireId(request), currentActorId());
            return ResponseJson.success();
        });
    }

    private ResponseJson<?> editorialOperation(String operation, Supplier<ResponseJson<?>> action) {
        try {
            return action.get();
        } catch (BlogEditorialException exception) {
            return ResponseJson.failure(exception.getMessage());
        } catch (Exception exception) {
            return unexpected(operation, exception);
        }
    }

    private ResponseJson<?> unexpected(String operation, Exception exception) {
        LOGGER.error("Unexpected blog {} failure", operation, exception);
        return ResponseJson.error(GENERIC_ERROR);
    }

    private static void validateListRequest(int page, int limit, String keyword, Integer status) {
        if (page < 1) {
            throw new BlogEditorialException("Page must be at least 1");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BlogEditorialException("Limit must be between 1 and 100");
        }
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_CODE_POINTS) {
            throw new BlogEditorialException("Keyword is too long");
        }
        if (status != null
                && status != BlogPost.POST_STATUS_DRAFT
                && status != BlogPost.POST_STATUS_PUBLISHED) {
            throw new BlogEditorialException("Status must be 0 or 1");
        }
    }

    private static String requireId(IdRequest request) {
        String id = request == null ? null : StringUtils.trimToNull(request.id());
        if (id == null) {
            throw new BlogEditorialException("Post ID is required");
        }
        return id;
    }

    private static String currentActorId() {
        User user = UserHolder.get();
        String actorId = user == null ? null : StringUtils.trimToNull(user.getId());
        if (actorId == null) {
            throw new BlogEditorialException("Administrator identity is required");
        }
        return actorId;
    }

    private static ListItemResponse toListItem(BlogPost post) {
        return new ListItemResponse(
            post.getId(), post.getSlug(), post.getTitle(), post.getSummary(), post.getAuthor(),
            post.getCategory(), post.getTags(), post.getStatus(), post.getPublishDate(),
            post.getContentUpdatedAt());
    }

    private static DetailResponse toDetail(BlogPost post) {
        return new DetailResponse(
            post.getId(), post.getSlug(), post.getTitle(), post.getSummary(), post.getContent(),
            post.getAuthor(), post.getCategory(), post.getTags(), post.getMetaTitle(),
            post.getMetaDescription(), post.getStatus(), post.getPublishDate(),
            post.getContentUpdatedAt());
    }
}
