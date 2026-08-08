package info.wesite.web.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Sitemap")
@Controller
public class SitemapController {

    private static final URI NGINX_MANAGED_SITEMAP = URI.create("/sitemap_all.xml");

    @Operation(summary = "Redirect retired sitemap routes to the production sitemap")
    @GetMapping({
            "/sitemap.xml",
            "/sitemap-static.xml",
            "/sitemap-tools.xml",
            "/sitemap-blog.xml",
            "/sitemap-tld-{page}.xml",
            "/sitemap-20260413.xml"
    })
    public ResponseEntity<Void> retiredSitemap() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(NGINX_MANAGED_SITEMAP)
                .build();
    }
}
