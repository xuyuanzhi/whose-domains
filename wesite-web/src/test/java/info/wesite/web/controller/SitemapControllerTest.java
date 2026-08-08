package info.wesite.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SitemapControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SitemapController()).build();

    @ParameterizedTest
    @ValueSource(strings = {
            "/sitemap.xml",
            "/sitemap-static.xml",
            "/sitemap-tools.xml",
            "/sitemap-blog.xml",
            "/sitemap-tld-1.xml",
            "/sitemap-20260413.xml"
    })
    void retiredSitemapRoutesPermanentlyRedirectToNginxManagedSitemap(String route) throws Exception {
        mvc.perform(get(route))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/sitemap_all.xml"));
    }
}
