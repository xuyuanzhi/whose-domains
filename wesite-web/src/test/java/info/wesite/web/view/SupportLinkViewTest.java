package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class SupportLinkViewTest {

    private static final String PAYPAL_URL = "https://www.paypal.com/ncp/payment/ABC123";

    @Test
    void configuredSupportLinkRendersInAboutPageAndFooter() {
        Document document = Jsoup.parse(render(Set.of("#support-panel", "#footer-support-item"), PAYPAL_URL));

        Element aboutLink = document.selectFirst("#support-panel a[href='" + PAYPAL_URL + "']");
        Element footerLink = document.selectFirst("#footer-support-link[href='" + PAYPAL_URL + "']");
        assertNotNull(aboutLink);
        assertNotNull(footerLink);
        assertExternalPaypalLink(aboutLink);
        assertExternalPaypalLink(footerLink);
        assertTrue(document.selectFirst("#support-panel").text().contains("processed by PayPal"));
        assertTrue(document.selectFirst("#support-panel").text().contains("not a charitable donation"));
    }

    @Test
    void missingConfigurationOmitsSupportCallsToAction() {
        Document document = Jsoup.parse(render(Set.of("#support-panel", "#footer-support-item"), null));

        assertTrue(document.select("#support-panel").isEmpty(), document.outerHtml());
        assertTrue(document.select("#footer-support-item").isEmpty(), document.outerHtml());
    }

    private void assertExternalPaypalLink(Element link) {
        assertEquals("_blank", link.attr("target"));
        assertTrue(link.attr("rel").contains("noopener"));
        assertTrue(link.attr("rel").contains("noreferrer"));
        assertTrue(link.selectFirst(".sr-only").text().contains("new tab"));
    }

    private String render(Set<String> selectors, String supportUrl) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/views/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(servletContext)
                        .buildExchange(request, response),
                Locale.ROOT);
        if (supportUrl != null) {
            context.setVariable("supportUrl", supportUrl);
        }

        String about = engine.process("about_us", selectors, context);
        String footer = engine.process("template", selectors, context);
        return about + footer;
    }
}
