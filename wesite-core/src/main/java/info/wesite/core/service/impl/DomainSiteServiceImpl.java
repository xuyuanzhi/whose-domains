package info.wesite.core.service.impl;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.DomainSite;
import info.wesite.core.mapper.DomainSiteMapper;
import info.wesite.core.service.DomainSiteService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.HttpUtils;
import reactor.core.publisher.Mono;

@Service
public class DomainSiteServiceImpl extends ServiceImpl<DomainSiteMapper, DomainSite> implements DomainSiteService {

	@Override
	public boolean refreshWebByName(String name) {
		DomainSite site = getBaseMapper().selectOne(Wrappers.<DomainSite>lambdaQuery().eq(DomainSite::getName, name));
		if (site == null) {
			return false;
		}

		return refreshWeb(site);
	}
	
	@Override
	public boolean refreshWeb(DomainSite site) {
		if (DomainUtils.isPortOpen(site.getName(), 443)) {
			String url = "https://" + site.getName();

			HttpUtils.getWebClient().get().uri(url).exchangeToMono(resp -> {
				if (resp.statusCode().is2xxSuccessful()) {
					Mono<String> mono = resp.bodyToMono(String.class);

					site.setHomePageUrl(url);
					site.setHomePageHtml(mono.block());
					List<String> servers = resp.headers().header("server");
					if (servers != null && servers.size() > 0) {
						site.setServerName(servers.get(0));
					}
				}

				return null;
			});
		} else if (DomainUtils.isPortOpen(site.getName(), 80)) {
			String url = "http://" + site.getName();

			HttpUtils.getWebClient().get().uri(url).exchangeToMono(resp -> {
				if (resp.statusCode().is2xxSuccessful()) {
					Mono<String> mono = resp.bodyToMono(String.class);

					site.setHomePageUrl(url);
					site.setHomePageHtml(mono.block());
					List<String> servers = resp.headers().header("server");
					if (servers != null && servers.size() > 0) {
						site.setServerName(servers.get(0));
					}
				}

				return null;
			});
		}

		if (StringUtils.isNotBlank(site.getHomePageHtml())) {
			Document doc = Jsoup.parse(site.getHomePageHtml());

			Element titleElement = doc.select("title").first();
			if (titleElement != null) {
				site.setHomePageTitle(titleElement.text());
			}

			Element metaElement = doc.select("meta[name=description]").first();
			if (metaElement != null) {
				site.setHomePageMetaDesc(metaElement.attr("content"));
			}
		}

		site.setRefreshWebTime(LocalDateTime.now());
		return getBaseMapper().updateById(site) > 0;
	}
}
