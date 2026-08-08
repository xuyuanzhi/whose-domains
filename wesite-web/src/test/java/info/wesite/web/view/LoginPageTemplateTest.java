package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class LoginPageTemplateTest {

    private static final String LOGIN_TEMPLATE_RESOURCE = "/views/login.html";
    private static final String SHARED_TEMPLATE_RESOURCE = "/views/template.html";
    private static final String COMMON_CSS_RESOURCE = "/static/style/common.css";
    private static final Pattern ID_ATTRIBUTE = Pattern.compile("\\bid=\\\"([^\\\"]+)\\\"");

    @Test
    void standaloneGatewayKeepsBothSignInPathsAndIndependentStatusRegions() throws IOException {
        String template = resource(LOGIN_TEMPLATE_RESOURCE);

        assertTrue(template.contains("<html xmlns:th=\"http://www.thymeleaf.org\" lang=\"en\" data-login-gateway>"));
        assertTrue(template.contains("<h1 id=\"loginGatewayTitle\">Sign In</h1>"));
        assertTrue(template.contains("th:if=\"${_googleLoginEnabled}\""));
        assertTrue(template.contains("th:href=\"@{/login/google(returnTo=${returnTo})}\""));
        assertTrue(template.contains("<label for=\"gatewayLoginEmail\">Email address</label>"));
        assertTrue(template.contains("id=\"gatewayLoginEmail\""));
        assertTrue(template.contains("id=\"gatewayGoogleLoginMsg\" role=\"status\" aria-live=\"polite\""));
        assertTrue(template.contains("id=\"gatewayEmailLoginMsg\" role=\"status\" aria-live=\"polite\""));
        assertTrue(template.contains("JSON.stringify({email:email,returnTo:returnTo})"));
    }

    @Test
    void standaloneGatewayIdsAreUniqueWithinItsOwnTemplate() throws IOException {
        Map<String, Integer> counts = idCounts(resource(LOGIN_TEMPLATE_RESOURCE));

        assertTrue(duplicateIds(counts).isEmpty(),
                () -> "IDs duplicated within login.html: " + duplicateIds(counts));
    }

    @Test
    void sharedFragmentIdsAreUniqueWithinItsOwnTemplate() throws IOException {
        Map<String, Integer> counts = idCounts(resource(SHARED_TEMPLATE_RESOURCE));

        assertTrue(duplicateIds(counts).isEmpty(),
                () -> "IDs duplicated within template.html: " + duplicateIds(counts));
    }

    @Test
    void standaloneGatewayIdsDoNotCollideWithSharedFragments() throws IOException {
        Set<String> gatewayIds = idCounts(resource(LOGIN_TEMPLATE_RESOURCE)).keySet();
        Set<String> sharedIds = idCounts(resource(SHARED_TEMPLATE_RESOURCE)).keySet();
        Set<String> collisions = new LinkedHashSet<>(gatewayIds);

        collisions.retainAll(sharedIds);

        assertTrue(collisions.isEmpty(), () -> "IDs duplicated after fragment composition: " + collisions);
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

    private Map<String, Integer> idCounts(String html) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = ID_ATTRIBUTE.matcher(html);
        while (matcher.find()) {
            counts.merge(matcher.group(1), 1, Integer::sum);
        }
        return counts;
    }

    private Set<String> duplicateIds(Map<String, Integer> counts) {
        Set<String> duplicates = new LinkedHashSet<>();
        counts.forEach((id, count) -> {
            if (count > 1) {
                duplicates.add(id);
            }
        });
        return duplicates;
    }
}
