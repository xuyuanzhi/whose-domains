package info.wesite.admin.task;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.DomainTld;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.utils.HttpUtils;

@Profile({ "dev", "mac", "prod" })
@Component
@EnableScheduling
public class RefreshTldTask {

	protected static Logger logger = LoggerFactory.getLogger(RefreshTldTask.class);

	@Autowired
	private DomainTldService domainTldService;

	@Scheduled(cron = "0 25 1 * * ?")
	public void start() {
		String body = HttpUtils.get("https://data.iana.org/TLD/tlds-alpha-by-domain.txt").getBody();
		body.lines().forEach(line -> {
			if (line.startsWith("#")) {
				return;
			}

			try {
				String name = line.toLowerCase();
				DomainTld tld = domainTldService.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getName, name));
				if (tld == null) {
					String sourceUrl = "https://www.iana.org/domains/root/db/" + name + ".html";
					ResponseEntity<String> resp = HttpUtils.get(sourceUrl);
					if (!resp.getStatusCode().is2xxSuccessful()) {
						logger.error("域名 {} 的详细信息抓取失败！", name);
						return;
					}

					String tldHtml = resp.getBody();
					if (StringUtils.isBlank(tldHtml)) {
						logger.error("域名 {} 的详细信息为空！", name);
						return;
					}

					tld = new DomainTld();
					tld.setName(name);
					tld.setDisplayName(name);
					tld.setSourceHtml(tldHtml);
					tld.setSourceUrl(sourceUrl);
					tld.setCreateBy("task");
					tld.setCreateTime(new Date());
					tld.setStatus(DomainTld.STATUS_ACTIVE);

					if (tldHtml.contains("<p>(Generic top-level domain)</p>")) {
						tld.setType("generic");
					} else if (tldHtml.contains("<p>(Restricted generic top-level domain)</p>")) {
						tld.setType("generic-restricted");
					} else if (tldHtml.contains("<p>(Country-code top-level domain)</p>") || tldHtml
							.contains("(Country-code top-level domain designated for two-letter country code")) {
						tld.setType("country-code");
						// orgEle = doc.select("h2.contains(ccTLD Manager)").first();
					} else if (tldHtml.contains("<p>(Sponsored top-level domain)</p>")) {
						tld.setType("sponsored");
					} else if (tldHtml.contains("<p>(Infrastructure top-level domain)</p>")) {
						tld.setType("infrastructure");
					} else {
						logger.warn("域名 {} 的类别无法识别！", name);
						return;
					}

					domainTldService.save(tld);

					logger.info("TLD [{}] 已添加！", name);

					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

//	@Scheduled(cron = "0 30 1 * * ?")
	public void updateTld() {
//		List<DomainTld> list = domainTldService.list();
		List<DomainTld> list = domainTldService.list(Wrappers.<DomainTld>lambdaQuery().isNull(DomainTld::getOrgName));
		if (list == null || list.isEmpty()) {
			return;
		}

		for (DomainTld item : list) {
			try {
				Document doc = Jsoup.parse(item.getSourceHtml());

				Element h1Element = doc.select("h1").first();
				if (h1Element != null) {
					if (h1Element.text().startsWith("Delegation Record for .")) {
						String displayName = h1Element.text().replace("Delegation Record for .", "").trim()
								.toLowerCase();
						if (!displayName.equals(item.getName())) {
							item.setDisplayName(displayName);
						}
					} else {
						logger.warn("域名 {} 的显示名称无法识别！", item.getName());
						continue;
					}
				} else {
					logger.warn("域名 {} 页面没有h1标签！", item.getName());
					continue;
				}

				// main节点
				Element main = doc.select("main").first();
				// main的子节点
				List<Node> nodeList = main.childNodes();

				// 删除回车节点
				for (Node node : nodeList) {
					if (node instanceof Element) {
						Element ele = (Element) node;
						if (ele.is("br")) {
							ele.remove();
						}
					}
				}

				// 重新获取子节点
				nodeList = main.childNodes();

				// <h2>Sponsoring Organisation</h2>
				Element orgEle = doc.select("h2:contains(Sponsoring Organisation)").first();
				if (item.getType().equals("country-code")) {
					orgEle = doc.select("h2:contains(ccTLD Manager)").first();
				}
				int orgIndex = nodeList.indexOf(orgEle);

				// <h2>Administrative Contact</h2>
				Element adminEle = doc.select("h2:contains(Administrative Contact)").first();
				int adminIndex = nodeList.indexOf(adminEle);

				// <h2>Technical Contact</h2>
				Element techEle = doc.select("h2:contains(Technical Contact)").first();
				int techIndex = nodeList.indexOf(techEle);

				// <h2>Name Servers</h2>
				Element nsEle = doc.select("h2:contains(Name Servers)").first();
				int nsIndex = nodeList.indexOf(nsEle);

				// <h2>Registry Information</h2>
//				Element riEle = doc.select("h2:contains(Registry Information)").first();
//				int riIndex = nodeList.indexOf(riEle);

				try {
					// Organisation
					item.setOrgName(((Element) nodeList.get(orgIndex + 2)).text().trim());
					item.setOrgCountry(((TextNode) nodeList.get(adminIndex - 2)).text().trim());
					item.setOrgAddr2(((TextNode) nodeList.get(adminIndex - 3)).text().trim());

					String addr = ((TextNode) nodeList.get(orgIndex + 3)).text().trim();
					if (addr.startsWith("c/o")) {
						item.setOrgName(item.getOrgName() + " " + addr);
						item.setOrgAddr(((TextNode) nodeList.get(orgIndex + 4)).text().trim());

						if (orgIndex + 4 < adminIndex - 4) {
							for (int i = orgIndex + 5; i <= adminIndex - 4; i++) {
								String s = ((TextNode) nodeList.get(i)).text().trim();
								item.setOrgAddr(item.getOrgAddr() + ", " + s);
							}
						}
					} else {
						item.setOrgAddr(addr);

						if (orgIndex + 3 < adminIndex - 4) {
							for (int i = orgIndex + 4; i <= adminIndex - 4; i++) {
								String s = ((TextNode) nodeList.get(i)).text().trim();
								item.setOrgAddr(item.getOrgAddr() + ", " + s);
							}
						}
					}
				} catch (Exception e) {
					logger.error("TLD【{}】信息解析出错", item.getName(), e);
				}

				try {
					// Administrative Contact
					item.setAdminName(((Element) nodeList.get(adminIndex + 2)).text().trim());
					item.setAdminOrg(((TextNode) nodeList.get(adminIndex + 3)).text().trim());

					int emailIndex = -1;

					for (int i = adminIndex + 3; i < techIndex; i++) {
						Node node = nodeList.get(i);
						if (node instanceof Element) {
							Element ele = ((Element) node);
							if (ele.tagName().equals("b")) {
								if (ele.text().equals("Email:")) {
									emailIndex = i;
									item.setAdminEmail(((TextNode) nodeList.get(i + 1)).text().trim());
								} else if (ele.text().equals("Voice:")) {
									item.setAdminPhone(((TextNode) nodeList.get(i + 1)).text().trim());
								} else if (ele.text().equals("Fax:")) {
									item.setAdminFax(((TextNode) nodeList.get(i + 1)).text().trim());
								}
							}
						}
					}

					if (emailIndex < 0) {
						logger.error("域名【{}】解析admin地址信息失败", item.getName());
						throw new Exception("域名【" + item.getName() + "】没有admin email");
					}

					item.setAdminAddr2(((TextNode) nodeList.get(emailIndex - 3)).text().trim());
					item.setAdminCountry(((TextNode) nodeList.get(emailIndex - 2)).text().trim());

					String addr = ((TextNode) nodeList.get(adminIndex + 4)).text().trim();
					if (addr.startsWith("c/o ")) {
						item.setAdminOrg(item.getAdminOrg() + " " + addr);
						item.setAdminAddr(((TextNode) nodeList.get(adminIndex + 5)).text().trim());

						if (adminIndex + 5 < emailIndex - 4) {
							for (int i = adminIndex + 6; i <= emailIndex - 4; i++) {
								String s = ((TextNode) nodeList.get(i)).text().trim();
								item.setAdminAddr(item.getAdminAddr() + ", " + s);
							}
						}
					} else {
						item.setAdminAddr(addr);

						if (adminIndex + 4 < emailIndex - 4) {
							for (int i = adminIndex + 5; i <= emailIndex - 4; i++) {
								String s = ((TextNode) nodeList.get(i)).text().trim();
								item.setAdminAddr(item.getAdminAddr() + ", " + s);
							}
						}
					}
				} catch (Exception e) {
					logger.error("TLD【{}】信息解析出错", item.getName(), e);
				}

				try {
					// Technical Contact
					item.setTechName(((Element) nodeList.get(techIndex + 2)).text().trim());
					item.setTechOrg(((TextNode) nodeList.get(techIndex + 3)).text().trim());

					int emailIndex = -1;
					for (int i = techIndex + 4; i < nsIndex; i++) {
						Node node = nodeList.get(i);
						if (node instanceof Element) {
							Element ele = ((Element) node);
							if (ele.tagName().equals("b")) {
								if (ele.text().equals("Email:")) {
									emailIndex = i;
									item.setTechEmail(((TextNode) nodeList.get(i + 1)).text().trim());
								} else if (ele.text().equals("Voice:")) {
									item.setTechPhone(((TextNode) nodeList.get(i + 1)).text().trim());
								} else if (ele.text().equals("Fax:")) {
									item.setTechFax(((TextNode) nodeList.get(i + 1)).text().trim());
								}
							}
						}
					}

					if (emailIndex < 0) {
						logger.error("域名【{}】解析tech地址信息失败", item.getName());
						throw new Exception("域名【" + item.getName() + "】没有tech email");
					}

					item.setTechAddr2(((TextNode) nodeList.get(emailIndex - 3)).text().trim());
					item.setTechCountry(((TextNode) nodeList.get(emailIndex - 2)).text().trim());

					String addr = ((TextNode) nodeList.get(techIndex + 4)).text().trim();
					if (addr.startsWith("c/o ")) {
						item.setTechOrg(item.getTechOrg() + " " + addr);
						item.setTechAddr(((TextNode) nodeList.get(techIndex + 5)).text().trim());

						if (techIndex + 5 < emailIndex - 4) {
							for (int i = techIndex + 6; i <= emailIndex - 4; i++) {
								String s = ((TextNode) nodeList.get(i)).text().trim();
								item.setTechAddr(item.getTechAddr() + ", " + s);
							}
						}
					} else {
						item.setTechAddr(addr);

						if (techIndex + 4 < emailIndex - 4) {
							for (int i = techIndex + 5; i <= emailIndex - 4; i++) {
								String s = ((TextNode) nodeList.get(i)).text().trim();
								item.setTechAddr(item.getTechAddr() + ", " + s);
							}
						}
					}
				} catch (Exception e) {
					logger.error("TLD【{}】信息解析出错", item.getName(), e);
				}

				// URL for registration services
				Element urlEle = doc.select("b:contains(URL for registration services:)").first();
				if (urlEle != null) {
					Element a = urlEle.nextElementSibling();
					if (a != null && a.hasText()) {
						item.setRegistrationUrl(a.text().trim());
					}
				}

				// WHOIS Server
				Element whoisEle = doc.select("b:contains(WHOIS Server:)").first();
				if (whoisEle != null) {
					List<Node> nodes = whoisEle.parent().childNodes();
					int index = nodes.indexOf(whoisEle);

					TextNode a = (TextNode) nodes.get(index + 1);
					if (a != null && !a.isBlank()) {
						item.setWhoisServer(a.text().trim());
					}
				}

				// RDAP Server
				Element rdapEle = doc.select("b:contains(RDAP Server:)").first();
				if (rdapEle != null) {
					List<Node> nodes = rdapEle.parent().childNodes();
					int index = nodes.indexOf(rdapEle);

					TextNode a = (TextNode) nodes.get(index + 1);
					if (a != null && !a.isBlank()) {
						item.setRdapServer(a.text().trim());
					}
				}

				Element dateEle = doc.select("i:contains(Record last updated)").first();
				// Record last updated 2024-12-11. Registration date 2015-08-13.
				String text = dateEle.text();
				String[] ss = text.replace("Record last updated", "").replace("Registration date", "").split("\\.");
				item.setLastUpdatedDate(ss[0].trim());
				item.setRegistrationDate(ss[1].trim());

				item.setUpdateBy("task");
				item.setUpdateTime(new Date());
				domainTldService.updateById(item);
				logger.info("TLD【{}】已更新！", item.getName());
			} catch (Exception e) {
				logger.error("TLD【{}】信息解析出错", item.getName(), e);
			}

//			Element metaElement = doc.select("meta[name=description]").first();
//			if (metaElement != null) {
//				site.setHomePageMetaDesc(metaElement.attr("content"));
//			}
		}
	}
}
