package info.wesite.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wesite.google-login")
public record GoogleLoginProperties(boolean enabled, String clientId, String clientSecret) {
}
