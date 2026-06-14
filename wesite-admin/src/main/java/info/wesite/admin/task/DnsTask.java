package info.wesite.admin.task;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainSiteService;

@Profile({ "prod" })
@Component
@EnableScheduling
public class DnsTask {

	protected static Logger logger = LoggerFactory.getLogger(DnsTask.class);

	@Autowired
	private DomainService domainService;
	@Autowired
	private DomainDnsService domainDnsService;
	@Autowired
	private DomainSiteService domainSiteService;

	/**
	 * 更新域名的DNS记录
	 */
	@Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
	public void refreshDomainDns() {
		LambdaQueryWrapper<Domain> query = Wrappers.<Domain>lambdaQuery().eq(Domain::getStatus, Domain.STATUS_ACTIVE)
				.isNull(Domain::getRefreshDnsTime);

		long total = domainService.count(query);

		if (total == 0) {
			return;
		}

		long size = 500;

		logger.info("有 {} 条域名数据需要刷新DNS！", total);

		for (int index = 1;; index++) {
			Page<Domain> pageData = domainService.page(Page.of(index, size), query);

			if (pageData.getRecords() == null || pageData.getRecords().isEmpty()) {
				break;
			}

			for (Domain d : pageData.getRecords()) {
				try {
					domainDnsService.refreshByDomain(d);

					logger.info("域名 {} 刷新DNS数据成功！", d.getName());
				} catch (Exception e) {
					logger.error("域名 {} 刷新DNS数据失败！！！", d.getName(), e);
				} finally {
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
					}
				}
			}
		}

		logger.info("刷新主域名DNS定时任务结束！");
	}

	@Scheduled(fixedDelay = 40, timeUnit = TimeUnit.SECONDS)
	public void refreshSiteDns() {
		LambdaQueryWrapper<DomainSite> query = Wrappers.<DomainSite>lambdaQuery()
				.eq(DomainSite::getStatus, DomainSite.STATUS_ACTIVE).isNull(DomainSite::getRefreshDnsTime);

		long total = domainSiteService.count(query);

		if (total == 0) {
			return;
		}

		long size = 500;

		logger.info("有 {} 条子域名数据需要查询DNS！", total);

		for (int index = 1;; index++) {
			Page<DomainSite> pageData = domainSiteService.page(Page.of(index, size), query);

			if (pageData.getRecords() == null || pageData.getRecords().isEmpty()) {
				break;
			}

			for (DomainSite d : pageData.getRecords()) {
				try {
					domainDnsService.refreshByDomainSite(d);

					logger.info("子域名 {} 刷新DNS数据成功！", d.getName());
				} catch (Exception e) {
					logger.error("子域名 {} 刷新DNS数据失败！！！", d.getName(), e);
				} finally {
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
					}
				}
			}
		}

		logger.info("刷新子域名DNS定时任务结束！");
	}
}
