package info.wesite.core.utils;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.entity.DomainTldExt;
import info.wesite.core.service.DomainTldExtService;
import info.wesite.core.service.DomainTldService;
import jakarta.annotation.PostConstruct;

/**
 * 顶级域名：https://www.iana.org/domains/root/db
 */
@Component
public class DomainUtils {

	private static final Logger log = LoggerFactory.getLogger(DomainUtils.class);
	
	private static final List<String> EXT_LIST = new ArrayList<>();

	@Autowired
	private DomainTldService domainTldService;
	@Autowired
	private DomainTldExtService domainTldExtService;
	
	private static DomainTldService s_domainTldService;
	private static DomainTldExtService s_domainTldExtService;

	@PostConstruct
	public void init() {
		s_domainTldService = domainTldService;
		s_domainTldExtService = domainTldExtService;
		refreshExtList();
	}
	
	public static int refreshExtList() {
		List<DomainTldExt> list = s_domainTldExtService
				.list(Wrappers.<DomainTldExt>lambdaQuery().eq(DomainTldExt::getStatus, DomainTldExt.STATUS_ACTIVE));
		EXT_LIST.clear();
		EXT_LIST.addAll(list.stream().map(DomainTldExt::getDotName).toList());
		return EXT_LIST.size();
	}

	/**
	 * 获取主域名
	 * 
	 * @param domain
	 * @return
	 */
	public static String getMainDomain(String domain) {
		domain = domain.toLowerCase();
		
		for (String ext : EXT_LIST) {
			if (domain.endsWith(ext)) {
				String tmp = domain.substring(0, domain.length() - ext.length());
				if (tmp.contains(".")) {
					return domain.substring(tmp.lastIndexOf(".") + 1);
				} else {
					return domain;
				}
			}
		}

		String tmp = domain.substring(0, domain.lastIndexOf("."));
		if (tmp.contains(".")) {
			return domain.substring(tmp.lastIndexOf(".") + 1);
		} else {
			return domain;
		}
	}

	/**
	 * 获取顶级域名，例如.com/.cn
	 * 
	 * @param domain
	 * @return
	 */
	public static String getTldName(String domain) {
		if (domain.contains(".")) {
			domain = domain.toLowerCase();
			return domain.substring(domain.lastIndexOf("."));
		} else {
			return null;
		}
	}
	
	/**
	 * 获取域名的保留二级域名，例如.gov.cn/.edu.cn
	 * @param domain
	 * @return
	 */
	public static String getSldName(String domain) {
		domain = domain.toLowerCase();

		for (String sld : EXT_LIST) {
			if (domain.endsWith(sld)) {
				return sld;
			}
		}

		return null;
	}
	
	/**
	 * 是否二级保留域名
	 * @return
	 */
	public static boolean isTldExt(String domain) {
		return EXT_LIST.contains(domain);
	}

	public static boolean fillMainDomainInfo(Domain domain) {
		boolean filled = false;
//		if (StringUtils.isNotBlank(domain.getWhoisServer())) {
//			String text = null;
//			try {
//				text = WhoisUtils.getWhoisText(domain.getName(), domain.getWhoisServer());
//				if (StringUtils.isNotBlank(text)) {
//					domain.setWhoisText(text);
//					filled = WhoisUtils.fillWhoisInfoFromText(domain, text);
//				}
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
//		}
//
//		if (!filled && StringUtils.isNotBlank(domain.getRdapServer())) {
//			String text = RdapUtils.getText(domain.getName(), domain.getRdapServer());
//			if (StringUtils.isNotBlank(text)) {
//				domain.setRdapText(text);
//				filled = RdapUtils.fillRdapInfoFromText(domain, text);
//			}
//		}
		
		if (StringUtils.isNotBlank(domain.getParentWhoisServer())) {
			if (StringUtils.isBlank(domain.getWhoisText())) {
				String text = null;
				try {
					text = WhoisUtils.getWhoisText(domain.getName(), domain.getParentWhoisServer());
					if (StringUtils.isNotBlank(text)) {
						domain.setParentWhoisText(text);
						filled = WhoisUtils.fillWhoisInfoFromText(domain, text);
					}
				} catch (IOException e) {
					log.error("Failed to get whois text for domain: {}", domain.getName(), e);
				}
			}
		} else if (StringUtils.isNotBlank(domain.getParentRdapServer())) {
			if (StringUtils.isBlank(domain.getRdapText())) {
				String text = RdapUtils.getText(domain.getName(), domain.getParentRdapServer());
				if (StringUtils.isNotBlank(text)) {
					domain.setParentRdapText(text);
					filled = RdapUtils.fillRdapInfoFromText(domain, text);
				}
			}
		}

		if (filled) {
			return true;
		}

		Domain newOne = getDomainInfoByMainName(domain.getName());
		if (newOne == null) {
			return false;
		} else {
			// domainStatus 需要特殊处理：长度截断
			if (newOne.getDomainStatus() != null && newOne.getDomainStatus().length() > 400) {
				newOne.setDomainStatus(newOne.getDomainStatus().substring(0, 400));
			}

			// 将 newOne 中非空属性拷贝到 domain（跳过基类字段）
			copyNonNullDomainFields(newOne, domain);

			// 修改状态
			domain.setStatus(newOne.getStatus());
			return true;
		}
	}

