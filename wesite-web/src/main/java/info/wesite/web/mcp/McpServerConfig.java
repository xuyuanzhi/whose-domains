package info.wesite.web.mcp;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Registers a read-only, anonymous MCP endpoint over the MCP Streamable HTTP transport (the
 * servlet transport shipped in the official SDK). The endpoint is disabled unless
 * {@code wesite.mcp.enabled=true}, so existing web behaviour is unchanged by default.
 *
 * <p>Only the public {@code /mcp} endpoint uses this; the website APIs are untouched. The tools
 * delegate to the shared {@code DomainIntelService}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "wesite.mcp", name = "enabled", havingValue = "true")
public class McpServerConfig {

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(McpProperties properties) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint(properties.getEndpoint())
                .keepAliveInterval(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider, McpProperties properties) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, properties.getEndpoint());
        registration.setName("mcpStreamableServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<McpQuotaFilter> mcpQuotaFilter(
            StringRedisTemplate redis, McpProperties properties) {
        FilterRegistrationBean<McpQuotaFilter> registration =
                new FilterRegistrationBean<>(new McpQuotaFilter(redis, properties));
        registration.addUrlPatterns(properties.getEndpoint());
        registration.setName("mcpAnonymousQuotaFilter");
        registration.setOrder(1);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transportProvider,
            McpProperties properties, DomainIntelMcpTools tools) {
        return McpServer.sync(transportProvider)
                .serverInfo(properties.getServerName(), properties.getServerVersion())
                .instructions("Whose.Domains domain intelligence. Use check_domain for one domain, "
                        + "check_domains for a small batch, and get_domain_info for full registration "
                        + "details. Status values: registered, available, unregistered_candidate, "
                        + "reserved, unknown. A missing DNS record or a failed lookup never means a "
                        + "domain is available; only 'available' indicates a confirmed registrable name, "
                        + "and this server currently reports 'unregistered_candidate' when a registry "
                        + "source simply has no record.")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(tools.specifications())
                .build();
    }
}
