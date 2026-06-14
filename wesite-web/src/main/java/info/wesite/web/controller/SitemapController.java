package info.wesite.web.controller;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.BlogPost;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.service.BlogPostService;
import info.wesite.core.service.DomainTldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Sitemap")
@Controller
public class SitemapController {

    private static final String BASE_URL = "https://whose.domains";
    private static final String SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";

    /** 单个 sitemap 中 url 上限（Google 规定 5 万，留一些 buffer 用 4 万）*/
    private static final int TLD_PAGE_SIZE = 40000;

    private static final String[] STATIC_PAGES = {
        "/",
        "/about-us",
        "/contact-us",
        "/privacy-policy",
        "/terms-of-service",
        "/help-center",
        "/expiring-domains",
        "/api-docs",
        "/top-level-domains",
        // Info
        "/info/what-is-a-domain-name",
        "/info/domain-structure-and-tlds",
        "/info/how-domain-registration-works",
        "/info/understanding-domain-ownership",
        "/info/what-is-whois",
        "/info/how-to-perform-a-whois-lookup",
        "/info/reading-whois-results",
        "/info/domain-privacy-protection",
        "/info/why-domain-history-matters",
        "/info/tracking-ownership-changes",
        "/info/dns-record-history",
        "/info/expiration-and-renewal-history",
        "/info/domain-security-basics",
        "/info/domain-lock-and-transfer-protection",
        "/info/understanding-dnssec",
        "/info/preventing-domain-fraud",
        // SSL/TLS Certificates
        "/info/what-is-ssl-tls-certificate",
        "/info/types-of-ssl-certificates",
        "/info/how-to-check-ssl-certificate",
        "/info/ssl-certificate-expiration",
        // DNS Deep Dive
        "/info/how-dns-works",
        "/info/common-dns-record-types",
        "/info/dns-propagation-explained",
        "/info/choosing-dns-providers",
        // Advanced Security
        "/info/domain-hijacking-prevention",
        "/info/email-authentication-spf-dkim-dmarc",
        "/info/phishing-and-domain-spoofing",
        "/info/typosquatting-protection",
        // Domain Valuation & Trading
        "/info/how-domain-valuation-works",
        "/info/domain-aftermarket-guide",
        "/info/understanding-domain-auctions",
        "/info/expired-domain-investing",
        // Domain Management
        "/info/how-to-transfer-a-domain",
        "/info/domain-renewal-best-practices",
        "/info/managing-multiple-domains",
        "/info/domain-forwarding-and-redirects",
        // Domain Research & Tools
        "/info/domain-age-checker-guide",
        "/info/reverse-whois-lookup",
        "/info/ip-to-domain-lookup",
        "/info/bulk-domain-lookup"
    };

    /** 工具页（独立提取，便于赋更高的 priority）*/
    private static final String[] TOOL_PAGES = {
        "/tools/whois-lookup",
        "/tools/rdap-lookup",
        "/tools/domain-availability",
        "/tools/bulk-domain-search",
        "/tools/whois-compare",
        "/tools/related-domains",
        "/tools/domain-analyzer",
        "/tools/domain-score",
        "/tools/dns-analyzer",
        "/tools/ssl-checker",
        "/tools/domain-history",
        "/tools/reverse-ip",
        "/tools/my-ip-address",
        "/tools/json-formatter",
        "/tools/timezone-converter",
        "/tools/competitor-analysis",
        // New tools (added 2026-04)
        "/tools/email-checker",
        "/tools/domain-valuation",
        "/tools/port-checker",
        "/tools/ping-test"
    };

    @Autowired
    private DomainTldService domainTldService;

    @Autowired
    private BlogPostService blogPostService;

    /**
     * Sitemap 入口（标准路径），返回 sitemap index，引用静态/工具/TLD 多个子 sitemap
     * 规范：https://www.sitemaps.org/protocol.html#index
     */
    @Operation(summary = "Sitemap Index")
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemapIndex() {
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

        Document document = DocumentHelper.createDocument();
        document.setXMLEncoding("UTF-8");
        Namespace ns = Namespace.get(SITEMAP_NS);
        Element root = document.addElement(QName.get("sitemapindex", ns));

        addSitemapEntry(root, ns, BASE_URL + "/sitemap-static.xml", today);
        addSitemapEntry(root, ns, BASE_URL + "/sitemap-tools.xml", today);
        addSitemapEntry(root, ns, BASE_URL + "/sitemap-blog.xml", today);

        long total = domainTldService.count(Wrappers.<DomainTld>lambdaQuery()
                .eq(DomainTld::getStatus, DomainTld.STATUS_ACTIVE));
        int pages = (int) Math.max(1, (total + TLD_PAGE_SIZE - 1) / TLD_PAGE_SIZE);
        for (int i = 1; i <= pages; i++) {
            addSitemapEntry(root, ns, BASE_URL + "/sitemap-tld-" + i + ".xml", today);
        }

        return document.asXML();
    }

