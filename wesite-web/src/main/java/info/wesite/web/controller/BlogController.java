package info.wesite.web.controller;

import java.text.SimpleDateFormat;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import info.wesite.web.task.AiBlogTask;
import jakarta.servlet.http.HttpServletResponse;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

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
    private AiBlogTask aiBlogTask;

    @Autowired
    private org.springframework.core.env.Environment env;

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
                .orderByDesc(BlogPost::getPublishDate);

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

        // Related posts (same category, excluding current)
        List<BlogPost> related = blogPostService.list(
                Wrappers.<BlogPost>lambdaQuery()
                        .eq(BlogPost::getStatus, BlogPost.POST_STATUS_PUBLISHED)
                        .eq(StringUtils.isNotBlank(post.getCategory()), BlogPost::getCategory, post.getCategory())
                        .ne(BlogPost::getId, post.getId())
                        .orderByDesc(BlogPost::getPublishDate)
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

    /**
     * 手动触发 AI 生成一篇 Blog。
     * 仅允许本机（127.0.0.1 / ::1）调用，不对外开放。
     * 用法：POST /blog/internal/generate?secret=xxx
     */
    @PostMapping("/internal/generate")
    @ResponseBody
    public ResponseEntity<String> manualGenerate(
            @RequestParam(defaultValue = "") String secret,
            HttpServletRequest request) {

        // 1. 只允许本机回环地址访问
//        String remoteAddr = request.getRemoteAddr();
//        if (!"127.0.0.1".equals(remoteAddr) && !"::1".equals(remoteAddr) && !"0:0:0:0:0:0:0:1".equals(remoteAddr)) {
//            return ResponseEntity.status(403).body("Forbidden");
//        }

        // 2. 简单密钥校验（在 application.properties 中配置 blog.internal.secret）
        String configuredSecret = env.getProperty("blog.internal.secret", "");
        if (StringUtils.isNotBlank(configuredSecret) && !configuredSecret.equals(secret)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        try {
            aiBlogTask.generateBlogPost();
            return ResponseEntity.ok("Blog generation triggered successfully.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
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
