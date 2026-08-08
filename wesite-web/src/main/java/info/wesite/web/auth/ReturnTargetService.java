package info.wesite.web.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ReturnTargetService {

    public static final String DEFAULT_TARGET = "/user/watchlist?login=success";
    public static final String GOOGLE_RETURN_TARGET_SESSION_KEY = "LOGIN_RETURN_TO";

    public String resolve(String candidate) {
        return validated(candidate).orElse(DEFAULT_TARGET);
    }

    public Optional<String> validated(String candidate) {
        if (!StringUtils.hasText(candidate) || !candidate.startsWith("/") || candidate.startsWith("//")
                || candidate.indexOf('\\') >= 0 || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(candidate);
            String path = uri.getRawPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || !StringUtils.hasText(path)
                    || path.indexOf('%') >= 0
                    || "/login".equals(path) || path.startsWith("/login/")) {
                return Optional.empty();
            }
            return Optional.of(path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String loginUrl(String target) {
        return UriComponentsBuilder.fromPath("/login")
                .queryParam("returnTo", URLEncoder.encode(resolve(target), StandardCharsets.UTF_8))
                .build(true).toUriString();
    }

    public String loginFailureUrl(String code, String target) {
        return UriComponentsBuilder.fromPath("/login").queryParam("login", code)
                .queryParam("returnTo", URLEncoder.encode(resolve(target), StandardCharsets.UTF_8))
                .build(true).toUriString();
    }
}
