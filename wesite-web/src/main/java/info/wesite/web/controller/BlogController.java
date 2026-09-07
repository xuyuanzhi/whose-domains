package info.wesite.web.controller;

import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import info.wesite.core.blog.BlogHtmlSanitizer;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.service.BlogPostService;
import info.wesite.core.utils.Constants;
import info.wesite.web.config.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@Tag(name = "Blog")
@Controller
@RequestMapping("/blog")
public class BlogController {

    private static final int PAGE_SIZE = 10;

    @Autowired
    private BlogPostService blogPostService;

    @Autowired
    private BlogHtmlSanitizer htmlSanitizer;

    /** Blog 首页 / 列表页 */
    @GetMapping({"", "/"})
    public String index(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            Model model, HttpServletRequest request) {

        var wrapper = Wrappers.<BlogPost>lambdaQuery()
                .eq(BlogPost::getStatus, BlogPost.POST_STATUS_PUBLISHED)
                .eq(StringUtils.isNotBlank(category), BlogPost::getCategory, category)
                .like(StringUtils.isNotBlank(tag), BlogPost::getTags, tag)
                .orderByDesc(BlogPost::getPublishDate)
                .orderByDesc(BlogPost::getId);

        Page<BlogPost> pageResult = blogPostService.page(new Page<>(page, PAGE_SIZE), wrapper);
        formatDates(pageResult.getRecords());

        model.addAttribute("posts", pageResult.getRecords());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", (int) pageResult.getPages());
        model.addAttribute("totalPosts", pageResult.getTotal());
        model.addAttribute("category", category);
        model.addAttribute("tag", tag);
        model.addAttribute("_page_title", "Blog - Domain Tips, Guides & Insights | Whose.Domains");
        model.addAttribute("_page_metaDescription",
                "Expert guides on domain registration, WHOIS lookup, DNS records, SSL certificates, and domain investing. Stay informed with Whose.Domains.");
        model.addAttribute("requestURI", request.getRequestURI());
        return "blog/index";
    }

    /** 分类页 */
    @GetMapping("/category/{cat}")
    public String category(@PathVariable String cat,
                           @RequestParam(defaultValue = "1") int page,
                           Model model, HttpServletRequest request) {
        return index(page, cat, null, model, request);
    }

    /** 文章详情页 */
    @GetMapping("/{slug}")
    public String detail(@PathVariable String slug, Model model, HttpServletRequest request) {
        BlogPost post = blogPostService.getOne(
                Wrappers.<BlogPost>lambdaQuery()
                        .eq(BlogPost::getSlug, slug)
                        .eq(BlogPost::getStatus, BlogPost.POST_STATUS_PUBLISHED));

        if (post == null) {
            throw new ResourceNotFoundException("Blog post not found: " + slug);
        }

        post.setContent(htmlSanitizer.sanitize(post.getContent()));

        // Increment view count
        blogPostService.update(Wrappers.<BlogPost>lambdaUpdate()
                .setSql("VIEW_COUNT = IFNULL(VIEW_COUNT,0) + 1")
                .eq(BlogPost::getId, post.getId()));

        if (post.getPublishDate() != null) {
            post.setPublishDateText(new SimpleDateFormat("MMMM d, yyyy").format(post.getPublishDate()));
        }
        if (post.getTags() != null) {
            post.setTagArray(post.getTags().split(","));
        }

        model.addAttribute("_blogSchema", buildBlogSchema(post));

        // Related posts (same category, excluding current)
        List<BlogPost> related = blogPostService.list(
                Wrappers.<BlogPost>lambdaQuery()
                        .eq(BlogPost::getStatus, BlogPost.POST_STATUS_PUBLISHED)
                        .eq(StringUtils.isNotBlank(post.getCategory()), BlogPost::getCategory, post.getCategory())
                        .ne(BlogPost::getId, post.getId())
                        .orderByDesc(BlogPost::getPublishDate)
                        .orderByDesc(BlogPost::getId)
                        .last("LIMIT 3"));
        formatDates(related);

        model.addAttribute("post", post);
        model.addAttribute("relatedPosts", related);
        model.addAttribute(Constants.PAGE_TITLE, post.getEffectiveMetaTitle());
        model.addAttribute(Constants.PAGE_META_DESC, post.getEffectiveMetaDescription());
        model.addAttribute(Constants.OG_TYPE, "article");

        // Dynamic OG image
        try {
            String ogTitle    = java.net.URLEncoder.encode(post.getTitle(), "UTF-8");
            String ogSubtitle = java.net.URLEncoder.encode(
                post.getSummary() != null ? post.getSummary().substring(0, Math.min(post.getSummary().length(), 80)) : "Whose.Domains Blog", "UTF-8");
            model.addAttribute("_og_image_url",
                "https://whose.domains/og-image.png?type=blog&title=" + ogTitle + "&subtitle=" + ogSubtitle);
        } catch (Exception ignored) {}

        model.addAttribute("requestURI", request.getRequestURI());
        return "blog/detail";
    }