	/**
	 * 将 source 中非空的域名业务字段拷贝到 target
	 */
	private static void copyNonNullDomainFields(Domain source, Domain target) {
		if (source.getDnssec() != null) target.setDnssec(source.getDnssec());
		if (source.getDomainStatus() != null) target.setDomainStatus(source.getDomainStatus());
		if (source.getNameServers() != null) target.setNameServers(source.getNameServers());
		if (source.getRegistCreateDateText() != null) target.setRegistCreateDateText(source.getRegistCreateDateText());
		if (source.getRegistExpiryDateText() != null) target.setRegistExpiryDateText(source.getRegistExpiryDateText());
		if (source.getRegistUpdateDateText() != null) target.setRegistUpdateDateText(source.getRegistUpdateDateText());
		if (source.getRegistrantCity() != null) target.setRegistrantCity(source.getRegistrantCity());
		if (source.getRegistrantCountry() != null) target.setRegistrantCountry(source.getRegistrantCountry());
		if (source.getRegistrantEmail() != null) target.setRegistrantEmail(source.getRegistrantEmail());
		if (source.getRegistrantName() != null) target.setRegistrantName(source.getRegistrantName());
		if (source.getRegistrantOrg() != null) target.setRegistrantOrg(source.getRegistrantOrg());
		if (source.getRegistrantPhone() != null) target.setRegistrantPhone(source.getRegistrantPhone());
		if (source.getRegistrantState() != null) target.setRegistrantState(source.getRegistrantState());
		if (source.getRegistrar() != null) target.setRegistrar(source.getRegistrar());
		if (source.getRegistrarIanaID() != null) target.setRegistrarIanaID(source.getRegistrarIanaID());
		if (source.getRegistrarUrl() != null) target.setRegistrarUrl(source.getRegistrarUrl());
		if (source.getRegistryDomainID() != null) target.setRegistryDomainID(source.getRegistryDomainID());
		if (source.getTechEmail() != null) target.setTechEmail(source.getTechEmail());
		if (source.getTechName() != null) target.setTechName(source.getTechName());
		if (source.getTechPhone() != null) target.setTechPhone(source.getTechPhone());
		if (source.getParentWhoisServer() != null) target.setParentWhoisServer(source.getParentWhoisServer());
		if (source.getParentWhoisText() != null) target.setParentWhoisText(source.getParentWhoisText());
		if (source.getWhoisServer() != null) target.setWhoisServer(source.getWhoisServer());
		if (source.getWhoisText() != null) target.setWhoisText(source.getWhoisText());
		if (source.getParentRdapServer() != null) target.setParentRdapServer(source.getParentRdapServer());
		if (source.getParentRdapText() != null) target.setParentRdapText(source.getParentRdapText());
		if (source.getRdapServer() != null) target.setRdapServer(source.getRdapServer());
		if (source.getRdapText() != null) target.setRdapText(source.getRdapText());
	}

//	/**
//	 * 填充子域名信息
//	 * @param domain
//	 * @return
//	 */
//	public static boolean fillDomainSite(DomainSite site) {
//		if (isDnsEnabled(site.getName())) {
//			return true;
//		} else {
//			return false;
//		}
//	}
	
