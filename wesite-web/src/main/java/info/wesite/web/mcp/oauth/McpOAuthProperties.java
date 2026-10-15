package info.wesite.web.mcp.oauth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the MCP OAuth 2.1 authorization server and resource server. Disabled by
 * default; when enabled the MCP endpoint additionally accepts OAuth bearer tokens and can meter
 * usage per authenticated account instead of per anonymous subnet.
 */
@ConfigurationProperties(prefix = "wesite.mcp.oauth")
public class McpOAuthProperties {

    private boolean enabled = false;

    /** Issuer identifier; the base URL of this authorization server. */
    private String issuer = "https://whose.domains";

    /** Canonical resource identifier advertised for the protected MCP endpoint. */
    private String resource = "https://whose.domains/mcp";

    /** Registered OAuth client id used by the MCP client (e.g. ChatGPT). */
    private String clientId = "chatgpt";

    /** Client secret; blank means a public client using PKCE only. */
    private String clientSecret = "";

    /** Allowed redirect URIs. Must be configured for a real client. */
    private List<String> redirectUris = new ArrayList<>();

    private List<String> scopes = new ArrayList<>(List.of("domain.read"));

    /** When true the authorization endpoint requires an explicit consent step. */
    private boolean requireConsent = false;

    private int accessTokenMinutes = 30;

    /** Optional path to a JSON JWK Set file so signing keys survive restarts. */
    private String jwkFile = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public List<String> getRedirectUris() {
        return redirectUris;
    }

    public void setRedirectUris(List<String> redirectUris) {
        this.redirectUris = redirectUris;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public boolean isRequireConsent() {
        return requireConsent;
    }

    public void setRequireConsent(boolean requireConsent) {
        this.requireConsent = requireConsent;
    }

    public int getAccessTokenMinutes() {
        return accessTokenMinutes;
    }

    public void setAccessTokenMinutes(int accessTokenMinutes) {
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public String getJwkFile() {
        return jwkFile;
    }

    public void setJwkFile(String jwkFile) {
        this.jwkFile = jwkFile;
    }
}
