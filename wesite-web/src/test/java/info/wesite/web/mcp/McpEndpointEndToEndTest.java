package info.wesite.web.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

import info.wesite.core.model.DomainIntelResult;
import info.wesite.core.model.RegistrationStatus;
import info.wesite.core.service.DomainIntelService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import jakarta.servlet.ServletRegistration;

/**
 * End-to-end test of the MCP endpoint over real HTTP: an embedded servlet container hosts the
 * Streamable HTTP transport and the official MCP Java client drives initialize / listTools /
 * callTool. No MySQL, Redis or Spring context is required — the domain service is stubbed — so this
 * verifies the connector wire protocol independently of the environment.
 */
class McpEndpointEndToEndTest {

    private static final String ENDPOINT = "/mcp";

    private static DomainIntelService stubService() {
        return domain -> {
            DomainIntelResult result = new DomainIntelResult();
            if ("example.com".equals(domain)) {
                result.setDomain(domain);
                result.setStatus(RegistrationStatus.REGISTERED);
                result.setSource("rdap");
                result.setCheckedAt("2026-10-15T00:00:00Z");
                result.setRegistrar("Example Registrar");
            } else {
                result.setDomain(domain);
                result.setStatus(RegistrationStatus.UNKNOWN);
                result.setSource("none");
                result.setCheckedAt("2026-10-15T00:00:00Z");
            }
            return result;
        };
    }

    @Test
    void clientCanInitializeListToolsAndCallToolsOverHttp() throws Exception {
        McpProperties properties = new McpProperties();
        HttpServletStreamableServerTransportProvider transportProvider =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(McpJsonDefaults.getMapper())
                        .mcpEndpoint(ENDPOINT)
                        .build();

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("whose-domains-test", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(new DomainIntelMcpTools(stubService(), properties).specifications())
                .build();

        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        WebServer webServer = factory.getWebServer(servletContext -> {
            ServletRegistration.Dynamic registration = servletContext.addServlet("mcpStreamableServlet", transportProvider);
            registration.setLoadOnStartup(1);
            registration.setAsyncSupported(true);
            registration.addMapping(ENDPOINT);
        });
        webServer.start();

        String baseUrl = "http://localhost:" + webServer.getPort();
        try (McpSyncClient client = McpClient
                .sync(HttpClientStreamableHttpTransport.builder(baseUrl).endpoint(ENDPOINT).build())
                .requestTimeout(Duration.ofSeconds(15))
                .clientInfo(new Implementation("whose-domains-e2e", "1.0.0"))
                .build()) {

            assertTrue(client.initialize().serverInfo().name().contains("whose-domains"));

            Set<String> toolNames = client.listTools().tools().stream().map(t -> t.name())
                    .collect(Collectors.toSet());
            assertEquals(Set.of("check_domain", "check_domains", "get_domain_info"), toolNames);

            CallToolResult single = client.callTool(new CallToolRequest("check_domain", Map.of("domain", "example.com")));
            assertFalse(Boolean.TRUE.equals(single.isError()));
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) single.structuredContent();
            assertNotNull(structured);
            assertEquals("registered", structured.get("status"));

            CallToolResult invalid = client.callTool(new CallToolRequest("check_domain", Map.of("domain", "not a domain")));
            assertTrue(Boolean.TRUE.equals(invalid.isError()));

            CallToolResult batch = client.callTool(new CallToolRequest("check_domains",
                    Map.of("domains", List.of("example.com", "unknown.example", "example.com"))));
            assertFalse(Boolean.TRUE.equals(batch.isError()));
            @SuppressWarnings("unchecked")
            Map<String, Object> batchBody = (Map<String, Object>) batch.structuredContent();
            assertEquals(2, batchBody.get("total"));
        } finally {
            webServer.stop();
            server.closeGracefully();
        }
    }
}
