package info.wesite.web.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import info.wesite.core.entity.UserQueryHistory;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Email 验证工具 API
 */
@Tag(name = "Email Checker API")
@RestController
@RequestMapping("/api/tools")
public class EmailCheckerController {

    private static final Logger log = LoggerFactory.getLogger(EmailCheckerController.class);

    // RFC 5322 simplified regex
    private static final Pattern EMAIL_REGEX = Pattern.compile(
        "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    /** Common disposable/temporary email domains */
    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
        "mailinator.com", "guerrillamail.com", "guerrillamail.net", "guerrillamail.org",
        "guerrillamail.de", "guerrillamail.biz", "guerrillamail.info",
        "throwam.com", "trashmail.com", "trashmail.at", "trashmail.io",
        "yopmail.com", "yopmail.fr", "cool.fr.nf", "jetable.fr.nf",
        "spam4.me", "spamgourmet.com", "spamgourmet.net",
        "10minutemail.com", "10minutemail.net", "10minutemail.org",
        "tempmail.com", "temp-mail.org", "sharklasers.com",
        "dispostable.com", "fakeinbox.com", "getairmail.com",
        "maildrop.cc", "mailnull.com", "spamex.com",
        "mailnesia.com", "nospam.ze.tc",
        "spamherelots.com", "trashmail.me",
        "getnada.com", "filzmail.com", "discard.email",
        "spamspot.com", "spamthisplease.com", "binkmail.com",
        "bobmail.info", "chammy.info", "devnullmail.com",
        "frapmail.com", "obobbo.com", "rppkn.com"
    );

    @Autowired
    private QueryHistoryRecorder queryHistoryRecorder;

    @Operation(summary = "验证邮箱地址")
    @PostMapping("/email-check")
    public ResponseJson<Map<String, Object>> checkEmail(
            @RequestBody EmailCheckRequest request,
            HttpServletRequest httpRequest) {

        String ip = IpUtils.getRequestIp(httpRequest);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        String email = request.getEmail();
        if (StringUtils.isBlank(email)) {
            return ResponseJson.failure("Email address is required.");
        }
        email = email.toLowerCase().trim();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("email", email);

        // 1. Syntax check
        boolean syntaxOk = EMAIL_REGEX.matcher(email).matches();
        result.put("syntaxValid", syntaxOk);
        if (!syntaxOk) {
            result.put("syntaxError", "Invalid email format (RFC 5322)");
            result.put("overallValid", false);
            return ResponseJson.success(result);
        }

        String domain = email.substring(email.indexOf('@') + 1);
        result.put("domain", domain);

        // 2. Disposable check
        boolean isDisposable = DISPOSABLE_DOMAINS.contains(domain);
        result.put("isDisposable", isDisposable);

        // 3. MX record check
        boolean hasMx = false;
        String mxRecord = null;
        try {
            InitialDirContext ctx = new InitialDirContext();
            Attributes attrs = ctx.getAttributes("dns:/" + domain, new String[]{"MX"});
            javax.naming.directory.Attribute mx = attrs.get("MX");
            if (mx != null && mx.size() > 0) {
                hasMx = true;
                mxRecord = mx.get(0).toString();
                // Extract just the hostname part (strip priority)
                if (mxRecord.contains(" ")) {
                    mxRecord = mxRecord.substring(mxRecord.lastIndexOf(' ') + 1);
                }
            }
        } catch (Exception e) {
            log.debug("MX lookup failed for domain {}: {}", domain, e.getMessage());
        }
        result.put("hasMxRecord", hasMx);
        result.put("mxRecord", mxRecord);

        // 4. Domain A record check
        boolean domainExists = false;
        try {
            InitialDirContext ctx2 = new InitialDirContext();
            // 建议：分开查，只要有一个存在就算成功
            Attributes aAttrs = ctx2.getAttributes("dns:/" + domain, new String[]{"A"});
            if (aAttrs.get("A") != null) {
                domainExists = true;
            } else {
                // 如果 A 查不到，再试一下 AAAA
                Attributes aaaaAttrs = ctx2.getAttributes("dns:/" + domain, new String[]{"AAAA"});
                domainExists = (aaaaAttrs.get("AAAA") != null);
            }
        } catch (Exception e) {
            // Also check via InetAddress
            try {
                java.net.InetAddress.getByName(domain);
                domainExists = true;
            } catch (Exception ignored) {}
        }
        result.put("domainExists", domainExists);

        // 5. Overall verdict
        boolean overallValid = syntaxOk && !isDisposable && hasMx && domainExists;
        result.put("overallValid", overallValid);

        // Score (0-100)
        int score = 0;
        if (syntaxOk) score += 30;
        if (domainExists) score += 25;
        if (hasMx) score += 35;
        if (!isDisposable) score += 10;
        result.put("score", score);

        // Summary message
        String summary;
        if (overallValid) {
            summary = "Email appears valid and deliverable.";
        } else if (isDisposable) {
            summary = "Disposable/temporary email address detected.";
        } else if (!hasMx) {
            summary = "Domain has no MX records — email cannot be delivered.";
        } else if (!domainExists) {
            summary = "Domain does not exist.";
        } else {
            summary = "Email may not be deliverable.";
        }
        result.put("summary", summary);

        RateLimitUtils.incrementRequestCount(ip);
        queryHistoryRecorder.recordAsync(UserQueryHistory.TYPE_EMAIL, email, summary);
        return ResponseJson.success(result);
    }

    public static class EmailCheckRequest {
        private String email;
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }
}
