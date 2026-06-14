package info.wesite.web.controller.api;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.utils.RdapUtils;
import info.wesite.core.utils.WhoisUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 批量域名查询 API
 */
@Tag(name = "Bulk Domain Search API")
@RestController
@RequestMapping("/api/tools")
public class BulkDomainSearchController {

    /** 按 sessionId → 最近一次查询结果，支持导出 */
    private final ConcurrentHashMap<String, List<Map<String, Object>>> lastResultCache = new ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(BulkDomainSearchController.class);

    private static final int MAX_DOMAINS = 100;
    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";

    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @Autowired
    private DomainService domainService;

    @Autowired
    private DomainTldService domainTldService;

    @Operation(summary = "批量域名查询")
    @PostMapping("/bulk-search")
    public ResponseJson<Map<String, Object>> bulkSearch(@RequestBody BulkSearchRequest request,
            HttpServletRequest httpRequest, HttpSession session) {
        String ip = IpUtils.getRequestIp(httpRequest);

        // 速率限制：每分钟最多3次批量查询
        if (!RateLimitUtils.isAllowed(ip, 3, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        if (request.getDomains() == null || request.getDomains().isEmpty()) {
            return ResponseJson.failure("Please provide at least one domain name.");
        }

        // 限制数量
        List<String> domains = request.getDomains().stream()
                .map(String::toLowerCase)
                .map(String::trim)
                .filter(d -> !d.isEmpty())
                .distinct()
                .limit(MAX_DOMAINS)
                .toList();

        if (domains.isEmpty()) {
            return ResponseJson.failure("No valid domain names found.");
        }

        String searchType = request.getSearchType() != null ? request.getSearchType() : "availability";

        try {
            List<Map<String, Object>> results = new ArrayList<>();
            int available = 0;
            int taken = 0;
            int errors = 0;

            // 并行查询各域名
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (String domain : domains) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    return checkSingleDomain(domain, searchType);
                }, executor));
            }

