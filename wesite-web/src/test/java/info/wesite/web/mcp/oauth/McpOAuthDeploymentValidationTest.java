package info.wesite.web.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class McpOAuthDeploymentValidationTest {
    @TempDir java.nio.file.Path directory;

    @Test
    void explicitlyConfiguredMissingKeyMustNotFallBackToAnEphemeralKey() {
        McpOAuthProperties properties = new McpOAuthProperties();
        properties.setJwkFile(directory.resolve("missing.json").toString());
        assertThatThrownBy(() -> new McpOAuthConfig().jwkSource(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "public", "encryption"})
    void rejectsFilesWithoutUsableSigningPrivateKey(String kind) throws Exception {
        var key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test-key").generate();
        var keys = switch (kind) {
            case "empty" -> new com.nimbusds.jose.jwk.JWKSet();
            case "public" -> new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK());
            default -> new com.nimbusds.jose.jwk.JWKSet(new com.nimbusds.jose.jwk.RSAKey.Builder(key)
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.ENCRYPTION).build());
        };
        var path = directory.resolve("invalid-key.json");
        java.nio.file.Files.writeString(path, keys.toString(false));
        var properties = new McpOAuthProperties();
        properties.setJwkFile(path.toString());
        assertThatThrownBy(() -> new McpOAuthConfig().jwkSource(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "scope", "expired"})
    void rejectsSignedTokensNotAuthorizedForThisResource(String invalidClaim) throws Exception {
        McpOAuthConfig config = new McpOAuthConfig();
        McpOAuthProperties properties = new McpOAuthProperties();
        var keys = config.jwkSource(properties);
        var claims = JwtClaimsSet.builder().subject("user")
                .issuer(invalidClaim.equals("issuer") ? "https://other.example" : properties.getIssuer())
                .audience(List.of(invalidClaim.equals("audience") ? "https://other.example/mcp" : properties.getResource()))
                .claim("scope", invalidClaim.equals("scope") ? List.of("other.read") : properties.getScopes())
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().plusSeconds(invalidClaim.equals("expired") ? -300 : 300)).build();
        String token = new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        assertThatThrownBy(() -> config.jwtDecoder(keys, properties).decode(token)).isInstanceOf(JwtException.class);
    }
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void persistedKeyValidatesTokensAfterReloadWithBothScopeFormats(boolean stringScope) throws Exception {
        var key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test-persisted-key").generate();
        var file = directory.resolve("signing-key.json");
        java.nio.file.Files.writeString(file, new com.nimbusds.jose.jwk.JWKSet(key).toString(false));
        McpOAuthProperties properties = new McpOAuthProperties();
        properties.setJwkFile(file.toString());
        McpOAuthConfig config = new McpOAuthConfig();
        var claims = JwtClaimsSet.builder().subject("persisted-user").issuer(properties.getIssuer())
                .audience(List.of(properties.getResource()))
                .claim("scope", stringScope ? "other.read domain.read" : properties.getScopes())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        String token = new NimbusJwtEncoder(config.jwkSource(properties)).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        var restarted = new McpOAuthConfig();
        assertThat(restarted.jwtDecoder(restarted.jwkSource(properties), properties).decode(token).getSubject())
                .isEqualTo("persisted-user");
    }

}
