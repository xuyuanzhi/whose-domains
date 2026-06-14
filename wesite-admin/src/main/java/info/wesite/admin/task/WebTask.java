package info.wesite.admin.task;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.service.DomainSiteService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.HttpUtils;

@Profile({ "prod" })
@Component
@EnableScheduling
public class WebTask {

	protected static Logger logger = LoggerFactory.getLogger(WebTask.class);

	@Autowired
	private DomainSiteService domainSiteService;

	/**
	 * 抓取子域名的官网首页
	 */
	@Scheduled(fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
	public void fetchHomePage() {
		LambdaQueryWrapper<DomainSite> query = Wrappers.<DomainSite>lambdaQuery()
				.eq(DomainSite::getStatus, Domain.STATUS_ACTIVE).isNull(DomainSite::getRefreshWebTime);

		long total = domainSiteService.count(query);

		if (total == 0) {
			return;
		}

		long size = 500;

		logger.info("有 {} 条子域名数据需要查询官网！", total);

		for (int index = 1;; index++) {
			Page<DomainSite> pageData = domainSiteService.page(Page.of(index, size), query);

			if (pageData.getRecords() == null || pageData.getRecords().isEmpty()) {
				break;
			}

			for (DomainSite site : pageData.getRecords()) {
				try {
					if (DomainUtils.isPortOpen(site.getName(), 443)) {
						String url = "https://" + site.getName();
						site.setHomePageUrl(url);
//						requestAndSaveResponse(url, site);
					} else if (DomainUtils.isPortOpen(site.getName(), 80)) {
						String url = "http://" + site.getName();
						site.setHomePageUrl(url);
//						requestAndSaveResponse(url, site);
					} else {
						logger.warn("域名 {} 没有开启web服务！", site.getName());
					}
					
					site.setRefreshWebTime(LocalDateTime.now());
					domainSiteService.updateById(site);
				} catch (Exception e) {
					logger.error("域名 {} 的web数据抓取失败!!", site.getName());

					site.setRefreshWebTime(LocalDateTime.now());
					domainSiteService.updateById(site);
				} finally {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
					}
				}
			}
		}

		logger.info("查询官网定时任务结束！");
	}

	private void requestAndSaveResponse(String url, DomainSite site) {
		ResponseEntity<String> resp = HttpUtils.get(url);
		if (resp == null) {
			return;
		}
		
		if (resp.getStatusCode().is2xxSuccessful()) {
			List<String> servers = resp.getHeaders().get("server");
			if (servers != null && servers.size() > 0) {
				site.setServerName(servers.get(0));
			}
			
			site.setHomePageUrl(url);
			site.setHomePageHtml(resp.getBody());
			
			Document doc = Jsoup.parse(site.getHomePageHtml());

			Element titleElement = doc.select("title").first();
			if (titleElement != null) {
				site.setHomePageTitle(titleElement.text());
			}

			Element metaElement = doc.select("meta[name=description]").first();
			if (metaElement != null) {
				site.setHomePageMetaDesc(metaElement.attr("content"));
			}

			site.setRefreshWebTime(LocalDateTime.now());
			domainSiteService.updateById(site);

			logger.info("域名 {} 的web数据抓取成功！", site.getName());
		} else {
			site.setRefreshWebTime(LocalDateTime.now());
			domainSiteService.updateById(site);

			logger.warn("域名 {} 的web数据抓取失败！", site.getName());
		}
	}
}
