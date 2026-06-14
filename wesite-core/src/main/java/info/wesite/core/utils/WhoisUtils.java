package info.wesite.core.utils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.whois.WhoisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;
import info.wesite.core.handler.domain.AuWhoisConverter;
import info.wesite.core.handler.domain.BrWhoisConverter;
import info.wesite.core.handler.domain.CnWhoisConverter;
import info.wesite.core.handler.domain.ComWhoisConverter;
import info.wesite.core.handler.domain.CzWhoisConverter;
import info.wesite.core.handler.domain.EduWhoisConverter;
import info.wesite.core.handler.domain.GovWhoisConverter;
import info.wesite.core.handler.domain.JpWhoisConverter;
import info.wesite.core.handler.domain.RsWhoisConverter;
import info.wesite.core.handler.domain.RuWhoisConverter;
import info.wesite.core.handler.domain.SaEduWhoisConverter;
import info.wesite.core.handler.domain.SeWhoisConverter;
import info.wesite.core.handler.domain.SkWhoisConverter;
import info.wesite.core.handler.domain.UaWhoisConverter;
import info.wesite.core.handler.domain.UsWhoisConverter;
import info.wesite.core.handler.domain.WhoisConverter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * https://www.cnblogs.com/sfqas/p/12181797.html
 * 
 * @author yuanzhi
 *
 */
@Component
public class WhoisUtils {

	private static final Logger log = LoggerFactory.getLogger(WhoisUtils.class);

	@Resource
	private CnWhoisConverter cnWhoisConverter;
	@Resource
	private EduWhoisConverter eduWhoisConverter;
	@Resource
	private ComWhoisConverter comWhoisConverter;
	@Resource
	private GovWhoisConverter govWhoisConverter;
	@Resource
	private UsWhoisConverter usWhoisConverter;
	@Resource
	private AuWhoisConverter auWhoisConverter;
	@Resource
	private JpWhoisConverter jpWhoisConverter;
	@Resource
	private RuWhoisConverter ruWhoisConverter;
	@Resource
	private CzWhoisConverter czWhoisConverter;
	@Resource
	private RsWhoisConverter rsWhoisConverter;
	@Resource
	private SkWhoisConverter skWhoisConverter;
	@Resource
	private SeWhoisConverter seWhoisConverter;
	@Resource
	private SaEduWhoisConverter saEduWhoisConverter;
	@Resource
	private BrWhoisConverter brWhoisConverter;
	@Resource
	private UaWhoisConverter uaWhoisConverter;
	

//	private static final Map<String, String> map = new HashMap<>();
//	static {
//		map.put(".com", "whois.verisign-grs.com");
//		map.put(".cn", "whois.cnnic.cn");
////		map.put(".com.cn", "whois.cnnic.cn");
////		map.put(".info", "whois.afilias.info");
//		map.put(".info", "whois.afilias.net");
//		map.put(".net", "whois.verisign-grs.com");
//		map.put(".ca", "whois.cira.ca");
//		map.put(".org", "whois.publicinterestregistry.org");
//		map.put(".co", "whois.nic.co");
//		map.put(".app", "whois.nic.app");
//		map.put(".ai", "whois.nic.ai");
//		map.put(".domains", "whois.nic.domains");
//		map.put(".io", "whois.nic.io");
//	}

	private static WhoisUtils instance;

	private static final Map<String, WhoisConverter> converterMap = new HashMap<>();

