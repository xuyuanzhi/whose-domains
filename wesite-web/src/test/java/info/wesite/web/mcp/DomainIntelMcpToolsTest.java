package info.wesite.web.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import info.wesite.core.model.DomainIntelResult;
import info.wesite.core.model.RegistrationStatus;
import info.wesite.core.service.DomainIntelService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class DomainIntelMcpToolsTest {

    private DomainIntelService service;
    private McpProperties properties;
    private DomainIntelMcpTools tools;

    @BeforeEach
    void setUp() {
        service = mock(DomainIntelService.class);
        properties = new McpProperties();
        tools = new DomainIntelMcpTools(service, properties);
    }

    private SyncToolSpecification spec(String name) {
        return tools.specifications().stream()
                .filter(s -> s.tool().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private CallToolResult call(SyncToolSpecification spec, Map<String, Object> arguments) {
        return spec.callHandler().apply(null, new CallToolRequest(spec.tool().name(), arguments));
    }

    @Test
    void exposesTheThreeReadOnlyToolsWithAccurateAnnotations() {
        Set<String> names = tools.specifications().stream()
                .map(s -> s.tool().name()).collect(Collectors.toSet());
        assertEquals(Set.of("check_domain", "check_domains", "get_domain_info"), names);

        for (SyncToolSpecification spec : tools.specifications()) {
            assertTrue(spec.tool().description() != null && !spec.tool().description().isBlank());
            assertNotNull(spec.tool().inputSchema());
            assertEquals(Boolean.TRUE, spec.tool().annotations().readOnlyHint());
            assertEquals(Boolean.FALSE, spec.tool().annotations().destructiveHint());
            assertEquals(Boolean.TRUE, spec.tool().annotations().openWorldHint());
        }
    }

    @Test
    void invalidDomainIsAToolLevelError() {
        CallToolResult result = call(spec("check_domain"), Map.of("domain", "not a domain"));
        assertTrue(result.isError());
    }

    @Test
    void singleCheckReturnsStructuredContent() {
        DomainIntelResult registered = new DomainIntelResult();
        registered.setDomain("example.com");
        registered.setStatus(RegistrationStatus.REGISTERED);
        registered.setSource("rdap");
        registered.setCheckedAt("2026-10-15T00:00:00Z");
        when(service.check("example.com")).thenReturn(registered);

        CallToolResult result = call(spec("check_domain"), Map.of("domain", "Example.com"));

        assertFalse(result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertEquals("registered", structured.get("status"));
        assertEquals("rdap", structured.get("source"));
    }

    @Test
    void batchCollapsesDuplicatesAndEnforcesTheCap() {
        properties.setMaxDomainsPerBatch(2);

        CallToolResult ok = call(spec("check_domains"), Map.of("domains", List.of("a.com", "A.com", "b.com")));
        assertFalse(ok.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) ok.structuredContent();
        assertEquals(2, structured.get("total"));

        CallToolResult tooMany = call(spec("check_domains"),
                Map.of("domains", List.of("a.com", "b.com", "c.com")));
        assertTrue(tooMany.isError());
    }

    @Test
    void batchReportsPerDomainFailuresWithoutFailingTheWholeCall() {
        when(service.check(anyString())).thenThrow(new RuntimeException("registry down"));

        CallToolResult result = call(spec("check_domains"), Map.of("domains", List.of("a.com", "b.com")));

        assertFalse(result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) structured.get("results");
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> "unknown".equals(r.get("status"))));
    }
}
