package info.wesite.web.task;

import java.io.File;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.redfin.sitemapgenerator.WebSitemapGenerator;
import com.redfin.sitemapgenerator.WebSitemapUrl;

import info.wesite.core.entity.BlogPost;
import info.wesite.core.service.BlogPostService;

@Profile({ "test", "prod" })
@Component
@EnableScheduling
public class SitemapTask {

	protected static Logger logger = LoggerFactory.getLogger(SitemapTask.class);

	private static final int FILE_LIMIT  = 50000;
	private static final int PAGE_SIZE   = 1000;
	private static final String PREFIX   = "https://whose.domains";

	/** 工具类页面 */
	private static final String[] TOOL_PAGES = {
		"/tools/whois-lookup",
		"/tools/rdap-lookup",
		"/tools/domain-availability",
		"/tools/bulk-domain-search",
		"/tools/whois-compare",
		"/tools/related-domains",
		"/tools/domain_analyzer",
		"/tools/domain-score",
		"/tools/dns_analyzer",
		"/tools/ssl_checker",
		"/tools/domain-history",
		"/tools/reverse-ip",
		"/tools/my-ip-address",
		"/tools/json-formatter",
		"/tools/xml-formatter",
		"/tools/html-formatter",
		"/tools/timezone-converter",
		"/tools/competitor_analysis",
		"/tools/email-checker",
		"/tools/domain-valuation",
		"/tools/port-checker",
		"/tools/ping-test",
	};
	
	/** InfoController 中的所有页面（@RequestMapping("/info")） */
	private static final String[] INFO_PAGES = {
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
		"/info/what-is-ssl-tls-certificate",
		"/info/types-of-ssl-certificates",
		"/info/how-to-check-ssl-certificate",
		"/info/ssl-certificate-expiration",
		"/info/how-dns-works",
		"/info/common-dns-record-types",
		"/info/dns-propagation-explained",
		"/info/choosing-dns-providers",
		"/info/domain-hijacking-prevention",
		"/info/email-authentication-spf-dkim-dmarc",
		"/info/phishing-and-domain-spoofing",
		"/info/typosquatting-protection",
		"/info/how-domain-valuation-works",
		"/info/domain-aftermarket-guide",
		"/info/understanding-domain-auctions",
		"/info/expired-domain-investing",
		"/info/how-to-transfer-a-domain",
		"/info/domain-renewal-best-practices",
		"/info/managing-multiple-domains",
		"/info/domain-forwarding-and-redirects",
		"/info/domain-age-checker-guide",
		"/info/reverse-whois-lookup",
		"/info/ip-to-domain-lookup",
		"/info/bulk-domain-lookup",
	};


	

	/** API 文档页面 */
	private static final String[] API_PAGES = {
		"/api-docs"
	};

	@Autowired
	private BlogPostService blogPostService;

	@Value("${sitemap.root}")
	private String sitemapRoot;

	@Scheduled(cron = "0 25 1 * * ?")
	public void createFile() {
		logger.info("定时任务生成sitemap开始（全量）");

		File folder = new File(sitemapRoot);
		if (!folder.exists()) {
			folder.mkdirs();
		}

		// 删除所有旧 sitemap 文件，全量重新生成
		deleteFilesWithPrefix(folder, "sitemap_");

		try {
			List<File> files = generateFullSitemap(folder);
			logger.info("定时任务生成sitemap结束，共{}个文件", files.size());
		} catch (Exception e) {
			logger.error("生成sitemap失败", e);
		}
	}

	// ── 工具方法 ───────────────────────────────────────────────

	/**
	 * 全量生成单个 sitemap 文件，包含所有页面：
	 *   - 工具类页面
	 *   - TLD 列表页（/top-level-domains，不含各 TLD 详情页）
	 *   - 即将过期域名列表页
	 *   - Help Center 页面及文档条目
	 *   - API 页面
	 *   - Blog 首页及各已发布文章
	 *   - 所有域名页面
	 */
	private List<File> generateFullSitemap(File folder) throws MalformedURLException {
		WebSitemapGenerator gen = WebSitemapGenerator.builder(PREFIX, folder)
				.fileNamePrefix("sitemap_all").build();
		
		gen.addUrl(new WebSitemapUrl.Options(PREFIX)
				.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.WEEKLY)
				.priority(1.0)
				.build());
		
		// 1. 工具类页面
		gen.addUrl(new WebSitemapUrl.Options(PREFIX + "/tools")
				.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.WEEKLY)
				.priority(0.9)
				.build());
		
		for (String page : TOOL_PAGES) {
			gen.addUrl(new WebSitemapUrl.Options(PREFIX + page)
					.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.WEEKLY)
					.priority(0.9)
					.build());
		}
		
		// 2. Help Center 首页 + Info 文章页面
		gen.addUrl(new WebSitemapUrl.Options(PREFIX + "/help-center")
				.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.WEEKLY)
				.priority(0.7)
				.build());

		for (String page : INFO_PAGES) {
			gen.addUrl(new WebSitemapUrl.Options(PREFIX + page)
					.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.MONTHLY)
					.priority(0.7)
					.build());
		}

		// 3. TLD 列表页
		gen.addUrl(new WebSitemapUrl.Options(PREFIX + "/top-level-domains")
				.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.WEEKLY)
				.priority(0.7)
				.build());

		// 4. 即将过期域名列表页
		gen.addUrl(new WebSitemapUrl.Options(PREFIX + "/expiring-domains")
				.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.DAILY)
				.priority(0.8)
				.build());
		
		// 5. API 页面
		gen.addUrl(new WebSitemapUrl.Options(PREFIX + "/api-docs")
				.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.MONTHLY)
				.priority(0.6)
				.build());

		// 6. Blog 首页 + 已发布文章
		gen.addUrl(new WebSitemapUrl.Options(PREFIX + "/blog")
				.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.DAILY)
				.priority(0.8)
				.build());

		List<BlogPost> posts = blogPostService.list(
				Wrappers.<BlogPost>lambdaQuery()
						.eq(BlogPost::getStatus, BlogPost.POST_STATUS_PUBLISHED)
						.select(BlogPost::getSlug, BlogPost::getUpdateTime, BlogPost::getPublishDate));
		for (BlogPost post : posts) {
			if (post.getSlug() == null || post.getSlug().isBlank()) continue;
			Date lastMod = post.getUpdateTime() != null ? post.getUpdateTime()
					: (post.getPublishDate() != null ? post.getPublishDate() : new Date());
			gen.addUrl(new WebSitemapUrl.Options(PREFIX + "/blog/" + post.getSlug())
					.lastMod(lastMod)
					.changeFreq(com.redfin.sitemapgenerator.ChangeFreq.WEEKLY)
					.priority(0.7)
					.build());
		}

		return gen.write();
	}

	/** 删除 folder 下以 prefix 开头的文件，避免残留旧分页文件 */
	private void deleteFilesWithPrefix(File folder, String prefix) {
		File[] old = folder.listFiles(f -> f.getName().startsWith(prefix));
		if (old != null) {
			for (File f : old) {
				if (f.delete()) logger.debug("已删除旧sitemap文件: {}", f.getName());
			}
		}
	}
}
