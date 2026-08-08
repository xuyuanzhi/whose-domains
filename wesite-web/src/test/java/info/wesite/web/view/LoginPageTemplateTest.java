package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class LoginPageTemplateTest {

    private static final String LOGIN_TEMPLATE_RESOURCE = "/views/login.html";
    private static final String COMMON_CSS_RESOURCE = "/static/style/common.css";

    @Test
    void standaloneGatewayKeepsBothSignInPathsAndIndependentStatusRegions() throws IOException {
        String template = resource(LOGIN_TEMPLATE_RESOURCE);

        assertTrue(template.contains("<h1 id=\"loginGatewayTitle\">Sign In</h1>"));
        assertTrue(template.contains("th:if=\"${_googleLoginEnabled}\""));
        assertTrue(template.contains("th:href=\"@{/login/google(returnTo=${returnTo})}\""));
        assertTrue(template.contains("id=\"loginEmail\""));
        assertTrue(template.contains("id=\"googleLoginMsg\" role=\"status\" aria-live=\"polite\""));
        assertTrue(template.contains("id=\"emailLoginMsg\" role=\"status\" aria-live=\"polite\""));
        assertTrue(template.contains("JSON.stringify({email:email,returnTo:returnTo})"));
    }

    @Test
    void standaloneGatewayIsMobileSafeAndKeyboardVisible() throws IOException {
        String css = resource(COMMON_CSS_RESOURCE);
        String cardRule = cssRule(css, ".login-gateway-card");
        String focusRule = cssRule(css,
                ".login-gateway :is(a, button, input):focus-visible");

        assertTrue(cardRule.contains("width: min(100% - 32px, 520px)"));
        assertTrue(cardRule.contains("max-width: 520px"));
        assertTrue(focusRule.contains("outline: 2px solid var(--primary)"));
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, () -> "Missing test resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String cssRule(String css, String selector) {
        int start = css.indexOf(selector + " {");
        int end = css.indexOf('}', start);
        return start >= 0 && end > start ? css.substring(start, end) : "";
    }
}
