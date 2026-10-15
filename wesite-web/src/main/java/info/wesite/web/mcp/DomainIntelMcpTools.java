package info.wesite.web.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;

import info.wesite.core.entity.ApiPlan;
import info.wesite.core.model.DomainIntelResult;
import info.wesite.core.model.QuotaDecision;
import info.wesite.core.service.ApiPlanService;
import info.wesite.core.service.ApiQuotaService;
import info.wesite.core.service.DomainIntelService;
import info.wesite.core.utils.DomainNameValidator;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * MCP tool surface for Whose.Domains. Each tool delegates to the shared {@link DomainIntelService}
 * so no lookup logic is duplicated; the tools only validate input, apply batch bounds and shape the
 * response for a model.
 *
 * <p>All three tools are read-only and only read public registry data, so they are declared with
 * {@code readOnlyHint=true} and {@code openWorldHint=true}.</p>
 */
@Component
public class DomainIntelMcpTools {

    private static final Logger log = LoggerFactory.getLogger(DomainIntelMcpTools.class);

    private final DomainIntelService domainIntelService;
    private final McpProperties properties;
    private final ApiPlanService apiPlanService;
    private final ApiQuotaService apiQuotaService;

    /** Anonymous-only wiring: per-IP quota is enforced by the transport filter. */
    public DomainIntelMcpTools(DomainIntelService domainIntelService, McpProperties properties) {
        this(domainIntelService, properties, null, null);
    }

    @Autowired
    public DomainIntelMcpTools(DomainIntelService domainIntelService, McpProperties properties,
            ApiPlanService apiPlanService, ApiQuotaService apiQuotaService) {
        this.domainIntelService = domainIntelService;
        this.properties = properties;
        this.apiPlanService = apiPlanService;
        this.apiQuotaService = apiQuotaService;
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(checkDomain(), checkDomains(), getDomainInfo());
    }

    private int batchLimit() {
        return Math.max(1, Math.min(properties.getMaxDomainsPerBatch(), properties.getMaxDomainsPerBatchHardLimit()));
    }

