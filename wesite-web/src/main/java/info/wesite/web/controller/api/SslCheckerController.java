package info.wesite.web.controller.api;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import info.wesite.core.utils.HttpUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * SSL Certificate Checker API
 * 检查域名的SSL证书详情、有效期、安全配置、证书链等
 */
@Tag(name = "SSL Checker API")
@RestController
@RequestMapping("/api/tools")
public class SslCheckerController {

    private static final Logger log = LoggerFactory.getLogger(SslCheckerController.class);

    @Operation(summary = "Check SSL certificate for a domain")
    @GetMapping("/ssl-check/{domainName}")
    public ResponseJson<Map<String, Object>> checkSsl(
            @PathVariable("domainName") String domainName,
            HttpServletRequest request) {

        String ip = IpUtils.getRequestIp(request);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        domainName = domainName.toLowerCase().trim();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domain", domainName);

        try {
            // ===== 1. SSL Certificate Details =====
            Map<String, Object> certInfo = checkCertificate(domainName);
            result.put("certificate", certInfo);

            // ===== 2. Validity Period =====
            Map<String, Object> validityInfo = buildValidityInfo(certInfo);
            result.put("validity", validityInfo);

            // ===== 3. Security Configuration (HSTS, protocols, etc.) =====
            Map<String, Object> securityInfo = checkSecurityConfig(domainName);
            result.put("security", securityInfo);

            // ===== 4. Issues & Recommendations =====
            List<Map<String, String>> issues = detectIssues(certInfo, securityInfo, validityInfo);
            result.put("issues", issues);

            RateLimitUtils.incrementRequestCount(ip);
            return ResponseJson.success(result);
        } catch (Exception e) {
            log.error("Error checking SSL for domain: {}", domainName, e);
            return ResponseJson.failure("SSL check failed: " + e.getMessage());
        }
    }

    // ==================== Certificate Details ====================

    private Map<String, Object> checkCertificate(String domainName) {
        Map<String, Object> info = new LinkedHashMap<>();

        TrustManager[] trustAll = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };

        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            SSLSocketFactory factory = sc.getSocketFactory();
            SSLSocket socket = (SSLSocket) factory.createSocket(domainName, 443);
            socket.setSoTimeout(15000);
            socket.startHandshake();

            SSLSession session = socket.getSession();
            Certificate[] certs = session.getPeerCertificates();
            X509Certificate cert = (X509Certificate) certs[0];

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            // 基本信息
            info.put("valid", true);
            info.put("issuer", cert.getIssuerDN().toString());
            info.put("subject", cert.getSubjectDN().toString());
            info.put("signatureAlgorithm", cert.getSigAlgName());
            info.put("serialNumber", cert.getSerialNumber().toString(16));
            info.put("version", "v" + cert.getVersion());

            // 公钥信息
            String pubKeyAlg = cert.getPublicKey().getAlgorithm();
            info.put("publicKeyAlgorithm", pubKeyAlg);
            int keySize = getKeySize(cert);
            info.put("publicKeySize", keySize > 0 ? keySize + " bits" : "Unknown");

            // 有效期
            info.put("notBefore", sdf.format(cert.getNotBefore()));
            info.put("notAfter", sdf.format(cert.getNotAfter()));

            long daysRemaining = (cert.getNotAfter().getTime() - System.currentTimeMillis()) / (24 * 60 * 60 * 1000);
            long totalDays = (cert.getNotAfter().getTime() - cert.getNotBefore().getTime()) / (24 * 60 * 60 * 1000);
            info.put("daysUntilExpiration", daysRemaining);
            info.put("totalValidityDays", totalDays);
            info.put("isExpired", daysRemaining < 0);
            info.put("isExpiringSoon", daysRemaining >= 0 && daysRemaining <= 30);

            // 当前有效性
            try {
                cert.checkValidity();
                info.put("currentlyValid", true);
            } catch (Exception e) {
                info.put("currentlyValid", false);
            }

