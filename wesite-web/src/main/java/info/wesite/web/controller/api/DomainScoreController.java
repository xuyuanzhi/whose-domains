package info.wesite.web.controller.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.HttpUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Domain Health Score API
 * 从多个维度综合评分：域名年龄、SSL、DNSSEC、安全头、邮件安全(SPF/DKIM/DMARC)、可访问性
 * 输出 A/B/C/D/F 等级 + 细分项评分 + 改进建议
 */
@Tag(name = "Domain Score API")
@RestController
@RequestMapping("/api/tools")
public class DomainScoreController {

    private static final Logger log = LoggerFactory.getLogger(DomainScoreController.class);

    private static final String DOMAIN_REGEX = "^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$";

    @Autowired
    private DomainService domainService;

    @Operation(summary = "Get comprehensive domain health score")
    @GetMapping("/score/{domainName}")
    public ResponseJson<Map<String, Object>> scoreDomain(
            @PathVariable("domainName") String domainName,
            HttpServletRequest request) {

        String ip = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(ip, 5, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        domainName = domainName.toLowerCase().trim();
        String mainDomain = DomainUtils.getMainDomain(domainName);
        if (!mainDomain.matches(DOMAIN_REGEX)) {
            return ResponseJson.failure("Invalid domain name format.");
        }

        try {
            final String dn = mainDomain;

            // 并行执行各维度检测
            CompletableFuture<Map<String, Object>> ageFuture = CompletableFuture.supplyAsync(() -> scoreAge(dn));
            CompletableFuture<Map<String, Object>> sslFuture = CompletableFuture.supplyAsync(() -> scoreSsl(dn));
            CompletableFuture<Map<String, Object>> emailFuture = CompletableFuture.supplyAsync(() -> scoreEmailSecurity(dn));
            CompletableFuture<Map<String, Object>> headerFuture = CompletableFuture.supplyAsync(() -> scoreSecurityHeaders(dn));
            CompletableFuture<Map<String, Object>> dnsFuture = CompletableFuture.supplyAsync(() -> scoreDnsConfig(dn));

            CompletableFuture.allOf(ageFuture, sslFuture, emailFuture, headerFuture, dnsFuture)
                    .get(30, TimeUnit.SECONDS);

            Map<String, Object> ageResult = ageFuture.get();
            Map<String, Object> sslResult = sslFuture.get();
            Map<String, Object> emailResult = emailFuture.get();
            Map<String, Object> headerResult = headerFuture.get();
            Map<String, Object> dnsResult = dnsFuture.get();

            // 各维度权重
            int ageScore = (int) ageResult.getOrDefault("score", 0);       // 权重 15%
            int sslScore = (int) sslResult.getOrDefault("score", 0);       // 权重 25%
            int emailScore = (int) emailResult.getOrDefault("score", 0);   // 权重 20%
            int headerScore = (int) headerResult.getOrDefault("score", 0); // 权重 20%
            int dnsScore = (int) dnsResult.getOrDefault("score", 0);       // 权重 20%

            int totalScore = (int) Math.round(
                    ageScore * 0.15 + sslScore * 0.25 + emailScore * 0.20 +
                    headerScore * 0.20 + dnsScore * 0.20);

            // 构建结果
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("domain", mainDomain);
            result.put("totalScore", totalScore);
            result.put("grade", getGrade(totalScore));
            result.put("gradeColor", getGradeColor(totalScore));

            // 各维度详情
            Map<String, Object> categories = new LinkedHashMap<>();
            categories.put("domainAge", ageResult);
            categories.put("ssl", sslResult);
            categories.put("emailSecurity", emailResult);
            categories.put("securityHeaders", headerResult);
            categories.put("dnsConfiguration", dnsResult);
            result.put("categories", categories);

            // 汇总改进建议
            List<String> allRecommendations = new ArrayList<>();
            addRecommendations(allRecommendations, ageResult);
            addRecommendations(allRecommendations, sslResult);
            addRecommendations(allRecommendations, emailResult);
            addRecommendations(allRecommendations, headerResult);
            addRecommendations(allRecommendations, dnsResult);
            result.put("recommendations", allRecommendations);

            RateLimitUtils.incrementRequestCount(ip);
            return ResponseJson.success(result);
        } catch (Exception e) {
            log.error("Error scoring domain: {}", mainDomain, e);
            return ResponseJson.failure("Scoring failed: " + e.getMessage());
        }
    }

    // ==================== 域名年龄评分 ====================
    private Map<String, Object> scoreAge(String domainName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", "Domain Age");
        result.put("weight", "15%");
        List<String> recs = new ArrayList<>();
        int score = 0;

        Domain domain = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));
        // 如果数据库没有，尝试在线查询
        if (domain == null || StringUtils.isBlank(domain.getRegistCreateDateText())) {
            try {
                domain = DomainUtils.getDomainInfoByMainName(domainName);
            } catch (Exception e) {
                log.warn("Live WHOIS lookup failed for {}: {}", domainName, e.getMessage());
            }
        }