	/**
	 * 根据主域名查询信息，域名信息不存在时使用
	 * 
	 * @param name
	 * @return
	 * @throws IOException
	 */
	public static Domain getDomainInfoByMainName(String name) {
		String tldName = getTldName(name);
		DomainTld tld = s_domainTldService.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getDisplayName, tldName));
		if (tld == null) {
			return null;
		}
		
		Domain result = null;
		if (StringUtils.isNotBlank(tld.getRdapServer())) {
			//使用rdap服务
			result = getDomainInfoFromRdap(name, tld.getRdapServer());
		} else if (StringUtils.isNotBlank(tld.getWhoisServer())) {
			//使用whois服务
			result = getDomainInfoFromWhois(name, tld.getWhoisServer());
		}
		
		//whois和rdap都无法使用的情况下，查询域名的dns解析是否可用
		if (result == null) {
			if (isDnsEnabled(name)) {
				Domain domain = new Domain();
				domain.setName(name);
				domain.setTldName(tldName);
				domain.setSldName(getSldName(name));
				domain.setStatus(Domain.STATUS_ACTIVE);
				return domain;
			} else {
				return null;
			}
		} else {
			result.setTldName(tldName);
			result.setSldName(getSldName(name));
			return result;
		}
	}
	
	/**
	 * 根据子域名查询信息
	 * @param name
	 * @return
	 */
	public static DomainSite getDomainSiteByName(String name) {
		if (isDnsEnabled(name)) {
			DomainSite site = new DomainSite();
			site.setName(name);
			site.setStatus(Domain.STATUS_ACTIVE);
			return site;
		} else {
			return null;
		}
	}
	
	public static boolean isDnsEnabled(String domainName) {
		try {
			int[] types = { Type.A, Type.AAAA, Type.CNAME, Type.MX, Type.TXT };

			// 并行查询所有 DNS 记录类型
			CompletableFuture<Boolean>[] futures = Stream.of(Type.A, Type.AAAA, Type.CNAME, Type.MX, Type.TXT)
					.map(type -> CompletableFuture.supplyAsync(() -> {
						try {
							Lookup lookup = new Lookup(domainName, type);
							return lookup.run() != null;
						} catch (TextParseException e) {
							return false;
						}
					}))
					.toArray(CompletableFuture[]::new);

			// 任意一个查询返回 true 即可
			CompletableFuture<Object> anyResult = CompletableFuture.anyOf(futures);
			// 等待所有完成，检查是否有任何一个为 true
			CompletableFuture.allOf(futures).join();
			for (CompletableFuture<Boolean> f : futures) {
				if (f.join()) {
					return true;
				}
			}
			return false;
		} catch (Exception e) {
			log.error("Failed to perform DNS lookup for domain: {}", domainName, e);
			return false;
		}
	}
	
	private static Domain getDomainInfoFromRdap(String name, String rdapServer) {
		//从一级rdap服务器获取信息
		String rdapText = RdapUtils.getText(name, rdapServer);
		if (StringUtils.isBlank(rdapText)) {
			return null;
		}
		
		Domain domain = new Domain();
		domain.setName(name);
		domain.setStatus(Domain.STATUS_ACTIVE);
		domain.setParentRdapServer(rdapServer);//一级rdap服务器
		domain.setParentRdapText(rdapText);
		
		JSONObject json = JSON.parseObject(rdapText);
		if (json != null && json.containsKey("ldhName")) {
			if (json.containsKey("links")) {
				JSONArray links = json.getJSONArray("links");
				for (int i = 0; i < links.size(); i++) {
					JSONObject link = links.getJSONObject(i);
					if (link.getString("rel").equals("related")) {
						String url = link.getString("href").toLowerCase();
						domain.setRdapServer(url.replace("domain/" + name, ""));//二级rdap服务器
						//domain.setRdapText(RdapUtils.getTextByUrl(url));
						break;
					}
				}
			}
		}
		
		if (StringUtils.isNotBlank(domain.getRdapText())) {
			RdapUtils.fillRdapInfoFromText(domain, domain.getRdapText());
		} else if (StringUtils.isNotBlank(domain.getParentRdapText())) {
			RdapUtils.fillRdapInfoFromText(domain, domain.getParentRdapText());
		}
		
		return domain;
	}
	
	/**
	 * 使用whois服务查询域名信息
	 * @param name 查询域名
	 * @param whoisServer 一级whois服务器
	 */
	private static Domain getDomainInfoFromWhois(String name, String whoisServer) {
		String text = null;
		try {
			//使用一级whois服务器查询whois信息
			text = WhoisUtils.getWhoisText(name, whoisServer);
		} catch (IOException e) {
			log.error("Failed to get whois text for domain: {} from server: {}", name, whoisServer, e);
			return null;
		}
		
		if (!WhoisUtils.isValid(text)) {
			//返回whois信息不合法
			log.warn("查询whois信息返回为空或No match===域名：" + name + ", whois服务器：" + whoisServer);
			return null;
		}
		
		Domain domain = new Domain();
		domain.setName(name);
		domain.setStatus(Domain.STATUS_ACTIVE);
		domain.setParentWhoisServer(whoisServer);//一级whois服务器
		domain.setParentWhoisText(text);
		
		//从一级whois服务器返回的信息中解析二级whois服务器
		String nextServer = WhoisUtils.getWhoisServerFromText(text);
		//二级whois服务器不为空时，从二级whois服务器获取信息
		if (StringUtils.isNotBlank(nextServer)) {
			if (nextServer.startsWith("http://")) {
				nextServer = nextServer.replace("http://", "");
			}
			if (nextServer.startsWith("https://")) {
				nextServer = nextServer.replace("https://", "");
			}
			
			domain.setWhoisServer(nextServer);
//			try {
//				domain.setWhoisText(WhoisUtils.getWhoisText(name, nextServer));
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
		}
		
		if (StringUtils.isNotBlank(domain.getWhoisText())) {
			WhoisUtils.fillWhoisInfoFromText(domain, domain.getWhoisText());
		} else if (StringUtils.isNotBlank(domain.getParentWhoisText())) {
			WhoisUtils.fillWhoisInfoFromText(domain, domain.getParentWhoisText());
		}
		
		return domain;
	}