    SyncToolSpecification checkDomain() {
        Tool tool = Tool.builder("check_domain")
                .title("Check domain registration")
                .description("Check whether a single domain is registered, reserved or an unregistered "
                        + "candidate, and return the registration source and timestamp. Never reports a "
                        + "domain as available based only on DNS or HTTP results.")
                .inputSchema(domainInputSchema())
                .annotations(readOnlyAnnotations("Check domain registration"))
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> singleResult(exchange, arguments(request), "check_domain"))
                .build();
    }

    SyncToolSpecification getDomainInfo() {
        Tool tool = Tool.builder("get_domain_info")
                .title("Get domain information")
                .description("Return detailed registration intelligence for one domain (WHOIS/RDAP/DNS "
                        + "derived): registrar, dates, registry statuses, name servers, DNSSEC, WHOIS and "
                        + "RDAP endpoints, plus provenance and any lookup errors.")
                .inputSchema(domainInputSchema())
                .annotations(readOnlyAnnotations("Get domain information"))
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> singleResult(exchange, arguments(request), "get_domain_info"))
                .build();
    }

    SyncToolSpecification checkDomains() {
        int limit = batchLimit();
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "domains", Map.of(
                                "type", "array",
                                "description", "Domains to check in one call. Duplicates are collapsed.",
                                "items", Map.of("type", "string"),
                                "maxItems", limit,
                                "minItems", 1)),
                "required", List.of("domains"),
                "additionalProperties", false);
        Tool tool = Tool.builder("check_domains")
                .title("Check multiple domain registrations")
                .description("Check the registration status of several domains in one call (maximum "
                        + limit + " per call). Returns one result per unique domain; domains that fail "
                        + "are reported individually without failing the whole batch.")
                .inputSchema(schema)
                .annotations(readOnlyAnnotations("Check multiple domain registrations"))
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> batchResult(exchange, arguments(request), limit))
                .build();
    }

    private CallToolResult singleResult(McpSyncServerExchange exchange, Map<String, Object> arguments, String tool) {
        String raw = asString(arguments.get("domain"));
        String normalized = DomainNameValidator.normalize(raw);
        if (raw == null || normalized == null || !DomainNameValidator.isValid(normalized)) {
            return error("Invalid argument: 'domain' must be a valid domain name such as example.com.");
        }
        CallToolResult denied = enforceQuota(exchange, 1);
        if (denied != null) {
            return denied;
        }
        DomainIntelResult result = domainIntelService.check(normalized);
        return ok(result, "get_domain_info".equals(tool));
    }

    private CallToolResult batchResult(McpSyncServerExchange exchange, Map<String, Object> arguments, int limit) {
        Object value = arguments.get("domains");
        if (!(value instanceof List<?> rawList) || rawList.isEmpty()) {
            return error("Invalid argument: 'domains' must be a non-empty array of domain names.");
        }
        List<String> unique = new ArrayList<>();
        for (Object item : rawList) {
            String candidate = asString(item);
            if (candidate == null) {
                continue;
            }
            String normalized = DomainNameValidator.normalize(candidate);
            if (normalized != null && !unique.contains(normalized)) {
                unique.add(normalized);
            }
        }
        if (unique.isEmpty()) {
            return error("No valid domain names were supplied.");
        }
        if (unique.size() > limit) {
            return error("Too many domains: at most " + limit + " domains are allowed per call.");
        }
        CallToolResult denied = enforceQuota(exchange, unique.size());
        if (denied != null) {
            return denied;
        }

        List<Map<String, Object>> results = new ArrayList<>(unique.size());
        for (String domain : unique) {
            try {
                results.add(toMap(domainIntelService.check(domain), true));
            } catch (RuntimeException e) {
                log.warn("Batch MCP lookup failed for {}", domain, e);
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("domain", domain);
                failure.put("status", "unknown");
                failure.put("source", "none");
                failure.put("errors", List.of("lookup failed"));
                results.add(failure);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", unique.size());
        summary.put("results", results);
        return text(summary);
    }

    private CallToolResult ok(DomainIntelResult result, boolean detailed) {
        return text(toMap(result, detailed));
    }

    /**
     * Charges the authenticated account's plan when the request carries a validated bearer token.
     * Returns an error result when the quota is exhausted, or {@code null} when the call may proceed
     * (anonymous callers are limited by the transport filter instead).
     */
    private CallToolResult enforceQuota(McpSyncServerExchange exchange, int units) {
        if (apiQuotaService == null || apiPlanService == null || exchange == null
                || exchange.transportContext() == null) {
            return null;
        }
        Object userIdValue = exchange.transportContext().get("userId");
        if (!(userIdValue instanceof String userId) || userId.isBlank()) {
            return null;
        }
        ApiPlan plan = apiPlanService.resolveForUser(userId);
        QuotaDecision decision;
        try {
            decision = apiQuotaService.consume(userId, plan, null, Math.max(1, units));
        } catch (RuntimeException e) {
            log.warn("MCP quota check failed for user {}", userId, e);
            return error("Usage metering is temporarily unavailable. Please retry shortly.");
        }
        if (!decision.isAllowed()) {
            return error("API quota exceeded (" + decision.getReason()
                    + "). Please retry later or review your plan at /pricing.");
        }
        return null;
    }

    private CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private CallToolResult text(Map<String, Object> structured) {
        return CallToolResult.builder()
                .addTextContent(JSON.toJSONString(structured))
                .structuredContent(structured)
                .isError(false)
                .build();
    }

    private static Map<String, Object> toMap(DomainIntelResult result, boolean detailed) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("domain", result.getDomain());
        map.put("status", result.getStatus().wireName());
        map.put("source", result.getSource());
        map.put("checked_at", result.getCheckedAt());
        map.put("registrar", result.getRegistrar());
        map.put("expires_at", result.getExpiresAt());
        if (detailed) {
            map.put("cached", result.isCached());
            map.put("created_at", result.getCreatedAt());
            map.put("updated_at", result.getUpdatedAt());
            map.put("registry_statuses", result.getRegistryStatuses());
            map.put("name_servers", result.getNameServers());
            map.put("dnssec", result.getDnssec());
            map.put("whois_server", result.getWhoisServer());
            map.put("rdap_server", result.getRdapServer());
        }
        if (!result.getErrors().isEmpty()) {
            map.put("errors", result.getErrors());
        }
        return map;
    }

    private static Map<String, Object> domainInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "domain", Map.of(
                                "type", "string",
                                "description", "The domain name to check, for example example.com")),
                "required", List.of("domain"),
                "additionalProperties", false);
    }

    private static ToolAnnotations readOnlyAnnotations(String title) {
        return ToolAnnotations.builder()
                .title(title)
                .readOnlyHint(true)
                .destructiveHint(false)
                .idempotentHint(true)
                .openWorldHint(true)
                .build();
    }

    private static Map<String, Object> arguments(CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        return arguments == null ? Map.of() : arguments;
    }

    private static String asString(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }
}