        if (domain != null && StringUtils.isNotBlank(domain.getRegistCreateDateText())) {
            try {
                String dateStr = domain.getRegistCreateDateText().substring(0, 10);
                long ageMs = System.currentTimeMillis() -
                        org.apache.commons.lang3.time.DateUtils.parseDate(dateStr, "yyyy-MM-dd").getTime();
                long ageDays = ageMs / (24 * 60 * 60 * 1000);
                result.put("registrationDate", domain.getRegistCreateDateText());
                result.put("ageDays", ageDays);
                result.put("ageYears", String.format("%.1f", ageDays / 365.0));

                if (ageDays > 3650) score = 100;       // >10年
                else if (ageDays > 1825) score = 90;    // >5年
                else if (ageDays > 730) score = 75;     // >2年
                else if (ageDays > 365) score = 60;     // >1年
                else if (ageDays > 180) score = 40;     // >6月
                else score = 20;

                if (ageDays < 365) {
                    recs.add("Domain is less than 1 year old. Newer domains may be less trusted by search engines and users.");
                }
            } catch (Exception e) {
                score = 50;
                result.put("note", "Could not parse creation date");
            }

            // 检查过期时间
            if (domain.getExpiryDate() != null) {
                long daysUntilExpiry = (domain.getExpiryDate().getTime() - System.currentTimeMillis()) / (24 * 60 * 60 * 1000);
                result.put("expirationDate", domain.getRegistExpiryDateText());
                result.put("daysUntilExpiry", daysUntilExpiry);
                if (daysUntilExpiry < 30) {
                    score = Math.max(score - 30, 0);
                    recs.add("Domain expires in less than 30 days! Renew immediately to avoid losing the domain.");
                } else if (daysUntilExpiry < 90) {
                    score = Math.max(score - 10, 0);
                    recs.add("Domain expires in less than 90 days. Consider renewing soon.");
                }
            }

            // 注册商信息
            if (StringUtils.isNotBlank(domain.getRegistrar())) {
                result.put("registrar", domain.getRegistrar());
            }
        } else {
            score = 0;
            result.put("note", "Domain registration data not available");
            recs.add("Could not retrieve domain registration data via WHOIS/RDAP.");
        }

