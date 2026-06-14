package info.wesite.web.controller;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.ContactInfo;
import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainDns;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.entity.DomainTldExt;
import info.wesite.core.entity.TldContent;
import info.wesite.core.service.ContactInfoService;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainSiteService;
import info.wesite.core.service.DomainTldExtService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.service.TldContentService;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.HttpUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.utils.RdapUtils;
import info.wesite.core.view.ContactForm;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.config.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@Tag(name = "主类")
@Controller
public class MainController {

	protected static Logger logger = LoggerFactory.getLogger(MainController.class);

	@Autowired
	private DomainService domainService;
	@Autowired
	private DomainSiteService domainSiteService;
	@Autowired
	private DomainTldService domainTldService;
	@Autowired
	private DomainTldExtService domainTldExtService;
	@Autowired
	private TldContentService tldContentService;
	@Autowired
	private DomainDnsService domainDnsService;
//	@Autowired
//	private IpAddressService ipAddressService;
	@Autowired
	private ContactInfoService contactInfoService;
	@Autowired
	private info.wesite.core.service.DomainSnapshotService domainSnapshotService;

	private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";

	@Operation(summary = "首页")
	@GetMapping("/")
	public String index(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Free WHOIS Lookup & Domain Search – RDAP, DNS, History | Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC,
				"Free WHOIS & RDAP lookup for any domain. Get registrar, DNS records, SSL status, WHOIS history, and domain health score — all in one place. No signup required.");
		// GEO: FAQ schema for AI understanding
		model.addAttribute("_pageSchema", buildFaqSchema(new String[][] {
			{"What is WHOIS lookup?", "WHOIS lookup is a query protocol that retrieves registration information about a domain name, including the registrar, registrant, creation date, expiration date, nameservers, and contact details. Whose.Domains provides free, instant WHOIS lookups for any domain."},
			{"What is RDAP?", "RDAP (Registration Data Access Protocol) is the modern replacement for WHOIS. It provides structured, machine-readable domain registration data via RESTful HTTP APIs, with better internationalization and access control support."},
			{"How do I check if a domain is available?", "Use the Domain Availability Checker at whose.domains/tools/domain-availability. Enter your desired domain name and we'll instantly check its registration status across multiple TLD extensions like .com, .net, .org, .io, and more."},
			{"What is a Domain Health Score?", "Domain Health Score is a comprehensive grading system (A+ to F) that evaluates a domain's SSL configuration, DNS setup, email authentication (SPF/DKIM/DMARC), security headers, and domain age to assess overall domain quality."},
			{"Is the Whose.Domains API free?", "Yes, Whose.Domains offers a free RESTful API for developers to access WHOIS data, DNS records, domain history, SSL certificate info, and domain scoring programmatically. Visit our API Documentation page for details."}
		}));
		return "index";
	}

	@Operation(summary = "列表页面")
	@GetMapping("/list")
	public String list(Model model) {
		return "list";
	}

	@Operation(summary = "详情页面")
	@GetMapping("/domain/{domainName}")
	public Object detail(@PathVariable("domainName") String domainName, HttpServletRequest request) {
		logger.info("域名【{}】详情方法开始======>>>", domainName);

		// 统一小写
		domainName = domainName.toLowerCase();

		// TLD
		if (!domainName.contains(".")) {
			DomainTld tld = domainTldService
					.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getName, domainName));
			if (tld == null) {
				throw new ResourceNotFoundException("域名", domainName);
			}

			List<DomainTldExt> sldList = domainTldExtService
					.list(Wrappers.<DomainTldExt>lambdaQuery().eq(DomainTldExt::getStatus, DomainTldExt.STATUS_ACTIVE)
							.eq(DomainTldExt::getTldName, tld.getDotName()).orderByAsc(DomainTldExt::getName));

			List<Domain> list = domainService.list(Wrappers.<Domain>lambdaQuery()
					.eq(Domain::getStatus, Domain.STATUS_ACTIVE).eq(Domain::getTldName, tld.getDotName())
					.orderByDesc(Domain::getUpdateTime, Domain::getCreateTime).last("limit 100"));

			ModelAndView mv = new ModelAndView();
			mv.addObject("tld", tld);
			mv.addObject("sldList", sldList);
			if (list != null && !list.isEmpty()) {
				mv.addObject("domainList", list);
			}

			// TLD rich content (SEO)
			TldContent tldContent = tldContentService.getOne(
					Wrappers.<TldContent>lambdaQuery().eq(TldContent::getTld, domainName));
			if (tldContent != null) {
				mv.addObject("tldContent", tldContent);
				if (tldContent.getFamousSitesJson() != null && !tldContent.getFamousSitesJson().isEmpty()) {
					try {
						List<?> famousSitesList = com.alibaba.fastjson2.JSON.parseArray(tldContent.getFamousSitesJson());
						mv.addObject("famousSitesList", famousSitesList);
					} catch (Exception ignored) {}
				}
			}

			mv.addObject(Constants.PAGE_TITLE, tld.getDisplayName() + " - Whose.Domains");
			mv.addObject(Constants.PAGE_META_DESC, tld.getMetaDescription());

			// TLD keyword coverage: {ext} domain meaning, price, history, who owns
			String dot = tld.getDisplayName(); // e.g. ".com"
			String ext = domainName;           // e.g. "com"
			String keywords = dot + " domain meaning, " + dot + " domain price, " + dot + " domain history, "
					+ "what is " + dot + " domain, " + ext + " extension meaning, "
					+ "register " + dot + " domain, " + dot + " domain cost, "
					+ "who owns " + dot + " domain, " + dot + " domain registry";
			mv.addObject("_page_keywords", keywords);

			mv.setViewName("domain_tld");
			return mv;
		}

		// 保留的二级域名
		if (DomainUtils.isTldExt("." + domainName)) {
			DomainTldExt ext = domainTldExtService
					.getOne(Wrappers.<DomainTldExt>lambdaQuery().eq(DomainTldExt::getDotName, "." + domainName));

			DomainTld tld = domainTldService
					.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getDotName, ext.getTldName()));

			if (tld == null) {
				throw new ResourceNotFoundException("域名", domainName);
			}

			List<Domain> list = domainService.list(Wrappers.<Domain>lambdaQuery()
					.eq(Domain::getStatus, Domain.STATUS_ACTIVE).eq(Domain::getSldName, "." + domainName)
					.orderByDesc(Domain::getUpdateTime, Domain::getCreateTime).last("limit 100"));

			ModelAndView mv = new ModelAndView();
			mv.addObject("item", ext);
			mv.addObject("tld", tld);
			if (list != null && !list.isEmpty()) {
				mv.addObject("domainList", list);
			}
			mv.addObject(Constants.PAGE_TITLE, domainName + " - Whose.Domains");
			mv.addObject(Constants.PAGE_META_DESC, "");
			mv.setViewName("domain_tldext");
			return mv;
		}

		if (!domainName.matches(DOMAIN_REGEX)) {
			throw new ResourceNotFoundException("域名", domainName);
		}

		// 获取主域名
		String mainDomain = DomainUtils.getMainDomain(domainName);

		logger.info("域名【{}】的主域名是 {}", domainName, mainDomain);

		if (mainDomain.equals(domainName)) {// 使用主域名查�?
			Domain domain = getDomainByName(domainName, request);

			logger.info("域名【{}】查询对象结果", domainName);

			if (domain != null && (domain.getStatus() == Domain.STATUS_ACTIVE || domain.getRdapServer() != null)) {
				if (domain.getRefreshDnsTime() == null) {
					try {
						domainDnsService.refreshByDomain(domain);
					} catch (Exception e) {
						logger.error("Failed to refresh DNS for domain: {}", domainName, e);
					}
				}

				ModelAndView mv = new ModelAndView();
				mv.addObject("domain", domain);
				mv.addObject(Constants.PAGE_TITLE, domainName + " - Whose.Domains");
				mv.addObject(Constants.PAGE_META_DESC, domain.getMetaDescription());

				List<DomainSite> siteList = domainSiteService
						.list(Wrappers.<DomainSite>lambdaQuery().eq(DomainSite::getDomainId, domain.getId())
								.eq(DomainSite::getStatus, DomainSite.STATUS_ACTIVE).orderByAsc(DomainSite::getName));

				logger.info("域名【{}】子域名查询完成", domainName);

				if (siteList != null && !siteList.isEmpty()) {
					mv.addObject("siteList", siteList);
				} else {
					// 如果此域名没有子域名，尝试www
					DomainSite site = getDomainSiteByName("www." + domainName, request);
					if (site != null) {
						mv.addObject("siteList", Arrays.asList(site));
					}
				}

				List<DomainDns> dnsList = domainDnsService.list(Wrappers.<DomainDns>lambdaQuery()
						.eq(DomainDns::getDomainId, domain.getId()).eq(DomainDns::getStatus, DomainDns.STATUS_ACTIVE)
						.orderByAsc(DomainDns::getType).orderByAsc(DomainDns::getName).orderByAsc(DomainDns::getValue));
				if (dnsList != null && !dnsList.isEmpty()) {
					mv.addObject("dnsList", dnsList);
				}

				logger.info("域名【{}】DNS查询完成", domainName);

				// 保存WHOIS快照（异步，不影响页面响应）
				try {
					domainSnapshotService.saveSnapshotIfChanged(domain, IpUtils.getRequestIp(request));
				} catch (Exception e) {
					logger.warn("保存域名 {} 的WHOIS快照失败: {}", domainName, e.getMessage());
				}

				mv.setViewName("domain_detail");
				
				// GEO: Dynamic structured data for domain detail page
				mv.addObject("_pageSchema", buildDomainSchema(domain));
				
				return mv;
			} else {
				throw new ResourceNotFoundException("域名", domainName);
			}
		} else {// 使用子域名查询
			DomainSite site = getDomainSiteByName(domainName, request);

			if (site != null && site.getStatus() == DomainSite.STATUS_ACTIVE) {
				if (site.getRefreshDnsTime() == null) {
					try {
						domainDnsService.refreshByDomainSite(site);
					} catch (Exception e) {
						logger.error("Failed to refresh DNS for sub-domain: {}", domainName, e);
					}

					logger.info("域名【{}】DNS刷新完成", domainName);
				}

				if (site.getRefreshWebTime() == null) {
					String url = "https://" + domainName;
					ResponseEntity<String> resp = HttpUtils.get(url);
					if (resp == null || !resp.getStatusCode().is2xxSuccessful()) {
						url = "http://" + domainName;
						resp = HttpUtils.get(url);
					}

					if (resp != null && resp.getStatusCode().is2xxSuccessful()
							&& StringUtils.isNotBlank(resp.getBody())) {
						site.setHomePageUrl(url);
						site.setHomePageHtml(resp.getBody());

						List<String> servers = resp.getHeaders().get("server");
						if (servers != null && !servers.isEmpty()) {
							site.setServerName(servers.get(0));
						}

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
					domainSiteService.updateById(site);

					logger.info("域名【{}】web页面查询完成", domainName);
				}

				ModelAndView mv = new ModelAndView();
				mv.addObject("domain", domainService.getById(site.getDomainId()));
				mv.addObject("domainSite", site);
				mv.addObject(Constants.PAGE_TITLE, domainName + " - Whose.Domains");
				mv.addObject(Constants.PAGE_META_DESC, site.getMetaDescription());

				List<DomainDns> dnsList = domainDnsService.list(Wrappers.<DomainDns>lambdaQuery()
						.eq(DomainDns::getDomainId, site.getDomainId()).eq(DomainDns::getName, domainName + ".")
						.eq(DomainDns::getStatus, DomainDns.STATUS_ACTIVE).orderByAsc(DomainDns::getType)
						.orderByAsc(DomainDns::getName).orderByAsc(DomainDns::getValue));
				if (dnsList != null && !dnsList.isEmpty()) {
					mv.addObject("dnsList", dnsList);
				}

				logger.info("域名【{}】DNS查询完成", domainName);

				mv.setViewName("domain_sub_detail");
				return mv;
			} else {
				throw new ResourceNotFoundException("域名", domainName);
			}
		}
	}