            // 等待所有结果，最多60秒
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);

            for (CompletableFuture<Map<String, Object>> f : futures) {
                Map<String, Object> result = f.get();
                results.add(result);

                String status = (String) result.get("status");
                if ("available".equals(status)) {
                    available++;
                } else if ("taken".equals(status) || "active".equals(status)) {
                    taken++;
                } else {
                    errors++;
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("results", results);

            Map<String, Object> summary = new HashMap<>();
            summary.put("total", domains.size());
            summary.put("available", available);
            summary.put("taken", taken);
            summary.put("errors", errors);
            response.put("summary", summary);

            // 成功后增加请求计数
            RateLimitUtils.incrementRequestCount(ip);

            // 缓存结果，供导出使用（按 sessionId）
            lastResultCache.put(session.getId(), results);

            return ResponseJson.success(response);
        } catch (Exception e) {
            log.error("Bulk domain search error", e);
            return ResponseJson.failure("Bulk search failed: " + e.getMessage());
        }
    }

    /**
     * 导出最近一次批量查询结果
     * GET /api/tools/bulk-search/export?format=csv  or  ?format=json
     */
    @Operation(summary = "导出批量查询结果 (CSV / JSON)")
    @GetMapping("/bulk-search/export")
    public void exportResults(@RequestParam(defaultValue = "csv") String format,
            HttpSession session, HttpServletResponse response) throws Exception {

        List<Map<String, Object>> results = lastResultCache.get(session.getId());
        if (results == null || results.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write("No results to export. Please run a bulk search first.");
            return;
        }

        if ("json".equalsIgnoreCase(format)) {
            response.setContentType("application/json");
            response.setHeader("Content-Disposition", "attachment; filename=\"bulk-domain-results.json\"");
            com.alibaba.fastjson2.JSONArray arr = new com.alibaba.fastjson2.JSONArray();
            for (Map<String, Object> r : results) arr.add(r);
            response.getWriter().write(arr.toJSONString());
        } else {
            // Default CSV
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"bulk-domain-results.csv\"");
            PrintWriter pw = response.getWriter();
            // Header
            pw.println("Domain,Status,Details,Registrar,ExpiryDate,CreationDate");
            for (Map<String, Object> r : results) {
                String domain     = csvEscape(String.valueOf(r.getOrDefault("domain", "")));
                String status     = csvEscape(String.valueOf(r.getOrDefault("status", "")));
                String details    = csvEscape(String.valueOf(r.getOrDefault("details", "")));
                String registrar  = csvEscape(String.valueOf(r.getOrDefault("registrar", "")));
                String expiry     = csvEscape(String.valueOf(r.getOrDefault("expiryDate", "")));
                String creation   = csvEscape(String.valueOf(r.getOrDefault("creationDate", "")));
                pw.println(domain + "," + status + "," + details + "," + registrar + "," + expiry + "," + creation);
            }
            pw.flush();
        }
    }

    private String csvEscape(String val) {
        if (val == null || "null".equals(val)) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    /**
     * 检查单个域名
     */
    private Map<String, Object> checkSingleDomain(String domainName, String searchType) {
        Map<String, Object> result = new HashMap<>();
        result.put("domain", domainName);

        // 验证域名格式
        if (!domainName.matches(DOMAIN_REGEX)) {
            result.put("status", "error");
            result.put("details", "Invalid domain format");
            return result;
        }

        try {
            // 获取主域名
            String mainDomain = DomainUtils.getMainDomain(domainName);

            switch (searchType) {
                case "availability":
                    return checkAvailability(domainName, mainDomain, result);
                case "whois":
                    return checkWhois(domainName, mainDomain, result);
                case "dns":
                    return checkDns(domainName, result);
                default:
                    return checkAvailability(domainName, mainDomain, result);
            }
        } catch (Exception e) {
            log.warn("Error checking domain {}: {}", domainName, e.getMessage());
            result.put("status", "error");
            result.put("details", "Check failed: " + e.getMessage());
            return result;
        }
    }

    /**
     * 可用性检查：数据库 → RDAP → WHOIS → DNS
     */
    private Map<String, Object> checkAvailability(String domainName, String mainDomain,
            Map<String, Object> result) {
        // 1. 先从数据库查
        Domain domain = domainService.getOne(
                Wrappers.<Domain>lambdaQuery().eq(Domain::getName, mainDomain));

        if (domain != null && domain.getStatus() == Domain.STATUS_ACTIVE) {
            result.put("status", "taken");
            result.put("details", "Domain is registered");
            result.put("registrar", domain.getRegistrar());
            result.put("expiryDate", domain.getRegistExpiryDateText());
            return result;
        }

        // 2. 获取TLD信息，查找RDAP/WHOIS服务器
        String tldName = DomainUtils.getTldName(mainDomain);
        DomainTld tld = null;
        if (tldName != null) {
            tld = domainTldService.getOne(
                Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getDisplayName, tldName));
        }

        if (tld != null) {
            // 3. 优先使用RDAP查询
            if (StringUtils.isNotBlank(tld.getRdapServer())) {
                try {
                    String rdapText = RdapUtils.getText(mainDomain, tld.getRdapServer());
                    if (StringUtils.isNotBlank(rdapText)) {
                        result.put("status", "taken");
                        result.put("details", "Domain is registered (RDAP verified)");
                        return result;
                    } else {
                        result.put("status", "available");
                        result.put("details", "Domain appears available for registration");
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("RDAP check failed for {}", mainDomain);
                }
            }

            // 4. 使用WHOIS查询
            if (StringUtils.isNotBlank(tld.getWhoisServer())) {
                try {
                    String whoisText = WhoisUtils.getWhoisText(mainDomain, tld.getWhoisServer());
                    if (WhoisUtils.isValid(whoisText)) {
                        result.put("status", "taken");
                        result.put("details", "Domain is registered (WHOIS verified)");
                        return result;
                    } else {
                        result.put("status", "available");
                        result.put("details", "Domain appears available for registration");
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("WHOIS check failed for {}", mainDomain);
                }
            }
        }

        // 5. 兜底：通过DNS检查
        boolean dnsEnabled = DomainUtils.isDnsEnabled(mainDomain);
        if (dnsEnabled) {
            result.put("status", "taken");
            result.put("details", "Domain has active DNS records");
            return result;
        }

        result.put("status", "available");
        result.put("details", "Domain appears available for registration");
        return result;
    }

    /**
     * WHOIS信息查询
     */
    private Map<String, Object> checkWhois(String domainName, String mainDomain,
            Map<String, Object> result) {
        Domain domain = domainService.getOne(
                Wrappers.<Domain>lambdaQuery().eq(Domain::getName, mainDomain));

        if (domain != null && domain.getStatus() == Domain.STATUS_ACTIVE) {
            result.put("status", "taken");
            result.put("details", "Registered");
            result.put("registrar", domain.getRegistrar());
            result.put("expiryDate", domain.getRegistExpiryDateText());
            result.put("creationDate", domain.getRegistCreateDateText());
            result.put("nameServers", domain.getNameServers());
            result.put("registrantOrg", domain.getRegistrantOrg());
            result.put("registrantCountry", domain.getRegistrantCountry());
        } else {
            // 尝试实时查询
            Domain newDomain = DomainUtils.getDomainInfoByMainName(mainDomain);
            if (newDomain != null) {
                result.put("status", "taken");
                result.put("details", "Registered");
                result.put("registrar", newDomain.getRegistrar());
                result.put("expiryDate", newDomain.getRegistExpiryDateText());
                result.put("creationDate", newDomain.getRegistCreateDateText());
                result.put("nameServers", newDomain.getNameServers());
            } else {
                result.put("status", "available");
                result.put("details", "Domain appears available");
            }
        }

        return result;
    }

    /**
     * DNS检查
     */
    private Map<String, Object> checkDns(String domainName, Map<String, Object> result) {
        boolean hasRecords = DomainUtils.isDnsEnabled(domainName);

        if (hasRecords) {
            result.put("status", "active");
            result.put("details", "DNS records found");
        } else {
            result.put("status", "available");
            result.put("details", "No DNS records found");
        }

        return result;
    }

    /**
     * 批量搜索请求体
     */
    public static class BulkSearchRequest {
        private List<String> domains;
        private String searchType; // "availability", "whois", "dns"

        public List<String> getDomains() {
            return domains;
        }

        public void setDomains(List<String> domains) {
            this.domains = domains;
        }

        public String getSearchType() {
            return searchType;
        }

        public void setSearchType(String searchType) {
            this.searchType = searchType;
        }
    }
}
