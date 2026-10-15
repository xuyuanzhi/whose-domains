package info.wesite.web.mcp;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Anonymous free-tier quota for the MCP endpoint.
 *
 * <p>The MCP connector is open (no OAuth in this phase), so the anonymous identity is the client
 * IP/subnet: a per-IP request budget per minute (reusing {@link RateLimitUtils}) plus a per-subnet
 * daily request budget in Redis. When Redis is unavailable the filter fails open so a metering
 * outage cannot take the connector down; this is logged.</p>
 */
public class McpQuotaFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpQuotaFilter.class);
    private static final String DAILY_KEY_PREFIX = "mcp:anon:";
    private static final Duration DAILY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;
    private final McpProperties properties;

    public McpQuotaFilter(StringRedisTemplate redis, McpProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String ip = IpUtils.getRequestIp(httpRequest);

        if (!RateLimitUtils.isAllowed("mcp:" + ip, properties.getRequestsPerMinute(), 60_000)) {
            reject(httpResponse, "rate_limit_exceeded", "Too many requests. Please retry shortly.");
            return;
        }

        try {
            String key = DAILY_KEY_PREFIX + LocalDate.now() + ":" + IpUtils.subnetId(ip);
            Long used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redis.expire(key, DAILY_TTL);
            }
            if (used != null && used > properties.getDailyRequestLimit()) {
                reject(httpResponse, "daily_quota_exceeded",
                        "Anonymous daily quota exceeded. Please try again tomorrow.");
                return;
            }
        } catch (RuntimeException e) {
            log.warn("MCP anonymous metering unavailable; allowing request", e);
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
