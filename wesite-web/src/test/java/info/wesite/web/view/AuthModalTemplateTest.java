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

    @Test
    void authenticationMethodsKeepTheirCopyAndStatusTogether() throws IOException {
        String template = template();

        int googleSection = template.indexOf("class=\"auth-method auth-google-section\"");
        int googleButton = template.indexOf("id=\"googleLoginButton\"");
        int googleMessage = template.indexOf("id=\"googleLoginMsg\"");
        int divider = template.indexOf("class=\"auth-login-divider\"");
        int emailSection = template.indexOf("class=\"auth-method auth-email-section\"");
        int emailCopy = template.indexOf("We'll email you a secure, password-free sign-in link.");
        int emailButton = template.indexOf("id=\"sendEmailLoginButton\"");
        int emailMessage = template.indexOf("id=\"emailLoginMsg\"");

        assertTrue(googleSection < googleButton && googleButton < googleMessage);
        assertTrue(googleMessage < divider && divider < emailSection);
        assertTrue(emailSection < emailCopy && emailCopy < emailButton && emailButton < emailMessage);
        assertTrue(template.contains("id=\"googleLoginMsg\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
        assertTrue(template.contains("id=\"emailLoginMsg\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
    }

    @Test
    void authenticationResultsRouteToTheirOwnMethod() throws IOException {
        String template = template();

        assertTrue(template.contains("setAuthMsg('emailLoginMsg','Please enter your email address.'"));
        assertTrue(template.contains("setAuthMsg('emailLoginMsg',d.msg||'Check your inbox for a sign-in link.'"));
        assertTrue(template.contains("function loginMessageTarget(loginCode)"));
        assertTrue(template.contains("return loginCode==='invalid'?'emailLoginMsg':'googleLoginMsg'"));
        assertTrue(template.contains("setAuthMsg(loginMessageTarget(loginCode),result.message,result.color)"));
    }

    @Test
    void authModalHasAccessibleDialogSemantics() throws IOException {
        String template = template();

        assertTrue(template.contains("id=\"authModal\""));
        assertTrue(template.contains("aria-hidden=\"true\""));
        assertTrue(template.contains("role=\"dialog\""));
        assertTrue(template.contains("aria-modal=\"true\""));
        assertTrue(template.contains("aria-labelledby=\"authModalTitle\""));
        assertTrue(template.contains("id=\"authModalTitle\""));
        assertTrue(template.contains("tabindex=\"-1\""));
    }

    @Test
    void authModalMovesTrapsAndRestoresFocus() throws IOException {
        String template = template();

        assertTrue(template.contains("openAuthModal(this)"));
        assertTrue(template.contains("authModal.setAttribute('aria-hidden','false')"));
        assertTrue(template.contains("authModal.setAttribute('aria-hidden','true')"));
        assertTrue(template.contains("document.getElementById('googleLoginButton')||document.getElementById('loginEmail')"));
        assertTrue(template.contains("authModalTrigger.focus()"));
        assertTrue(template.contains("event.key==='Escape'"));
        assertTrue(template.contains("event.key!=='Tab'"));
        assertTrue(template.contains("event.shiftKey"));
    }

    private String template() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(TEMPLATE_RESOURCE)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
