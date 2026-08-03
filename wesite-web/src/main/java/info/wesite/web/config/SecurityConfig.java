package info.wesite.web.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.util.StringUtils;

import info.wesite.web.auth.google.GoogleAuthenticationFailureHandler;
import info.wesite.web.auth.google.GoogleAuthenticationSuccessHandler;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(GoogleLoginProperties.class)
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(prefix = "wesite.google-login", name = "enabled", havingValue = "true")
    ClientRegistrationRepository googleClientRegistrationRepository(GoogleLoginProperties properties,
            @Value("${wesite.public-base-url}") String publicBaseUrl, Environment environment) {
        requireText(properties.clientId(), "Google client ID must not be blank");
        requireText(properties.clientSecret(), "Google client secret must not be blank");

        String baseUrl = validatedBaseUrl(publicBaseUrl, environment);
        ClientRegistration registration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(properties.clientId().trim())
                .clientSecret(properties.clientSecret().trim())
                .scope("openid", "email", "profile")
                .redirectUri(baseUrl + "/login/oauth2/code/{registrationId}")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "wesite.google-login", name = "enabled", havingValue = "true")
    SecurityFilterChain googleOAuthSecurityFilterChain(HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository,
            GoogleAuthenticationSuccessHandler successHandler,
            GoogleAuthenticationFailureHandler failureHandler) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        http.securityMatcher(new OrRequestMatcher(
                paths.matcher("/oauth2/**"),
                paths.matcher("/login/oauth2/**")))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .clientRegistrationRepository(clientRegistrationRepository)
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain passThroughSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(new NullSecurityContextRepository()));
        return http.build();
    }

    private static void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
    }

    private static String validatedBaseUrl(String value, Environment environment) {
        requireText(value, "Google login public base URL must not be blank");
        String baseUrl = value.trim().replaceFirst("/+$", "");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Google login public base URL is invalid", exception);
        }

        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean localHttp = !production
                && "http".equalsIgnoreCase(uri.getScheme())
                && "localhost".equalsIgnoreCase(uri.getHost());
        if (!https && !localHttp) {
            throw new IllegalStateException("Google login public base URL must use HTTPS");
        }
        return baseUrl;
    }
}