	@PostConstruct
	public void init() {
		instance = this;

		converterMap.put(".com", comWhoisConverter);
		converterMap.put(".cn", cnWhoisConverter);
		converterMap.put(".edu", eduWhoisConverter);
		converterMap.put(".gov", govWhoisConverter);
		converterMap.put(".org", comWhoisConverter);
		converterMap.put(".net", comWhoisConverter);
		converterMap.put(".mil", comWhoisConverter);
		converterMap.put(".int", comWhoisConverter);
		converterMap.put(".arpa", comWhoisConverter);
		converterMap.put(".biz", comWhoisConverter);
		converterMap.put(".info", comWhoisConverter);
		converterMap.put(".name", comWhoisConverter);
		converterMap.put(".pro", comWhoisConverter);
		converterMap.put(".io", comWhoisConverter);
		converterMap.put(".vip", comWhoisConverter);
		converterMap.put(".tv", comWhoisConverter);
		converterMap.put(".app", comWhoisConverter);
		converterMap.put(".co", comWhoisConverter);
		converterMap.put(".us", usWhoisConverter);
		converterMap.put(".uk", comWhoisConverter);
		converterMap.put(".ca", comWhoisConverter);
		converterMap.put(".au", auWhoisConverter);
		converterMap.put(".fr", comWhoisConverter);
		converterMap.put(".de", comWhoisConverter);
		converterMap.put(".jp", jpWhoisConverter);
		converterMap.put(".ru", ruWhoisConverter);
		converterMap.put(".cz", czWhoisConverter);
		converterMap.put(".rs", rsWhoisConverter);
		converterMap.put(".sk", skWhoisConverter);
		converterMap.put(".se", seWhoisConverter);
		converterMap.put(".edu.sa", saEduWhoisConverter);
		converterMap.put(".br", brWhoisConverter);
		converterMap.put(".ua", uaWhoisConverter);
	}

//	private static String getWhoisServer(String domain) {
//		domain = domain.toLowerCase();
//		String suffix = domain.substring(domain.lastIndexOf("."));
//		if (map.containsKey(suffix)) {
//			return map.get(suffix);
//		} else {
//			// log.error("domain suffix[" + suffix + "] isn't supported!");
//			return null;
//		}
//	}

//	public static String getWhoisText(String domain) throws IOException {
//		String server = getWhoisServer(domain);
//		String topDomain = DomainUtils.getMainDomain(domain);
//		if (server == null) {
//			server = getWhoisServerFromIANA(topDomain);
//			if (server == null) {
//				return null;
//			}
//		}
//
//		return getWhoisText(topDomain, server);
//	}

	public static String getWhoisServerFromText(String text) {
		try {
			BufferedReader br = new BufferedReader(
					new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
			String line = null;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.startsWith(WhoisConverter.Registrar_WHOIS_Server)) {
					return line.replace(WhoisConverter.Registrar_WHOIS_Server, "").trim();
				}
			}
		} catch (IOException e) {
			log.error("Failed to parse whois server from text", e);
		}
		return null;
	}

	public static boolean fillWhoisInfoFromText(Domain result, String text) {
		return instance.fillWhoisInfoFromText0(result, text);
	}

	public boolean fillWhoisInfoFromText0(Domain result, String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}

		try {
			result.setDomainStatus(null);
			result.setNameServers(null);

			String tld = DomainUtils.getTldName(result.getName());

			if (converterMap.containsKey(tld)) {
				converterMap.get(tld).fillDomainWithText(result, text);
			} else {
				comWhoisConverter.fillDomainWithText(result, text);
			}

			return true;
		} catch (IOException e) {
			log.error("Failed to fill whois info from text", e);
			return false;
		}
	}

