package info.wesite.web.controller.api;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Ping / 可用性检测 API
 * 双探测：InetAddress.isReachable (ICMP) + HTTP HEAD 请求
 */
@Tag(name = "Ping Test API")
@RestController
@RequestMapping("/api/tools")
public class PingTestController {

    private static final Logger log = LoggerFactory.getLogger(PingTestController.class);
    private static final int TIMEOUT_MS = 5000;
    private static final List<String> BLOCKED_PREFIXES = Arrays.asList(
        "127.", "10.", "192.168.", "169.254.", "0.", "::1", "fc", "fd"
    );

    @Autowired
    private QueryHistoryRecorder queryHistoryRecorder;

    @Operation(summary = "Ping / 可用性检测")
    @PostMapping("/ping")
    public ResponseJson<Map<String, Object>> ping(
            @RequestBody PingRequest request,
            HttpServletRequest httpRequest) {

        String ip = IpUtils.getRequestIp(httpRequest);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }

        String host = request.getHost();
        if (StringUtils.isBlank(host)) return ResponseJson.failure("Host is required.");

        host = host.toLowerCase().trim();
        if (host.startsWith("https://")) host = host.substring(8);
        if (host.startsWith("http://"))  host = host.substring(7);
        if (host.contains("/")) host = host.substring(0, host.indexOf('/'));

        // Resolve and block private ranges
        String resolvedIp = null;
        try {
            InetAddress addr = InetAddress.getByName(host);
            resolvedIp = addr.getHostAddress();
            for (String prefix : BLOCKED_PREFIXES) {
                if (resolvedIp.startsWith(prefix)) {
                    return ResponseJson.failure("Private/loopback addresses are not allowed.");
                }
            }
        } catch (Exception e) {
            return ResponseJson.failure("Cannot resolve host: " + host);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", host);
        result.put("resolvedIp", resolvedIp);

        // ── ICMP Ping ────────────────────────────────────────────────────────
        long icmpStart = System.currentTimeMillis();
        boolean icmpReachable = false;
        try {
            icmpReachable = InetAddress.getByName(host).isReachable(TIMEOUT_MS);
        } catch (Exception ignored) {}
        long icmpMs = System.currentTimeMillis() - icmpStart;
        result.put("icmpReachable", icmpReachable);
        result.put("icmpResponseMs", icmpMs);

        // ── HTTP HEAD ────────────────────────────────────────────────────────
        int httpStatus = -1;
        long httpMs = -1;
        boolean httpReachable = false;
        try {
            long httpStart = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection)
                new URL("https://" + host).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (compatible; WhoseDomains/1.0; +https://whose.domains)");
            httpStatus = conn.getResponseCode();
            httpMs = System.currentTimeMillis() - httpStart;
            httpReachable = httpStatus > 0 && httpStatus < 600;
            conn.disconnect();
        } catch (Exception e) {
            // Try HTTP fallback
            try {
                long httpStart = System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://" + host).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                httpStatus = conn.getResponseCode();
                httpMs = System.currentTimeMillis() - httpStart;
                httpReachable = httpStatus > 0 && httpStatus < 600;
                conn.disconnect();
            } catch (Exception ignored) {}
        }
        result.put("httpReachable", httpReachable);
        result.put("httpStatus", httpStatus > 0 ? httpStatus : null);
        result.put("httpResponseMs", httpMs > 0 ? httpMs : null);

        // ── Overall status ────────────────────────────────────────────────
        boolean online = icmpReachable || httpReachable;
        result.put("online", online);

        long avgMs = -1;
        if (icmpReachable && httpReachable) avgMs = (icmpMs + httpMs) / 2;
        else if (icmpReachable) avgMs = icmpMs;
        else if (httpReachable) avgMs = httpMs;
        result.put("avgResponseMs", avgMs > 0 ? avgMs : null);

        // Speed rating
        String speed = "N/A";
        if (avgMs > 0) {
            if (avgMs < 100) speed = "Excellent";
            else if (avgMs < 300) speed = "Good";
            else if (avgMs < 600) speed = "Fair";
            else speed = "Slow";
        }
        result.put("speed", speed);

        RateLimitUtils.incrementRequestCount(ip);
        queryHistoryRecorder.recordAsync(UserQueryHistory.TYPE_PING, host,
            online ? "Online — " + (avgMs > 0 ? avgMs + "ms" : "N/A") : "Offline");
        return ResponseJson.success(result);
    }

    public static class PingRequest {
        private String host;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
    }
}
