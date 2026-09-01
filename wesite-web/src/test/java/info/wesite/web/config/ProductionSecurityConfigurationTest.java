package info.wesite.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class ProductionSecurityConfigurationTest {

    @Test
    void webProductionExampleSecuresSessionCookiesAndAcceptsForwardedHeadersOnlyFromTrustedProxies()
            throws IOException {
        Properties properties = properties("src", "main", "resources", "application-prod.properties.example");

        assertSessionCookiePolicy(properties);
        assertEquals("native", properties.getProperty("server.forward-headers-strategy"));
        assertEquals("x-forwarded-proto", properties.getProperty("server.tomcat.remoteip.protocol-header"));
        assertEquals("x-forwarded-host", properties.getProperty("server.tomcat.remoteip.host-header"));
        assertEquals("${WESITE_TRUSTED_PROXY_REGEX:127\\.0\\.0\\.1|::1}",
                properties.getProperty("server.tomcat.remoteip.internal-proxies"));
        assertEquals("${WESITE_AUTH_COOKIE_SECURE:true}", properties.getProperty("wesite.auth-cookie-secure"));
    }

    @Test
    void adminProductionExampleSecuresSessionCookiesAndTrustsOnlyConfiguredProxies() throws IOException {
        Properties properties = properties("..", "wesite-admin", "src", "main", "resources",
                "application-prod.properties.example");

        assertSessionCookiePolicy(properties);
        assertEquals("native", properties.getProperty("server.forward-headers-strategy"));
        assertEquals("${WESITE_TRUSTED_PROXY_REGEX:127\\.0\\.0\\.1|::1}",
                properties.getProperty("server.tomcat.remoteip.internal-proxies"));
    }

    @Test
    void applicationAuthCookieIsSecureByDefaultUnlessDevelopmentExplicitlyOverridesIt() throws IOException {
        Properties properties = properties("src", "main", "resources", "application.properties");

        assertEquals("${WESITE_AUTH_COOKIE_SECURE:true}", properties.getProperty("wesite.auth-cookie-secure"));
    }

    @Test
    void productionExampleExplicitlyEnablesCanonicalRedirect() throws IOException {
        Properties production = properties("src", "main", "resources", "application-prod.properties.example");

        assertEquals("true", production.getProperty("wesite.seo.canonical-redirect-enabled"));
    }

    private void assertSessionCookiePolicy(Properties properties) {
        assertEquals("JSESSIONID", properties.getProperty("server.servlet.session.cookie.name"));
        assertEquals("true", properties.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("true", properties.getProperty("server.servlet.session.cookie.http-only"));
        assertEquals("lax", properties.getProperty("server.servlet.session.cookie.same-site"));
    }

    private Properties properties(String first, String... more) throws IOException {
        Path path = Path.of(first, more);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }
}