//	public static String getWhoisTextFromRegistrar(String domain) throws IOException {
//		String server = getWhoisServer(domain);
//		String topDomain = DomainUtils.getMainDomain(domain);
//		if (server == null) {
//			server = getWhoisServerFromIANA(topDomain);
//			if (server == null) {
//				return null;
//			}
//		}
//
//		String text = getWhoisText(topDomain, server);
//		if (text == null) {
//			return null;
//		}
//
//		String registrarServer = null;
//		try {
//			BufferedReader br = new BufferedReader(
//					new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
//			String line = null;
//			while ((line = br.readLine()) != null) {
//				line = line.trim();
//				if (line.startsWith("Registrar WHOIS Server:")) {
//					registrarServer = line.replace("Registrar WHOIS Server:", "").trim();
//					break;
//				}
//			}
//		} catch (IOException e) {
//			e.printStackTrace();
//			throw e;
//		}
//
//		if (registrarServer != null && !registrarServer.equalsIgnoreCase(server)) {
//			return getWhoisText(topDomain, registrarServer);
//		} else {
//			return text;
//		}
//	}

	public static String getWhoisServerFromIANA(String domain) throws IOException {
		String text = getWhoisText(domain, "whois.iana.org");
		if (StringUtils.isBlank(text)) {
			return null;
		}

//		log.info("从whois.iana.org查询的信息：{}", text);

		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
			String line = null;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("whois:")) {
					String server = line.replace("whois:", "").trim();
					return server;
				}
			}

			return null;
		} catch (IOException e) {
			throw e;
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
				}
			}
		}
	}

//    public static void request(String domain, String whoisServer) {
//        Socket socket = null;
//        
//        try {
//            socket = new Socket(whoisServer, 43);
//            
//            PrintWriter pw = new PrintWriter(socket.getOutputStream());
//            pw.println(domain);
//            pw.flush();
//            
//            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
//            
//            String line = null;
//            while ((line = br.readLine()) != null) {
//                System.out.println(line);
//            }
//        } catch (UnknownHostException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        } catch (IOException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        } finally {
//            log.error("finally, 关闭socket");
//            if (socket != null) {
//                try {
//                    socket.close();
//                } catch (IOException e) {
//                }
//            }
//        }
//    }

	public static String getWhoisText(String domain, String whoisServer) throws IOException {
		//log.info("查询whois信息方法开始===域名：" + domain + ", whois服务器：" + whoisServer);
		WhoisClient client = new WhoisClient();
		try {
			client.setConnectTimeout(10000);
			client.connect(whoisServer);
			return client.query(domain);
		} catch (SocketException e1) {
			throw e1;
		} catch (IOException e1) {
			throw e1;
		} finally {
			try {
				client.disconnect();
			} catch (IOException e) {
			}
		}
	}
	
	public static boolean isValid(String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}
		
		if (text.contains("No entries found for the selected source(s).")) {
			return false;
		}
		
		if (text.contains("You have exceeded your access quota")) {
			return false;
		}
		
		if (text.contains("Your connection limit exceeded")) {
			return false;
		}
		
		if (text.contains("No match for")) {
			return false;
		}
		
		if (text.contains("request limit exceeded")) {
			return false;
		}
		
		if (text.contains("Whois limit exceeded")) {
			return false;
		}
		
		if (text.contains("Query limit exceeded, try again later")) {
			return false;
		}
		
		if (text.contains("Error: ratelimit exceeded")) {
			return false;
		}
		
		if (text.contains("Quota exceeded")) {
			return false;
		}
		
		if (text.contains("The IP address used to perform the query  is not authorised  or  has exceeded the established limit")) {
			return false;
		}
		
		if (text.contains("This WHOIS server is being retired. Please use our RDAP service instead")) {
			return false;
		}
		
		if (text.contains("Out of this registry")) {
			return false;
		}
		
		if (text.contains("Domain not found.")) {
			return false;
		}
		
		if (text.contains("No matching record.")) {
			return false;
		}
		
		return true;
	}

