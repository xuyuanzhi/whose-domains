package info.wesite.web.seo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical and retired paths for tool pages that previously used underscores. */
public final class CanonicalToolRoutes {

    public static final String LEGACY_DOMAIN_ANALYZER = "/tools/domain_analyzer";
    public static final String DOMAIN_ANALYZER = "/tools/domain-analyzer";
    public static final String LEGACY_DNS_ANALYZER = "/tools/dns_analyzer";
    public static final String DNS_ANALYZER = "/tools/dns-analyzer";
    public static final String LEGACY_SSL_CHECKER = "/tools/ssl_checker";
    public static final String SSL_CHECKER = "/tools/ssl-checker";
    public static final String LEGACY_COMPETITOR_ANALYSIS = "/tools/competitor_analysis";
    public static final String COMPETITOR_ANALYSIS = "/tools/competitor-analysis";

    private static final Map<String, String> LEGACY_TO_CANONICAL;

    static {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put(LEGACY_DOMAIN_ANALYZER, DOMAIN_ANALYZER);
        routes.put(LEGACY_DNS_ANALYZER, DNS_ANALYZER);
        routes.put(LEGACY_SSL_CHECKER, SSL_CHECKER);
        routes.put(LEGACY_COMPETITOR_ANALYSIS, COMPETITOR_ANALYSIS);
        LEGACY_TO_CANONICAL = Map.copyOf(routes);
    }

    private CanonicalToolRoutes() {
    }

    public static Optional<String> canonicalFor(String path) {
        return Optional.ofNullable(LEGACY_TO_CANONICAL.get(path));
    }

    public static String canonicalizeInternalLinks(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }

        String normalized = html;
        for (Map.Entry<String, String> route : LEGACY_TO_CANONICAL.entrySet()) {
            normalized = rewriteHref(normalized, route.getKey(), route.getValue());
        }
        return normalized;
    }

    public static boolean containsLegacyInternalLink(String html) {
        return html != null && !Objects.equals(html, canonicalizeInternalLinks(html));
    }

    private static String rewriteHref(String html, String legacyPath, String canonicalPath) {
        Pattern pattern = Pattern.compile(
                "(?i)(\\bhref\\s*=\\s*)([\"'])(" + Pattern.quote(legacyPath)
                        + ")(?=([?#]|\\2))");
        Matcher matcher = pattern.matcher(html);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + canonicalPath));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }
}