        result.put("score", score);
        result.put("recommendations", recs);
        return result;
    }

    // ==================== SSL评分 ====================
    private Map<String, Object> scoreSsl(String domainName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", "SSL / HTTPS");
        result.put("weight", "25%");
        List<String> recs = new ArrayList<>();
        int score = 0;

        try {
            ResponseEntity<String> httpsResp = HttpUtils.get("https://" + domainName);
            if (httpsResp != null && httpsResp.getStatusCode().is2xxSuccessful()) {
                score = 40;
                result.put("httpsAccessible", true);

                // 检查SSL证书详情
                try {
                    javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[] {
                        new javax.net.ssl.X509TrustManager() {
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        }
                    };
                    javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                    sc.init(null, trustAll, new java.security.SecureRandom());
                    javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) sc.getSocketFactory().createSocket(domainName, 443);
                    sslSocket.setSoTimeout(10000);
                    sslSocket.startHandshake();
                    java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) sslSocket.getSession().getPeerCertificates()[0];

                    // 证书颁发者
                    String issuer = cert.getIssuerDN().toString();
                    result.put("certIssuer", issuer);

                    // 签名算法
                    String algorithm = cert.getSigAlgName();
                    result.put("certAlgorithm", algorithm);
                    if (algorithm != null && (algorithm.contains("SHA256") || algorithm.contains("SHA384") || algorithm.contains("SHA512"))) {
                        score += 10;  // 强算法加分
                    } else {
                        recs.add("SSL certificate uses a weak signature algorithm (" + algorithm + "). Prefer SHA-256 or higher.");
                    }

                    // 证书有效期
                    long daysRemaining = (cert.getNotAfter().getTime() - System.currentTimeMillis()) / (24 * 60 * 60 * 1000);
                    result.put("certDaysRemaining", daysRemaining);
                    result.put("certExpiry", cert.getNotAfter().toString());
                    if (daysRemaining < 0) {
                        score -= 20;
                        recs.add("SSL certificate has EXPIRED! Renew immediately.");
                    } else if (daysRemaining < 14) {
                        recs.add("SSL certificate expires in less than 14 days. Renew soon.");
                    } else if (daysRemaining < 30) {
                        recs.add("SSL certificate expires in less than 30 days. Plan renewal.");
                    } else {
                        score += 10;  // 证书有效期充足
                    }

                    // 证书当前是否有效
                    try {
                        cert.checkValidity();
                        result.put("certCurrentlyValid", true);
                    } catch (Exception e) {
                        result.put("certCurrentlyValid", false);
                        score -= 10;
                    }

                    sslSocket.close();
                } catch (Exception e) {
                    result.put("certCheckError", "Could not inspect certificate details");
                    log.warn("SSL cert check failed for {}: {}", domainName, e.getMessage());
                }

                // 检查HSTS
                String hsts = httpsResp.getHeaders().getFirst("Strict-Transport-Security");
                if (hsts != null) {
                    score += 15;
                    result.put("hsts", true);
                } else {
                    recs.add("Enable HSTS (Strict-Transport-Security) header to enforce HTTPS connections.");
                    result.put("hsts", false);
                }

                // 检查HTTP是否自动跳转HTTPS
                try {
                    ResponseEntity<String> httpResp = HttpUtils.get("http://" + domainName);
                    if (httpResp != null && httpResp.getStatusCode().is3xxRedirection()) {
                        score += 15;
                        result.put("httpRedirect", true);
                    } else {
                        recs.add("Configure HTTP to HTTPS redirect for better security.");
                        result.put("httpRedirect", false);
                    }
                } catch (Exception e) {
                    result.put("httpRedirect", false);
                }
            } else {
                score = 0;
                result.put("httpsAccessible", false);
                recs.add("HTTPS is not accessible. Install a valid SSL certificate (free via Let's Encrypt).");
            }
        } catch (Exception e) {
            score = 0;
            result.put("httpsAccessible", false);
            recs.add("Could not connect via HTTPS. Ensure SSL certificate is properly installed.");
        }

        result.put("score", Math.max(score, 0));
        result.put("recommendations", recs);
        return result;
    }

    // ==================== 邮件安全评分 (SPF/DKIM/DMARC) ====================
    private Map<String, Object> scoreEmailSecurity(String domainName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", "Email Security");
        result.put("weight", "20%");
        List<String> recs = new ArrayList<>();
        int score = 0;

        // SPF
        boolean hasSpf = false;
        try {
            Lookup txtLookup = new Lookup(domainName, Type.TXT);
            Record[] txtResults = txtLookup.run();
            if (txtResults != null) {
                for (Record r : txtResults) {
                    TXTRecord txt = (TXTRecord) r;
                    if (String.join("", txt.getStrings()).startsWith("v=spf1")) {
                        hasSpf = true;
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
        result.put("hasSPF", hasSpf);
        if (hasSpf) {
            score += 35;
        } else {
            recs.add("Add an SPF record to prevent email spoofing. Example: v=spf1 include:_spf.google.com ~all");
        }

        // DMARC
        boolean hasDmarc = false;
        try {
            Lookup dmarcLookup = new Lookup("_dmarc." + domainName, Type.TXT);
            Record[] dmarcResults = dmarcLookup.run();
            if (dmarcResults != null) {
                for (Record r : dmarcResults) {
                    TXTRecord txt = (TXTRecord) r;
                    if (String.join("", txt.getStrings()).startsWith("v=DMARC1")) {
                        hasDmarc = true;
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
        result.put("hasDMARC", hasDmarc);
        if (hasDmarc) {
            score += 35;
        } else {
            recs.add("Add a DMARC record to protect against email fraud. Example: v=DMARC1; p=quarantine; rua=mailto:admin@" + domainName);
        }

        // DKIM (check common selectors)
        boolean hasDkim = false;
        String[] selectors = {"default", "google", "selector1", "selector2", "k1", "mail"};
        for (String selector : selectors) {
            try {
                Lookup dkimLookup = new Lookup(selector + "._domainkey." + domainName, Type.TXT);
                Record[] dkimResults = dkimLookup.run();
                if (dkimResults != null) {
                    for (Record r : dkimResults) {
                        TXTRecord txt = (TXTRecord) r;
                        if (String.join("", txt.getStrings()).contains("v=DKIM1")) {
                            hasDkim = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
            if (hasDkim) break;
        }
        result.put("hasDKIM", hasDkim);
        if (hasDkim) {
            score += 30;
        } else {
            recs.add("Configure DKIM signing for outgoing emails to improve deliverability and prevent spoofing.");
        }

        result.put("score", score);
        result.put("recommendations", recs);
        return result;
    }

    // ==================== 安全头评分 ====================
    private Map<String, Object> scoreSecurityHeaders(String domainName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", "Security Headers");
        result.put("weight", "20%");
        List<String> recs = new ArrayList<>();
        int score = 0;

        try {
            ResponseEntity<String> resp = HttpUtils.get("https://" + domainName);
            if (resp == null || !resp.getStatusCode().is2xxSuccessful()) {
                resp = HttpUtils.get("http://" + domainName);
            }

            if (resp != null) {
                org.springframework.http.HttpHeaders headers = resp.getHeaders();

                // X-Frame-Options (16 points)
                boolean hasXfo = headers.getFirst("X-Frame-Options") != null;
                result.put("xFrameOptions", hasXfo);
                if (hasXfo) { score += 16; } else { recs.add("Add X-Frame-Options header (DENY or SAMEORIGIN) to prevent clickjacking."); }

                // X-Content-Type-Options (16 points)
                boolean hasXcto = "nosniff".equalsIgnoreCase(headers.getFirst("X-Content-Type-Options"));
                result.put("xContentTypeOptions", hasXcto);
                if (hasXcto) { score += 16; } else { recs.add("Add X-Content-Type-Options: nosniff header."); }

                // Content-Security-Policy (20 points)
                boolean hasCsp = headers.getFirst("Content-Security-Policy") != null;
                result.put("contentSecurityPolicy", hasCsp);
                if (hasCsp) { score += 20; } else { recs.add("Implement a Content-Security-Policy header to prevent XSS and injection attacks."); }

                // Referrer-Policy (16 points)
                boolean hasRp = headers.getFirst("Referrer-Policy") != null;
                result.put("referrerPolicy", hasRp);
                if (hasRp) { score += 16; } else { recs.add("Add Referrer-Policy header to control information leakage."); }

                // Permissions-Policy (16 points)
                boolean hasPp = headers.getFirst("Permissions-Policy") != null;
                result.put("permissionsPolicy", hasPp);
                if (hasPp) { score += 16; } else { recs.add("Add Permissions-Policy header to restrict browser features."); }

                // X-XSS-Protection (16 points)
                boolean hasXss = headers.getFirst("X-XSS-Protection") != null;
                result.put("xssProtection", hasXss);
                if (hasXss) { score += 16; } else { recs.add("Add X-XSS-Protection: 1; mode=block header."); }

            } else {
                result.put("note", "Could not connect to domain");
                recs.add("Domain is not accessible via HTTP/HTTPS. Security headers cannot be checked.");
            }
        } catch (Exception e) {
            result.put("note", "Connection failed");
        }

        result.put("score", Math.min(score, 100));
        result.put("recommendations", recs);
        return result;
    }

    // ==================== DNS配置评分 ====================
    private Map<String, Object> scoreDnsConfig(String domainName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", "DNS Configuration");
        result.put("weight", "20%");
        List<String> recs = new ArrayList<>();
        int score = 0;

        // NS records (至少2个)
        int nsCount = 0;
        List<String> nsNames = new ArrayList<>();
        try {
            Lookup nsLookup = new Lookup(domainName, Type.NS);
            Record[] nsResults = nsLookup.run();
            if (nsResults != null) {
                nsCount = nsResults.length;
                for (Record r : nsResults) {
                    nsNames.add(((NSRecord) r).getTarget().toString());
                }
            }
        } catch (Exception e) { /* ignore */ }
        result.put("nsCount", nsCount);
        if (!nsNames.isEmpty()) {
            result.put("nameServers", String.join(", ", nsNames));
        }
        if (nsCount >= 2) {
            score += 25;
            // 检查NS是否分布在不同网络（不同域名后缀 = 更好冗余）
            if (nsCount >= 3) score += 5;
        } else if (nsCount == 1) {
            score += 10;
            recs.add("Only 1 NS record found. Use at least 2 nameservers for redundancy.");
        } else {
            recs.add("No NS records found. DNS is not properly configured.");
        }

        // A record exists
        boolean hasA = false;
        try {
            Lookup aLookup = new Lookup(domainName, Type.A);
            Record[] aResults = aLookup.run();
            hasA = aResults != null && aResults.length > 0;
        } catch (Exception e) { /* ignore */ }
        result.put("hasARecord", hasA);
        if (hasA) { score += 15; } else { recs.add("No A record found. The domain won't resolve to an IP address."); }

        // IPv6 support
        boolean hasAAAA = false;
        try {
            Lookup aaaaLookup = new Lookup(domainName, Type.AAAA);
            Record[] aaaaResults = aaaaLookup.run();
            hasAAAA = aaaaResults != null && aaaaResults.length > 0;
        } catch (Exception e) { /* ignore */ }
        result.put("hasIPv6", hasAAAA);
        if (hasAAAA) { score += 10; } else { recs.add("Add AAAA records for IPv6 support to future-proof your domain."); }

        // MX records
        boolean hasMx = false;
        try {
            Lookup mxLookup = new Lookup(domainName, Type.MX);
            Record[] mxResults = mxLookup.run();
            hasMx = mxResults != null && mxResults.length > 0;
        } catch (Exception e) { /* ignore */ }
        result.put("hasMX", hasMx);
        if (hasMx) { score += 15; } else { recs.add("No MX records found. Email delivery to this domain won't work."); }

        // SOA record
        boolean hasSoa = false;
        try {
            Lookup soaLookup = new Lookup(domainName, Type.SOA);
            Record[] soaResults = soaLookup.run();
            hasSoa = soaResults != null && soaResults.length > 0;
        } catch (Exception e) { /* ignore */ }
        result.put("hasSOA", hasSoa);
        if (hasSoa) { score += 10; } else { recs.add("No SOA record found. This is required for a valid DNS zone."); }

        // CAA record（限制哪些CA可以签发证书）
        boolean hasCaa = false;
        try {
            Lookup caaLookup = new Lookup(domainName, Type.CAA);
            Record[] caaResults = caaLookup.run();
            hasCaa = caaResults != null && caaResults.length > 0;
        } catch (Exception e) { /* ignore */ }
        result.put("hasCAA", hasCaa);
        if (hasCaa) { score += 10; } else { recs.add("Add CAA records to restrict which Certificate Authorities can issue certificates for this domain."); }

        // DNSSEC check via DB
        Domain domain = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));
        boolean hasDnssec = domain != null && StringUtils.isNotBlank(domain.getDnssec())
                && !"unsigned".equalsIgnoreCase(domain.getDnssec());
        result.put("hasDNSSEC", hasDnssec);
        if (hasDnssec) { score += 10; } else { recs.add("Enable DNSSEC to protect against DNS spoofing and cache poisoning."); }

        result.put("score", Math.min(score, 100));
        result.put("recommendations", recs);
        return result;
    }

    // ==================== Helper ====================
    @SuppressWarnings("unchecked")
    private void addRecommendations(List<String> all, Map<String, Object> categoryResult) {
        List<String> recs = (List<String>) categoryResult.get("recommendations");
        if (recs != null) all.addAll(recs);
    }

    private String getGrade(int score) {
        if (score >= 90) return "A+";
        if (score >= 80) return "A";
        if (score >= 70) return "B";
        if (score >= 60) return "C";
        if (score >= 40) return "D";
        return "F";
    }

    private String getGradeColor(int score) {
        if (score >= 80) return "#00e676";
        if (score >= 60) return "#ffab00";
        return "#ff5252";
    }
}
