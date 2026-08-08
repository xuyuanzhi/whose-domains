package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AuthModalTemplateTest {

    private static final String TEMPLATE_RESOURCE = "/views/template.html";
    private static final String COMMON_CSS_RESOURCE = "/static/style/common.css";

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
    void googleButtonHasDecorativeProviderIconAndIndependentText() throws IOException {
        String template = template();

        assertTrue(template.contains("class=\"auth-google-icon\" aria-hidden=\"true\""));
        assertTrue(template.contains("id=\"googleLoginButtonText\">Continue with Google</span>"));
        assertTrue(template.contains("document.getElementById('googleLoginButtonText')"));
        assertTrue(template.contains("buttonText.textContent='Finish with Google'"));
    }

    @Test
    void googleButtonKeepsExplicitSpaceBetweenProviderIconAndLabel() throws IOException {
        String googleButtonRule = cssRule(resource(COMMON_CSS_RESOURCE), ".auth-google-button");

        assertTrue(googleButtonRule.contains("gap: 10px"));
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
        assertTrue(template.contains("buttonText.textContent='Finish with Google'"));
        assertTrue(template.contains("el.textContent=msg"));
    }

    @Test
    void standaloneLoginPageOwnsCallbackRenderingWithoutOpeningTheSharedModal() throws IOException {
        String template = template();
        int callbackHandler = template.indexOf("(function showLoginResult() {");
        int standaloneGuard = template.indexOf("if(location.pathname==='/login')return;", callbackHandler);
        int callbackRead = template.indexOf("new URLSearchParams(location.search).get('login')", callbackHandler);
        int modalOpen = template.indexOf("openAuthModal();", callbackHandler);

        assertTrue(callbackHandler >= 0);
        assertTrue(callbackHandler < standaloneGuard && standaloneGuard < callbackRead);
        assertTrue(callbackRead < modalOpen);
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

    @Test
    void signOutUsesAnAccessibleConfirmationDialog() throws IOException {
        String template = template();

        assertTrue(template.contains("<button type=\"button\" class=\"dropdown-menu-action\" onclick=\"openLogoutConfirm(this)\""));
        assertTrue(template.contains("id=\"logoutConfirmModal\" aria-hidden=\"true\""));
        assertTrue(template.contains("id=\"logoutConfirmDialog\" role=\"dialog\" aria-modal=\"true\""));
        assertTrue(template.contains("aria-labelledby=\"logoutConfirmTitle\""));
        assertTrue(template.contains("id=\"logoutConfirmMsg\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
        assertTrue(template.contains("id=\"logoutCancelButton\""));
        assertTrue(template.contains("id=\"logoutConfirmButton\""));
    }

    @Test
    void signOutButtonMatchesMenuActionsAndLogoutDialogIsScrollSafe() throws IOException {
        String css = resource(COMMON_CSS_RESOURCE);
        String menuButtonRule = cssRule(css, ".dropdown-menu .dropdown-menu-action");
        String menuButtonFocusRule = cssRule(css, ".dropdown-menu .dropdown-menu-action:focus-visible");
        String modalRule = cssRule(css, ".logout-confirm-modal");
        String dialogRule = cssRule(css, ".logout-confirm-dialog");

        assertTrue(menuButtonRule.contains("width: 100%"));
        assertTrue(menuButtonRule.contains("font: inherit"));
        assertTrue(menuButtonRule.contains("text-align: left"));
        assertTrue(menuButtonFocusRule.contains("outline: 2px solid var(--primary)"));
        assertTrue(modalRule.contains("overflow-y: auto"));
        assertTrue(dialogRule.contains("max-height: calc(100vh - 32px)"));
        assertTrue(dialogRule.contains("max-height: calc(100dvh - 32px)"));
        assertTrue(dialogRule.contains("overflow-y: auto"));
    }

    @Test
    void logoutDangerActionMeetsNormalTextContrast() throws IOException {
        String rule = cssRule(resource(COMMON_CSS_RESOURCE), "#logoutConfirmButton");

        assertTrue(rule.contains("background: #c62828"));
        assertTrue(contrastRatio("#ffffff", "#c62828") >= 4.5);
    }

    @Test
    void authModalHasADedicatedTitleBar() throws IOException {
        String template = template();

        assertTrue(template.contains("id=\"authModalTitleBar\" class=\"auth-modal-titlebar\""));
        assertTrue(template.contains("<h2 id=\"authModalTitle\">Sign In</h2>"));
        assertTrue(template.contains("class=\"auth-modal-close\""));
    }

    @Test
    void shortViewportsKeepTheTitleVisibleAndTheFormScrollable() throws IOException {
        String css = resource(COMMON_CSS_RESOURCE);
        String cardRule = cssRule(css, ".auth-modal-card");
        String titleBarRule = cssRule(css, ".auth-modal-titlebar");
        String formRule = cssRule(css, "#loginForm");

        assertTrue(cardRule.contains("max-height: calc(100vh - 16px)"));
        assertTrue(cardRule.contains("flex-direction: column"));
        assertTrue(titleBarRule.contains("flex: 0 0 auto"));
        assertTrue(formRule.contains("overflow-y: auto"));
        assertTrue(formRule.contains("min-height: 0"));
    }

    private String template() throws IOException {
        return resource(TEMPLATE_RESOURCE);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String cssRule(String css, String selector) {
        int start = css.indexOf(selector + " {");
        int end = css.indexOf('}', start);
        return start >= 0 && end > start ? css.substring(start, end) : "";
    }

    private double contrastRatio(String first, String second) {
        double firstLuminance = luminance(first);
        double secondLuminance = luminance(second);
        return (Math.max(firstLuminance, secondLuminance) + 0.05)
                / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private double luminance(String hex) {
        int value = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * linear((value >> 16) & 0xff)
                + 0.7152 * linear((value >> 8) & 0xff)
                + 0.0722 * linear(value & 0xff);
    }

    private double linear(int component) {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
