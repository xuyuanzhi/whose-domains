package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AuthModalTemplateTest {

    private static final String TEMPLATE_RESOURCE = "/views/template.html";

    @Test
    void emailSignInButtonShowsAndClearsSendingState() throws IOException {
        String template = template();

        assertTrue(template.contains("id=\"sendEmailLoginButton\""));
        assertTrue(template.contains("Sending link..."));
        assertTrue(template.contains("setEmailLoginLoading"));
    }

    @Test
    void googleSignInIsConditionalAndKeepsEmailAsAnAlternative() throws IOException {
        String template = template();

        assertTrue(template.contains("id=\"googleLoginButton\""));
        assertTrue(template.contains("th:if=\"${_googleLoginEnabled}\""));
        assertTrue(template.contains("href=\"/oauth2/authorization/google\""));
        assertTrue(template.contains("Continue with Google"));
        assertTrue(template.contains("class=\"auth-login-divider\""));
        assertTrue(template.contains(">or<"));
        assertTrue(template.contains("id=\"sendEmailLoginButton\""));
    }

    @Test
    void loginResultUsesFixedCodesAndTextContent() throws IOException {
        String template = template();

        assertTrue(template.contains("new URLSearchParams(location.search).get('login')"));
        assertTrue(template.contains("var loginMessages={"));
        assertTrue(template.contains("google_error:"));
        assertTrue(template.contains("google_invalid:"));
        assertTrue(template.contains("google_unverified:"));
        assertTrue(template.contains("google_conflict:"));
        assertTrue(template.contains("google_inactive:"));
        assertTrue(template.contains("google_email_unavailable:"));
        assertTrue(template.contains("google_expired:"));
        assertTrue(template.contains("google_check_email:"));
        assertTrue(template.contains("google_bind_required:"));
        assertTrue(template.contains("button.textContent='Finish with Google'"));
        assertTrue(template.contains("el.textContent=msg"));
    }

    private String template() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(TEMPLATE_RESOURCE)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