//	public static Domain getServerInfo(String mainDomain) {
//		String rdapServer = RdapUtils.getServerByDomain(mainDomain);
//		if (rdapServer != null) {//rdap服务可用
//			log.info("连接到RDAP服务器【{}】查询域名【{}】", rdapServer, mainDomain);
//			String text = RdapUtils.getText(mainDomain, rdapServer);
//			if (text == null) {
//				return null;
//			} else {
//				JSONObject json = JSON.parseObject(text);
//				if (json != null && json.containsKey("ldhName") && json.getString("ldhName").equalsIgnoreCase(mainDomain)) {
//					Domain domain = new Domain();
//					domain.setName(mainDomain);
//					domain.setRdapServer(rdapServer);
//					domain.setRdapText(text);
//					
//					if (json.containsKey("links")) {
//						JSONArray links = json.getJSONArray("links");
//						for (int i=0; i<links.size(); i++) {
//							JSONObject link = links.getJSONObject(i);
//							if (link.getString("rel").equals("related")) {
//								String url = link.getString("value").toLowerCase().replace("domain/" + mainDomain, "");
//								if (url.equals("https://rdap.markmonitor.com/rdap/")) {//特别处理markmonitor
//									domain.setRdapServer(null);
//									domain.setRdapText(null);
//									domain.setWhoisServer("whois.markmonitor.com");
//									try {
//										String whoisText = WhoisUtils.getWhoisText(mainDomain, domain.getWhoisServer());
//										if (whoisText != null) {
//											domain.setWhoisText(whoisText);
//											WhoisUtils.fillWhoisInfoFromText(domain, whoisText);
//										}
//									} catch (IOException e) {
//										e.printStackTrace();
//									}
//									break;
//								} else if (!url.equals(rdapServer)) {
//									domain.setRdapServer(url);
//									domain.setRdapText(null);
//									break;
//								}
//							}
//						}
//					}
//					
//					if (domain.getRdapText() != null) {
//						RdapUtils.fillRdapInfoFromText(domain, domain.getRdapText());
//					}
//					
//					return domain;
//				} else {
//					return null;
//				}
//			}
//		} else {//使用whois服务
//			log.info("连接到WHOIS服务器查询域名【{}】", mainDomain);
//			try {
//				String whoisServer = WhoisUtils.getWhoisServerFromIANA(mainDomain);
//				if (whoisServer == null) {
//					return null;
//				}
//				
//				Domain domain = new Domain();
//				domain.setName(mainDomain);
//				domain.setWhoisServer(whoisServer);
//				
//				String text = WhoisUtils.getWhoisText(mainDomain, whoisServer);
//				if (text != null) {
//					domain.setWhoisText(text);
//					WhoisUtils.fillWhoisInfoFromText(domain, text);
//					
//					String nextServer = WhoisUtils.getWhoisServerFromText(text);
//					if (StringUtils.isNotBlank(nextServer)) {
//						String text2 = WhoisUtils.getWhoisText(mainDomain, nextServer);
//						domain.setWhoisServer(nextServer);
//						domain.setWhoisText(text2);
//						WhoisUtils.fillWhoisInfoFromText(domain, text2);
//					}
//				}
//				
//				return domain;
//			} catch (IOException e) {
//				e.printStackTrace();
//				return null;
//			}
//		}
//	}
	
	/**
	 * 端口是否打开
	 * @param host
	 * @param port
	 * @return
	 */
	public static boolean isPortOpen(String host, int port) {
		try (Socket socket = new Socket(host, port)) {
			return true;
		} catch (IOException e) {
			return false;
		}
	}
	
//	public static void main(String[] args) {
//		System.out.println(getTld("corezon.com"));
//	}
}
