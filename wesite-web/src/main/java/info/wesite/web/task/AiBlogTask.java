package info.wesite.web.task;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.BlogPost;
import info.wesite.core.service.BlogPostService;
import info.wesite.web.ai.DeepSeekClient;

/**
 * AI 博客自动生成定时任务
 *
 * 每3天凌晨2点自动调用 DeepSeek 生成一篇博客文章并发布。
 * 话题由 DeepSeek 根据已有文章动态生成，不依赖固定列表，可长期无人值守运行。
 */
@Profile({ "prod", "mac", "dev" })
@Component
@EnableScheduling
public class AiBlogTask {

    private static final Logger log = LoggerFactory.getLogger(AiBlogTask.class);

    /** 笔名池，随机选取，避免单一作者名 */
    private static final List<String> AUTHORS = List.of(
        "James Chen", "Mark Zhang"
    );

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private BlogPostService blogPostService;

    /**
     * 每3天凌晨2点执行一次（避免流量高峰）
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 2 */3 * ?")
    public void generateBlogPost() {
        if (!deepSeekClient.isConfigured()) {
            log.warn("[AiBlogTask] DeepSeek API key not configured, skipping.");
            return;
        }

        try {
            // Step 1: 让 DeepSeek 根据已有文章生成一个新话题
            TopicDef topic = generateTopic();
            if (topic == null) {
                log.warn("[AiBlogTask] Failed to generate a topic, skipping.");
                return;
            }
            log.info("[AiBlogTask] Generated topic: {} [{}]", topic.title, topic.category);

            String slug = toSlug(topic.title);

            // 防止 slug 重复
            long exists = blogPostService.count(
                Wrappers.<BlogPost>lambdaQuery().eq(BlogPost::getSlug, slug));
            if (exists > 0) {
                log.info("[AiBlogTask] Post with slug '{}' already exists, skipping.", slug);
                return;
            }

            // Step 2: 生成正文
            String rawContent = deepSeekClient.chat(buildSystemPrompt(), buildUserPrompt(topic.title));

            String summary  = extractSection(rawContent, "SUMMARY");
            String content  = extractSection(rawContent, "CONTENT");
            String metaDesc = extractSection(rawContent, "META_DESCRIPTION");

            if (StringUtils.isBlank(content)) {
                log.warn("[AiBlogTask] Empty content returned, skipping save.");
                return;
            }

            String author = AUTHORS.get((int) (Math.random() * AUTHORS.size()));

            BlogPost post = new BlogPost();
            post.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            post.setSlug(slug);
            post.setTitle(topic.title);
            post.setSummary(StringUtils.isBlank(summary) ? "" : summary.trim());
            post.setContent(content.trim());
            post.setAuthor(author);
            post.setCategory(topic.category);
            post.setTags(topic.tags);
            post.setPublishDate(new Date());
            post.setViewCount(0);
            post.setStatus(BlogPost.POST_STATUS_PUBLISHED);
            post.setMetaTitle(topic.title + " | Whose.Domains Blog");
            post.setMetaDescription(StringUtils.isBlank(metaDesc) ? summary : metaDesc.trim());

            blogPostService.save(post);
            log.info("[AiBlogTask] Blog post saved: slug={}, author={}", slug, author);

        } catch (Exception e) {
            log.error("[AiBlogTask] Failed to generate blog post", e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * 调用 DeepSeek 动态生成一个新话题，避免与已发布文章重复。
     * 返回解析后的 TopicDef，解析失败返回 null。
     */
    private TopicDef generateTopic() {
        // 收集最近已有标题，让模型刻意避开
        List<String> recentTitles = blogPostService.list(
                Wrappers.<BlogPost>lambdaQuery()
                    .select(BlogPost::getTitle)
                    .orderByDesc(BlogPost::getPublishDate)
                    .last("LIMIT 30"))
            .stream().map(BlogPost::getTitle).toList();

        String existing = recentTitles.isEmpty() ? "none"
            : String.join("\n- ", recentTitles);

        String systemPrompt = """
                You are an editorial planner for Whose.Domains, a website offering domain lookup, WHOIS, DNS, SSL, and network tools.
                Your job is to propose ONE unique, SEO-friendly blog article topic.
                The topic must be relevant to at least one of: domain names, WHOIS, RDAP, DNS, SSL/TLS, IP addresses,
                domain investing, brand protection, cybersecurity, or web hosting.
                Respond ONLY with these three lines, no extra text:
                TITLE: <article title>
                CATEGORY: <one of: domain-tools|dns|security|seo|domain-investing|brand-protection|network|hosting>
                TAGS: <comma-separated keywords, 3-6 tags>
                """;

        String userPrompt = "Already published topics (avoid repeating or being too similar):\n- " + existing
            + "\n\nPropose a fresh, interesting topic that hasn't been covered yet.";

        try {
            String raw = deepSeekClient.chat(systemPrompt, userPrompt);
            return parseTopic(raw);
        } catch (Exception e) {
            log.error("[AiBlogTask] Topic generation failed", e);
            return null;
        }
    }

    /** 解析 TITLE / CATEGORY / TAGS 三行格式 */
    private TopicDef parseTopic(String raw) {
        String title = "", category = "domain-tools", tags = "domain";
        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("TITLE:"))    title    = line.substring(6).trim();
            else if (line.startsWith("CATEGORY:")) category = line.substring(9).trim();
            else if (line.startsWith("TAGS:"))     tags     = line.substring(5).trim();
        }
        if (StringUtils.isBlank(title)) return null;
        // 保险起见做简单清洗
        category = category.replaceAll("[^a-z\\-]", "");
        if (category.isEmpty()) category = "domain-tools";
        return new TopicDef(title, category, tags);
    }

    /**
     * 站内工具链接表：工具名 -> 路径
     * 写入 prompt，让模型在合适时自然引用（不强制每篇都用）
     */
    private static final String TOOL_LINKS_HINT = """
            The following tools are available on Whose.Domains. \
            When it is natural and helpful to mention one of them in the article, \
            embed an HTML anchor like <a href="/tools/SLUG">Tool Name</a>. \
            Do NOT force links where they don't fit — only use them when genuinely relevant (0-3 links per article is ideal).

            Available tools:
            - WHOIS Lookup          -> /tools/whois-lookup
            - RDAP Lookup           -> /tools/rdap-lookup
            - DNS Analyzer          -> /tools/dns_analyzer
            - Domain Availability   -> /tools/domain-availability
            - Domain History        -> /tools/domain-history
            - Domain Score          -> /tools/domain-score
            - Domain Valuation      -> /tools/domain-valuation
            - Domain Analyzer       -> /tools/domain_analyzer
            - Bulk Domain Search    -> /tools/bulk-domain-search
            - WHOIS Compare         -> /tools/whois-compare
            - Related Domains       -> /tools/related-domains
            - Reverse IP Lookup     -> /tools/reverse-ip
            - SSL Checker           -> /tools/ssl_checker
            - My IP Address         -> /tools/my-ip-address
            - Ping Test             -> /tools/ping-test
            - Port Checker          -> /tools/port-checker
            - Email Checker         -> /tools/email-checker
            - HTML Formatter        -> /tools/html-formatter
            - JSON Formatter        -> /tools/json-formatter
            - XML Formatter         -> /tools/xml-formatter
            - Timezone Converter    -> /tools/timezone-converter
            """;

    private String buildSystemPrompt() {
        return """
                You are a professional technical writer for Whose.Domains, a domain lookup and network tools website.
                Write in a clear, informative, and slightly casual tone. Target audience: website owners, developers, and domain investors.
                Output clean HTML suitable for direct embedding in a blog page (use <h2>, <h3>, <p>, <ul>, <li>, <strong>, <em>, <code>, <a> tags).
                Do NOT include <html>, <head>, <body>, or <style> tags.
                Always structure your response with these exact section markers on their own lines:
                ===SUMMARY===
                (2-3 sentence plain-text summary, no HTML)
                ===CONTENT===
                (full article HTML, minimum 600 words)
                ===META_DESCRIPTION===
                (plain-text SEO meta description, 150-160 characters)
                """ + TOOL_LINKS_HINT;
    }

    private String buildUserPrompt(String title) {
        return "Write a comprehensive, SEO-optimized blog article titled: \"" + title + "\"\n"
             + "Include practical tips, real-world examples, and actionable advice. "
             + "Aim for 700-1000 words in the CONTENT section. "
             + "Where it feels natural, link to relevant tools on Whose.Domains using the anchor tags described in the system instructions — but do not force links if they don't fit the context.";
    }

    /** 从带分隔符文本中提取指定节 */
    private String extractSection(String raw, String sectionName) {
        String startTag = "===" + sectionName + "===";
        int start = raw.indexOf(startTag);
        if (start < 0) return "";
        start += startTag.length();
        int end = raw.indexOf("===", start);
        return end < 0 ? raw.substring(start).trim() : raw.substring(start, end).trim();
    }

    /** 标题转 URL slug */
    private String toSlug(String title) {
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }

    // ── inner record ──────────────────────────────────────────────────────

    private record TopicDef(String title, String category, String tags) {}
}
