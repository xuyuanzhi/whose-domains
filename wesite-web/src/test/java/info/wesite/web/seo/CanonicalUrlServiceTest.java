package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CanonicalUrlServiceTest {

    private final CanonicalUrlService service = new CanonicalUrlService();

    @Test
    void normalizesRootAndHtmlTrailingSlashes() {
        assertEquals("/", service.normalizePath("/"));
        assertEquals("/tools/whois-lookup", service.normalizePath("/tools/whois-lookup/"));
        assertEquals("/tools/whois-lookup", service.normalizePath("//tools//whois-lookup//"));
    }

    @Test
    void buildsCanonicalUrlsFromTheSingleProductionOrigin() {
        assertEquals("https://whose.domains/", service.canonicalUrl("/"));
        assertEquals("https://whose.domains/info/what-is-whois",
                service.canonicalUrl("/info/what-is-whois/"));
    }
}