//	public static JSONObject getJson(String domain, String whoisServer) {
//		log.info("查询whois信息方法开始===域名：" + domain + ", whois服务器：" + whoisServer);
//		WhoisClient client = new WhoisClient();
//		try {
//			client.connect(whoisServer);
//			String text = client.query(domain);
//
//			log.info("返回whois信息===域名：" + domain + ", whois服务器：" + whoisServer);
//			if (StringUtils.isBlank(text)) {
//				return null;
//			}
//
//			BufferedReader br = new BufferedReader(
//					new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
//			String line = null;
//			JSONObject json = new JSONObject();
//			while ((line = br.readLine()) != null) {
//				if (StringUtils.isBlank(line) || !line.contains(SEPERATOR)) {
//					continue;
//				}
//				String[] ss = line.split(SEPERATOR);
//				if (ss.length == 2) {
//					json.put(ss[0].trim(), ss[1].trim());
//				}
//			}
//
//			return json;
//		} catch (SocketException e1) {
//			e1.printStackTrace();
//			return null;
//		} catch (IOException e1) {
//			e1.printStackTrace();
//			return null;
//		} finally {
//			try {
//				client.disconnect();
//			} catch (IOException e) {
//			}
//		}
//
////        Socket socket = null;
////        PrintWriter pw = null;
////        BufferedReader br = null;
////
////        try {
////            log.error("连接【" + whoisServer + "】的端口【43】!");
////            socket = new Socket(whoisServer, 43);
////
////            pw = new PrintWriter(socket.getOutputStream());
////            pw.println(domain);
////            pw.flush();
////
////            log.error("发送域名【" + domain + "】到socket");
////
////            br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
////
////            log.error("获取socket的响应");
////
////            JSONObject json = new JSONObject();
////
////            log.error("构建JSONObject对象");
////
////            String line = null;
////            log.error("循环每一行数据");
////
////            while ((line = br.readLine()) != null) {
////                System.out.println(line);
////                if (StringUtils.isBlank(line) || !line.contains(SEPERATOR)) {
////                    continue;
////                }
////
////                String[] ss = line.split(SEPERATOR);
////                if (ss.length == 2) {
////                    json.put(ss[0].trim(), ss[1].trim());
//////                    if (json.containsKey(ss[0].trim())) {
//////                        Object val = json.get(ss[0].trim());
//////                        if (val instanceof String) {
//////                            json.put(ss[0].trim(), new String[] { val.toString(), ss[1].trim() });
//////                        } else if (val instanceof String[]) {
//////                            String[] arr = (String[]) val;
//////                            String[] newVal = new String[arr.length + 1];
//////                            for (int i = 0; i < arr.length; i++) {
//////                                newVal[i] = arr[i];
//////                            }
//////                            newVal[arr.length] = ss[1].trim();
//////                            json.put(ss[0].trim(), newVal);
//////                        }
//////                    } else {
//////                        json.put(ss[0].trim(), ss[1].trim());
//////                    }
////                }
////            }
////
////            log.error("循环每一行数据结束");
////
////            return json;
////        } catch (UnknownHostException e) {
////            e.printStackTrace();
////            log.error("域名【" + domain + "】处理错误：", e);
////            return null;
////        } catch (IOException e) {
////            e.printStackTrace();
////            log.error("域名【" + domain + "】处理错误：", e);
////            return null;
////        } finally {
////            log.error("finally, 关闭socket");
////            if (br != null) {
////                try {
////                    br.close();
////                } catch (IOException e) {
////                }
////            }
////
////            if (pw != null) {
////                pw.close();
////            }
////
////            if (socket != null) {
////                try {
////                    socket.close();
////                } catch (IOException e) {
////                }
////            }
////        }
//
////      System.out.println(JSON.toJSONString(json, true));
//
////        if (json.containsKey("Registrar WHOIS Server")
////                && !whoisServer.equals(json.getString("Registrar WHOIS Server"))) {
////            log.error("找到下一级whois server：" + json.getString("Registrar WHOIS Server"));
////            return getJson(domain, json.getString("Registrar WHOIS Server"));
////        }
//
//	}

