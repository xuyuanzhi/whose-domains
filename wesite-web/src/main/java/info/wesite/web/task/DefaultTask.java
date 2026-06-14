package info.wesite.web.task;

import java.util.Date;

import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.DomainTldExt;
import info.wesite.core.service.DomainTldExtService;
import info.wesite.core.utils.DomainUtils;

@Profile({ "prod", "mac" })
@Component
@EnableScheduling
public class DefaultTask {

	protected static Logger logger = LoggerFactory.getLogger(DefaultTask.class);

//    @Autowired
//    private RestTemplate restTemplate;

//	@Autowired
//	private DomainUtils domainUtils;

	@Autowired
	private DomainTldExtService domainTldExtService;


	/**
	 * 每小时执行，如果过去的1小时内数据有改动则刷新
	 */
	@Scheduled(cron = "0 0 * * * ?")
	public void refreshDomainExt() {
		Date _1H = DateUtils.addHours(new Date(), -1);
		long total = domainTldExtService
				.count(Wrappers.<DomainTldExt>lambdaQuery().eq(DomainTldExt::getStatus, DomainTldExt.STATUS_ACTIVE)
						.and(q -> q.ge(DomainTldExt::getCreateTime, _1H).or().ge(DomainTldExt::getUpdateTime, _1H)));

		if (total > 0) {
			logger.info("过去1小时内有 {} 条域名后缀数据变动，开始刷新缓存！", total);

			int size = DomainUtils.refreshExtList();

			logger.info("刷新缓存完成，共加载 {} 条域名后缀数据！", size);
		}
	}

}
