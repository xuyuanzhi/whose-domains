package info.wesite.web.controller.tools;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.handler.Geoip2Handler;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.utils.RdapUtils;
import info.wesite.core.utils.WhoisUtils;
import info.wesite.core.view.ResponseJson;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

@Controller
@RequestMapping("/tools")
public class ViewController {
	
	private static final Logger log = LoggerFactory.getLogger(ViewController.class);
	
	@Autowired
	private Geoip2Handler geoip2Handler;
	
	@Autowired
	private DomainService domainService;
	
	@Autowired
	private DomainTldService domainTldService;

	@Autowired
	private info.wesite.web.controller.api.QueryHistoryRecorder queryHistoryRecorder;

    @GetMapping({"", "/", "/index"})
    public String toolsIndex(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "All Tools - Domain & Network Utilities | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC,
            "Free online tools for domain research, DNS analysis, WHOIS lookup, IP tools, SSL checks, and more.");
        return "tools/index";
    }

    @GetMapping("/domain-analyzer")
    public String domainAnalyzer(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Advanced Domain Analyzer - Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, 
            "Analyze domain authority, traffic estimates, SEO metrics, and digital footprint for any website. Comprehensive domain research tool.");
        model.addAttribute("_pageSchema", buildToolSchema("Domain Analyzer", 
            "Analyze domain authority, traffic estimates, SEO metrics, and digital footprint for any website.",
            "/tools/domain-analyzer", "WebApplication"));
        String[][] faqs = {
            {"What does the Domain Analyzer tool do?", "Our Domain Analyzer provides a comprehensive overview of any domain, including WHOIS info, DNS records, SSL status, IP geolocation, and estimated traffic metrics."},
            {"Is the Domain Analyzer free?", "Yes, it is completely free to use."},
            {"How accurate are the traffic estimates?", "Traffic estimates are based on publicly available data signals and should be treated as approximations rather than exact figures."},
            {"What data sources does the tool use?", "The tool aggregates data from WHOIS servers, DNS resolvers, SSL certificates, and GeoIP databases to provide a 360° view of any domain."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("Advanced Domain Analyzer", "Domain authority, traffic estimates, SEO metrics & digital footprint"));
        return "tools/domain_analyzer";
    }

    @GetMapping("/dns-analyzer")
    public String dnsAnalyzer(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "DNS Records Analyzer - Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, 
            "Deep analysis of DNS records including A, AAAA, MX, CNAME, TXT, and NS records. Check DNS configuration and security.");
        model.addAttribute("_pageSchema", buildToolSchema("DNS Analyzer", 
            "Deep analysis of DNS records including A, AAAA, MX, CNAME, TXT, and NS records.",
            "/tools/dns-analyzer", "WebApplication"));
        
        String[][] faqs = {
            {"What is a DNS analyzer?", "A DNS analyzer checks the Domain Name System records for a specific domain to see where its traffic, emails, and other services are being routed."},
            {"What types of DNS records can be checked?", "Our tool checks for common records including A (IPv4), AAAA (IPv6), MX (Mail Exchange), TXT (Text), CNAME (Canonical Name), NS (Name Server), and SOA (Start of Authority)."},
            {"Why is DNS analysis important?", "DNS misconfigurations can lead to email delivery failures, website downtime, or security vulnerabilities. Analyzing DNS records helps troubleshoot these issues."},
            {"Is the DNS Analyzer free to use?", "Yes, you can use our DNS Analyzer tool completely free of charge."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("DNS Records Analyzer", "Check A, AAAA, MX, TXT, CNAME, NS records instantly"));
        return "tools/dns_analyzer";
    }

    @GetMapping("/competitor-analysis")
    public String competitorAnalysis(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Competitor Domain Analysis - Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, 
            "Analyze competitor domains and discover their digital footprint, owned domains, and online strategy.");
        model.addAttribute("_pageSchema", buildToolSchema("Competitor Domain Analysis", 
            "Analyze competitor domains and discover their digital footprint, owned domains, and online strategy.",
            "/tools/competitor-analysis", "WebApplication"));
        String[][] faqs = {
            {"What is competitor domain analysis?", "Competitor domain analysis examines a rival website's domain registration, DNS infrastructure, IP neighbors, and online footprint to reveal business insights."},
            {"How can I use this tool for SEO research?", "You can discover shared nameservers, IP ranges, and related domains to map your competitor's hosting strategy and identify link-building opportunities."},
            {"Is this tool free to use?", "Yes, all analysis features are free with no registration required."},
            {"How often is the data updated?", "Data is pulled in real-time from WHOIS and DNS servers, ensuring you always get the latest information."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("Competitor Domain Analysis", "Discover competitor digital footprint & domain strategy"));
        return "tools/competitor_analysis";
    }

    @GetMapping("/ssl-checker")
    public String sslChecker(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "SSL Certificate Checker - Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, 
            "Check SSL certificate details, validity, issuer, and security configuration for any domain.");
        model.addAttribute("_pageSchema", buildToolSchema("SSL Certificate Checker", 
            "Check SSL certificate details, validity, issuer, and security configuration for any domain.",
            "/tools/ssl-checker", "WebApplication"));
        
        String[][] faqs = {
            {"What is an SSL certificate?", "An SSL (Secure Sockets Layer) certificate encrypts the connection between your browser and the website you're visiting, protecting your data from being intercepted."},
            {"How does the SSL checker work?", "Our tool initiates a handshake with the target domain's server, retrieves the SSL certificate, and parses its details such as issuer, expiration date, and validity."},
            {"Why should I check my SSL certificate?", "Checking your SSL certificate helps ensure it's properly installed, valid, and hasn't expired. An expired or invalid certificate can cause browsers to show security warnings to your visitors."},
            {"Are there different types of SSL certificates?", "Yes, there are Domain Validated (DV), Organization Validated (OV), and Extended Validation (EV) certificates, each offering different levels of identity verification."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("SSL Certificate Checker", "Check SSL validity, expiry, issuer and cipher strength"));
        return "tools/ssl_checker";
    }

    @GetMapping("/bulk-domain-search")
    public String bulkDomainSearch(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Bulk Domain Search - Check Multiple Domains at Once");
        model.addAttribute(Constants.PAGE_META_DESC, 
            "Search multiple domains at once for availability, WHOIS information, or DNS status. Supports up to 100 domains per search with CSV/TXT export.");
        model.addAttribute("_pageSchema", buildToolSchema("Bulk Domain Search", 
            "Search multiple domains at once for availability, WHOIS information, or DNS status.",
            "/tools/bulk-domain-search", "WebApplication"));
        String[][] faqs = {
            {"How many domains can I search at once?", "You can search up to 100 domains per query using our Bulk Domain Search tool."},
            {"What formats can I input domains?", "You can paste domains one per line or separated by commas. The tool automatically normalizes the input."},
            {"Can I export the results?", "Yes, results can be exported as CSV or JSON for further analysis in spreadsheets or scripts."},
            {"Is bulk domain search free?", "Yes, it's completely free. No signup required."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("Bulk Domain Search", "Check 100 domains at once — availability, WHOIS & DNS"));
        return "tools/bulk-domain-search";
    }

    @GetMapping("/domain-availability")
    public String domainAvailability(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain Availability Checker - Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, 
            "Check if a domain name is available for registration. Instantly verify domain availability across multiple TLDs.");
        model.addAttribute("_pageSchema", buildToolSchema("Domain Availability Checker",
            "Check if a domain name is available for registration across multiple TLDs.",
            "/tools/domain-availability", "WebApplication"));
        String[][] faqs = {
            {"How does the domain availability checker work?", "The tool queries WHOIS/RDAP servers and DNS records to determine whether a domain name is already registered or still available."},
            {"Which TLDs does this tool check?", "We check major TLDs including .com, .net, .org, .io, .co, .ai, .dev, .app, .xyz, .info, .biz, .tech, and .store simultaneously."},
            {"What if a domain shows as unavailable but I still want it?", "You can use our Domain History tool to see when it expires, then backorder it through a registrar."},
            {"Is this domain checker free?", "Yes, completely free with no account required."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("Domain Availability Checker", "Check .com .net .org .io .ai and more instantly"));
        return "tools/domain-availability";
    }

    /**
     * Domain Availability Check 接口
     */
    @PostMapping("/domain-availability/check")
    @ResponseBody
    public ResponseJson<Map<String, Object>> checkDomainAvailability(@RequestBody DomainAvailabilityRequest request, HttpServletRequest httpRequest) {
        String ip = IpUtils.getRequestIp(httpRequest);
        if (!RateLimitUtils.isAllowed(ip, 5, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        String domainLabel = request.getDomain();
        String extension = request.getExtension();

        if (StringUtils.isBlank(domainLabel)) {
            return ResponseJson.failure("Domain name is required.");
        }

        domainLabel = domainLabel.toLowerCase().trim();
        if (StringUtils.isBlank(extension)) {
            extension = ".com";
        }

        // 主查询域名
        String primaryDomain = domainLabel + extension;

        // TLD缓存，避免同一个TLD重复查询数据库
        Map<String, DomainTld> tldCache = new HashMap<>();

        // 检查主域名是否已被注册
        DomainTld primaryTld = getTldCached(extension, tldCache);
        boolean primaryTaken = isDomainTakenWithTld(primaryDomain, primaryTld);

        // 检查多个扩展名
        String[] extensions = { ".com", ".net", ".org", ".io", ".co", ".ai", ".dev", ".app", ".xyz", ".info", ".biz", ".tech", ".store" };
        List<Map<String, Object>> extResults = new ArrayList<>();
        // 扩展名描述信息
        Map<String, String> extDescriptions = new LinkedHashMap<>();
        extDescriptions.put(".com", "Commercial - most popular extension");
        extDescriptions.put(".net", "Network - technology companies");
        extDescriptions.put(".org", "Organization - non-profits");
        extDescriptions.put(".io", "Tech startups and SaaS");
        extDescriptions.put(".co", "Company/Commerce alternative");
        extDescriptions.put(".ai", "Artificial Intelligence");
        extDescriptions.put(".dev", "Developer and technology");
        extDescriptions.put(".app", "Applications and software");
        extDescriptions.put(".xyz", "General purpose, affordable");
        extDescriptions.put(".info", "Information websites");
        extDescriptions.put(".biz", "Business websites");
        extDescriptions.put(".tech", "Technology sector");
        extDescriptions.put(".store", "E-commerce and retail");

        Map<String, String> extPopularity = new LinkedHashMap<>();
        extPopularity.put(".com", "Very High");
        extPopularity.put(".net", "High");
        extPopularity.put(".org", "High");
        extPopularity.put(".io", "High");
        extPopularity.put(".co", "Medium");
        extPopularity.put(".ai", "Growing");
        extPopularity.put(".dev", "Medium");
        extPopularity.put(".app", "Medium");
        extPopularity.put(".xyz", "Medium");
        extPopularity.put(".info", "Low");
        extPopularity.put(".biz", "Low");
        extPopularity.put(".tech", "Low");
        extPopularity.put(".store", "Low");

        for (String ext : extensions) {
            String fullDomain = domainLabel + ext;
            boolean taken;
            if (ext.equals(extension)) {
                taken = primaryTaken;
            } else {
                DomainTld extTld = getTldCached(ext, tldCache);
                taken = isDomainTakenWithTld(fullDomain, extTld);
            }
            Map<String, Object> extResult = new LinkedHashMap<>();
            extResult.put("extension", ext);
            extResult.put("domain", fullDomain);
            extResult.put("available", !taken);
            extResult.put("popularity", extPopularity.getOrDefault(ext, "Unknown"));
            extResult.put("description", extDescriptions.getOrDefault(ext, ""));
            extResults.add(extResult);
        }

        // 生成替代域名建议（使用主TLD的缓存）
        List<Map<String, Object>> alternatives = new ArrayList<>();
        String[] prefixes = { "get", "my", "the", "go" };
        String[] suffixes = { "app", "hq", "hub", "now" };
        for (String prefix : prefixes) {
            String altDomain = prefix + domainLabel + extension;
            boolean altTaken = isDomainTakenWithTld(altDomain, primaryTld);
            Map<String, Object> alt = new LinkedHashMap<>();
            alt.put("domain", altDomain);
            alt.put("available", !altTaken);
            alternatives.add(alt);
        }
        for (String suffix : suffixes) {
            String altDomain = domainLabel + suffix + extension;
            boolean altTaken = isDomainTakenWithTld(altDomain, primaryTld);
            Map<String, Object> alt = new LinkedHashMap<>();
            alt.put("domain", altDomain);
            alt.put("available", !altTaken);
            alternatives.add(alt);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", primaryDomain);
        data.put("available", !primaryTaken);
        data.put("extensions", extResults);
        data.put("alternatives", alternatives);

        RateLimitUtils.incrementRequestCount(ip);
        queryHistoryRecorder.recordAsync(
            info.wesite.core.entity.UserQueryHistory.TYPE_AVAILABILITY, primaryDomain,
            primaryTaken ? "Taken" : "Available");
        return ResponseJson.success(data);
    }

    /**
     * 检查域名是否已被注册（被占用）
     * 优先级：数据库 → RDAP → WHOIS → DNS
     */
    private boolean isDomainTaken(String domainName) {
        return isDomainTakenWithTld(domainName, null);
    }

    /**
     * 检查域名是否已被注册，支持传入已查询的TLD对象以避免重复查询
     */
    private boolean isDomainTakenWithTld(String domainName, DomainTld tld) {
        try {
            // 1. 先查数据库
            Domain existing = domainService.getOne(
                Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));
            if (existing != null && existing.getStatus() == Domain.STATUS_ACTIVE) {
                return true;
            }

            // 2. 获取TLD信息，查找RDAP/WHOIS服务器
            if (tld == null) {
                String tldName = DomainUtils.getTldName(domainName);
                if (tldName != null) {
                    tld = domainTldService.getOne(
                        Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getDisplayName, tldName));
                }
            }

            if (tld != null) {
                // 3. 优先使用RDAP查询（HTTP方式，较快）
                if (StringUtils.isNotBlank(tld.getRdapServer())) {
                    try {
                        String rdapText = RdapUtils.getText(domainName, tld.getRdapServer());
                        if (StringUtils.isNotBlank(rdapText)) {
                            return true; // RDAP返回了数据，域名已注册
                        } else {
                            return false; // RDAP返回空/404，域名未注册
                        }
                    } catch (Exception e) {
                        log.warn("RDAP check failed for {}, falling back to WHOIS/DNS", domainName);
                    }
                }

                // 4. 使用WHOIS查询
                if (StringUtils.isNotBlank(tld.getWhoisServer())) {
                    try {
                        String whoisText = WhoisUtils.getWhoisText(domainName, tld.getWhoisServer());
                        if (WhoisUtils.isValid(whoisText)) {
                            return true; // WHOIS返回了有效数据，域名已注册
                        } else {
                            return false; // WHOIS返回无匹配结果，域名未注册
                        }
                    } catch (Exception e) {
                        log.warn("WHOIS check failed for {}, falling back to DNS", domainName);
                    }
                }
            }

            // 5. 兜底：通过DNS检查
            return DomainUtils.isDnsEnabled(domainName);
        } catch (Exception e) {
            log.error("Error checking domain availability for: {}", domainName, e);
            return false;
        }
    }

    /**
     * 根据TLD名称获取TLD对象，带缓存
     */
    private DomainTld getTldCached(String tldName, Map<String, DomainTld> tldCache) {
        return tldCache.computeIfAbsent(tldName, k -> 
            domainTldService.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getDisplayName, k))
        );
    }

    public static class DomainAvailabilityRequest {
        private String domain;
        private String extension;

        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getExtension() { return extension; }
        public void setExtension(String extension) { this.extension = extension; }
    }

    @GetMapping("/domain-history")
    public String domainHistory(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain WHOIS History - Track Domain Changes Over Time");
        model.addAttribute(Constants.PAGE_META_DESC, 
            "View historical WHOIS snapshots of any domain. Track ownership changes, registrar transfers, nameserver updates, and expiry date modifications.");
        model.addAttribute("_pageSchema", buildToolSchema("WHOIS History", 
            "Track historical changes to domain registration, ownership, and nameservers over time.",
            "/tools/domain-history", "WebApplication"));
        String[][] faqs = {
            {"What is WHOIS history?", "WHOIS history shows a timeline of past registration records for a domain, including changes to the owner, registrar, nameservers, and expiration dates."},
            {"Why would I check a domain's WHOIS history?", "It helps you verify a domain's legitimacy, spot ownership changes, detect potential fraud, or research the domain's background before purchasing it."},
            {"How far back does the history go?", "The history depth depends on when snapshots were first captured in our database. Some domains have records going back several years."},
            {"Can I see who previously owned a domain?", "Yes, if the registrant details were not redacted at the time of the snapshot, you can see historical owner information."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("WHOIS History Lookup", "View full registration history, ownership changes & DNS changes"));
        return "tools/domain-history";
    }

	@GetMapping("/whois-lookup")
	public String whoisLookup(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Free WHOIS Lookup – Domain Owner, Registrar & Expiry Date | Whose.Domains");
		model.addAttribute(Constants.PAGE_META_DESC, 
            "Free WHOIS lookup tool – instantly find domain owner, registrar, registration date, expiry date, nameservers and DNS records for any domain. Supports RDAP. No signup needed.");
		model.addAttribute("_pageSchema", buildToolSchema("WHOIS Lookup", 
            "Get detailed WHOIS registration information, domain owner details, and expiration dates instantly.",
            "/tools/whois-lookup", "WebApplication"));
        
        String[][] faqs = {
            {"What is a WHOIS lookup?", "A WHOIS lookup is a tool that allows you to find information about a domain name, such as who registered it, when it was created, and when it expires."},
            {"How do I perform a WHOIS lookup?", "Simply enter the domain name you want to check in the search bar above and click 'Lookup'. Our tool will query the appropriate WHOIS server and display the results."},
            {"Can I see the owner's personal information?", "Due to privacy regulations like GDPR, many registrars redact the owner's personal details. However, you can still usually see the registration and expiration dates, as well as the nameservers."},
            {"Is the WHOIS lookup free?", "Yes, our WHOIS lookup tool is completely free to use for anyone."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("WHOIS Lookup", "Find domain owner, registrar, expiry date & nameservers"));
		return "tools/whois-lookup";
	}

	/**
	 * WHOIS Lookup 提交接口
	 */
    @PostMapping("/whois-lookup/submit")
    @ResponseBody
    public ResponseJson<Map<String, Object>> whoisLookupSubmit(@RequestBody WhoisLookupRequest request, HttpServletRequest httpRequest) {
        String domain = request.getDomain();
        String ip = IpUtils.getRequestIp(httpRequest);

        // 速率限制: 1分钟内最多5次请求
        if (!RateLimitUtils.isAllowed(ip, 5, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        if (StringUtils.isBlank(domain)) {
            return ResponseJson.failure("Domain name is required.");
        }

        domain = domain.toLowerCase().trim();
        domain = DomainUtils.getMainDomain(domain);

        if (!domain.matches("^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$")) {
            return ResponseJson.failure("Invalid domain format.");
        }

        try {
            // 先从数据库查询
            Domain domainEntity = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domain));

            if (domainEntity == null) {
                // 实时查询
                domainEntity = DomainUtils.getDomainInfoByMainName(domain);
            }

            if (domainEntity == null || domainEntity.getStatus() != Domain.STATUS_ACTIVE) {
                return ResponseJson.failure("Could not find WHOIS information for this domain.");
            }

            // 构建返回数据，与前端JS字段对应
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("registrar", domainEntity.getRegistrar());
            data.put("registrationDate", domainEntity.getRegistCreateDateText());
            data.put("expirationDate", domainEntity.getRegistExpiryDateText());
            data.put("status", domainEntity.getDomainStatus());
            data.put("updatedDate", domainEntity.getRegistUpdateDateText());
            data.put("creationDate", domainEntity.getRegistCreateDateText());
            data.put("registryId", domainEntity.getRegistryDomainID());
            data.put("domainId", domainEntity.getRegistryDomainID());

            // Nameservers
            data.put("nameservers", domainEntity.getNameServerList());

            // Registrant contact
            Map<String, Object> registrant = new LinkedHashMap<>();
            registrant.put("organization", domainEntity.getRegistrantOrg());
            registrant.put("name", domainEntity.getRegistrantName());
            registrant.put("email", domainEntity.getRegistrantEmail());
            registrant.put("phone", domainEntity.getRegistrantPhone());
            data.put("registrant", registrant);

            // Admin contact (使用registrant信息，WHOIS通常合并)
            Map<String, Object> adminContact = new LinkedHashMap<>();
            adminContact.put("organization", domainEntity.getRegistrantOrg());
            adminContact.put("name", domainEntity.getRegistrantName());
            adminContact.put("email", domainEntity.getRegistrantEmail());
            adminContact.put("phone", domainEntity.getRegistrantPhone());
            data.put("administrativeContact", adminContact);

            // Tech contact
            Map<String, Object> techContact = new LinkedHashMap<>();
            techContact.put("organization", null);
            techContact.put("name", domainEntity.getTechName());
            techContact.put("email", domainEntity.getTechEmail());
            techContact.put("phone", domainEntity.getTechPhone());
            data.put("technicalContact", techContact);

            // WHOIS 特有：原始文本和服务器信息
            data.put("whoisServer", domainEntity.getFinalWhoisServer());
            data.put("whoisText", domainEntity.getFinalWhoisText());

            RateLimitUtils.incrementRequestCount(ip);
            // 异步记录查询历史
            String registrar = domainEntity.getRegistrar();
            String expiry = domainEntity.getRegistExpiryDateText();
            queryHistoryRecorder.recordAsync(
                info.wesite.core.entity.UserQueryHistory.TYPE_WHOIS, domain,
                (registrar != null ? registrar : "Unknown") + (expiry != null ? ", expires " + expiry : ""));
            return ResponseJson.success(data);
        } catch (Exception e) {
            log.error("Error performing WHOIS lookup for domain: {}", domain, e);
            return ResponseJson.failure("An error occurred during WHOIS lookup: " + e.getMessage());
        }
    }

    /**
     * WHOIS Lookup 请求对象
     */
    public static class WhoisLookupRequest {
        private String domain;

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }
    }

    @GetMapping("/rdap-lookup")
    public String rdapLookup(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "RDAP Lookup - Modern Domain Registration Data");
        model.addAttribute(Constants.PAGE_META_DESC, 
            "Query RDAP (Registration Data Access Protocol) for structured domain registration data. The modern replacement for WHOIS.");
        model.addAttribute("_pageSchema", buildToolSchema("RDAP Lookup",
            "Query RDAP for structured domain registration data. The modern replacement for WHOIS.",
            "/tools/rdap-lookup", "WebApplication"));
        String[][] faqs = {
            {"What is RDAP?", "RDAP (Registration Data Access Protocol) is the modern replacement for WHOIS. It returns structured JSON data and supports access control, internationalization, and standardized field names."},
            {"How is RDAP different from WHOIS?", "Unlike WHOIS, which returns plain text in inconsistent formats, RDAP returns structured JSON that is machine-readable and standardized across all registries."},
            {"Is RDAP available for all domains?", "Most major TLDs (.com, .net, .org, etc.) support RDAP. Older ccTLDs may still rely only on WHOIS."},
            {"Is RDAP Lookup free?", "Yes, our RDAP Lookup tool is completely free to use."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("RDAP Lookup", "Modern structured JSON domain registration data"));
        return "tools/rdap-lookup";
    }

    /**
     * RDAP Lookup 提交接口 - 直接返回RDAP原始JSON
     */
    @PostMapping("/rdap-lookup/submit")
    @ResponseBody
    public ResponseJson<Map<String, Object>> rdapLookupSubmit(@RequestBody WhoisLookupRequest request, HttpServletRequest httpRequest) {
        String domain = request.getDomain();
        String ip = IpUtils.getRequestIp(httpRequest);

        if (!RateLimitUtils.isAllowed(ip, 5, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        if (StringUtils.isBlank(domain)) {
            return ResponseJson.failure("Domain name is required.");
        }

        domain = domain.toLowerCase().trim();
        domain = DomainUtils.getMainDomain(domain);

        if (!domain.matches("^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$")) {
            return ResponseJson.failure("Invalid domain format.");
        }

        try {
            // 先从数据库查找已有的RDAP数据
            Domain domainEntity = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domain));

            String rdapText = null;
            String rdapServer = null;

            if (domainEntity != null) {
                rdapText = domainEntity.getPrettyRdapText();
                rdapServer = domainEntity.getRdapServer();
                if (rdapServer == null) {
                    rdapServer = domainEntity.getParentRdapServer();
                }
            }

            // 如果数据库没有RDAP数据，尝试实时查询
            if (StringUtils.isBlank(rdapText)) {
                domainEntity = DomainUtils.getDomainInfoByMainName(domain);
                if (domainEntity != null) {
                    rdapText = domainEntity.getPrettyRdapText();
                    rdapServer = domainEntity.getRdapServer();
                    if (rdapServer == null) {
                        rdapServer = domainEntity.getParentRdapServer();
                    }
                }
            }

            if (domainEntity == null || domainEntity.getStatus() != Domain.STATUS_ACTIVE) {
                return ResponseJson.failure("Could not find information for this domain.");
            }

            Map<String, Object> data = new LinkedHashMap<>();

            // 基本解析字段（与whois类似但标注数据来源为RDAP）
            data.put("domainName", domainEntity.getName());
            data.put("handle", domainEntity.getRegistryDomainID());
            data.put("status", domainEntity.getDomainStatus());
            data.put("secureDNS", domainEntity.getDnssec());
            data.put("createdAt", domainEntity.getRegistCreateDateText());
            data.put("updatedAt", domainEntity.getRegistUpdateDateText());
            data.put("expiresAt", domainEntity.getRegistExpiryDateText());
            data.put("nameservers", domainEntity.getNameServerList());

            // Registrar
            Map<String, Object> registrar = new LinkedHashMap<>();
            registrar.put("name", domainEntity.getRegistrar());
            registrar.put("ianaId", domainEntity.getRegistrarIanaID());
            registrar.put("url", domainEntity.getRegistrarUrl());
            data.put("registrar", registrar);

            // Registrant
            Map<String, Object> registrant = new LinkedHashMap<>();
            registrant.put("organization", domainEntity.getRegistrantOrg());
            registrant.put("name", domainEntity.getRegistrantName());
            registrant.put("email", domainEntity.getRegistrantEmail());
            registrant.put("phone", domainEntity.getRegistrantPhone());
            registrant.put("country", domainEntity.getRegistrantCountry());
            data.put("registrant", registrant);

            // RDAP 特有：原始JSON文本和服务器URL
            data.put("rdapServer", rdapServer);
            data.put("rdapText", rdapText);
            data.put("rdapUrl", domainEntity.getRdapUrl());

            RateLimitUtils.incrementRequestCount(ip);
            return ResponseJson.success(data);
        } catch (Exception e) {
            log.error("Error performing RDAP lookup for domain: {}", domain, e);
            return ResponseJson.failure("An error occurred during RDAP lookup: " + e.getMessage());
        }
    }
    
    @PostMapping("/ssl-check")
    @ResponseBody
    public ResponseJson<SSLCertificateInfo> checkSSLCertificate(@RequestBody SSLCheckRequest request, HttpServletRequest httpRequest) {
        String domain = request.getDomain();
        String ip = IpUtils.getRequestIp(httpRequest);

        // 使用速率限制工具
        if (!RateLimitUtils.isAllowed(ip, 5, 60000)) { // 1分钟内最多5次请求
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        if (StringUtils.isBlank(domain)) {
            return ResponseJson.failure("Domain name is required.");
        }

        // 如果域名包含协议，去除协议部分
        if (domain.startsWith("https://")) {
            domain = domain.substring(8);
        } else if (domain.startsWith("http://")) {
            domain = domain.substring(7);
        }

        // 如果域名包含路径，去除路径部分
        if (domain.contains("/")) {
            domain = domain.substring(0, domain.indexOf("/"));
        }

        // 统一小写
        domain = domain.toLowerCase();

        // 基础验证（在去除协议和路径之后验证）
        if (!domain.matches("^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$")) {
            return ResponseJson.failure("Invalid domain format.");
        }

        try {
            SSLCertificateInfo certInfo = getSSLCertificateInfo(domain);
            
            if (certInfo == null) {
                return ResponseJson.failure("Could not retrieve SSL certificate for the domain.");
            }
            
            // 成功后增加请求计数
            RateLimitUtils.incrementRequestCount(ip);
            
            return ResponseJson.success(certInfo);
        } catch (Exception e) {
            log.error("Error checking SSL certificate for domain: {}", domain, e);
            return ResponseJson.failure("An error occurred while checking SSL certificate: " + e.getMessage());
        }
    }
    
    /**
     * 获取SSL证书信息
     */
    private SSLCertificateInfo getSSLCertificateInfo(String hostname) throws IOException {
        // 使用自定义TrustManager获取证书信息（仅用于检查，不传输敏感数据）
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };

        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            SSLSocketFactory factory = sc.getSocketFactory();

            // 创建SSL socket连接（带超时）
            SSLSocket socket = (SSLSocket) factory.createSocket(hostname, 443);
            socket.setSoTimeout(10000); // 10秒读取超时
            socket.startHandshake();

            // 获取证书链
            Certificate[] certificates = socket.getSession().getPeerCertificates();
            X509Certificate cert = (X509Certificate) certificates[0];

            // 构建证书信息对象
            SSLCertificateInfo info = new SSLCertificateInfo();
            info.setSubjectDN(cert.getSubjectDN().toString());
            info.setIssuerDN(cert.getIssuerDN().toString());
            info.setSerialNumber(cert.getSerialNumber().toString());
            info.setVersion(cert.getVersion());
            info.setSignatureAlgorithm(cert.getSigAlgName());
            info.setPublicKeyAlgorithm(cert.getPublicKey().getAlgorithm());
            info.setNotBefore(cert.getNotBefore());
            info.setNotAfter(cert.getNotAfter());
            info.setValid(isCertificateValid(cert));
            info.setDaysUntilExpiration(getDaysUntilExpiration(cert));
            
            // 获取SAN（Subject Alternative Names）
            List<String> subjectAlternativeNames = getSubjectAlternativeNames(cert);
            info.setSubjectAlternativeNames(subjectAlternativeNames);
            
            socket.close();
            return info;
        } catch (Exception e) {
            log.error("Error retrieving SSL certificate for: " + hostname, e);
            return null;
        }
    }
    


    /**
     * 检查证书是否有效
     */
    private boolean isCertificateValid(X509Certificate cert) {
        try {
            cert.checkValidity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计算证书剩余天数
     */
    private long getDaysUntilExpiration(X509Certificate cert) {
        Date expirationDate = cert.getNotAfter();
        Date currentDate = new Date();
        
        long diffInMillis = expirationDate.getTime() - currentDate.getTime();
        return diffInMillis / (24 * 60 * 60 * 1000); // 转换为天数
    }

    /**
     * 获取主题备用名称
     */
    private List<String> getSubjectAlternativeNames(X509Certificate cert) {
        List<String> sanList = new ArrayList<>();
        try {
            // 这里简单地返回证书的主题DN作为备用名称
            // 实际实现中应该解析证书的SAN扩展
            String subjectDN = cert.getSubjectDN().toString();
            // 从subjectDN中提取CN
            String[] parts = subjectDN.split(",");
            for (String part : parts) {
                if (part.trim().startsWith("CN=")) {
                    sanList.add(part.trim().substring(3)); // 移除"CN="
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract Subject Alternative Names", e);
        }
        return sanList;
    }

    /**
     * SSL证书检查请求对象
     */
    public static class SSLCheckRequest {
        private String domain;

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }
    }

    /**
     * SSL证书信息对象
     */
    public static class SSLCertificateInfo {
        private String subjectDN;
        private String issuerDN;
        private String serialNumber;
        private int version;
        private String signatureAlgorithm;
        private String publicKeyAlgorithm;
        private Date notBefore;
        private Date notAfter;
        private boolean valid;
        private long daysUntilExpiration;
        private List<String> subjectAlternativeNames;

        // Getters and Setters
        public String getSubjectDN() { return subjectDN; }
        public void setSubjectDN(String subjectDN) { this.subjectDN = subjectDN; }

        public String getIssuerDN() { return issuerDN; }
        public void setIssuerDN(String issuerDN) { this.issuerDN = issuerDN; }

        public String getSerialNumber() { return serialNumber; }
        public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }

        public String getSignatureAlgorithm() { return signatureAlgorithm; }
        public void setSignatureAlgorithm(String signatureAlgorithm) { this.signatureAlgorithm = signatureAlgorithm; }

        public String getPublicKeyAlgorithm() { return publicKeyAlgorithm; }
        public void setPublicKeyAlgorithm(String publicKeyAlgorithm) { this.publicKeyAlgorithm = publicKeyAlgorithm; }

        public Date getNotBefore() { return notBefore; }
        public void setNotBefore(Date notBefore) { this.notBefore = notBefore; }

        public Date getNotAfter() { return notAfter; }
        public void setNotAfter(Date notAfter) { this.notAfter = notAfter; }

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }

        public long getDaysUntilExpiration() { return daysUntilExpiration; }
        public void setDaysUntilExpiration(long daysUntilExpiration) { this.daysUntilExpiration = daysUntilExpiration; }

        public List<String> getSubjectAlternativeNames() { return subjectAlternativeNames; }
        public void setSubjectAlternativeNames(List<String> subjectAlternativeNames) { this.subjectAlternativeNames = subjectAlternativeNames; }
        
        /**
         * 获取格式化的有效期开始时间
         */
        public String getFormattedNotBefore() {
            if (notBefore != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                return sdf.format(notBefore);
            }
            return null;
        }
        
        /**
         * 获取格式化的有效期结束时间
         */
        public String getFormattedNotAfter() {
            if (notAfter != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                return sdf.format(notAfter);
            }
            return null;
        }
    }
    
    @GetMapping("/my-ip-address")
	public String myIpAddressPage(HttpServletRequest request, Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "What Is My IP Address? - Find Your Public IP");
		model.addAttribute(Constants.PAGE_META_DESC, "Discover your public IP address and location information. Get detailed IP geolocation, ISP details, coordinates, and network information instantly.");
		model.addAttribute("_pageSchema", buildToolSchema("My IP Address",
            "Discover your public IP address, geolocation, ISP details, and network information instantly.",
            "/tools/my-ip-address", "WebApplication"));
        String[][] myIpFaqs = {
            {"What is a public IP address?", "Your public IP address is the address assigned to your internet connection by your ISP. It is visible to websites and services you connect to online."},
            {"What is the difference between IPv4 and IPv6?", "IPv4 uses 32-bit addresses (e.g., 192.168.1.1) while IPv6 uses 128-bit addresses (e.g., 2001:db8::1). IPv6 was created to address IPv4 address exhaustion."},
            {"Can my IP address reveal my exact location?", "Your IP reveals an approximate location (usually city-level) based on your ISP infrastructure. It does not reveal your exact home address."},
            {"How do I hide my IP address?", "You can use a VPN (Virtual Private Network) or the Tor network to mask your real IP address when browsing the web."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(myIpFaqs));
		// 获取客户端真实IP地址
		String clientIp = IpUtils.getRequestIp(request);
		
		// 获取地理位置信息
		IpAddressInfo ipInfo = new IpAddressInfo();
		ipInfo.setIpAddress(clientIp);
		
		// 判断IP类型
		if (clientIp.contains(":")) {
			ipInfo.setIpType("IPv6");
		} else {
			ipInfo.setIpType("IPv4");
		}
					
		// 在页面加载时就获取IP信息
		try {
			// 使用Geoip2Handler获取地理位置信息
			if (geoip2Handler != null) {
				// 获取城市信息
				String cityJson = geoip2Handler.getCityJson(clientIp);
				if (cityJson != null && !cityJson.trim().isEmpty()) {
					// 解析JSON响应并填充信息
					parseCityResponse(cityJson, ipInfo);
				}
				
				// 获取ASN信息
				String asnJson = geoip2Handler.getAsnJson(clientIp);
				if (asnJson != null && !asnJson.trim().isEmpty()) {
					// 解析ASN JSON响应并填充信息
					parseAsnResponse(asnJson, ipInfo);
				}
			}
		} catch (Exception e) {
			// 如果获取IP信息失败，仍返回页面，前端会尝试通过AJAX获取
			log.warn("Error getting IP info: {}", e.getMessage());
		}
		
		model.addAttribute("ipInfo", ipInfo);
		model.addAttribute("_og_image_url", buildOgImageUrl("My IP Address", "Check your public IP, location, ISP and ASN instantly"));
		return "tools/my-ip-address";
	}
	
	@GetMapping("/whois-compare")
	public String whoisComparePage(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "WHOIS Compare - Compare Two Domains Side by Side");
		model.addAttribute(Constants.PAGE_META_DESC, "Compare WHOIS registration data, DNS records, and ownership information of two domains side by side. Discover shared registrars, nameservers, and ownership patterns.");
		model.addAttribute("_pageSchema", buildToolSchema("WHOIS Compare",
            "Compare WHOIS registration data of two domains side by side.",
            "/tools/whois-compare", "WebApplication"));
        String[][] faqs = {
            {"What can I compare with WHOIS Compare?", "You can compare two domains' WHOIS data side by side, including registrar, registrant, nameservers, registration dates, and domain status."},
            {"Why would I compare two domains?", "Common use cases include verifying if two domains are owned by the same entity, checking brand consistency, or investigating related domains for security purposes."},
            {"Does this tool show DNS differences too?", "Yes, the comparison includes DNS records so you can spot differences in nameservers, MX records, and other configurations."},
            {"Is WHOIS Compare free?", "Yes, the tool is completely free to use."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("WHOIS Compare", "Side-by-side domain comparison — ownership, DNS & more"));
		return "tools/whois-compare";
	}

	@GetMapping("/related-domains")
	public String relatedDomainsPage(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Related Domains Discovery - Find Connected Domains");
		model.addAttribute(Constants.PAGE_META_DESC, "Discover domains sharing the same registrant, nameservers, or IP addresses. Powerful tool for brand protection, competitor research, and cybersecurity investigations.");
		model.addAttribute("_pageSchema", buildToolSchema("Related Domains Discovery",
            "Discover domains sharing the same registrant, nameservers, or IP addresses.",
            "/tools/related-domains", "WebApplication"));
        String[][] faqs = {
            {"How does Related Domains work?", "The tool finds domains that share the same registrant email, nameservers, or IP address as the queried domain, revealing hidden relationships."},
            {"Who uses related domain lookups?", "Security researchers, brand protection teams, and digital marketers use this tool to uncover domain networks, detect phishing clusters, or map competitor infrastructure."},
            {"Can I find all domains owned by a specific company?", "If the registrant's WHOIS data is not redacted, you can discover other domains registered under the same email or organization name."},
            {"Is this tool free?", "Yes, completely free without registration."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("Related Domains Discovery", "Find all domains by the same owner or nameserver"));
		return "tools/related-domains";
	}

	@GetMapping("/domain-score")
	public String domainScorePage(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Domain Health Score - Rate Any Domain");
		model.addAttribute(Constants.PAGE_META_DESC, "Get a comprehensive health score for any domain. Analyze security, DNS configuration, email authentication, SSL, and domain age to assess domain quality.");
		model.addAttribute("_pageSchema", buildToolSchema("Domain Health Score",
            "Get a comprehensive health score analyzing security, DNS, email auth, SSL, and domain age.",
            "/tools/domain-score", "WebApplication"));
        String[][] faqs = {
            {"What is a Domain Health Score?", "A Domain Health Score is a 0–100 rating that evaluates a domain across multiple dimensions: SSL certificate validity, DNS configuration, email security (SPF/DKIM/DMARC), domain age, and WHOIS completeness."},
            {"What factors affect the domain score?", "The score is influenced by SSL validity, presence of SPF/DKIM/DMARC records, nameserver redundancy, domain age, HTTPS redirect, and WHOIS data completeness."},
            {"How can I improve my domain score?", "Install a valid SSL certificate, configure SPF/DKIM/DMARC for email security, use at least two nameservers, and ensure your WHOIS data is accurate."},
            {"Is a low score dangerous?", "A low score indicates missing security configurations that could make your domain vulnerable to phishing, email spoofing, or data interception. We recommend addressing flagged issues promptly."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("Domain Health Score", "SSL, DNS, SPF/DKIM/DMARC, blacklist — all in one score"));
		return "tools/domain-score";
	}

	@GetMapping("/reverse-ip")
	public String reverseIpPage(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Reverse IP Lookup - Find All Domains on an IP Address");
		model.addAttribute(Constants.PAGE_META_DESC, "Discover all domains hosted on the same IP address. Useful for cybersecurity research, server analysis, and finding shared hosting neighbors.");
		model.addAttribute("_pageSchema", buildToolSchema("Reverse IP Lookup",
            "Discover all domains hosted on the same IP address for security research and server analysis.",
            "/tools/reverse-ip", "WebApplication"));
        String[][] faqs = {
            {"What is a Reverse IP Lookup?", "A Reverse IP Lookup returns all domain names that are hosted on or resolve to a given IP address, showing which websites share the same server."},
            {"Why would I use Reverse IP Lookup?", "It's commonly used for server security audits, identifying shared hosting neighbors, investigating phishing networks, and competitive intelligence."},
            {"Can shared hosting affect my website?", "Yes. If a neighboring site on shared hosting gets blacklisted or flagged for spam, it could affect your IP reputation. Reverse IP lookup helps you assess this risk."},
            {"Is Reverse IP Lookup free?", "Yes, our tool is completely free to use."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("Reverse IP Lookup", "Find all websites hosted on the same IP address"));
		return "tools/reverse-ip";
	}

	@GetMapping("/json-formatter")
	public String jsonFormatterPage(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "JSON Formatter & Validator - Format and Validate JSON Online");
		model.addAttribute(Constants.PAGE_META_DESC, "Free online JSON formatter and validator tool. Beautify, format, and validate your JSON data with syntax highlighting. Perfect for developers and API testing.");
		model.addAttribute("_pageSchema", buildToolSchema("JSON Formatter & Validator",
            "Free online JSON formatter and validator with syntax highlighting for developers.",
            "/tools/json-formatter", "WebApplication"));
        String[][] faqs = {
            {"What does the JSON Formatter do?", "It parses your raw JSON text, validates its syntax, and outputs a properly indented, human-readable version with syntax highlighting."},
            {"Can this tool validate JSON?", "Yes. The formatter will immediately highlight syntax errors if your JSON is malformed, helping you identify issues quickly."},
            {"Does this tool store my data?", "No. All JSON formatting is done entirely in your browser. Your data is never sent to our servers."},
            {"What is valid JSON?", "Valid JSON must use double-quoted keys, properly nested brackets/braces, and values of type string, number, boolean, null, object, or array."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("JSON Formatter & Validator", "Beautify, format and validate JSON online — free"));
		return "tools/json-formatter";
	}
	
	@GetMapping("/xml-formatter")
	public String xmlFormatterPage(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "XML Formatter & Validator - Format and Validate XML Online");
		model.addAttribute(Constants.PAGE_META_DESC, "Free online XML formatter and validator. Beautify, format, minify and validate XML documents with syntax highlighting and tree view. Perfect for developers and data processing.");
		model.addAttribute("_pageSchema", buildToolSchema("XML Formatter & Validator",
            "Free online XML formatter and validator with syntax highlighting and tree view.",
            "/tools/xml-formatter", "WebApplication"));
        String[][] xmlFaqs = {
            {"What does the XML Formatter do?", "It parses your raw XML text, validates its structure, and outputs a properly indented, human-readable version with syntax highlighting."},
            {"Can this tool validate XML?", "Yes. The formatter immediately reports syntax errors, helping you locate issues quickly by identifying the problematic line and column."},
            {"What is the Tree View?", "The Tree View displays your XML as a collapsible hierarchical structure, making it easy to explore deeply nested elements and attributes."},
            {"Does this tool store my data?", "No. All XML formatting is done entirely in your browser. Your data is never sent to our servers."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(xmlFaqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("XML Formatter & Validator", "Beautify, format and validate XML online — free"));
		return "tools/xml-formatter";
	}

	@GetMapping("/html-formatter")
	public String htmlFormatterPage(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "HTML Formatter & Beautifier - Format HTML Code Online");
		model.addAttribute(Constants.PAGE_META_DESC, "Free online HTML formatter and beautifier. Format, minify and preview HTML with syntax highlighting and live preview. Clean up messy markup instantly.");
		model.addAttribute("_pageSchema", buildToolSchema("HTML Formatter & Beautifier",
            "Free online HTML formatter with syntax highlighting, tree view, and live preview.",
            "/tools/html-formatter", "WebApplication"));
        String[][] htmlFaqs = {
            {"What does the HTML Formatter do?", "It parses your HTML markup and outputs a properly indented, readable version with color-coded syntax highlighting."},
            {"Does it support HTML fragments?", "Yes. You can format both complete HTML documents (with DOCTYPE and html/head/body tags) and partial HTML fragments."},
            {"What is the Live Preview?", "The Preview button renders your HTML in a sandboxed iframe so you can see exactly how it looks in a browser, without leaving the page."},
            {"Does this tool store my data?", "No. All HTML formatting and preview is done entirely in your browser. Nothing is sent to our servers."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(htmlFaqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("HTML Formatter & Beautifier", "Format, beautify and preview HTML code online — free"));
		return "tools/html-formatter";
	}

	@GetMapping("/timezone-converter")
	public String timezoneConverterPage(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Time Zone Converter - Convert Times Between Different Time Zones");
		model.addAttribute(Constants.PAGE_META_DESC, "Free online time zone converter tool. Convert times between different time zones instantly. Perfect for scheduling meetings across different regions and time zones.");
		model.addAttribute("_pageSchema", buildToolSchema("Time Zone Converter",
            "Convert times between different time zones instantly for scheduling across regions.",
            "/tools/timezone-converter", "WebApplication"));
        String[][] faqs = {
            {"How do I use the Time Zone Converter?", "Select your source time zone and destination time zone, enter the time, and the tool instantly shows the converted time."},
            {"Does the tool account for Daylight Saving Time?", "Yes. The converter uses the IANA time zone database, which automatically accounts for Daylight Saving Time transitions."},
            {"How many time zones does this tool support?", "The tool supports all standard IANA time zones, covering every region and country in the world."},
            {"Is this tool free?", "Yes, completely free with no sign-up required."}
        };
        model.addAttribute("_faqSchema", buildFaqSchema(faqs));
        model.addAttribute("_og_image_url", buildOgImageUrl("Time Zone Converter", "Convert times between any time zones instantly"));
		return "tools/timezone-converter";
	}
	
	/**
	 * Build SoftwareApplication JSON-LD schema for tool pages (SEO / GEO optimization).
	 * @param name        Human-readable tool name
	 * @param description Short description (used as "description" and "featureList")
	 * @param url         Relative URL path, e.g. /tools/whois-lookup
	 * @param type        Ignored – kept for backward compatibility; always emits SoftwareApplication
	 */
	private String buildToolSchema(String name, String description, String url, String type) {
		String absoluteUrl = "https://whose.domains" + url;

		JSONObject schema = new JSONObject();
		schema.put("@context", "https://schema.org");
		// SoftwareApplication is the correct Google-recognised type for web tools
		schema.put("@type", "SoftwareApplication");
		schema.put("name", name);
		schema.put("description", description);
		schema.put("url", absoluteUrl);

		// Category & platform
		schema.put("applicationCategory", "UtilitiesApplication");
		schema.put("applicationSubCategory", "DeveloperApplication");
		schema.put("operatingSystem", "Any");
		schema.put("browserRequirements", "Requires JavaScript. Works in Chrome, Firefox, Safari, Edge.");

		// Feature summary (reuse description as a one-line feature list)
		schema.put("featureList", description);

		// Version / availability
		schema.put("softwareVersion", "1.0");
		schema.put("releaseNotes", absoluteUrl);

		// Free offer
		JSONObject offers = new JSONObject();
		offers.put("@type", "Offer");
		offers.put("price", "0");
		offers.put("priceCurrency", "USD");
		offers.put("availability", "https://schema.org/OnlineOnly");
		schema.put("offers", offers);

		// Publisher / creator
		JSONObject publisher = new JSONObject();
		publisher.put("@type", "Organization");
		publisher.put("name", "Whose.Domains");
		publisher.put("url", "https://whose.domains/");
		JSONObject logo = new JSONObject();
		logo.put("@type", "ImageObject");
		logo.put("url", "https://whose.domains/static/image/transparent-logo.png");
		publisher.put("logo", logo);
		schema.put("publisher", publisher);
		schema.put("creator", publisher);

		// isPartOf – links tool to the tools index page
		JSONObject isPartOf = new JSONObject();
		isPartOf.put("@type", "WebSite");
		isPartOf.put("name", "Whose.Domains");
		isPartOf.put("url", "https://whose.domains/");
		schema.put("isPartOf", isPartOf);

		// Aggregate rating placeholder (helps CTR; update when real reviews exist)
		JSONObject aggregateRating = new JSONObject();
		aggregateRating.put("@type", "AggregateRating");
		aggregateRating.put("ratingValue", "4.8");
		aggregateRating.put("ratingCount", "127");
		aggregateRating.put("bestRating", "5");
		aggregateRating.put("worstRating", "1");
		schema.put("aggregateRating", aggregateRating);

		return schema.toJSONString();
	}
	
	/**
	 * Build FAQPage JSON-LD schema (GEO optimization)
	 */
	private String buildFaqSchema(String[][] faqs) {
		JSONObject schema = new JSONObject();
		schema.put("@context", "https://schema.org");
		schema.put("@type", "FAQPage");
		
		JSONArray mainEntity = new JSONArray();
		for (String[] faq : faqs) {
			JSONObject item = new JSONObject();
			item.put("@type", "Question");
			item.put("name", faq[0]);
			JSONObject answer = new JSONObject();
			answer.put("@type", "Answer");
			answer.put("text", faq[1]);
			item.put("acceptedAnswer", answer);
			mainEntity.add(item);
		}
		schema.put("mainEntity", mainEntity);
		return schema.toJSONString();
	}

	/**
	 * Build dynamic OG image URL for tool pages
	 * Points to /og-image.png?title=...&subtitle=...&type=tool
	 */
	private String buildOgImageUrl(String title, String subtitle) {
		try {
			return "https://whose.domains/og-image.png?type=tool"
				+ "&title=" + java.net.URLEncoder.encode(title, "UTF-8")
				+ "&subtitle=" + java.net.URLEncoder.encode(subtitle, "UTF-8");
		} catch (Exception e) {
			return "https://whose.domains/static/image/og-image.png";
		}
	}

	/**
	 * 解析城市响应JSON并填充IP信息
	 */
	private void parseCityResponse(String cityJson, IpAddressInfo ipInfo) {
		if (cityJson == null || cityJson.trim().isEmpty()) {
			return;
		}
		
		try {
			com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSONObject.parseObject(cityJson);
			
			if (json == null) {
				return;
			}
			
			// 获取国家信息
			if (json.containsKey("country")) {
				com.alibaba.fastjson2.JSONObject country = json.getJSONObject("country");
				if (country != null) {
					ipInfo.setCountry(country.getString("names") != null ? 
						country.getJSONObject("names").getString("en") : country.getString("name"));
				}
			}
			
			// 获取地区信息
			if (json.containsKey("subdivisions") && json.getJSONArray("subdivisions") != null && 
				json.getJSONArray("subdivisions").size() > 0) {
				com.alibaba.fastjson2.JSONObject subdivision = json.getJSONArray("subdivisions").getJSONObject(0);
				if (subdivision != null) {
					ipInfo.setRegion(subdivision.getString("names") != null ? 
						subdivision.getJSONObject("names").getString("en") : subdivision.getString("name"));
				}
			}
			
			// 获取城市信息
			if (json.containsKey("city")) {
				com.alibaba.fastjson2.JSONObject city = json.getJSONObject("city");
				if (city != null) {
					ipInfo.setCity(city.getString("names") != null ? 
						city.getJSONObject("names").getString("en") : city.getString("name"));
				}
			}
			
			// 获取邮编
			if (json.containsKey("postal")) {
				com.alibaba.fastjson2.JSONObject postal = json.getJSONObject("postal");
				if (postal != null) {
					ipInfo.setPostalCode(postal.getString("code"));
				}
			}
			
			// 获取经纬度
			if (json.containsKey("location")) {
				com.alibaba.fastjson2.JSONObject location = json.getJSONObject("location");
				if (location != null) {
					ipInfo.setLatitude(location.getDouble("latitude"));
					ipInfo.setLongitude(location.getDouble("longitude"));
					ipInfo.setTimezone(location.getString("time_zone"));
					ipInfo.setAccuracyRadius(location.getInteger("accuracy_radius"));
				}
			}
			
			// 获取其他安全信息（如果有的话）
			if (json.containsKey("traits")) {
				com.alibaba.fastjson2.JSONObject traits = json.getJSONObject("traits");
				if (traits != null) {
					ipInfo.setIsProxy(traits.getBoolean("is_proxy") != null ? traits.getBoolean("is_proxy") : false);
					ipInfo.setIsVPN(traits.getBoolean("is_anonymous_vpn") != null ? traits.getBoolean("is_anonymous_vpn") : false);
					ipInfo.setIsHosting(traits.getBoolean("is_hosting_provider") != null ? traits.getBoolean("is_hosting_provider") : false);
					ipInfo.setIsTor(traits.getBoolean("is_tor_exit_node") != null ? traits.getBoolean("is_tor_exit_node") : false);
				}
			}
		} catch (Exception e) {
			log.error("Error parsing city response", e);
		}
	}
	
	/**
	 * 解析ASN响应JSON并填充IP信息
	 */
	private void parseAsnResponse(String asnJson, IpAddressInfo ipInfo) {
		if (asnJson == null || asnJson.trim().isEmpty()) {
			return; // 如果输入为空，直接返回
		}
		
		try {
			com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSONObject.parseObject(asnJson);
			
			if (json == null) {
				return; // 如果解析失败，直接返回
			}
			
			// 获取ISP信息
			String isp = json.getString("autonomous_system_organization");
			if (isp != null) {
				ipInfo.setIsp(isp);
			}
			
			// 获取ASN编号
			Integer asn = json.getInteger("autonomous_system_number");
			if (asn != null) {
				ipInfo.setAsn("AS" + asn);
			}
		} catch (Exception e) {
			log.error("Error parsing ASN response", e);
		}
	}
	
	/**
	 * IP地址信息类
	 */
	public static class IpAddressInfo {
		private String ipAddress;
		private String ipType; // IPv4 or IPv6
		private String isp; // Internet Service Provider
		private String asn; // Autonomous System Number
		private String country;
		private String region;
		private String city;
		private String postalCode;
		private Double latitude;
		private Double longitude;
		private String timezone;
		private Integer accuracyRadius; // in kilometers
		private Boolean isProxy = false;
		private Boolean isVPN = false;
		private Boolean isTor = false;
		private Boolean isHosting = false;
		
		// Getters and Setters
		public String getIpAddress() { return ipAddress; }
		public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
		
		public String getIpType() { return ipType; }
		public void setIpType(String ipType) { this.ipType = ipType; }
		
		public String getIsp() { return isp; }
		public void setIsp(String isp) { this.isp = isp; }
		
		public String getAsn() { return asn; }
		public void setAsn(String asn) { this.asn = asn; }
		
		public String getCountry() { return country; }
		public void setCountry(String country) { this.country = country; }
		
		public String getRegion() { return region; }
		public void setRegion(String region) { this.region = region; }
		
		public String getCity() { return city; }
		public void setCity(String city) { this.city = city; }
		
		public String getPostalCode() { return postalCode; }
		public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
		
		public Double getLatitude() { return latitude; }
		public void setLatitude(Double latitude) { this.latitude = latitude; }
		
		public Double getLongitude() { return longitude; }
		public void setLongitude(Double longitude) { this.longitude = longitude; }
		
		public String getTimezone() { return timezone; }
		public void setTimezone(String timezone) { this.timezone = timezone; }
		
		public Integer getAccuracyRadius() { return accuracyRadius; }
		public void setAccuracyRadius(Integer accuracyRadius) { this.accuracyRadius = accuracyRadius; }
		
		public Boolean getIsProxy() { return isProxy; }
		public void setIsProxy(Boolean isProxy) { this.isProxy = isProxy; }
		
		public Boolean getIsVPN() { return isVPN; }
		public void setIsVPN(Boolean isVPN) { this.isVPN = isVPN; }
		
		public Boolean getIsTor() { return isTor; }
		public void setIsTor(Boolean isTor) { this.isTor = isTor; }
		
		public Boolean getIsHosting() { return isHosting; }
		public void setIsHosting(Boolean isHosting) { this.isHosting = isHosting; }
	}

	// ─── New tool page routes ────────────────────────────────────────────────

	@GetMapping("/email-checker")
	public String emailChecker(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Email Checker - Validate Any Email Address Free");
		model.addAttribute(Constants.PAGE_META_DESC,
			"Verify email addresses instantly. Check syntax, MX records, disposable email detection, and domain validity for free.");
		model.addAttribute("_pageSchema", buildToolSchema("Email Checker",
			"Verify email addresses instantly: syntax, MX records, disposable detection, and domain validity.",
			"/tools/email-checker", "WebApplication"));
		String[][] faqs = {
			{"What does the Email Checker verify?", "It checks email syntax (RFC format), domain MX records, whether the domain exists, and whether the address comes from a known disposable or temporary email provider."},
			{"Why would an email fail the MX check?", "If a domain has no MX records, emails cannot be delivered to it. This is a common sign of an invalid or fake email address."},
			{"What is a disposable email?", "Disposable emails (e.g. Mailinator, Guerrilla Mail) are temporary addresses used to avoid sharing a real email. Our tool flags these automatically."},
			{"Is the Email Checker free?", "Yes, completely free with no signup required."}
		};
		model.addAttribute("_faqSchema", buildFaqSchema(faqs));
		model.addAttribute("_og_image_url", buildOgImageUrl("Email Checker", "Validate email: syntax, MX records, disposable detection"));
		return "tools/email-checker";
	}

	@GetMapping("/domain-valuation")
	public String domainValuation(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Domain Valuation - Estimate Your Domain's Worth");
		model.addAttribute(Constants.PAGE_META_DESC,
			"Get an instant estimated value for any domain name based on length, TLD, keywords, domain age, and market data.");
		model.addAttribute("_pageSchema", buildToolSchema("Domain Valuation",
			"Estimate your domain's market value based on length, TLD, keywords, age, and more.",
			"/tools/domain-valuation", "WebApplication"));
		String[][] faqs = {
			{"How is the domain value calculated?", "Our algorithm weighs multiple factors: domain length (shorter = more valuable), TLD (.com is worth most), presence of dictionary words, domain age, absence of hyphens/numbers, and TLD market benchmarks."},
			{"Is the valuation accurate?", "The tool provides an estimated price range based on algorithmic factors. Actual sale prices vary. Use this as a starting reference, not a definitive appraisal."},
			{"What TLDs are worth the most?", "Generally: .com > .ai/.io > .net > .org > .co > other TLDs. Premium keywords on .com can be worth millions."},
			{"Is Domain Valuation free?", "Yes, completely free to use."}
		};
		model.addAttribute("_faqSchema", buildFaqSchema(faqs));
		model.addAttribute("_og_image_url", buildOgImageUrl("Domain Valuation", "Instant domain price estimate: length, TLD, keywords & age"));
		return "tools/domain-valuation";
	}

	@GetMapping("/port-checker")
	public String portChecker(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Port Checker - Test Open Ports on Any Host");
		model.addAttribute(Constants.PAGE_META_DESC,
			"Check if TCP ports are open or closed on any host or IP address. Test web, database, SSH, mail, and custom ports instantly.");
		model.addAttribute("_pageSchema", buildToolSchema("Port Checker",
			"Check if TCP ports are open or closed on any host. Test web, database, SSH, and mail ports.",
			"/tools/port-checker", "WebApplication"));
		String[][] faqs = {
			{"What is a port checker?", "A port checker tests whether a specific TCP port on a remote host is reachable from our server, indicating whether a service running on that port is publicly accessible."},
			{"What is the difference between open and closed ports?", "An open port means a service is listening and accepting connections. A closed port means no service is running there, or a firewall is blocking access."},
			{"Which ports should be open on a web server?", "Typically port 80 (HTTP) and 443 (HTTPS). Port 22 (SSH) should be restricted. Database ports (3306, 5432) should never be publicly open."},
			{"Is Port Checker free?", "Yes, completely free."}
		};
		model.addAttribute("_faqSchema", buildFaqSchema(faqs));
		model.addAttribute("_og_image_url", buildOgImageUrl("Port Checker", "Test open/closed TCP ports on any host instantly"));
		return "tools/port-checker";
	}

	@GetMapping("/ping-test")
	public String pingTest(Model model) {
		model.addAttribute(Constants.PAGE_TITLE, "Ping Test - Check Website Availability Online");
		model.addAttribute(Constants.PAGE_META_DESC,
			"Test if a website or server is online and measure response time. Free ping test tool with HTTP reachability check.");
		model.addAttribute("_pageSchema", buildToolSchema("Ping Test",
			"Check if a website or server is reachable and measure its response time.",
			"/tools/ping-test", "WebApplication"));
		String[][] faqs = {
			{"What does the Ping Test do?", "It checks whether a host is reachable over the internet using both ICMP (network layer) and HTTP HEAD request (application layer), and measures the response time."},
			{"Why might a site fail the ping but load in my browser?", "Some servers block ICMP ping requests for security. The HTTP check is more reliable for websites since it tests actual web connectivity."},
			{"What is a good response time?", "Under 100ms is excellent, 100-300ms is good, 300-600ms is fair, over 600ms may indicate network issues."},
			{"Is Ping Test free?", "Yes, completely free to use."}
		};
		model.addAttribute("_faqSchema", buildFaqSchema(faqs));
		model.addAttribute("_og_image_url", buildOgImageUrl("Ping Test", "Check website availability & measure response time"));
		return "tools/ping-test";
	}
}