//	@Operation(summary = "IP详情页面")
//	@GetMapping("/ip/{ip}")
//	public String ipDetail(@PathVariable("ip") String ip, Model model) {
//		IpAddress ipAddr = ipAddressService.getOne(new QueryWrapper<IpAddress>().eq("IP", ip));
//
//		if (ipAddr == null || ipAddr.getStatus() != IpAddress.STATUS_ACTIVE) {
//			return "404";
//		}
//
//		List<Domain> domainList = domainService
//				.list(new QueryWrapper<Domain>().eq("STATUS", Domain.STATUS_ACTIVE).like("IPS", ip));
//
//		model.addAttribute("ipAddr", ipAddr);
//		model.addAttribute("domainList", domainList);
//
//		return "ip";
//	}

	@Operation(summary = "更新域名RDAP TEXT")
	@PostMapping("/domain/updateText")
	@ResponseBody
	public ResponseJson<String> updateRdapText(@RequestBody String text, HttpServletRequest request) {
		JSONObject json = JSON.parseObject(text);
		if (json != null && json.containsKey("ldhName") && json.containsKey("events") && json.containsKey("entities")) {
			String domainName = json.getString("ldhName").toLowerCase();
			if (domainName.endsWith(".")) {
				domainName = domainName.substring(0, domainName.length() - 1);
			}

			Domain domain = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));
			if (domain != null && domain.getRdapText() == null) {
				domain.setRdapText(text);
				domain.setStatus(Domain.STATUS_ACTIVE);
				domain.setUpdateBy(IpUtils.getRequestIp(request));
				domain.setUpdateTime(new Date());
				RdapUtils.fillRdapInfoFromText(domain, text);
				domainService.updateById(domain);
			}
		}

		return ResponseJson.success();
	}

	@Operation(summary = "查询域名")
	@PostMapping("/domain/{name}/search")
	@ResponseBody
	public ResponseJson<JSONObject> searchDomain(@PathVariable("name") String name, HttpServletRequest request) {
		// 统一小写
		name = name.toLowerCase();
		// 获取主域名
		String mainName = DomainUtils.getMainDomain(name);

		if (mainName.equals(name)) {// 使用主域名查询
			Domain domain = getDomainByName(name, request);
			if (domain != null && (domain.getStatus() == Domain.STATUS_ACTIVE || domain.getRdapServer() != null)) {
				if (domain.getRdapServer() != null && domain.getRdapText() == null) {
					JSONObject json = new JSONObject();
					json.put("rdapUrl", domain.getRdapServer() + "domain/" + domain.getName());
					return ResponseJson.success(json);
				} else {
					return ResponseJson.success();
				}
			} else {
				return ResponseJson.failure("Fail to find information of domain '" + name + "'!");
			}
		} else {
			DomainSite site = getDomainSiteByName(name, request);
			if (site != null && site.getStatus() == DomainSite.STATUS_ACTIVE) {
				return ResponseJson.success();
			} else {
				return ResponseJson.failure("Fail to find information of domain '" + name + "'!");
			}
		}
	}

	@Operation(summary = "请求刷新域名")
	@PostMapping("/domain/{name}/refresh")
	@ResponseBody
	public ResponseJson<JSONObject> refreshDomain(@PathVariable("name") String name, HttpServletRequest request) {
		// 统一小写
		name = name.toLowerCase();

		String ip = IpUtils.getRequestIp(request);
		LocalDateTime _1day = LocalDateTime.now().minusDays(1);

		long count = domainService.count(Wrappers.<Domain>lambdaQuery().eq(Domain::getRequestRefreshIp, ip)
				.gt(Domain::getRequestRefreshTime, _1day));
		if (count >= 99) {
			return ResponseJson.failure("Too many requests.");
		}

		Domain domain = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, name));
		if (domain == null || domain.getStatus() != Domain.STATUS_ACTIVE) {
			return ResponseJson.failure("Illegal request.");
		}

		if (!domain.isRequestRefreshBtnEnable()) {
			return ResponseJson.failure("Illegal request.");
		}

		domain.setRequestRefreshIp(IpUtils.getRequestIp(request));
		domain.setRequestRefreshTime(LocalDateTime.now());
		if (domainService.updateById(domain)) {
			return ResponseJson.success();
		} else {
			return ResponseJson.failure("Fail to request refresh on this domain.");
		}
	}

	private Domain getDomainByName(String domainName, HttpServletRequest req) {
		// 统一小写
		domainName = domainName.toLowerCase();
		// 客户IP
		String ip = IpUtils.getRequestIp(req);

		Domain domain = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));
		if (domain == null) {
			Domain newOne = DomainUtils.getDomainInfoByMainName(domainName);
			if (newOne != null) {
				newOne.setStatus(Domain.STATUS_ACTIVE);
				newOne.setSourceIp(ip);
				newOne.setCreateBy(ip);
				newOne.setCreateTime(new Date());
				domainService.save(newOne);
				try {
					domainDnsService.refreshByDomain(newOne);
				} catch (Exception e) {
					logger.error("Failed to refresh DNS for new domain: {}", domainName, e);
				}
				
				DomainSite wwwSite = DomainUtils.getDomainSiteByName("www." + domainName);
				if (wwwSite != null) {
					wwwSite.setDomainId(newOne.getId());
					wwwSite.setMainName(domainName);
					wwwSite.setName("www." + domainName);
					wwwSite.setStatus(Domain.STATUS_ACTIVE);
					wwwSite.setSourceIp(ip);
					wwwSite.setCreateBy(ip);
					wwwSite.setCreateTime(new Date());
					domainSiteService.save(wwwSite);
					try {
						domainDnsService.refreshByDomainSite(wwwSite);
					} catch (Exception e) {
						logger.error("Failed to refresh DNS for www sub-domain: {}", domainName, e);
					}
				}

				return newOne;
			} else {
				return null;
			}
		} else if (domain.isUpdatable()) {
			boolean result = DomainUtils.fillMainDomainInfo(domain);
			if (result) {
				domain.setStatus(Domain.STATUS_ACTIVE);
				domain.setUpdateBy(ip);
				domain.setUpdateTime(new Date());
				domainService.updateById(domain);
			} else {
				domain.setStatus(Domain.STATUS_INACTIVE);
				domain.setUpdateBy(ip);
				domain.setUpdateTime(new Date());
				domainService.updateById(domain);
			}

			return domain;
		} else {
			return domain;
		}
	}

	private DomainSite getDomainSiteByName(String domainName, HttpServletRequest req) {
		// 统一小写
		domainName = domainName.toLowerCase();
		// 客户IP
		String ip = IpUtils.getRequestIp(req);

		DomainSite site = domainSiteService
				.getOne(Wrappers.<DomainSite>lambdaQuery().eq(DomainSite::getName, domainName));
		if (site == null) {
			DomainSite newOne = DomainUtils.getDomainSiteByName(domainName);
			if (newOne != null) {
				String mainName = DomainUtils.getMainDomain(domainName);
				Domain domain = getDomainByName(mainName, req);
				if (domain == null) {
					return null;
				}

				newOne.setDomainId(domain.getId());
				newOne.setMainName(mainName);
				newOne.setName(domainName);
				newOne.setStatus(Domain.STATUS_ACTIVE);
				newOne.setSourceIp(ip);
				newOne.setCreateBy(ip);
				newOne.setCreateTime(new Date());
				domainSiteService.save(newOne);
				try {
					domainDnsService.refreshByDomainSite(newOne);
				} catch (Exception e) {
					logger.error("Failed to refresh DNS for new site: {}", domainName, e);
				}

				return newOne;
			} else {
				return null;
			}
		} else if (site.isUpdatable()) {
			boolean result = DomainUtils.isDnsEnabled(domainName);
			if (result) {
				site.setStatus(Domain.STATUS_ACTIVE);
				site.setUpdateBy(ip);
				site.setUpdateTime(new Date());
				domainSiteService.updateById(site);
				try {
					domainDnsService.refreshByDomainSite(site);
				} catch (Exception e) {
					logger.error("Failed to refresh DNS for site: {}", domainName, e);
				}
			} else {
				site.setStatus(Domain.STATUS_INACTIVE);
				site.setUpdateBy(ip);
				site.setUpdateTime(new Date());
				domainSiteService.updateById(site);
			}

			return site;
		} else {
			return site;
		}
	}

	@Operation(summary = "关于我们")
	@GetMapping("/about-us")
	public String aboutUs(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "About Us - Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "Learn more about Whose.Domains, our mission, team, and commitment to providing comprehensive domain information services. Discover how we help users access domain registration data.");
		// GEO: AboutPage schema
		com.alibaba.fastjson2.JSONObject schema = new com.alibaba.fastjson2.JSONObject();
		schema.put("@context", "https://schema.org");
		schema.put("@type", "AboutPage");
		schema.put("name", "About Whose.Domains");
		schema.put("url", "https://whose.domains/about-us");
		schema.put("description", "Learn about Whose.Domains — our mission to democratize domain intelligence, our story, values, and technology.");
		schema.put("inLanguage", "en-US");
		com.alibaba.fastjson2.JSONObject mainEntity = new com.alibaba.fastjson2.JSONObject();
		mainEntity.put("@type", "Organization");
		mainEntity.put("name", "Whose.Domains");
		mainEntity.put("url", "https://whose.domains/");
		mainEntity.put("foundingDate", "2025");
		mainEntity.put("description", "Whose.Domains provides free domain WHOIS/RDAP lookup, DNS analysis, SSL checking, domain scoring, and competitive analysis tools.");
		com.alibaba.fastjson2.JSONArray knowsAbout = new com.alibaba.fastjson2.JSONArray();
		knowsAbout.add("WHOIS lookup");
		knowsAbout.add("RDAP protocol");
		knowsAbout.add("DNS analysis");
		knowsAbout.add("SSL certificates");
		knowsAbout.add("domain registration");
		knowsAbout.add("domain investing");
		mainEntity.put("knowsAbout", knowsAbout);
		schema.put("mainEntity", mainEntity);
		model.addAttribute("_pageSchema", schema.toJSONString());
		return "about_us";
	}

	@Operation(summary = "联系我们")
	@GetMapping("/contact-us")
	public String contactUs(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Contact Us - Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "Get in touch with the Whose.Domains team. Contact us for support, inquiries, partnerships, or feedback about our domain information services.");
		return "contact_us";
	}

	@Operation(summary = "隐私策略")
	@GetMapping("/privacy-policy")
	public String privacy(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Privacy Policy - Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "Our privacy policy explains how Whose.Domains collects, uses, and protects your personal information when using our domain lookup services.");
		return "privacy_policy";
	}

	@Operation(summary = "服务条款")
	@GetMapping("/terms-of-service")
	public String termsOfService(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Terms of Service - Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "Terms of service governing the use of Whose.Domains domain lookup tools and services. Please read our terms before using our platform.");
		return "terms_of_service";
	}

	@Operation(summary = "帮助中心")
	@GetMapping("/help-center")
	public String helpCenter(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Help Center - Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "Find answers to frequently asked questions about using our domain lookup tools. Get support for WHOIS, RDAP, domain history, and availability checks.");
		model.addAttribute("_pageSchema", buildFaqSchema(new String[][] {
			{"What is a domain name?", "A domain name is a human-readable address for a website on the internet. It translates to an IP address that computers use to locate web servers. For example, 'example.com' is a domain name that points to a specific server IP address."},
			{"What is WHOIS?", "WHOIS is a public database that contains information about registered domain names, including the registrant's contact information, registration date, expiration date, and the domain's nameservers. It's used to look up who owns a domain."},
			{"How do I perform a WHOIS lookup?", "Simply enter any domain name in the search box on our homepage and press Enter. Our system will query the appropriate WHOIS servers and display all available registration information."},
			{"Why are some domain details hidden?", "Many domain owners use privacy protection services to hide their personal information from public WHOIS records. This is a legitimate service offered by most registrars to protect against spam and unwanted contact."},
			{"What are Top-Level Domains (TLDs)?", "TLDs are the last part of a domain name, such as .com, .org, .net, or country-code TLDs like .cn, .uk, .jp. Different TLDs have different registration rules and purposes."},
			{"How current is your WHOIS data?", "We update our WHOIS data in real-time by querying the official registry servers when you make a request. However, registry servers may cache data for up to 24 hours, so very recent changes might not be immediately visible."},
			{"What is domain privacy protection?", "Domain privacy (also called WHOIS privacy) is a service that replaces your personal contact information in the WHOIS database with the contact information of a forwarding service. This helps protect your privacy and reduce spam."},
			{"How do I check if a domain is available?", "Use our WHOIS lookup tool. If a domain is available for registration, the WHOIS results will indicate that the domain has no registrant or show a status indicating it's available."}
		}));
		return "help_center";
	}

	@Operation(summary = "即将过期的域名")
	@GetMapping("/expiring-domains")
	public String expiringDomains(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Expiring Domains - Find Domains About to Expire | Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "Browse domains expiring in the next 7–90 days. Find premium expired domains for investment, monitor expiring domains for brand protection, and track domain market trends.");
		return "expiring_domains";
	}

	@Operation(summary = "API文档")
	@GetMapping("/api-docs")
	public String apiDocs(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "API Documentation - Whose.Domains Developer API");
		model.addAttribute(Constants.PAGE_META_DESC, "Free domain lookup API documentation. Access WHOIS data, DNS records, domain history, SSL certificates, and more programmatically.");
		// GEO: TechArticle schema for API documentation
		com.alibaba.fastjson2.JSONObject schema = new com.alibaba.fastjson2.JSONObject();
		schema.put("@context", "https://schema.org");
		schema.put("@type", "TechArticle");
		schema.put("headline", "Whose.Domains Developer API Documentation");
		schema.put("description", "Free RESTful APIs for domain research. Access WHOIS data, DNS records, domain scoring, domain history, SSL certificates, and more programmatically.");
		schema.put("url", "https://whose.domains/api-docs");
		schema.put("inLanguage", "en-US");
		schema.put("proficiencyLevel", "Beginner");
		com.alibaba.fastjson2.JSONArray dependencies = new com.alibaba.fastjson2.JSONArray();
		dependencies.add("HTTP client");
		dependencies.add("JSON parser");
		schema.put("dependencies", dependencies);
		com.alibaba.fastjson2.JSONObject author = new com.alibaba.fastjson2.JSONObject();
		author.put("@type", "Organization");
		author.put("name", "Whose.Domains");
		schema.put("author", author);
		model.addAttribute("_pageSchema", schema.toJSONString());
		return "api_docs";
	}

	@Operation(summary = "域名监控页面")
	@GetMapping("/user/watchlist")
	public String domainWatchlist(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "My Domain Watchlist - Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "Monitor your domains and receive expiry alerts. Never lose a domain you care about.");
		return "user/domain-watch";
	}

	@Operation(summary = "查询历史页面")
	@GetMapping("/user/query-history")
	public String queryHistory(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Query History - Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "View your recent domain query history including WHOIS, DNS, SSL and availability checks.");
		return "user/query-history";
	}

	@Operation(summary = "TLDs")
	@GetMapping("/top-level-domains")
	public String tld(Model model) {
		List<DomainTld> list = domainTldService.list(Wrappers.<DomainTld>lambdaQuery()
				.eq(DomainTld::getStatus, DomainTld.STATUS_ACTIVE).orderByAsc(DomainTld::getName));
		model.addAttribute("list", list);
		model.addAttribute(Constants.PAGE_TITLE, "Top-Level Domains (TLD) Directory — All gTLD & ccTLD Extensions | Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC,
				"Browse the complete, authoritative directory of all " + list.size() +
				" Internet top-level domains (TLDs) — including gTLDs like .com, .net, .org and every country-code TLD. " +
				"View registry details, WHOIS servers, and RDAP endpoints.");
		model.addAttribute("_page_keywords",
				"top level domains, TLD list, gTLD, ccTLD, domain extensions, WHOIS server, RDAP, ICANN, domain registry");
		model.addAttribute("_page_subject", "Domain Extensions & TLD Directory");
		return "domain_tlds";
	}
	
	
	@Operation(summary = "提交联系我们")
	@PostMapping("/contact-us/submit")
	@ResponseBody
	public ResponseJson<String> submitContactUs(@RequestBody ContactForm param, HttpServletRequest request) {
		String ip = IpUtils.getRequestIp(request);
		
		// 使用新的速率限制工具
		if (!RateLimitUtils.isAllowed(ip, 5, 60000)) { // 1分钟内最多5次请求
			return ResponseJson.failure("Too many requests. Please try again later.");
		}
		
		// 基础验证
		if (StringUtils.isBlank(param.getName())) {
			return ResponseJson.failure("Name is required.");
		}
		
		if (StringUtils.isBlank(param.getEmail())) {
			return ResponseJson.failure("Email is required.");
		}
		
		if (StringUtils.isBlank(param.getSubject())) {
			return ResponseJson.failure("Subject is required.");
		}
		
		if (StringUtils.isBlank(param.getMessage())) {
			return ResponseJson.failure("Message is required.");
		}
		
		// 增强验证：邮箱格式验证
		if (!param.getEmail().matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
			return ResponseJson.failure("Invalid email format.");
		}
		
		// 增强验证：长度限制
		if (param.getName().length() > 100) {
			return ResponseJson.failure("Name is too long (max 100 characters).");
		}
		
		if (param.getEmail().length() > 150) {
			return ResponseJson.failure("Email is too long (max 150 characters).");
		}
		
		if (param.getSubject().length() > 200) {
			return ResponseJson.failure("Subject is too long (max 200 characters).");
		}
		
		if (param.getMessage().length() > 2000) {
			return ResponseJson.failure("Message is too long (max 2000 characters).");
		}
		
		// 验证主题选项是否有效
		String[] validSubjects = {"technical", "billing", "api", "partnership", "general"};
		boolean isValidSubject = false;
		for (String validSubject : validSubjects) {
			if (validSubject.equals(param.getSubject())) {
				isValidSubject = true;
				break;
			}
		}
		if (!isValidSubject) {
			return ResponseJson.failure("Invalid subject selection.");
		}
		
		// 防止XSS和SQL注入攻击：使用更严格的过滤
		String sanitizedName = sanitizeInput(param.getName());
		String sanitizedEmail = sanitizeInput(param.getEmail());
		String sanitizedSubject = sanitizeInput(param.getSubject());
		String sanitizedMessage = sanitizeInput(param.getMessage());
		
		// 额外的安全检查：检测潜在的恶意内容
		if (containsMaliciousContent(sanitizedName) || 
			containsMaliciousContent(sanitizedEmail) || 
			containsMaliciousContent(sanitizedSubject) || 
			containsMaliciousContent(sanitizedMessage)) {
			logger.warn("Potential malicious content detected from IP: {}", ip);
			return ResponseJson.failure("Invalid content detected.");
		}
		
		// 额外的垃圾信息检测：检查是否包含过多URL或垃圾内容
		if (isSpamContent(sanitizedMessage)) {
			logger.warn("Spam content detected from IP: {}", ip);
			return ResponseJson.failure("Spam content detected.");
		}
		
		ContactInfo info = new ContactInfo();
		info.setId(RandomUtils.generateId());
		info.setName(sanitizedName);
		info.setEmail(sanitizedEmail);
		info.setSubject(sanitizedSubject);
		info.setMessage(sanitizedMessage);
		info.setRequestIp(ip);
		info.setCreateBy(ip);
		info.setCreateTime(new Date());
		if (contactInfoService.save(info)) {
			// 成功后增加请求计数
			RateLimitUtils.incrementRequestCount(ip);
			
			return ResponseJson.success("Thank you for contacting us! We'll get back to you soon.", null);
		} else {
			return ResponseJson.failure("Failed to send message. Please try again.");
		}
	}
	
	/**
	 * 输入净化：移除潜在危险字符
	 */
	private String sanitizeInput(String input) {
		if (input == null) {
			return null;
		}
		
		// 移除HTML标签
		input = input.replaceAll("<[^>]*>", "");
		
		// 移除潜在的SQL注入字符
		input = input.replaceAll("(?i)(union|select|insert|update|delete|drop|create|alter|exec|script)", "");
		
		// 移除JavaScript事件处理器
		input = input.replaceAll("(?i)on\\w+\\s*=", "");
		
		// 返回处理后的字符串
		return input.trim();
	}
	
	/**
	 * 检测垃圾内容
	 */
	private boolean isSpamContent(String message) {
		if (message == null) {
			return false;
		}
		
		// 检查是否包含过多URL
		int urlCount = message.split("https?://|www\\.").length - 1;
		if (urlCount > 3) {
			return true;
		}
		
		// 检查是否包含垃圾关键词
		String lowerMessage = message.toLowerCase();
		String[] spamKeywords = {"viagra", "casino", "loan", "credit", "money", "offer", "deal", "$$$", "buy now", "click here"};
		for (String keyword : spamKeywords) {
			if (lowerMessage.contains(keyword)) {
				// 如果包含垃圾关键词但同时包含正常词汇，则可能不是垃圾信息
				if (hasNormalContent(lowerMessage)) {
					continue; // 不认为是垃圾信息
				} else {
					return true; // 确认为垃圾信息
				}
			}
		}
		
		// 检查是否包含过多重复字符
		if (hasExcessiveRepetition(message)) {
			return true;
		}
		
		return false;
	}
	
	/**
	 * 检查是否包含正常内容
	 */
	private boolean hasNormalContent(String content) {
		// 检查是否包含正常的句子结构
		String[] normalWords = {"question", "help", "support", "information", "service", "problem", "issue"};
		for (String word : normalWords) {
			if (content.contains(word)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * 检查是否包含过度重复字符
	 */
	private boolean hasExcessiveRepetition(String content) {
		// 检查连续相同字符超过一定数量的情况
		char prevChar = '\0';
		int repetitionCount = 0;
		for (char c : content.toCharArray()) {
			if (c == prevChar && (c == '.' || c == '!' || c == '?' || c == '*')) {
				repetitionCount++;
				if (repetitionCount > 5) {
					return true;
				}
			} else {
				repetitionCount = 1;
			}
			prevChar = c;
		}
		return false;
	}
	
	/**
	 * 检测恶意内容
	 */
	private boolean containsMaliciousContent(String input) {
		if (input == null) {
			return false;
		}
		
		// 转换为小写进行检查
		String lowerInput = input.toLowerCase();
		
		// 检查常见的恶意模式
		String[] maliciousPatterns = {
			"<script", "javascript:", "vbscript:", "<iframe", "<object", "<embed", 
			"expression(", "eval(", "alert(", "document.cookie", "onerror", 
			"onload", "onclick", "onmouseover", "onfocus", "union select",
			"union all select", "insert into", "drop table", "exec(",
			"document.location", "window.location", "location.href"
		};
		
		for (String pattern : maliciousPatterns) {
			if (lowerInput.contains(pattern)) {
				return true;
			}
		}
		
		return false;
	}

	@Operation(summary = "/favicon.ico")
	@GetMapping("/favicon.ico")
	@ResponseBody
	public String favicon() {
		return "ok";
	}
	
	@Operation(summary = "/site.webmanifest")
	@GetMapping("/site.webmanifest")
	@ResponseBody
	public String webmanifest() {
		return "{}";
	}

	@Operation(summary = "LLMs.txt for AI crawlers")
	@GetMapping(value = "/llms.txt", produces = "text/plain;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<org.springframework.core.io.ClassPathResource> llmsTxt() {
		return ResponseEntity.ok()
				.header("Content-Type", "text/plain;charset=UTF-8")
				.body(new org.springframework.core.io.ClassPathResource("static/llms.txt"));
	}

	@Operation(summary = "robots.txt")
	@GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<org.springframework.core.io.ClassPathResource> robotsTxt() {
		return ResponseEntity.ok()
				.header("Content-Type", "text/plain;charset=UTF-8")
				.body(new org.springframework.core.io.ClassPathResource("static/robots.txt"));
	}

	@Operation(summary = "AI Plugin manifest for AI agents")
	@GetMapping(value = "/.well-known/ai-plugin.json", produces = "application/json;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<org.springframework.core.io.ClassPathResource> aiPlugin() {
		return ResponseEntity.ok()
				.header("Content-Type", "application/json;charset=UTF-8")
				.body(new org.springframework.core.io.ClassPathResource("static/.well-known/ai-plugin.json"));
	}

	/**
	 * Build FAQPage JSON-LD schema (GEO optimization for AI understanding)
	 */
	private String buildFaqSchema(String[][] faqs) {
		com.alibaba.fastjson2.JSONObject schema = new com.alibaba.fastjson2.JSONObject();
		schema.put("@context", "https://schema.org");
		schema.put("@type", "FAQPage");
		com.alibaba.fastjson2.JSONArray mainEntity = new com.alibaba.fastjson2.JSONArray();
		for (String[] faq : faqs) {
			com.alibaba.fastjson2.JSONObject item = new com.alibaba.fastjson2.JSONObject();
			item.put("@type", "Question");
			item.put("name", faq[0]);
			com.alibaba.fastjson2.JSONObject answer = new com.alibaba.fastjson2.JSONObject();
			answer.put("@type", "Answer");
			answer.put("text", faq[1]);
			item.put("acceptedAnswer", answer);
			mainEntity.add(item);
		}
		schema.put("mainEntity", mainEntity);
		return schema.toJSONString();
	}

	/**
	 * Build WebPage + Dataset schema for domain detail pages (GEO)
	 */
	private String buildDomainSchema(Domain domain) {
		com.alibaba.fastjson2.JSONObject schema = new com.alibaba.fastjson2.JSONObject();
		schema.put("@context", "https://schema.org");
		schema.put("@type", "WebPage");
		schema.put("name", domain.getName() + " WHOIS Information");
		schema.put("url", "https://whose.domains/domain/" + domain.getName());
		schema.put("description", domain.getMetaDescription());
		schema.put("dateModified", domain.getRegistUpdateDateText());
		schema.put("inLanguage", "en-US");

		// About: the domain as a Dataset
		com.alibaba.fastjson2.JSONObject about = new com.alibaba.fastjson2.JSONObject();
		about.put("@type", "Dataset");
		about.put("name", domain.getName() + " WHOIS Data");
		about.put("description", "Domain registration and WHOIS data for " + domain.getName());
		if (domain.getRegistrar() != null) about.put("creator", domain.getRegistrar());
		if (domain.getRegistCreateDateText() != null) about.put("dateCreated", domain.getRegistCreateDateText());
		if (domain.getRegistExpiryDateText() != null) {
			com.alibaba.fastjson2.JSONObject temporal = new com.alibaba.fastjson2.JSONObject();
			temporal.put("@type", "DateTime");
			about.put("expires", domain.getRegistExpiryDateText());
		}

		com.alibaba.fastjson2.JSONArray keywords = new com.alibaba.fastjson2.JSONArray();
		keywords.add("WHOIS");
		keywords.add("domain registration");
		keywords.add(domain.getName());
		if (domain.getRegistrar() != null) keywords.add(domain.getRegistrar());
		about.put("keywords", keywords);
		schema.put("about", about);

		// Provider
		com.alibaba.fastjson2.JSONObject provider = new com.alibaba.fastjson2.JSONObject();
		provider.put("@type", "Organization");
		provider.put("name", "Whose.Domains");
		provider.put("url", "https://whose.domains/");
		schema.put("provider", provider);

		// Speakable: key info for voice/AI
		com.alibaba.fastjson2.JSONObject speakable = new com.alibaba.fastjson2.JSONObject();
		speakable.put("@type", "SpeakableSpecification");
		com.alibaba.fastjson2.JSONArray cssSelector = new com.alibaba.fastjson2.JSONArray();
		cssSelector.add(".domain-name");
		cssSelector.add(".results-grid .result-card:first-child");
		speakable.put("cssSelector", cssSelector);
		schema.put("speakable", speakable);

		return schema.toJSONString();
	}

}
