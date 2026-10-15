package info.wesite.web.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the public MCP endpoint. Everything is configurable so quotas and limits
 * are never hard-coded; the endpoint is disabled by default to preserve existing behaviour.
 */
@Component
@ConfigurationProperties(prefix = "wesite.mcp")
public class McpProperties {

    /** Master switch. When false no MCP endpoint, servlet or quota filter is registered. */
    private boolean enabled = false;

    /** Public MCP endpoint path. */
    private String endpoint = "/mcp";

    /** Server name advertised during MCP initialization. */
    private String serverName = "whose-domains";

    /** Server version advertised during MCP initialization. */
    private String serverVersion = "1.0.0";

    /** Maximum domains accepted by the batch tool. */
    private int maxDomainsPerBatch = 10;

    /** Maximum domains across one batch for the anonymous tier (hard cap). */
    private int maxDomainsPerBatchHardLimit = 50;

    /** Anonymous per-IP HTTP request budget per minute for the MCP endpoint. */
    private int requestsPerMinute = 60;

    /** Anonymous per-subnet daily request budget for the MCP endpoint. */
    private int dailyRequestLimit = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public int getMaxDomainsPerBatch() {
        return maxDomainsPerBatch;
    }

    public void setMaxDomainsPerBatch(int maxDomainsPerBatch) {
        this.maxDomainsPerBatch = maxDomainsPerBatch;
    }

    public int getMaxDomainsPerBatchHardLimit() {
        return maxDomainsPerBatchHardLimit;
    }

    public void setMaxDomainsPerBatchHardLimit(int maxDomainsPerBatchHardLimit) {
        this.maxDomainsPerBatchHardLimit = maxDomainsPerBatchHardLimit;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getDailyRequestLimit() {
        return dailyRequestLimit;
    }

    public void setDailyRequestLimit(int dailyRequestLimit) {
        this.dailyRequestLimit = dailyRequestLimit;
    }
}
