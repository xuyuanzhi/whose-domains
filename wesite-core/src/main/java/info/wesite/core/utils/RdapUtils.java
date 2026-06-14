package info.wesite.core.utils;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import info.wesite.core.entity.Domain;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * RDAP服务器目录：https://data.iana.org/rdap/dns.json
 */
@Component
public class RdapUtils {
	
	private static final Logger log = LoggerFactory.getLogger(RdapUtils.class);
	
	@Resource
	private RestTemplate restTemplate;
	
	private static RestTemplate s_restTemplate;
	
//	private static Map<String, String> ServerMappings = null;
	
	@PostConstruct
	public void init() {
		s_restTemplate = restTemplate;
	}

//	public static String getServerList() {
//		ResponseEntity<String> resp = s_restTemplate.getForEntity("https://data.iana.org/rdap/dns.json", String.class);
//		if (resp.getStatusCode().is2xxSuccessful()) {
//			return resp.getBody();
//		} else {
//			return null;
//		}
//	}
	
	public static String getText(String domain, String server) {
		String url = server + "domain/" + domain;
		try {
			ResponseEntity<String> resp = s_restTemplate.getForEntity(url, String.class);
			if (resp.getStatusCode().is2xxSuccessful()) {
				return resp.getBody();
			} else {
				return null;
			}
		} catch (Exception e) {
//			e.printStackTrace();
			return null;
		}
	}
	
	public static String getTextByUrl(String rdapUrl) {
		try {
			ResponseEntity<String> resp = s_restTemplate.getForEntity(rdapUrl, String.class);
			if (resp.getStatusCode().is2xxSuccessful()) {
				return resp.getBody();
			} else {
				return null;
			}
		} catch (Exception e) {
			log.error("Failed to get RDAP text from URL: {}", rdapUrl, e);
			return null;
		}
	}
//		String server = getServerByDomain(domain);
//		if (server == null) {
//			return null;
//		}
//		
//		String text = getText(domain, server);
//		if (StringUtils.isBlank(text)) {
//			return text;
//		}
//		
//		JSONObject root = JSON.parseObject(text);
//		JSONArray links = root.getJSONArray("links");
//		if (links != null && links.size() == 1 && links.getJSONObject(0).getString("rel").equals("self")) {
//			return text;
//		}
//		
//		for (int i=0; i<links.size(); i++) {
//			JSONObject link = links.getJSONObject(i);
//			if (link.getString("rel").equals("related")) {
//				ResponseEntity<String> resp = s_restTemplate.getForEntity(link.getString("href"), String.class);
//				if (resp.getStatusCode().is2xxSuccessful()) {
//					return resp.getBody();
//				}
//			}
//		}
//		
//		return null;
//	}
	
//	public static String getServerByDomain(String domain) {
//		if (ServerMappings == null) {
//			reloadServerMappings();
//		}
//		
//		String tld = domain.substring(domain.lastIndexOf(".")+1);
//		
//		return ServerMappings.get(tld);
//	}
	
//	public static String getDirectServerByDomain(String domain) {
//		String _1stServer = getServerByDomain(domain);
//		if (_1stServer == null) {
//			return null;
//		}
//		
//		String text = getText(domain, _1stServer);
//		if (StringUtils.isBlank(text)) {
//			return null;
//		}
//		
//		JSONObject root = JSON.parseObject(text);
//		JSONArray links = root.getJSONArray("links");
//		if (links != null && links.size() == 1 && links.getJSONObject(0).getString("rel").equals("self")) {
//			return text;
//		}
//		
//		for (int i=0; i<links.size(); i++) {
//			JSONObject link = links.getJSONObject(i);
//			if (link.getString("rel").equals("related")) {
//				return link.getString("href").toLowerCase().replace("domain/" + domain, "");
//			}
//		}
//		
//		return null;
//	}
	
//	public static void reloadServerMappings() {
//		if (ServerMappings == null) {
//			ServerMappings = new HashMap<>();
//		} else {
//			ServerMappings.clear();
//		}
//		
//		String text = getServerList();
//		JSONObject root = JSON.parseObject(text);
//		JSONArray services = root.getJSONArray("services");
//		for (int i=0; i<services.size(); i++) {
//			JSONArray item = services.getJSONArray(i);
//			JSONArray tlds = item.getJSONArray(0);
//			String rdapServer = item.getJSONArray(1).getString(0);
//			
//			for (int j=0; j<tlds.size(); j++) {
//				String tld = tlds.getString(j);
//				ServerMappings.put(tld, rdapServer);
//			}
//		}
//	}
	
	public static boolean fillRdapInfoFromText(Domain domain, String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}
		
		domain.setDomainStatus(null);
		domain.setNameServers(null);
		
		JSONObject root = JSON.parseObject(text);
		JSONArray events = root.getJSONArray("events");
		JSONArray nameservers = root.getJSONArray("nameservers");
		JSONArray entities = root.getJSONArray("entities");
		JSONArray links = root.getJSONArray("links");
		
