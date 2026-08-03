package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AuthModalTemplateTest {

    @Test
    void emailSignInButtonShowsAndClearsSendingState() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/views/template.html")) {
            String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(template.contains("id=\"sendEmailLoginButton\""));
            assertTrue(template.contains("Sending link..."));
            assertTrue(template.contains("setEmailLoginLoading"));
        }
    }
}
