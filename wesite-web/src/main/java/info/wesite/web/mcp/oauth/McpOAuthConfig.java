package info.wesite.web.mcp.oauth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.util.StringUtils;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * OAuth 2.1 authorization server + JWT resource server for the MCP endpoint.
 *
 * <p>The authorization server uses the standard {@code /oauth2/*} endpoints in its own filter
 * chain, requires PKCE, and authenticates the resource owner
 * with the existing Whose.Domains session cookie. The resource server validates bearer tokens on
 * {@code /mcp} while still allowing anonymous (free-tier) access.</p>
 *
 * <p>Disabled unless {@code wesite.mcp.oauth.enabled=true}.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "wesite.mcp.oauth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpOAuthProperties.class)
public class McpOAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthConfig.class);

    @Bean
    @Order(0)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .authorizationEndpoint(Customizer.withDefaults())
                .tokenEndpoint(Customizer.withDefaults());
        McpOAuthLoginContinuation continuations = new McpOAuthLoginContinuation();
        http.securityMatcher(new OrRequestMatcher(
                http.getConfigurer(OAuth2AuthorizationServerConfigurer.class).getEndpointsMatcher(),
                PathPatternRequestMatcher.withDefaults().matcher(McpOAuthLoginContinuation.PREFIX + "**")));
        http.requestCache(cache -> cache.requestCache(new NullRequestCache()));
        // Resolve the site cookie before the authorization endpoint examines the principal.
        http.addFilterAfter(new CookieAuthenticationFilter(), SecurityContextHolderFilter.class)
                .addFilterAfter(new McpOAuthLoginContinuation.ContinuationFilter(continuations), CookieAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        continuations,
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain mcpResourceServerSecurityFilterChain(HttpSecurity http,
            @Value("${wesite.mcp.endpoint:/mcp}") String endpoint) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        http.securityMatcher(new OrRequestMatcher(
                        paths.matcher(endpoint),
                        paths.matcher("/.well-known/oauth-protected-resource")))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()));
        return http.build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(McpOAuthProperties properties) {
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(properties.getClientId())
                .clientName(properties.getClientId())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(properties.isRequireConsent())
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(properties.getAccessTokenMinutes()))
                        .build());
        if (StringUtils.hasText(properties.getClientSecret())) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            builder.clientSecret("{noop}" + properties.getClientSecret());
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
        for (String scope : properties.getScopes()) {
            builder.scope(scope);
        }
        for (String redirectUri : properties.getRedirectUris()) {
            if (StringUtils.hasText(redirectUri)) {
                builder.redirectUri(redirectUri.trim());
            }
        }
        return new InMemoryRegisteredClientRepository(builder.build());
    }

    @Bean
    OAuth2AuthorizationService authorizationService() {
        return new InMemoryOAuth2AuthorizationService();
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService() {
        return new InMemoryOAuth2AuthorizationConsentService();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(McpOAuthProperties properties) {
        // The standard authorization-server paths do not overlap Google client login endpoints.
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuer())
                .build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(McpOAuthProperties properties) throws Exception {
        if (StringUtils.hasText(properties.getJwkFile())) {
            Path path = Path.of(properties.getJwkFile());
            if (Files.isRegularFile(path)) {
                try {
                    JWKSource<SecurityContext> source = new ImmutableJWKSet<>(
                            JWKSet.parse(Files.readString(path, StandardCharsets.UTF_8)));
                    // Exercise the same RS256 selection/signing path used to issue access tokens.
                    Instant now = Instant.now();
                    String probe = new NimbusJwtEncoder(source).encode(JwtEncoderParameters.from(
                            JwsHeader.with(SignatureAlgorithm.RS256).build(),
                            JwtClaimsSet.builder().subject("signing-key-validation")
                                    .issuedAt(now).expiresAt(now.plusSeconds(60)).build())).getTokenValue();
                    OAuth2AuthorizationServerConfiguration.jwtDecoder(source).decode(probe);
                    return source;
                } catch (Exception exception) {
                    throw new IllegalStateException("Configured MCP OAuth key file must contain a usable RS256 signing private key", exception);
                }
            }
            throw new IllegalStateException("Configured MCP OAuth signing key file does not exist or is not a regular file");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        log.warn("MCP OAuth signing key is generated at startup and will change on restart; set "
                + "wesite.mcp.oauth.jwk-file to persist it. Existing tokens are invalidated on restart.");
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, McpOAuthProperties properties) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
                jwt -> {
                    Object claim = jwt.getClaims().get("scope");
                    java.util.Collection<?> scopes = claim instanceof String value
                            ? java.util.Arrays.asList(value.split("\\s+"))
                            : claim instanceof java.util.Collection<?> values ? values : List.of();
                    if (jwt.getAudience() == null || !jwt.getAudience().contains(properties.getResource())
                            || jwt.getExpiresAt() == null || !StringUtils.hasText(jwt.getSubject())
                            || properties.getScopes().stream().noneMatch(scopes::contains)) {
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                "Token must identify an account and authorize this MCP resource and scope", null));
                    }
                    return OAuth2TokenValidatorResult.success();
                }));
        return decoder;
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> mcpJwtCustomizer(McpOAuthProperties properties) {
        List<String> audience = List.of(properties.getResource());
        return context -> {
            if (context.getTokenType() != null
                    && "access_token".equals(context.getTokenType().getValue())) {
                context.getClaims()
                        .subject(context.getPrincipal().getName())
                        .audience(audience);
            }
        };
    }
}
