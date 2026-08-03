package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ApiKeyConsoleTemplateTest {

    @Test
    void apiKeyConsoleProvidesSafeCreationAndManagementStates() throws IOException {
        String template = readTemplate("/views/user/api-keys.html");

        assertTrue(template.contains("api-key-console"));
        assertTrue(template.contains("one-time-key-panel"));
        assertTrue(template.contains("api-key-empty-state"));
        assertTrue(template.contains("navigator.clipboard.writeText"));
    }

    @Test
    void apiDocsLinkToApiKeyManagement() throws IOException {
        String template = readTemplate("/views/api_docs.html");

        assertTrue(template.contains("href=\"/user/api-keys\""));
        assertTrue(template.contains("api-start-steps"));
    }

    private String readTemplate(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
