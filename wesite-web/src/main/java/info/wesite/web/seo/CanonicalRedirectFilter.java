package info.wesite.web.seo;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(
        name = "wesite.seo.canonical-redirect-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CanonicalRedirectFilter extends OncePerRequestFilter {

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/api/", "/static/", "/oauth2/", "/login/", "/.well-known/");
    private static final Set<String> EXCLUDED_SUFFIXES = Set.of(".xml", ".txt", ".ico", ".webmanifest");

    private final CanonicalUrlService canonicalUrlService;

    public CanonicalRedirectFilter(CanonicalUrlService canonicalUrlService) {
        this.canonicalUrlService = canonicalUrlService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))
                || EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith)
                || isExcludedFile(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String canonicalPath = canonicalUrlService.normalizePath(path);
        boolean canonicalRequest = "https".equalsIgnoreCase(request.getScheme())
                && "whose.domains".equals(request.getServerName())
                && request.getServerPort() == 443
                && path.equals(canonicalPath);

        if (canonicalRequest) {
            filterChain.doFilter(request, response);
            return;
        }

        String target = canonicalUrlService.canonicalUrl(path);
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            target += "?" + query;
        }
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader("Location", target);
    }

    private boolean isExcludedFile(String path) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        return EXCLUDED_SUFFIXES.stream().anyMatch(suffix -> normalizedPath.endsWith(suffix)
                || normalizedPath.endsWith(suffix + "/"));
    }
}
