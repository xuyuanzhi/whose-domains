package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PricingPagesTemplateTest {

    @Test
    void pricingTemplateRendersConfiguredPlanCards() throws IOException {
        String template = resource("/views/pricing.html");

        assertTrue(template.contains("pricingGrid"));
        assertTrue(template.contains("th:each=\"plan : ${plans}\""));
        assertTrue(template.contains("href=\"/user/api-keys\""));
    }

    @Test
    void usageTemplateLoadsTheUsageEndpoint() throws IOException {
        String template = resource("/views/user/usage.html");

        assertTrue(template.contains("/api/user/usage"));
        assertTrue(template.contains("usageHistory"));
    }

    @Test
    void aiConnectionTemplateDescribesTheMcpEndpointAndTools() throws IOException {
        String template = resource("/views/ai_connection.html");

        assertTrue(template.contains("https://whose.domains/mcp"));
        assertTrue(template.contains("check_domain"));
        assertTrue(template.contains("get_domain_info"));
    }

    @Test
    void navigationLinksToTheNewPages() throws IOException {
        String template = resource("/views/template.html");

        assertTrue(template.contains("href=\"/pricing\""));
        assertTrue(template.contains("href=\"/ai-connection\""));
        assertTrue(template.contains("href=\"/user/usage\""));
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "Missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
