package info.wesite.web.mcp.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 9728 Protected Resource Metadata for the MCP endpoint. ChatGPT and other MCP clients fetch
 * this to discover the authorization server and available scopes.
 */
@RestController
@ConditionalOnProperty(prefix = "wesite.mcp.oauth", name = "enabled", havingValue = "true")
public class ProtectedResourceMetadataController {

    private final McpOAuthProperties properties;

    public ProtectedResourceMetadataController(McpOAuthProperties properties) {
        this.properties = properties;
    }

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> metadata() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", properties.getResource());
        body.put("authorization_servers", List.of(properties.getIssuer()));
        body.put("scopes_supported", properties.getScopes());
        body.put("resource_documentation", properties.getIssuer() + "/ai-connection");
        return body;
    }
}
