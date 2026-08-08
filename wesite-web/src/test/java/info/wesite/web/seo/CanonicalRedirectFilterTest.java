package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CanonicalRedirectFilterTest {

    private final CanonicalRedirectFilter filter = new CanonicalRedirectFilter(new CanonicalUrlService());

    @Test
    void redirectsWwwAndTrailingSlashAndPreservesQuery() throws Exception {
        MockHttpServletRequest request = request("GET", "/tools/whois-lookup/", "https", "www.whose.domains");
        request.setQueryString("d=example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(301, response.getStatus());
        assertEquals("https://whose.domains/tools/whois-lookup?d=example.com", response.getHeader("Location"));
        assertNull(chain.getRequest());
    }

    @Test
    void redirectsHttpHomePageToCanonicalOrigin() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "http", "whose.domains");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(301, response.getStatus());
        assertEquals("https://whose.domains/", response.getHeader("Location"));
    }

    @Test
    void redirectsHeadRequestsToCanonicalUrl() throws Exception {
        MockHttpServletRequest request = request("HEAD", "/info/what-is-whois/", "https", "www.whose.domains");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(301, response.getStatus());
        assertEquals("https://whose.domains/info/what-is-whois", response.getHeader("Location"));
    }

    @Test
    void redirectsRequestsOnNonDefaultHttpsPorts() throws Exception {
        MockHttpServletRequest request = request("GET", "/info/what-is-whois", "https", "whose.domains");
        request.setServerPort(8443);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(301, response.getStatus());
        assertEquals("https://whose.domains/info/what-is-whois", response.getHeader("Location"));
        assertNull(chain.getRequest());
    }

    @Test
    void passesThroughCanonicalHtmlRequests() throws Exception {
        MockHttpServletRequest request = request("GET", "/info/what-is-whois", "https", "whose.domains");

        assertPassedThrough(request);
    }

    @Test
    void doesNotRedirectPostApiOrSitemapRequests() throws Exception {
        assertPassedThrough(request("POST", "/domain/example.com/search", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/api/tools/score/example.com", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/sitemap_all.xml", "http", "www.whose.domains"));
    }

    @Test
    void doesNotRedirectStaticResourcesAuthenticationCallbacksOrOtherExcludedFiles() throws Exception {
        assertPassedThrough(request("GET", "/static/app.js", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/oauth2/authorization/google", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/login/oauth2/code/google", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/.well-known/security.txt", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/robots.txt", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/favicon.ico", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/site.webmanifest", "http", "www.whose.domains"));
    }

    @Test
    void doesNotRedirectExcludedFilesWithTrailingSlashesOrUppercaseExtensions() throws Exception {
        assertPassedThrough(request("GET", "/sitemap.XML/", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/robots.TXT/", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/favicon.ICO/", "http", "www.whose.domains"));
        assertPassedThrough(request("GET", "/site.WEBMANIFEST/", "http", "www.whose.domains"));
    }

    @Test
    void ignoresUntrustedForwardedHeaders() throws Exception {
        MockHttpServletRequest request = request("GET", "/info/what-is-whois", "http", "www.whose.domains");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "whose.domains");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(301, response.getStatus());
        assertEquals("https://whose.domains/info/what-is-whois", response.getHeader("Location"));
    }

    private void assertPassedThrough(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Location"));
        assertEquals(request, chain.getRequest());
    }

    private MockHttpServletRequest request(String method, String path, String scheme, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort("https".equalsIgnoreCase(scheme) ? 443 : 80);
        return request;
    }
}