    private String buildBlogSchema(BlogPost post) {
        String articleUrl = "https://whose.domains/blog/" + post.getSlug();

        JSONObject mainEntity = new JSONObject();
        mainEntity.put("@type", "WebPage");
        mainEntity.put("@id", articleUrl);

        JSONObject image = new JSONObject();
        image.put("@type", "ImageObject");
        image.put("url", StringUtils.defaultIfBlank(
            post.getCover(), "https://whose.domains/og-image.png?type=blog"));

        JSONObject author = new JSONObject();
        if (post.isOrganizationAuthor()) {
            author.put("@type", "Organization");
            author.put("name", post.getEffectiveAuthor());
            author.put("url", "https://whose.domains");
        } else {
            author.put("@type", "Person");
            author.put("name", post.getEffectiveAuthor());
            author.put("url", "https://whose.domains/blog");
        }

        JSONObject logo = new JSONObject();
        logo.put("@type", "ImageObject");
        logo.put("url", "https://whose.domains/static/image/transparent-logo.png");
        logo.put("width", 200);
        logo.put("height", 60);

        JSONObject publisher = new JSONObject();
        publisher.put("@type", "Organization");
        publisher.put("name", "Whose.Domains");
        publisher.put("url", "https://whose.domains");
        publisher.put("logo", logo);

        JSONObject isPartOf = new JSONObject();
        isPartOf.put("@type", "Blog");
        isPartOf.put("name", "Whose.Domains Blog");
        isPartOf.put("url", "https://whose.domains/blog");

        JSONObject root = new JSONObject();
        root.put("@context", "https://schema.org");
        root.put("@type", "BlogPosting");
        root.put("mainEntityOfPage", mainEntity);
        root.put("headline", post.getTitle());
        root.put("description", post.getEffectiveMetaDescription());
        root.put("image", image);
        root.put("author", author);
        root.put("publisher", publisher);
        putDate(root, "datePublished", post.getPublishDate());
        Date modified = post.getContentUpdatedAt() != null
            ? post.getContentUpdatedAt()
            : post.getPublishDate();
        putDate(root, "dateModified", modified);
        root.put("inLanguage", "en-US");
        root.put("articleSection", StringUtils.defaultIfBlank(post.getCategory(), "Blog"));
        root.put("keywords", StringUtils.defaultString(post.getTags()));
        root.put("url", articleUrl);
        root.put("isPartOf", isPartOf);
        return JSON.toJSONString(root, JSONWriter.Feature.BrowserSecure);
    }

    private static void putDate(JSONObject target, String name, Date date) {
        if (date != null) {
            target.put(name, date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString());
        }
    }

    private void formatDates(List<BlogPost> posts) {
        var fmt = new SimpleDateFormat("MMM d, yyyy");
        for (BlogPost p : posts) {
            if (p.getPublishDate() != null) {
                p.setPublishDateText(fmt.format(p.getPublishDate()));
            }
        }
    }
}