            // SAN (Subject Alternative Names)
            List<String> sanList = new ArrayList<>();
            try {
                Collection<List<?>> sans = cert.getSubjectAlternativeNames();
                if (sans != null) {
                    for (List<?> san : sans) {
                        if (san.size() >= 2) {
                            sanList.add(String.valueOf(san.get(1)));
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            info.put("subjectAlternativeNames", sanList);

            // 证书链
            info.put("chainDepth", certs.length);
            List<String> chainList = new ArrayList<>();
            for (Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    X509Certificate xc = (X509Certificate) c;
                    chainList.add(xc.getSubjectDN().toString());
                }
            }
            info.put("certificateChain", chainList);

            // TLS协议和密码套件
            info.put("protocol", session.getProtocol());
            info.put("cipherSuite", session.getCipherSuite());

            socket.close();
        } catch (Exception e) {
            info.put("valid", false);
            info.put("currentlyValid", false);
            info.put("error", "Could not retrieve SSL certificate: " + e.getMessage());
            log.warn("SSL cert check failed for {}: {}", domainName, e.getMessage());
        }

        return info;
    }

    private int getKeySize(X509Certificate cert) {
        try {
            String algorithm = cert.getPublicKey().getAlgorithm();
            if ("RSA".equals(algorithm)) {
                java.security.interfaces.RSAPublicKey rsaKey = (java.security.interfaces.RSAPublicKey) cert.getPublicKey();
                return rsaKey.getModulus().bitLength();
            } else if ("EC".equals(algorithm)) {
                java.security.interfaces.ECPublicKey ecKey = (java.security.interfaces.ECPublicKey) cert.getPublicKey();
                return ecKey.getParams().getOrder().bitLength();
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    // ==================== Validity Info ====================

    private Map<String, Object> buildValidityInfo(Map<String, Object> certInfo) {
        Map<String, Object> validity = new LinkedHashMap<>();
        validity.put("issuedOn", certInfo.getOrDefault("notBefore", "-"));
        validity.put("expiresOn", certInfo.getOrDefault("notAfter", "-"));
        validity.put("daysUntilExpiration", certInfo.getOrDefault("daysUntilExpiration", -1));

        Object totalObj = certInfo.get("totalValidityDays");
        if (totalObj instanceof Long) {
            long totalDays = (Long) totalObj;
            if (totalDays > 365) {
                validity.put("validityPeriod", String.format("%.1f years (%d days)", totalDays / 365.0, totalDays));
            } else {
                validity.put("validityPeriod", totalDays + " days");
            }
        } else {
            validity.put("validityPeriod", "-");
        }

        validity.put("isExpired", certInfo.getOrDefault("isExpired", false));
        validity.put("isExpiringSoon", certInfo.getOrDefault("isExpiringSoon", false));
        return validity;
    }

    // ==================== Security Config ====================

    private Map<String, Object> checkSecurityConfig(String domainName) {
        Map<String, Object> security = new LinkedHashMap<>();

        try {
            ResponseEntity<String> resp = HttpUtils.get("https://" + domainName);
            if (resp != null) {
                // HSTS
                String hsts = resp.getHeaders().getFirst("Strict-Transport-Security");
                security.put("hstsEnabled", hsts != null);
                if (hsts != null) {
                    security.put("hstsValue", hsts);
                }

                // Certificate Transparency (Expect-CT header)
                String expectCt = resp.getHeaders().getFirst("Expect-CT");
                security.put("certTransparency", expectCt != null);

                // Other security headers relevant to SSL
                String csp = resp.getHeaders().getFirst("Content-Security-Policy");
                security.put("hasCSP", csp != null);

                // Check if upgrade-insecure-requests is in CSP
                if (csp != null && csp.contains("upgrade-insecure-requests")) {
                    security.put("upgradeInsecureRequests", true);
                } else {
                    security.put("upgradeInsecureRequests", false);
                }
            }
        } catch (Exception e) {
            security.put("hstsEnabled", false);
            security.put("certTransparency", false);
            log.warn("Security config check failed for {}: {}", domainName, e.getMessage());
        }

        // Check supported TLS protocols
        List<String> supportedProtocols = new ArrayList<>();
        String[] protocols = {"TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1"};
        for (String proto : protocols) {
            if (testProtocol(domainName, proto)) {
                supportedProtocols.add(proto);
            }
        }
        security.put("protocolSupport", supportedProtocols);

        // Check HTTP to HTTPS redirect
        try {
            ResponseEntity<String> httpResp = HttpUtils.get("http://" + domainName);
            security.put("httpRedirect", httpResp != null && httpResp.getStatusCode().is3xxRedirection());
        } catch (Exception e) {
            security.put("httpRedirect", false);
        }

        return security;
    }

    private boolean testProtocol(String domainName, String protocol) {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
            };
            SSLContext sc = SSLContext.getInstance(protocol);
            sc.init(null, trustAll, new java.security.SecureRandom());
            SSLSocket socket = (SSLSocket) sc.getSocketFactory().createSocket(domainName, 443);
            socket.setSoTimeout(5000);
            socket.setEnabledProtocols(new String[]{protocol});
            socket.startHandshake();
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Issues Detection ====================

    private List<Map<String, String>> detectIssues(
            Map<String, Object> certInfo,
            Map<String, Object> securityInfo,
            Map<String, Object> validityInfo) {

        List<Map<String, String>> issues = new ArrayList<>();

        // Certificate not valid
        if (!Boolean.TRUE.equals(certInfo.get("currentlyValid"))) {
            issues.add(issue("Critical", "SSL certificate is not valid or could not be verified."));
        }

        // Expired
        if (Boolean.TRUE.equals(certInfo.get("isExpired"))) {
            issues.add(issue("Critical", "SSL certificate has expired! Renew immediately."));
        }

        // Expiring soon
        if (Boolean.TRUE.equals(certInfo.get("isExpiringSoon"))) {
            Object days = certInfo.get("daysUntilExpiration");
            issues.add(issue("Warning", "SSL certificate expires in " + days + " days. Plan renewal."));
        }

        // Weak signature algorithm
        String sigAlg = (String) certInfo.get("signatureAlgorithm");
        if (sigAlg != null && (sigAlg.contains("SHA1") || sigAlg.contains("MD5"))) {
            issues.add(issue("Warning", "Certificate uses weak signature algorithm: " + sigAlg + ". Upgrade to SHA-256 or higher."));
        }

        // Weak key size
        String keySize = (String) certInfo.get("publicKeySize");
        if (keySize != null && !keySize.equals("Unknown")) {
            try {
                int bits = Integer.parseInt(keySize.replace(" bits", ""));
                String alg = (String) certInfo.getOrDefault("publicKeyAlgorithm", "");
                if ("RSA".equals(alg) && bits < 2048) {
                    issues.add(issue("Warning", "RSA key size is " + bits + " bits. Minimum 2048 bits recommended."));
                }
            } catch (NumberFormatException e) { /* ignore */ }
        }

        // No HSTS
        if (!Boolean.TRUE.equals(securityInfo.get("hstsEnabled"))) {
            issues.add(issue("Info", "HSTS is not enabled. Add Strict-Transport-Security header to enforce HTTPS."));
        }

        // Legacy TLS versions
        @SuppressWarnings("unchecked")
        List<String> protocols = (List<String>) securityInfo.get("protocolSupport");
        if (protocols != null) {
            if (protocols.contains("TLSv1") || protocols.contains("TLSv1.1")) {
                issues.add(issue("Warning", "Legacy TLS protocols (1.0/1.1) are still supported. Disable them for better security."));
            }
            if (!protocols.contains("TLSv1.3")) {
                issues.add(issue("Info", "TLS 1.3 is not supported. Consider enabling it for improved performance and security."));
            }
        }

        // No HTTP redirect
        if (!Boolean.TRUE.equals(securityInfo.get("httpRedirect"))) {
            issues.add(issue("Info", "HTTP does not redirect to HTTPS. Configure a 301 redirect."));
        }

        return issues;
    }

    private Map<String, String> issue(String severity, String description) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("description", description);
        return m;
    }
}
