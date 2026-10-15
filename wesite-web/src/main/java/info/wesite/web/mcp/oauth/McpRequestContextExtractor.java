package info.wesite.web.mcp.oauth;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts the authenticated account from the MCP request so tools can meter usage per user. The
 * bearer token was already validated by the resource server; here we only read its subject
 * (the Whose.Domains user id) and place it in the per-request MCP transport context.
 */
@Component
@ConditionalOnProperty(prefix = "wesite.mcp.oauth", name = "enabled", havingValue = "true")
public class McpRequestContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;

    public McpRequestContextExtractor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return McpTransportContext.EMPTY;
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()).trim());
            String userId = jwt.getSubject();
            if (userId == null || userId.isBlank()) {
                return McpTransportContext.EMPTY;
            }
            Map<String, Object> values = new HashMap<>();
            values.put("userId", userId);
            return McpTransportContext.create(values);
        } catch (RuntimeException invalidToken) {
            return McpTransportContext.EMPTY;
        }
    }
}
