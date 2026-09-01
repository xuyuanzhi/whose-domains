package info.wesite.web.support;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class SupportLink {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "paypal.com",
            "www.paypal.com",
            "paypal.me",
            "www.paypal.me");

    private final String url;

    public SupportLink(@Value("${wesite.support.url:}") String configuredUrl) {
        this.url = validate(configuredUrl);
    }

    public Optional<String> url() {
        return Optional.ofNullable(url);
    }

    private String validate(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return null;
        }

        String candidate = configuredUrl.trim();
        final URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration(exception);
        }

        String host = uri.getHost();
        boolean allowedHost = host != null && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        boolean defaultHttpsPort = uri.getPort() == -1 || uri.getPort() == 443;
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !allowedHost
                || uri.getUserInfo() != null
                || !defaultHttpsPort) {
            throw invalidConfiguration(null);
        }
        return candidate;
    }

    private IllegalArgumentException invalidConfiguration(Exception cause) {
        String message = "wesite.support.url must be an HTTPS PayPal payment link";
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }
}
