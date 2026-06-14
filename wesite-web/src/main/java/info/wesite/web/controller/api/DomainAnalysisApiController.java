package info.wesite.web.controller.api;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.handler.Geoip2Handler;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.HttpUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Domain Analysis API")
@Controller
@RequestMapping("/api/tools")
public class DomainAnalysisApiController {

    private static final Logger log = LoggerFactory.getLogger(DomainAnalysisApiController.class);

    @Autowired
    private DomainService domainService;

    @Autowired
    private Geoip2Handler geoip2Handler;

    @Operation(summary = "Analyze domain for SEO and technical metrics")
    @GetMapping("/analyze/{domainName}")
    @ResponseBody
    public ResponseJson<Map<String, Object>> analyzeDomain(@PathVariable("domainName") String domainName) {
        Map<String, Object> analysisResult = new HashMap<>();
        
        try {
            domainName = domainName.toLowerCase();
            
            // Basic domain information
            Domain domain = domainService.getOne(Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domainName));
            if (domain != null) {
                analysisResult.put("basicInfo", getBasicDomainInfo(domain));
            } else {
                domain = DomainUtils.getDomainInfoByMainName(domainName);
                if (domain != null) {
                    analysisResult.put("basicInfo", getBasicDomainInfo(domain));
                } else {
                    analysisResult.put("basicInfo", getDefaultBasicInfo(domainName));
                }
            }
            
            // Run technical, SEO, security analysis in parallel
            final String dn = domainName;
            CompletableFuture<Map<String, Object>> techFuture = CompletableFuture.supplyAsync(() -> performTechnicalAnalysis(dn));
            CompletableFuture<Map<String, Object>> seoFuture = CompletableFuture.supplyAsync(() -> performSeoAnalysis(dn));
            CompletableFuture<Map<String, Object>> secFuture = CompletableFuture.supplyAsync(() -> performSecurityAnalysis(dn));
            
            CompletableFuture.allOf(techFuture, seoFuture, secFuture).get(30, TimeUnit.SECONDS);
            
            analysisResult.put("technical", techFuture.get());
            analysisResult.put("seo", seoFuture.get());
            analysisResult.put("security", secFuture.get());
            
            return ResponseJson.success(analysisResult);
        } catch (Exception e) {
            log.error("Error analyzing domain: {}", domainName, e);
            return ResponseJson.failure("Error analyzing domain: " + e.getMessage());
        }
    }

    @Operation(summary = "Analyze DNS records")
    @GetMapping("/dns-analyze/{domainName}")
    @ResponseBody
    public ResponseJson<Map<String, Object>> analyzeDns(@PathVariable("domainName") String domainName) {
        Map<String, Object> dnsAnalysis = new HashMap<>();
        
        try {
            domainName = domainName.toLowerCase();
            
            dnsAnalysis.put("nsRecords", getNsRecords(domainName));
            dnsAnalysis.put("addressRecords", getAddressRecords(domainName));
            dnsAnalysis.put("mailRecords", getMailRecords(domainName));
            dnsAnalysis.put("securityRecords", getSecurityRecords(domainName));
            dnsAnalysis.put("soaRecord", getSoaRecord(domainName));
            dnsAnalysis.put("cnameRecords", getCnameRecords(domainName));
            dnsAnalysis.put("issues", detectDnsIssues(domainName));
            
            return ResponseJson.success(dnsAnalysis);
        } catch (Exception e) {
            log.error("Error analyzing DNS for domain: {}", domainName, e);
            return ResponseJson.failure("Error analyzing DNS: " + e.getMessage());
        }
    }

    // ==================== Basic Info ====================

    private Map<String, Object> getBasicDomainInfo(Domain domain) {
        Map<String, Object> info = new HashMap<>();
        info.put("domain", domain.getName());
        info.put("registrar", domain.getRegistrar());
        info.put("registrationDate", domain.getRegistCreateDateText());
        info.put("expirationDate", domain.getRegistExpiryDateText());
        info.put("status", domain.getDomainStatus());
        info.put("nameServers", domain.getNameServers());
        info.put("dnssec", domain.getDnssec());
        info.put("registrantOrg", domain.getRegistrantOrg());
        info.put("registrantCountry", domain.getRegistrantCountry());
        return info;
    }

    private Map<String, Object> getDefaultBasicInfo(String domainName) {
        Map<String, Object> info = new HashMap<>();
        info.put("domain", domainName);
        info.put("registrar", "Unknown");
        info.put("registrationDate", "Unknown");
        info.put("expirationDate", "Unknown");
        info.put("status", "Unknown");
        info.put("nameServers", "Unknown");
        return info;
    }

    // ==================== Technical Analysis ====================

    private Map<String, Object> performTechnicalAnalysis(String domainName) {
        Map<String, Object> tech = new HashMap<>();
        
        // Measure response time and accessibility together
        long startTime = System.currentTimeMillis();
        ResponseEntity<String> httpsResp = null;
        ResponseEntity<String> httpResp = null;
        boolean usedHttps = false;
        
        try {
            httpsResp = HttpUtils.get("https://" + domainName);
            usedHttps = true;
        } catch (Exception e) {
            // HTTPS not available
        }
        
        if (httpsResp == null || !httpsResp.getStatusCode().is2xxSuccessful()) {
            try {
                httpResp = HttpUtils.get("http://" + domainName);
            } catch (Exception e) {
                // HTTP not available either
            }
        }
        
        long responseTime = System.currentTimeMillis() - startTime;
        ResponseEntity<String> resp = (httpsResp != null && httpsResp.getStatusCode().is2xxSuccessful()) ? httpsResp : httpResp;
        
        tech.put("accessible", resp != null && resp.getStatusCode().is2xxSuccessful());
        tech.put("responseTimeMs", resp != null ? responseTime : -1);
        tech.put("hasValidSsl", usedHttps && httpsResp != null && httpsResp.getStatusCode().is2xxSuccessful());
        
        // Extract server header
        if (resp != null) {
            List<String> serverHeaders = resp.getHeaders().get("Server");
            tech.put("serverSoftware", serverHeaders != null && !serverHeaders.isEmpty() ? serverHeaders.get(0) : "Unknown");
            
            List<String> poweredBy = resp.getHeaders().get("X-Powered-By");
            tech.put("poweredBy", poweredBy != null && !poweredBy.isEmpty() ? poweredBy.get(0) : null);
        }
        
        // Check www redirect
        tech.put("hasWwwRedirect", checkWwwRedirect(domainName));
        
        // Check common ports using real socket connection
        tech.put("ports", checkCommonPorts(domainName));
        
        // Resolve IP address and get geo/hosting info
        try {
            InetAddress inetAddress = InetAddress.getByName(domainName);
            String ip = inetAddress.getHostAddress();
            tech.put("ipAddress", ip);
            
            String location = geoip2Handler.getServerLocation(ip);
            tech.put("serverLocation", location != null ? location : "Unknown");
            
            String hosting = geoip2Handler.getAsnOrgName(ip);
            tech.put("hostingProvider", hosting != null ? hosting : "Unknown");
        } catch (Exception e) {
            log.warn("Failed to resolve IP for domain: {}", domainName, e);
            tech.put("ipAddress", "Unknown");
            tech.put("serverLocation", "Unknown");
            tech.put("hostingProvider", "Unknown");
        }
        
        return tech;
    }

    /**
     * Perform real SEO analysis by fetching the page HTML and inspecting key elements.
     */
    private Map<String, Object> performSeoAnalysis(String domainName) {
        Map<String, Object> seo = new HashMap<>();
        
        try {
            ResponseEntity<String> resp = HttpUtils.get("https://" + domainName);
            if (resp == null || !resp.getStatusCode().is2xxSuccessful()) {
                resp = HttpUtils.get("http://" + domainName);
            }
            
            if (resp != null && resp.getBody() != null) {
                Document doc = Jsoup.parse(resp.getBody());
                
                // Title analysis
                Element titleEl = doc.select("title").first();
                String title = titleEl != null ? titleEl.text() : "";
                seo.put("title", title);
                seo.put("titleLength", title.length());
                seo.put("titleOptimal", title.length() >= 30 && title.length() <= 60);
                
                // Meta description
                Element metaDesc = doc.select("meta[name=description]").first();
                String description = metaDesc != null ? metaDesc.attr("content") : "";
                seo.put("metaDescription", description);
                seo.put("metaDescriptionLength", description.length());
                seo.put("metaDescriptionOptimal", description.length() >= 120 && description.length() <= 160);
                
                // Heading structure
                Map<String, Integer> headings = new HashMap<>();
                for (int i = 1; i <= 6; i++) {
                    Elements hTags = doc.select("h" + i);
                    headings.put("h" + i, hTags.size());
                }
                seo.put("headings", headings);
                seo.put("hasH1", doc.select("h1").size() > 0);
                seo.put("multipleH1", doc.select("h1").size() > 1);
                
                // Image analysis
                Elements images = doc.select("img");
                int imagesWithoutAlt = 0;
                for (Element img : images) {
                    if (img.attr("alt").isEmpty()) {
                        imagesWithoutAlt++;
                    }
                }
                seo.put("totalImages", images.size());
                seo.put("imagesWithoutAlt", imagesWithoutAlt);
                
                // Link analysis
                Elements internalLinks = doc.select("a[href^=/], a[href^=https://" + domainName + "], a[href^=http://" + domainName + "]");
                Elements externalLinks = doc.select("a[href^=http]");
                int externalCount = externalLinks.size() - internalLinks.size();
                seo.put("internalLinks", internalLinks.size());
                seo.put("externalLinks", Math.max(externalCount, 0));
                
                // Meta robots
                Element metaRobots = doc.select("meta[name=robots]").first();
                seo.put("metaRobots", metaRobots != null ? metaRobots.attr("content") : "not set");
                
                // Canonical URL
                Element canonical = doc.select("link[rel=canonical]").first();
                seo.put("canonicalUrl", canonical != null ? canonical.attr("href") : "not set");
                
                // Open Graph
                seo.put("hasOpenGraph", doc.select("meta[property^=og:]").size() > 0);
                
                // Twitter Card
                seo.put("hasTwitterCard", doc.select("meta[name^=twitter:]").size() > 0);
                
                // Viewport meta
                seo.put("hasViewport", doc.select("meta[name=viewport]").size() > 0);
                
                // Language
                String lang = doc.select("html").attr("lang");
                seo.put("htmlLang", lang.isEmpty() ? "not set" : lang);
                
                // Schema.org structured data
                seo.put("hasStructuredData", doc.select("script[type=application/ld+json]").size() > 0);
                
            } else {
                seo.put("error", "Could not fetch page content");
            }
        } catch (Exception e) {
            log.warn("SEO analysis failed for {}: {}", domainName, e.getMessage());
            seo.put("error", "SEO analysis failed: " + e.getMessage());
        }
        
        return seo;
    }

    private Map<String, Object> performSecurityAnalysis(String domainName) {
        Map<String, Object> security = new HashMap<>();
        
        // Real security headers check via HTTP
        security.put("securityHeaders", checkSecurityHeaders(domainName));
        
        // Check for security.txt
        security.put("securityTxt", checkSecurityTxt(domainName));
        
        // SSL certificate details
        security.put("sslDetails", checkSslDetails(domainName));
        
        return security;
    }

    // ==================== Real HTTP checks ====================

    private boolean checkDomainAccessibility(String domainName) {
        try {
            ResponseEntity<String> response = HttpUtils.get("https://" + domainName);
            return response != null && response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            try {
                ResponseEntity<String> response = HttpUtils.get("http://" + domainName);
                return response != null && response.getStatusCode().is2xxSuccessful();
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private long measureResponseTime(String domainName) {
        try {
            long startTime = System.currentTimeMillis();
            HttpUtils.get("https://" + domainName);
            return System.currentTimeMillis() - startTime;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean checkWwwRedirect(String domainName) {
        try {
            ResponseEntity<String> response = HttpUtils.get("http://www." + domainName);
            return response != null && (response.getStatusCode().is3xxRedirection() || 
                    response.getStatusCode().is2xxSuccessful());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Real port check using socket connection with timeout
     */
    private Map<String, Object> checkCommonPorts(String domainName) {
        Map<String, Object> ports = new HashMap<>();
        int[][] portList = {{80, 0}, {443, 0}, {21, 0}, {22, 0}, {25, 0}, {53, 0}, {8080, 0}};
        String[] portNames = {"http_80", "https_443", "ftp_21", "ssh_22", "smtp_25", "dns_53", "http_alt_8080"};
        
        for (int i = 0; i < portList.length; i++) {
            int port = portList[i][0];
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(domainName, port), 2000);
                ports.put(portNames[i], true);
            } catch (Exception e) {
                ports.put(portNames[i], false);
            }
        }
        return ports;
    }

    /**
     * Real security headers detection via HTTP response headers
     */
    private Map<String, Object> checkSecurityHeaders(String domainName) {
        Map<String, Object> headers = new HashMap<>();
        
        try {
            ResponseEntity<String> response = HttpUtils.get("https://" + domainName);
            if (response == null) {
                response = HttpUtils.get("http://" + domainName);
            }
            
            if (response != null) {
                HttpHeaders respHeaders = response.getHeaders();
                
                // X-Frame-Options
                String xfo = respHeaders.getFirst("X-Frame-Options");
                headers.put("xFrameOptions", xfo != null);
                headers.put("xFrameOptionsValue", xfo);
                
                // X-Content-Type-Options
                String xcto = respHeaders.getFirst("X-Content-Type-Options");
                headers.put("xContentTypeOptions", xcto != null && "nosniff".equalsIgnoreCase(xcto));
                headers.put("xContentTypeOptionsValue", xcto);
                
                // Strict-Transport-Security (HSTS)
                String hsts = respHeaders.getFirst("Strict-Transport-Security");
                headers.put("strictTransportSecurity", hsts != null);
                headers.put("strictTransportSecurityValue", hsts);
                
                // Content-Security-Policy
                String csp = respHeaders.getFirst("Content-Security-Policy");
                headers.put("contentSecurityPolicy", csp != null);
                headers.put("contentSecurityPolicyValue", csp != null && csp.length() > 200 ? csp.substring(0, 200) + "..." : csp);
                
                // X-XSS-Protection
                String xss = respHeaders.getFirst("X-XSS-Protection");
                headers.put("xssProtection", xss != null);
                headers.put("xssProtectionValue", xss);
                
                // Referrer-Policy
                String rp = respHeaders.getFirst("Referrer-Policy");
                headers.put("referrerPolicy", rp != null);
                headers.put("referrerPolicyValue", rp);
                
                // Permissions-Policy
                String pp = respHeaders.getFirst("Permissions-Policy");
                headers.put("permissionsPolicy", pp != null);
                headers.put("permissionsPolicyValue", pp);
                
                // Calculate security score (out of 100)
                int score = 0;
                if (xfo != null) score += 15;
                if (xcto != null) score += 15;
                if (hsts != null) score += 20;
                if (csp != null) score += 20;
                if (rp != null) score += 15;
                if (pp != null) score += 15;
                headers.put("securityScore", score);
                
            } else {
                headers.put("error", "Could not connect to domain");
            }
        } catch (Exception e) {
            log.warn("Security headers check failed for {}: {}", domainName, e.getMessage());
            headers.put("error", "Security headers check failed");
        }
        
        return headers;
    }

    private boolean checkSecurityTxt(String domainName) {
        try {
            ResponseEntity<String> response = HttpUtils.get("https://" + domainName + "/.well-known/security.txt");
            return response != null && response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check SSL certificate details
     */
    private Map<String, Object> checkSslDetails(String domainName) {
        Map<String, Object> sslInfo = new HashMap<>();
        
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
            SSLSocket socket = (SSLSocket) factory.createSocket(domainName, 443);
            socket.setSoTimeout(10000);
            socket.startHandshake();
            
            Certificate[] certificates = socket.getSession().getPeerCertificates();
            X509Certificate cert = (X509Certificate) certificates[0];
            
            sslInfo.put("valid", true);
            sslInfo.put("issuer", cert.getIssuerDN().toString());
            sslInfo.put("subject", cert.getSubjectDN().toString());
            sslInfo.put("algorithm", cert.getSigAlgName());
            sslInfo.put("notBefore", cert.getNotBefore().toString());
            sslInfo.put("notAfter", cert.getNotAfter().toString());
            
            long daysRemaining = (cert.getNotAfter().getTime() - System.currentTimeMillis()) / (24 * 60 * 60 * 1000);
            sslInfo.put("daysUntilExpiration", daysRemaining);
            sslInfo.put("isExpired", daysRemaining < 0);
            sslInfo.put("isExpiringSoon", daysRemaining >= 0 && daysRemaining <= 30);
            
            // Check if certificate is currently valid
            try {
                cert.checkValidity();
                sslInfo.put("currentlyValid", true);
            } catch (Exception e) {
                sslInfo.put("currentlyValid", false);
            }
            
            socket.close();
        } catch (Exception e) {
            sslInfo.put("valid", false);
            sslInfo.put("error", "Could not retrieve SSL certificate: " + e.getMessage());
        }
        
        return sslInfo;
    }

    // ==================== Real DNS Analysis with dnsjava ====================

    /**
     * Query real NS records using dnsjava
     */
    private Map<String, Object> getNsRecords(String domainName) {
        Map<String, Object> records = new HashMap<>();
        List<String> nsList = new ArrayList<>();
        
        try {
            Lookup lookup = new Lookup(domainName, Type.NS);
            Record[] results = lookup.run();
            if (results != null) {
                for (Record r : results) {
                    NSRecord ns = (NSRecord) r;
                    nsList.add(ns.getTarget().toString(true));
                }
            }
        } catch (Exception e) {
            log.warn("NS lookup failed for {}: {}", domainName, e.getMessage());
        }
        
        records.put("nameServers", nsList);
        records.put("count", nsList.size());
        return records;
    }

    /**
     * Query real A and AAAA records using dnsjava
     */
    private Map<String, Object> getAddressRecords(String domainName) {
        Map<String, Object> records = new HashMap<>();
        List<String> aRecords = new ArrayList<>();
        List<String> aaaaRecords = new ArrayList<>();
        
        try {
            // A records (IPv4)
            Lookup lookupA = new Lookup(domainName, Type.A);
            Record[] resultsA = lookupA.run();
            if (resultsA != null) {
                for (Record r : resultsA) {
                    aRecords.add(r.rdataToString());
                }
            }
        } catch (Exception e) {
            log.warn("A record lookup failed for {}: {}", domainName, e.getMessage());
        }
        
        try {
            // AAAA records (IPv6)
            Lookup lookupAAAA = new Lookup(domainName, Type.AAAA);
            Record[] resultsAAAA = lookupAAAA.run();
            if (resultsAAAA != null) {
                for (Record r : resultsAAAA) {
                    aaaaRecords.add(r.rdataToString());
                }
            }
        } catch (Exception e) {
            log.warn("AAAA record lookup failed for {}: {}", domainName, e.getMessage());
        }
        
        records.put("A", aRecords);
        records.put("AAAA", aaaaRecords);
        records.put("hasIPv4", !aRecords.isEmpty());
        records.put("hasIPv6", !aaaaRecords.isEmpty());
        return records;
    }

    /**
     * Query real MX records and SPF records using dnsjava
     */
    private Map<String, Object> getMailRecords(String domainName) {
        Map<String, Object> records = new HashMap<>();
        List<Map<String, Object>> mxRecords = new ArrayList<>();
        String spfRecord = null;
        
        try {
            // MX records
            Lookup lookupMX = new Lookup(domainName, Type.MX);
            Record[] resultsMX = lookupMX.run();
            if (resultsMX != null) {
                for (Record r : resultsMX) {
                    MXRecord mx = (MXRecord) r;
                    Map<String, Object> mxEntry = new HashMap<>();
                    mxEntry.put("priority", mx.getPriority());
                    mxEntry.put("exchange", mx.getTarget().toString(true));
                    mxRecords.add(mxEntry);
                }
            }
        } catch (Exception e) {
            log.warn("MX lookup failed for {}: {}", domainName, e.getMessage());
        }
        
        try {
            // TXT records (to find SPF)
            Lookup lookupTXT = new Lookup(domainName, Type.TXT);
            Record[] resultsTXT = lookupTXT.run();
            if (resultsTXT != null) {
                for (Record r : resultsTXT) {
                    TXTRecord txt = (TXTRecord) r;
                    String txtValue = String.join("", txt.getStrings());
                    if (txtValue.startsWith("v=spf1")) {
                        spfRecord = txtValue;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("TXT lookup for SPF failed for {}: {}", domainName, e.getMessage());
        }
        
        records.put("MX", mxRecords);
        records.put("hasMX", !mxRecords.isEmpty());
        records.put("SPF", spfRecord);
        records.put("hasSPF", spfRecord != null);
        return records;
    }

    /**
     * Query real security-related DNS records: DMARC, DKIM indicators, TXT records
     */
    private Map<String, Object> getSecurityRecords(String domainName) {
        Map<String, Object> records = new HashMap<>();
        
        // DMARC record
        String dmarcRecord = null;
        try {
            Lookup lookupDmarc = new Lookup("_dmarc." + domainName, Type.TXT);
            Record[] resultsDmarc = lookupDmarc.run();
            if (resultsDmarc != null) {
                for (Record r : resultsDmarc) {
                    TXTRecord txt = (TXTRecord) r;
                    String txtValue = String.join("", txt.getStrings());
                    if (txtValue.startsWith("v=DMARC1")) {
                        dmarcRecord = txtValue;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DMARC lookup failed for {}: {}", domainName, e.getMessage());
        }
        records.put("DMARC", dmarcRecord);
        records.put("hasDMARC", dmarcRecord != null);
        
        // DKIM - check common selectors
        String[] dkimSelectors = {"default", "google", "selector1", "selector2", "k1", "mail", "dkim"};
        List<String> dkimFound = new ArrayList<>();
        for (String selector : dkimSelectors) {
            try {
                Lookup lookupDkim = new Lookup(selector + "._domainkey." + domainName, Type.TXT);
                Record[] resultsDkim = lookupDkim.run();
                if (resultsDkim != null) {
                    for (Record r : resultsDkim) {
                        TXTRecord txt = (TXTRecord) r;
                        String txtValue = String.join("", txt.getStrings());
                        if (txtValue.contains("v=DKIM1")) {
                            dkimFound.add(selector + "._domainkey." + domainName);
                        }
                    }
                }
            } catch (Exception e) {
                // selector not found, continue
            }
        }
        records.put("DKIM", dkimFound);
        records.put("hasDKIM", !dkimFound.isEmpty());
        
        // All TXT records
        List<String> txtRecords = new ArrayList<>();
        try {
            Lookup lookupTXT = new Lookup(domainName, Type.TXT);
            Record[] resultsTXT = lookupTXT.run();
            if (resultsTXT != null) {
                for (Record r : resultsTXT) {
                    TXTRecord txt = (TXTRecord) r;
                    txtRecords.add(String.join("", txt.getStrings()));
                }
            }
        } catch (Exception e) {
            log.warn("TXT record lookup failed for {}: {}", domainName, e.getMessage());
        }
        records.put("TXT", txtRecords);
        
        return records;
    }

    /**
     * Query real SOA record using dnsjava
     */
    private Map<String, Object> getSoaRecord(String domainName) {
        Map<String, Object> soaInfo = new HashMap<>();
        
        try {
            Lookup lookup = new Lookup(domainName, Type.SOA);
            Record[] results = lookup.run();
            if (results != null && results.length > 0) {
                SOARecord soa = (SOARecord) results[0];
                soaInfo.put("primaryNs", soa.getHost().toString(true));
                soaInfo.put("adminEmail", soa.getAdmin().toString(true));
                soaInfo.put("serial", soa.getSerial());
                soaInfo.put("refresh", soa.getRefresh());
                soaInfo.put("retry", soa.getRetry());
                soaInfo.put("expire", soa.getExpire());
                soaInfo.put("minimumTtl", soa.getMinimum());
                soaInfo.put("found", true);
            } else {
                soaInfo.put("found", false);
            }
        } catch (Exception e) {
            log.warn("SOA lookup failed for {}: {}", domainName, e.getMessage());
            soaInfo.put("found", false);
        }
        
        return soaInfo;
    }

    /**
     * Query real CNAME records using dnsjava
     */
    private Map<String, Object> getCnameRecords(String domainName) {
        Map<String, Object> records = new HashMap<>();
        List<String> cnameList = new ArrayList<>();
        
        try {
            Lookup lookup = new Lookup(domainName, Type.CNAME);
            Record[] results = lookup.run();
            if (results != null) {
                for (Record r : results) {
                    cnameList.add(r.rdataToString());
                }
            }
        } catch (Exception e) {
            log.warn("CNAME lookup failed for {}: {}", domainName, e.getMessage());
        }
        
        // Also check www CNAME
        List<String> wwwCname = new ArrayList<>();
        try {
            Lookup lookup = new Lookup("www." + domainName, Type.CNAME);
            Record[] results = lookup.run();
            if (results != null) {
                for (Record r : results) {
                    wwwCname.add(r.rdataToString());
                }
            }
        } catch (Exception e) {
            // ignore
        }
        
        records.put("CNAME", cnameList);
        records.put("wwwCNAME", wwwCname);
        return records;
    }

    /**
     * Detect real DNS configuration issues based on actual DNS query results
     */
    private Map<String, Object> detectDnsIssues(String domainName) {
        Map<String, Object> issues = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        // Check NS records: should have at least 2
        try {
            Lookup nsLookup = new Lookup(domainName, Type.NS);
            Record[] nsResults = nsLookup.run();
            if (nsResults == null || nsResults.length == 0) {
                errors.add("No NS records found");
            } else if (nsResults.length < 2) {
                warnings.add("Only 1 NS record found. At least 2 are recommended for redundancy.");
            }
        } catch (Exception e) {
            errors.add("NS record query failed");
        }
        
        // Check MX records
        try {
            Lookup mxLookup = new Lookup(domainName, Type.MX);
            Record[] mxResults = mxLookup.run();
            if (mxResults == null || mxResults.length == 0) {
                warnings.add("No MX records found. Email delivery to this domain may not work.");
            }
        } catch (Exception e) {
            // ignore
        }
        
        // Check SPF
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
        } catch (Exception e) {
            // ignore
        }
        if (!hasSpf) {
            warnings.add("No SPF record found. SPF helps prevent email spoofing.");
        }
        
        // Check DMARC
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
        } catch (Exception e) {
            // ignore
        }
        if (!hasDmarc) {
            warnings.add("No DMARC record found. DMARC helps protect against email fraud.");
        }
        
        // Check SOA
        try {
            Lookup soaLookup = new Lookup(domainName, Type.SOA);
            Record[] soaResults = soaLookup.run();
            if (soaResults == null || soaResults.length == 0) {
                errors.add("No SOA record found. This is required for a valid DNS zone.");
            }
        } catch (Exception e) {
            // ignore
        }
        
        // Check IPv6 support
        try {
            Lookup aaaaLookup = new Lookup(domainName, Type.AAAA);
            Record[] aaaaResults = aaaaLookup.run();
            if (aaaaResults == null || aaaaResults.length == 0) {
                warnings.add("No AAAA (IPv6) records found. Consider adding IPv6 support.");
            }
        } catch (Exception e) {
            // ignore
        }
        
        issues.put("warnings", warnings);
        issues.put("errors", errors);
        issues.put("warningCount", warnings.size());
        issues.put("errorCount", errors.size());
        issues.put("hasMissingSPF", !hasSpf);
        issues.put("hasMissingDMARC", !hasDmarc);
        
        return issues;
    }
}
