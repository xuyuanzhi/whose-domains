package info.wesite.admin.task;

import java.util.Date;
import java.util.List;
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

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainSiteService;
import info.wesite.core.utils.DomainUtils;

@Profile({ "prod" })
@Component
@EnableScheduling
public class RefreshDomainTask {

	protected static Logger logger = LoggerFactory.getLogger(RefreshDomainTask.class);

	@Autowired
	private DomainService domainService;
	@Autowired
	private DomainSiteService domainSiteService;
	@Autowired
	private DomainDnsService domainDnsService;

	/**
	 * 处理request refresh的域名
	 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public void refreshDoamins() {
		LambdaQueryWrapper<Domain> query = Wrappers.<Domain>lambdaQuery().isNotNull(Domain::getRequestRefreshTime);

		long total = domainService.count(query);

		if (total == 0) {
			return;
		}
		
		logger.info("查询到 {} 条客户提交的刷新请求", total);

		List<Domain> list = domainService.list(query.orderByAsc(Domain::getRequestRefreshTime).last("limit 100"));

		for (Domain d : list) {
			try {
				boolean filled = DomainUtils.fillMainDomainInfo(d);
				if (filled) {
					d.setUpdateBy("task");
					d.setUpdateTime(new Date());
					d.setRequestRefreshIp(null);
					d.setRequestRefreshTime(null);
					domainService.updateById(d);
				}
				
				domainDnsService.refreshByDomain(d);
				
				List<DomainSite> siteList = domainSiteService.list(Wrappers.<DomainSite>lambdaQuery().eq(DomainSite::getDomainId, d.getId()));
				
				if (siteList != null && siteList.size() > 0) {
					for (DomainSite site : siteList) {
						domainDnsService.refreshByDomainSite(site);
						
						domainSiteService.refreshWeb(site);
					}
				}
				
				logger.info("域名 {} 刷新完成", d.getName());
			} catch (Exception e) {
				logger.error("域名 {} 刷新失败", d.getName(), e);
			}
		}

		logger.info("{} 条客户提交的刷新请求处理完成", total);
	}

}