    /** 静态页面 sitemap */
    @Operation(summary = "Sitemap - static pages")
    @GetMapping(value = "/sitemap-static.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemapStatic() {
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Document document = DocumentHelper.createDocument();
        document.setXMLEncoding("UTF-8");
        Namespace ns = Namespace.get(SITEMAP_NS);
        Element urlset = document.addElement(QName.get("urlset", ns));

        for (String page : STATIC_PAGES) {
            String priority;
            String changefreq;
            if ("/".equals(page)) {
                priority = "1.0"; changefreq = "daily";
            } else if (page.startsWith("/info/") || page.startsWith("/help") || page.startsWith("/about")
                    || page.startsWith("/privacy") || page.startsWith("/terms") || page.startsWith("/api-")) {
                priority = "0.5"; changefreq = "monthly";
            } else {
                priority = "0.7"; changefreq = "weekly";
            }
            addUrl(urlset, ns, BASE_URL + page, today, changefreq, priority);
        }
        return document.asXML();
    }

    /** 工具页 sitemap（高 priority + weekly）*/
    @Operation(summary = "Sitemap - tool pages")
    @GetMapping(value = "/sitemap-tools.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemapTools() {
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Document document = DocumentHelper.createDocument();
        document.setXMLEncoding("UTF-8");
        Namespace ns = Namespace.get(SITEMAP_NS);
        Element urlset = document.addElement(QName.get("urlset", ns));

        for (String page : TOOL_PAGES) {
            addUrl(urlset, ns, BASE_URL + page, today, "weekly", "0.9");
        }
        return document.asXML();
    }

    /** TLD 分页 sitemap（每页 4 万）*/
    @Operation(summary = "Sitemap - TLD pages (paged)")
    @GetMapping(value = "/sitemap-tld-{page}.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemapTld(@PathVariable("page") int page) {
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Document document = DocumentHelper.createDocument();
        document.setXMLEncoding("UTF-8");
        Namespace ns = Namespace.get(SITEMAP_NS);
        Element urlset = document.addElement(QName.get("urlset", ns));

        if (page < 1) page = 1;

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<DomainTld> p =
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(page, TLD_PAGE_SIZE);
        p.setSearchCount(false);
        List<DomainTld> tlds = domainTldService.page(p, Wrappers.<DomainTld>lambdaQuery()
                .eq(DomainTld::getStatus, DomainTld.STATUS_ACTIVE)
                .orderByAsc(DomainTld::getName)).getRecords();

        for (DomainTld tld : tlds) {
            addUrl(urlset, ns, BASE_URL + "/domain/" + tld.getName(), today, "weekly", "0.7");
        }
        return document.asXML();
    }

    /** Blog 文章 sitemap */
    @Operation(summary = "Sitemap - blog posts")
    @GetMapping(value = "/sitemap-blog.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemapBlog() {
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Document document = DocumentHelper.createDocument();
        document.setXMLEncoding("UTF-8");
        Namespace ns = Namespace.get(SITEMAP_NS);
        Element urlset = document.addElement(QName.get("urlset", ns));

        // Blog index
        addUrl(urlset, ns, BASE_URL + "/blog", today, "daily", "0.8");

        // Published posts
        List<BlogPost> posts = blogPostService.list(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<BlogPost>lambdaQuery()
                        .eq(BlogPost::getStatus, BlogPost.POST_STATUS_PUBLISHED)
                        .select(BlogPost::getSlug, BlogPost::getUpdateTime));
        for (BlogPost post : posts) {
            String lastmod = post.getUpdateTime() != null
                    ? new SimpleDateFormat("yyyy-MM-dd").format(post.getUpdateTime()) : today;
            addUrl(urlset, ns, BASE_URL + "/blog/" + post.getSlug(), lastmod, "weekly", "0.7");
        }
        return document.asXML();
    }

    /** 旧路径兼容（GoogleBot 已收录此 URL，保留 301 重定向到新入口） */
    @Operation(summary = "Sitemap - legacy path (redirect)")
    @GetMapping(value = "/sitemap-20260413.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemapLegacy() {
        // 直接返回新的 index 内容，避免 301 影响已收录评分
        return sitemapIndex();
    }

    private void addSitemapEntry(Element root, Namespace ns, String loc, String lastmod) {
        Element sm = root.addElement(QName.get("sitemap", ns));
        sm.addElement(QName.get("loc", ns)).setText(loc);
        sm.addElement(QName.get("lastmod", ns)).setText(lastmod);
    }

    private void addUrl(Element urlset, Namespace ns, String loc, String lastmod, String changefreq, String priority) {
        Element url = urlset.addElement(QName.get("url", ns));
        url.addElement(QName.get("loc", ns)).setText(loc);
        url.addElement(QName.get("lastmod", ns)).setText(lastmod);
        url.addElement(QName.get("changefreq", ns)).setText(changefreq);
        url.addElement(QName.get("priority", ns)).setText(priority);
    }
}