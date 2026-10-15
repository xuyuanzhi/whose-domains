package info.wesite.web.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProtectedResourceMetadataControllerTest {

    @Test
    void advertisesTheResourceAuthorizationServerAndScopes() {
        McpOAuthProperties properties = new McpOAuthProperties();
        properties.setIssuer("https://whose.domains");
        properties.setResource("https://whose.domains/mcp");
        properties.setScopes(List.of("domain.read"));

        Map<String, Object> body = new ProtectedResourceMetadataController(properties).metadata();

        assertEquals("https://whose.domains/mcp", body.get("resource"));
        assertEquals(List.of("https://whose.domains"), body.get("authorization_servers"));
        assertEquals(List.of("domain.read"), body.get("scopes_supported"));
    }
}
