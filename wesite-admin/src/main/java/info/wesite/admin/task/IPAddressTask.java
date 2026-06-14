package info.wesite.admin.task;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Type;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.DomainDns;
import info.wesite.core.handler.Geoip2Handler;
import info.wesite.core.service.DomainDnsService;

@Profile({ "prod", "dev" })
@Component
@EnableScheduling
public class IPAddressTask {

	@Autowired
	private DomainDnsService domainDnsService;
	@Autowired
	private Geoip2Handler geoip2Handler;

	@Scheduled(fixedDelay = 300, timeUnit = TimeUnit.SECONDS)
	public void start() {
		LambdaQueryWrapper<DomainDns> query = Wrappers.<DomainDns>lambdaQuery()
				.in(DomainDns::getType, Type.string(Type.A), Type.string(Type.AAAA))
				.and(q -> q.isNull(DomainDns::getCityJson).or().isNull(DomainDns::getAsnJson))
				.orderByDesc(DomainDns::getId);

		for (int i = 1;; i++) {
			Page<DomainDns> pageData = domainDnsService.page(Page.of(i, 1000), query);
			if (pageData.getRecords() == null || pageData.getRecords().isEmpty()) {
				break;
			}

			for (DomainDns item : pageData.getRecords()) {
				if (item.getCityJson() == null) {
					String cityJson = geoip2Handler.getCityJson(item.getValue());
					if (cityJson == null) {
						item.setCityJson("{}");
					} else {
						item.setCityJson(cityJson);
					}
				}

				if (item.getAsnJson() == null) {
					String asnJson = geoip2Handler.getAsnJson(item.getValue());
					if (asnJson == null) {
						item.setAsnJson("{}");
					} else {
						item.setAsnJson(asnJson);
					}
				}

				domainDnsService.updateById(item);
			}
		}
	}
}
