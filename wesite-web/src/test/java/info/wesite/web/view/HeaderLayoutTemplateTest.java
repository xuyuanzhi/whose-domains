package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class HeaderLayoutTemplateTest {

    @Test
    void headerLogoUsesTheSourceImageAspectRatio() throws IOException {
        Document template = Jsoup.parse(resource("/views/template.html"));
        Element logo = template.selectFirst(".navbar .logo img");
        assertNotNull(logo);

        BufferedImage source = image("/static/image/transparent-logo.png");
        int renderedWidth = Integer.parseInt(logo.attr("width"));
        int renderedHeight = Integer.parseInt(logo.attr("height"));

        assertEquals(source.getWidth() * renderedHeight, source.getHeight() * renderedWidth,
                "The declared logo dimensions must preserve the source image aspect ratio");

        List<String> logoRules = cssRules(resource("/static/style/common.css"), ".logo img");
        assertTrue(logoRules.stream().anyMatch(rule -> hasCssProperty(rule, "height", "56px")
                && hasCssProperty(rule, "width", "auto")),
                "The desktop logo must derive its width from the source aspect ratio");
        assertTrue(logoRules.stream().anyMatch(rule -> hasCssProperty(rule, "height", "40px")
                && hasCssProperty(rule, "width", "auto")),
                "The mobile logo must derive its width from the source aspect ratio");
    }

    @Test
    void desktopNavigationCentersMenuItemsAndTheirContents() throws IOException {
        String css = resource("/static/style/common.css");

        assertEquals("flex", cascadedProperty(css, ".nav-links", "display"));
        assertEquals("center", cascadedProperty(css, ".nav-links", "align-items"));
        assertEquals("flex", cascadedProperty(css, ".nav-links > li", "display"));
        assertEquals("center", cascadedProperty(css, ".nav-links > li", "align-items"));
        assertEquals("flex", cascadedProperty(css, ".nav-links a", "display"));
        assertEquals("center", cascadedProperty(css, ".nav-links a", "align-items"));
    }

    @Test
    void userMenuSeparatesTheAvatarFromTheUserName() throws IOException {
        String css = resource("/static/style/common.css");

        assertEquals("4px", cascadedProperty(css,
                "#navUserToggle > .fa-user-circle", "margin-right"));
    }

    private BufferedImage image(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "Missing resource " + path);
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image, "Unreadable image " + path);
            return image;
        }
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "Missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String cascadedProperty(String css, String selector, String property) {
        String value = null;
        for (String rule : cssRules(css, selector)) {
            Matcher declaration = propertyPattern(property).matcher(rule);
            while (declaration.find()) {
                value = declaration.group(1).trim();
            }
        }
        assertNotNull(value, "Missing property " + property + " for " + selector);
        return value;
    }

    private boolean hasCssProperty(String rule, String property, String expectedValue) {
        Matcher declaration = propertyPattern(property).matcher(rule);
        while (declaration.find()) {
            if (expectedValue.equals(declaration.group(1).trim())) {
                return true;
            }
        }
        return false;
    }

    private Pattern propertyPattern(String property) {
        return Pattern.compile("(?:^|;)\\s*" + Pattern.quote(property) + "\\s*:\\s*([^;]+)");
    }

    private List<String> cssRules(String css, String selector) {
        Pattern rulePattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + "\\s*\\{([^}]*)}");
        List<String> rules = rulePattern.matcher(css).results()
                .map(result -> result.group(1))
                .toList();
        assertFalse(rules.isEmpty(), "Missing selector " + selector);
        return rules;
    }
}
