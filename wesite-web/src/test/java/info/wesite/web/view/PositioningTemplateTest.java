package info.wesite.web.view;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PositioningTemplateTest {

    @Test
    void homepageLeadsWithStructuredDataAndApi() throws IOException {
        String index = readTemplate("/views/index.html");

        assertTrue(index.contains("Structured WHOIS &amp; RDAP Data for Developers"));
        assertTrue(index.indexOf("/api-docs") < index.indexOf("/tools/whois-lookup"));
    }

    @Test
    void globalNavigationPrioritizesApiAndFooterUsesCurrentYear() throws IOException {
        String template = readTemplate("/views/template.html");

        assertTrue(template.indexOf("href=\"/api-docs\"") < template.indexOf("href=\"/tools\""));
        assertTrue(template.contains("href=\"/user/api-keys\""));
        assertTrue(template.contains("id=\"copyrightYear\""));
        assertTrue(template.contains("new Date().getFullYear()"));
    }

    private String readTemplate(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
