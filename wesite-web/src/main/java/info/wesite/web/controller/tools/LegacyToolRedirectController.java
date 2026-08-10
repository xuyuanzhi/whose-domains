package info.wesite.web.controller.tools;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import info.wesite.web.seo.CanonicalToolRoutes;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class LegacyToolRedirectController {

    @GetMapping({
            CanonicalToolRoutes.LEGACY_DOMAIN_ANALYZER,
            CanonicalToolRoutes.LEGACY_DNS_ANALYZER,
            CanonicalToolRoutes.LEGACY_SSL_CHECKER,
            CanonicalToolRoutes.LEGACY_COMPETITOR_ANALYSIS
    })
    public ResponseEntity<Void> redirect(HttpServletRequest request) {
        String target = CanonicalToolRoutes.canonicalFor(request.getRequestURI()).orElseThrow();
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create(target))
                .build();
    }
}
