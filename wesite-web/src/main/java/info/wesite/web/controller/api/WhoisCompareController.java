package info.wesite.web.controller.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainDns;
import info.wesite.core.service.DomainDnsService;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * WHOIS Comparison API - 两个域名并排对比
 * 对域名投资者、安全研究人员、SEO分析师极具价值
 */
@Tag(name = "WHOIS Comparison API")
@RestController
@RequestMapping("/api/tools")
public class WhoisCompareController {

    private static final Logger log = LoggerFactory.getLogger(WhoisCompareController.class);

    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";

    @Autowired
    private DomainService domainService;

    @Autowired
    private DomainDnsService domainDnsService;

    /**
     * 比较两个域名的WHOIS、DNS、技术信息
     */
    @Operation(summary = "Compare two domains side by side")
    @GetMapping("/compare")
    public ResponseJson<Map<String, Object>> compareDomains(
            @RequestParam("domain1") String domain1,
            @RequestParam("domain2") String domain2,
            HttpServletRequest request) {

        String ip = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        // 验证输入
        domain1 = domain1 != null ? domain1.toLowerCase().trim() : "";
        domain2 = domain2 != null ? domain2.toLowerCase().trim() : "";

        if (StringUtils.isBlank(domain1) || StringUtils.isBlank(domain2)) {
            return ResponseJson.failure("Both domain names are required.");
        }

        // 取主域名
        domain1 = DomainUtils.getMainDomain(domain1);
        domain2 = DomainUtils.getMainDomain(domain2);

        if (!domain1.matches(DOMAIN_REGEX) || !domain2.matches(DOMAIN_REGEX)) {
            return ResponseJson.failure("Invalid domain name format.");
        }

        if (domain1.equals(domain2)) {
            return ResponseJson.failure("Please provide two different domain names.");
        }

        try {
            // 并行获取两个域名的信息
            final String d1 = domain1;
            final String d2 = domain2;

            CompletableFuture<Map<String, Object>> f1 = CompletableFuture.supplyAsync(() -> buildDomainProfile(d1));
            CompletableFuture<Map<String, Object>> f2 = CompletableFuture.supplyAsync(() -> buildDomainProfile(d2));

            CompletableFuture.allOf(f1, f2).get(30, TimeUnit.SECONDS);

            Map<String, Object> profile1 = f1.get();
            Map<String, Object> profile2 = f2.get();

            // 域名不存在时返回友好错误
            if (!Boolean.TRUE.equals(profile1.get("found")) && !Boolean.TRUE.equals(profile2.get("found"))) {
                return ResponseJson.failure("Neither '" + d1 + "' nor '" + d2 + "' was found in our database. Please look them up on the homepage first.");
            }
            if (!Boolean.TRUE.equals(profile1.get("found"))) {
                return ResponseJson.failure("Domain '" + d1 + "' was not found in our database. Please look it up on the homepage first.");
            }
            if (!Boolean.TRUE.equals(profile2.get("found"))) {
                return ResponseJson.failure("Domain '" + d2 + "' was not found in our database. Please look it up on the homepage first.");
            }

            // 构建对比结果
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("domain1", profile1);
            result.put("domain2", profile2);

            // 生成差异摘要
            result.put("differences", buildDifferences(profile1, profile2));

            // 相似度评分 (0-100)
            result.put("similarityScore", calculateSimilarity(profile1, profile2));

            RateLimitUtils.incrementRequestCount(ip);
            return ResponseJson.success(result);
        } catch (Exception e) {
            log.error("Error comparing domains {} vs {}", domain1, domain2, e);
            return ResponseJson.failure("Comparison failed: " + e.getMessage());
        }
    }

    /**
     * 构建单个域名的完整 profile
     */
    private Map<String, Object> buildDomainProfile(String domainName) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("domain", domainName);