//	public static JSONObject getJson(String domain) {
//		String topDomain = DomainUtils.getFirstLevelDomain(domain);
//
//		String whoisServer = getWhoisServer(topDomain);
//		if (whoisServer == null) {
//			return null;
//		}
//
//		JSONObject result = getJson(topDomain, whoisServer);
//		if (result == null) {
//			return null;
//		}
//
//		if (result.containsKey("Registrar WHOIS Server")) {
//			String newServer = result.getString("Registrar WHOIS Server");
//
//			if (!whoisServer.equals(newServer)) {
//				result = getJson(topDomain, newServer);
//			}
//		}
//
//		return result;
//	}

//	public static Domain getDomain(String domainName) {
//		domainName = getMainDomain(domainName);
//		
//		JSONObject json = getJson(domainName);
//		if (json == null) {
//			return null;
//		}
//		
//		String registrar = null;
//		String dateStr = null;
//		if (json.containsKey("Registrar")) {
//			registrar = json.getString("Registrar");
//		}
//		
//		if (json.containsKey("Registry Expiry Date")) {
//			dateStr = json.getString("Registry Expiry Date");
//		} else if (json.containsKey("Registrar Registration Expiration Date")) {
//			dateStr = json.getString("Registrar Registration Expiration Date");
//		}
//		
//		if (registrar == null || dateStr == null) {
//			return null;
//		}
//
//		try {
//			Domain d = new Domain();
//			d.setName(domainName);
//			d.setExpireDate(DateUtils.parseDate(dateStr, "yyyy-MM-dd'T'HH:mm:ss'Z'"));
//			d.setSource(registrar);
//			return d;
//		} catch (ParseException e) {
//			e.printStackTrace();
//			return null;
//		}
//	}

//	private static String getMainDomain(String domainName) {
//		if (domainName.indexOf(".") == domainName.lastIndexOf(".")) {
//			return domainName;
//		}
//
//		int idx1 = domainName.lastIndexOf(".");
//		int idx2 = domainName.substring(0, idx1).lastIndexOf(".");
//		return domainName.substring(idx2 + 1);
//	}

//	public static void main(String[] args) {
////        System.out.println(getMainDomain("www.store1.fastcommerce.info"));
////        request("b4.com", "whois.ionos.com");
//		getJson("bq.com");
//	}

//	public static String getDetail(String domain) {
//		Socket socket = null;
//		StringBuffer result = new StringBuffer();
//		JSONObject json = new JSONObject();
//
//		try {
////			socket = new Socket("whois.cnnic.cn", 43);
//			socket = new Socket("whois.verisign-grs.com", 43);
//
//			PrintWriter pw = new PrintWriter(socket.getOutputStream());
//			pw.println(domain);
//			pw.flush();
//
//			BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
//			String line = null;
//			while ((line = br.readLine()) != null) {
//				result.append(line).append("\n");
//				if (StringUtils.isNotBlank(line) && line.contains(": ")) {
//					String[] ss = line.split(": ");
//					if (ss.length == 2) {
//						if (json.containsKey(ss[0].trim())) {
//							Object val = json.get(ss[0].trim());
//							if (val instanceof String) {
//								json.put(ss[0].trim(), new String[] { val.toString(), ss[1].trim() });
//							} else if (val instanceof String[]) {
//								String[] arr = (String[]) val;
//								String[] newVal = new String[arr.length + 1];
//								for (int i = 0; i < arr.length; i++) {
//									newVal[i] = arr[i];
//								}
//								newVal[arr.length] = ss[1].trim();
////								String[] newVal = (String[]) val;
////								newVal
//								json.put(ss[0].trim(), newVal);
//							}
////							String[] val = new String[] {json.getString(ss[0].trim()), ss[1].trim()};
//
//						} else {
//							json.put(ss[0].trim(), ss[1].trim());
//						}
//					}
//				}
//			}
//		} catch (UnknownHostException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		} finally {
//			if (socket != null) {
//				try {
//					socket.close();
//				} catch (IOException e) {
//				}
//			}
//		}
//
//		System.out.println(JSON.toJSONString(json, true));
//
//		return result.toString();
//	}

}
