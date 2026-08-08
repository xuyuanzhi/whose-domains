package info.wesite.web.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ReturnTargetService {

    public static final String DEFAULT_TARGET = "/user/watchlist?login=success";
    public static final int MAX_TARGET_LENGTH = 500;

    private static final Pattern LOGIN_RESULT = Pattern.compile("[a-z_]{1,40}");
    private static final Set<String> AUTHENTICATION_PATHS = Set.of(
            "/login",
            "/oauth2",
            "/user/verify-email",
            "/user/email-login",
            "/user/logout",
            "/user/session");

    public String resolve(String candidate) {
        return validated(candidate).orElse(DEFAULT_TARGET);
    }

    public Optional<String> validated(String candidate) {
        if (!StringUtils.hasText(candidate) || candidate.length() > MAX_TARGET_LENGTH
                || !candidate.startsWith("/") || candidate.startsWith("//")
                || candidate.indexOf('\\') >= 0 || candidate.indexOf(';') >= 0
                || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(candidate);
            String path = uri.getRawPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || !StringUtils.hasText(path)
                    || path.indexOf('%') >= 0 || containsDotSegment(path) || isAuthenticationPath(path)) {
                return Optional.empty();
            }
            return Optional.of(path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean containsDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAuthenticationPath(String path) {
        return AUTHENTICATION_PATHS.stream()
                .anyMatch(authenticationPath -> path.equals(authenticationPath)
                        || path.startsWith(authenticationPath + "/"));
    }

    public String loginUrl(String target) {
        return UriComponentsBuilder.fromPath("/login")
                .queryParam("returnTo", encodeQueryValue(resolve(target)))
                .build(true).toUriString();
    }

    public String loginFailureUrl(String code, String target) {
        return UriComponentsBuilder.fromPath("/login").queryParam("login", encodeQueryValue(code))
                .queryParam("returnTo", encodeQueryValue(resolve(target)))
                .build(true).toUriString();
    }

    public boolean isDomainMonitorContinuation(String target) {
        return validated(target).map(candidate -> {
            URI uri = URI.create(candidate);
            if (!uri.getPath().startsWith("/domain/")) {
                return false;
            }
            return UriComponentsBuilder.fromUriString(candidate).build()
                    .getQueryParams().getOrDefault("monitor", List.of()).contains("pending");
        }).orElse(false);
    }

    public String monitorLoginResultUrl(String target, String code) {
        String resolved = resolve(target);
        if (!isDomainMonitorContinuation(resolved) || code == null || !LOGIN_RESULT.matcher(code).matches()) {
            return resolved;
        }
        return UriComponentsBuilder.fromUriString(resolved)
                .replaceQueryParam("login", code)
                .build(true).toUriString();
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