        // 从数据库查询
        Domain domain = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));

        if (domain == null) {
            // 尝试实时查询
            domain = DomainUtils.getDomainInfoByMainName(domainName);
        }

        if (domain != null && domain.getStatus() == Domain.STATUS_ACTIVE) {
            profile.put("found", true);

            // Registration Info
            Map<String, Object> registration = new LinkedHashMap<>();
            registration.put("registrar", domain.getRegistrar());
            registration.put("registrarUrl", domain.getRegistrarUrl());
            registration.put("creationDate", domain.getRegistCreateDateText());
            registration.put("updateDate", domain.getRegistUpdateDateText());
            registration.put("expiryDate", domain.getRegistExpiryDateText());
            registration.put("domainStatus", domain.getDomainStatus());
            profile.put("registration", registration);

            // Registrant Info
            Map<String, Object> registrant = new LinkedHashMap<>();
            registrant.put("organization", domain.getRegistrantOrg());
            registrant.put("name", domain.getRegistrantName());
            registrant.put("country", domain.getRegistrantCountry());
            registrant.put("state", domain.getRegistrantState());
            registrant.put("city", domain.getRegistrantCity());
            registrant.put("email", domain.getRegistrantEmail());
            profile.put("registrant", registrant);

            // DNS Info
            Map<String, Object> dns = new LinkedHashMap<>();
            dns.put("nameServers", domain.getNameServers());
            dns.put("dnssec", domain.getDnssec());
            profile.put("dns", dns);

            // DNS records from DB
            List<DomainDns> dnsRecords = domainDnsService.list(Wrappers.<DomainDns>lambdaQuery()
                    .eq(DomainDns::getDomainId, domain.getId())
                    .eq(DomainDns::getStatus, DomainDns.STATUS_ACTIVE)
                    .orderByAsc(DomainDns::getType));
            if (dnsRecords != null && !dnsRecords.isEmpty()) {
                Map<String, List<String>> recordsByType = new LinkedHashMap<>();
                for (DomainDns record : dnsRecords) {
                    recordsByType.computeIfAbsent(record.getType(), k -> new ArrayList<>()).add(record.getValue());
                }
                dns.put("records", recordsByType);
                dns.put("totalRecords", dnsRecords.size());

                // 从A/AAAA记录中提取IP地址
                List<String> ipList = new ArrayList<>();
                if (recordsByType.containsKey(DomainDns.TYPE_A)) {
                    ipList.addAll(recordsByType.get(DomainDns.TYPE_A));
                }
                if (recordsByType.containsKey(DomainDns.TYPE_AAAA)) {
                    ipList.addAll(recordsByType.get(DomainDns.TYPE_AAAA));
                }
                dns.put("ips", String.join(",", ipList));
            }

            // Technical Info
            Map<String, Object> technical = new LinkedHashMap<>();
            technical.put("whoisServer", domain.getFinalWhoisServer());
            technical.put("rdapServer", domain.getRdapServer());
            technical.put("tld", domain.getTldName());
            profile.put("technical", technical);

            // Domain age (days)
            if (domain.getExpiryDate() != null && domain.getRegistCreateDateText() != null) {
                try {
                    long ageMs = System.currentTimeMillis() - org.apache.commons.lang3.time.DateUtils
                            .parseDate(domain.getRegistCreateDateText().substring(0, 10), "yyyy-MM-dd").getTime();
                    profile.put("ageDays", ageMs / (24 * 60 * 60 * 1000));
                } catch (Exception e) {
                    // ignore parse error
                }
            }
        } else {
            profile.put("found", false);
            // 尝试通过DNS检查是否存在
            profile.put("hasDns", DomainUtils.isDnsEnabled(domainName));
        }

        return profile;
    }

    /**
     * 生成两个域名之间的差异列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildDifferences(Map<String, Object> p1, Map<String, Object> p2) {
        List<Map<String, Object>> diffs = new ArrayList<>();

        boolean found1 = Boolean.TRUE.equals(p1.get("found"));
        boolean found2 = Boolean.TRUE.equals(p2.get("found"));

        if (!found1 || !found2) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("field", "Availability");
            diff.put("domain1", found1 ? "Registered" : "Not found / Available");
            diff.put("domain2", found2 ? "Registered" : "Not found / Available");
            diff.put("match", found1 == found2);
            diffs.add(diff);
            return diffs;
        }

        Map<String, Object> reg1 = (Map<String, Object>) p1.get("registration");
        Map<String, Object> reg2 = (Map<String, Object>) p2.get("registration");

        if (reg1 != null && reg2 != null) {
            addCompare(diffs, "Registrar", str(reg1.get("registrar")), str(reg2.get("registrar")));
            addCompare(diffs, "Creation Date", str(reg1.get("creationDate")), str(reg2.get("creationDate")));
            addCompare(diffs, "Expiry Date", str(reg1.get("expiryDate")), str(reg2.get("expiryDate")));
            addCompare(diffs, "Domain Status", str(reg1.get("domainStatus")), str(reg2.get("domainStatus")));
        }

        Map<String, Object> owner1 = (Map<String, Object>) p1.get("registrant");
        Map<String, Object> owner2 = (Map<String, Object>) p2.get("registrant");

        if (owner1 != null && owner2 != null) {
            addCompare(diffs, "Registrant Org", str(owner1.get("organization")), str(owner2.get("organization")));
            addCompare(diffs, "Registrant Country", str(owner1.get("country")), str(owner2.get("country")));
        }

        Map<String, Object> dns1 = (Map<String, Object>) p1.get("dns");
        Map<String, Object> dns2 = (Map<String, Object>) p2.get("dns");

        if (dns1 != null && dns2 != null) {
            addCompare(diffs, "Name Servers", str(dns1.get("nameServers")), str(dns2.get("nameServers")));
            addCompare(diffs, "DNSSEC", str(dns1.get("dnssec")), str(dns2.get("dnssec")));
            addCompare(diffs, "IP Addresses", str(dns1.get("ips")), str(dns2.get("ips")));
        }

        // Domain age
        addCompare(diffs, "Domain Age (days)", str(p1.get("ageDays")), str(p2.get("ageDays")));

        return diffs;
    }

    /**
     * 计算两个域名的相似度 (0-100)
     * 用于发现是否可能属于同一所有者
     */
    @SuppressWarnings("unchecked")
    private int calculateSimilarity(Map<String, Object> p1, Map<String, Object> p2) {
        boolean found1 = Boolean.TRUE.equals(p1.get("found"));
        boolean found2 = Boolean.TRUE.equals(p2.get("found"));

        if (!found1 || !found2) {
            return 0;
        }

        int score = 0;
        int maxScore = 0;

        Map<String, Object> reg1 = (Map<String, Object>) p1.get("registration");
        Map<String, Object> reg2 = (Map<String, Object>) p2.get("registration");

        // Same registrar (weight: 15)
        maxScore += 15;
        if (reg1 != null && reg2 != null) {
            String r1 = str(reg1.get("registrar"));
            String r2 = str(reg2.get("registrar"));
            if (StringUtils.isNotBlank(r1) && r1.trim().equalsIgnoreCase(r2 != null ? r2.trim() : null)) {
                score += 15;
            }
        }

        // Same registrant org (weight: 30)
        Map<String, Object> owner1 = (Map<String, Object>) p1.get("registrant");
        Map<String, Object> owner2 = (Map<String, Object>) p2.get("registrant");
        maxScore += 30;
        if (owner1 != null && owner2 != null) {
            String org1 = str(owner1.get("organization"));
            String org2 = str(owner2.get("organization"));
            if (StringUtils.isNotBlank(org1) && org1.equalsIgnoreCase(org2)) {
                score += 30;
            }
        }

        // Same registrant country (weight: 10)
        maxScore += 10;
        if (owner1 != null && owner2 != null && Objects.equals(owner1.get("country"), owner2.get("country"))) {
            score += 10;
        }

        // Same name servers (weight: 25)
        Map<String, Object> dns1 = (Map<String, Object>) p1.get("dns");
        Map<String, Object> dns2 = (Map<String, Object>) p2.get("dns");
        maxScore += 25;
        if (dns1 != null && dns2 != null) {
            String ns1 = str(dns1.get("nameServers"));
            String ns2 = str(dns2.get("nameServers"));
            if (StringUtils.isNotBlank(ns1) && ns1.equalsIgnoreCase(ns2)) {
                score += 25;
            }
        }

        // Same IPs (weight: 20)
        maxScore += 20;
        if (dns1 != null && dns2 != null) {
            String ip1 = str(dns1.get("ips"));
            String ip2 = str(dns2.get("ips"));
            if (StringUtils.isNotBlank(ip1) && ip1.equalsIgnoreCase(ip2)) {
                score += 20;
            }
        }

        return maxScore > 0 ? (score * 100 / maxScore) : 0;
    }

    private void addCompare(List<Map<String, Object>> diffs, String field, String val1, String val2) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("field", field);
        diff.put("domain1", StringUtils.isNotBlank(val1) ? val1 : "-");
        diff.put("domain2", StringUtils.isNotBlank(val2) ? val2 : "-");
        // 字符串比较忽略大小写和首尾空格
        String v1 = StringUtils.isBlank(val1) ? null : val1.trim();
        String v2 = StringUtils.isBlank(val2) ? null : val2.trim();
        boolean match = (v1 == null && v2 == null) || (v1 != null && v1.equalsIgnoreCase(v2));
        diff.put("match", match);
        diffs.add(diff);
    }

    private String str(Object obj) {
        return obj != null ? obj.toString() : null;
    }
}