		if (entities == null || events == null || nameservers == null) {
			return false;
		}
		
		domain.setRegistryDomainID(root.getString("handle"));
		
		if (links != null) {
			for (int i=0; i<links.size(); i++) {
				JSONObject link = links.getJSONObject(i);
				if (link.containsKey("rel") && link.getString("rel").equals("self")) {
					String href = link.getString("href");
					if (StringUtils.isNotBlank(href)) {
						domain.setRdapServer(href.toLowerCase().replace("domain/" + domain.getName().toLowerCase(), ""));
						break;
					}
				}
			}
		}
		
		for (int i=0; i<entities.size(); i++) {
			JSONObject entity = entities.getJSONObject(i);
			String role = null;
			Object roles = entity.get("roles");
			if (roles instanceof String) {
				role = (String) roles;
			} else if (roles instanceof JSONArray) {
				role = ((JSONArray)roles).getString(0);
			}
			
			if ("registrar".equals(role)) {
				//Registrar IANA ID
				domain.setRegistrarIanaID(entity.getString("handle"));
				//Registrar
				if (entity.containsKey("vcardArray")) {
					JSONArray vcardArray = entity.getJSONArray("vcardArray").getJSONArray(1);
					for (int j=0; j<vcardArray.size(); j++) {
						JSONArray item = vcardArray.getJSONArray(j);
						if (item.getString(0).equals("fn")) {
							domain.setRegistrar(item.getString(3));
						} else if (item.getString(0).equals("contact-uri")) {
							domain.setRegistrarUrl(item.getString(3));
						}
					}
				}
			} else if ("registrant".equals(role)) {
				if (entity.containsKey("vcardArray")) {
					JSONArray vcardArray = entity.getJSONArray("vcardArray").getJSONArray(1);
					for (int j=0; j<vcardArray.size(); j++) {
						JSONArray item = vcardArray.getJSONArray(j);
						if (item.getString(0).equals("org")) {
							domain.setRegistrantOrg(item.getString(3));
						} else if (item.getString(0).equals("fn")) {
							domain.setRegistrantName(item.getString(3));
						} else if (item.getString(0).equals("email")) {
							domain.setRegistrantEmail(item.getString(3));
						} else if (item.getString(0).equals("tel")) {
							domain.setRegistrantPhone(item.getString(3));
						} else if (item.getString(0).equals("adr")) {
							JSONObject country = item.getJSONObject(1);
							if (country != null && country.containsKey("cc")) {
								domain.setRegistrantCountry(country.getString("cc"));
							}
							
							JSONArray addr = item.getJSONArray(3);
							domain.setRegistrantCity(addr.getString(3));
							domain.setRegistrantState(addr.getString(4));
						}
					}
				}
			} else if ("technical".equals(role)) {
				if (entity.containsKey("vcardArray")) {
					JSONArray vcardArray = entity.getJSONArray("vcardArray").getJSONArray(1);
					for (int j=0; j<vcardArray.size(); j++) {
						JSONArray item = vcardArray.getJSONArray(j);
						if (item.getString(0).equals("fn")) {
							domain.setTechName(item.getString(3));
						} else if (item.getString(0).equals("email")) {
							domain.setTechEmail(item.getString(3));
						} else if (item.getString(0).equals("tel")) {
							domain.setTechPhone(item.getString(3));
						}
					}
				}
			}
		}
		
		for (int i=0; i<events.size(); i++) {
			JSONObject event = events.getJSONObject(i);
			if (event.getString("eventAction").equals("registration")) {
				domain.setRegistCreateDateText(event.getString("eventDate"));
			} else if (event.getString("eventAction").contains("last update") || event.getString("eventAction").contains("last changed")) {
				domain.setRegistUpdateDateText(event.getString("eventDate"));
			} else if (event.getString("eventAction").contains("expiration") || event.getString("eventAction").contains("expire")) {
				domain.setRegistExpiryDateText(event.getString("eventDate"));
			}
		}
		
		if (root.containsKey("status")) {
			JSONArray statusArr = root.getJSONArray("status");
			for (int i=0; i<statusArr.size(); i++) {
				String status = statusArr.getString(i);
				if (domain.getDomainStatus() == null) {
					domain.setDomainStatus(status);
				} else {
					domain.setDomainStatus(domain.getDomainStatus() + "," + status);
				}
			}
		}
		
		for (int i=0; i<nameservers.size(); i++) {
			JSONObject nameserver = nameservers.getJSONObject(i);
			if (domain.getNameServers() == null) {
				domain.setNameServers(nameserver.getString("ldhName"));
			} else {
				domain.setNameServers(domain.getNameServers() + "," + nameserver.getString("ldhName"));
			}
		}
		
		if (root.containsKey("secureDNS")) {
			Boolean signed = root.getJSONObject("secureDNS").getBoolean("delegationSigned");
			domain.setDnssec((signed != null && signed)?"true":"false");
		}
		
		return true;
	}
}
