package info.wesite.web.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import info.wesite.web.auth.AuthCookieService;
import info.wesite.web.auth.ReturnTargetService;
import info.wesite.web.auth.google.CurrentJwtUserResolver;
import info.wesite.web.auth.google.GoogleAuthenticationFailureHandler;
import info.wesite.web.auth.google.GoogleAuthenticationSuccessHandler;
import info.wesite.web.auth.google.GoogleIdentity;
import info.wesite.web.auth.google.GoogleIdentityParser;
import info.wesite.web.auth.google.GoogleLoginResult;
import info.wesite.web.auth.google.GoogleLoginService;
import info.wesite.web.auth.google.GoogleOAuth2AuthorizationRequestRepository;
import info.wesite.web.auth.google.OAuthSessionCleaner;
import info.wesite.web.config.SecurityConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.wesite.core.entity.User;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.TokenUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.UriComponentsBuilder;

class McpOAuthAuthorizationFlowTest {
    private static final String CALLBACK = "https://client.example/callback";
    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withUserConfiguration(McpOAuthConfig.class, TokenUtils.class)
            .withPropertyValues("wesite.mcp.oauth.enabled=true",
                    "wesite.mcp.oauth.redirect-uris=" + CALLBACK,
                    "app.jwt.secret=oauth-flow-test-secret-only", "app.jwt.issuer=oauth-flow-test");

    @Test
    void signedInUserCanExchangePkceCodeForAccountToken() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            User user = new User();
            user.setId("oauth-test-user");
            user.setName("OAuth test user");
            String location = mvc.perform(authorize().cookie(
                            new Cookie(Constants.TOKEN_KEY, TokenUtils.createToken(user, 5))))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            assertThat(location).startsWith(CALLBACK);
            var parameters = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
            assertThat(parameters.getFirst("state")).isEqualTo("test-state");
            assertThat(parameters.getFirst("error")).isNull();
            String code = parameters.getFirst("code");
            assertThat(code).isNotBlank();
            String body = mvc.perform(exchange(code, VERIFIER))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String accessToken = new ObjectMapper().readTree(body).get("access_token").asText();
            Jwt jwt = context.getBean(JwtDecoder.class).decode(accessToken);
            assertThat(jwt.getSubject()).isEqualTo("oauth-test-user");
            assertThat(jwt.getAudience()).contains("https://whose.domains/mcp");
            assertThat(jwt.getClaimAsStringList("scope")).contains("domain.read");
            mvc.perform(exchange(code, VERIFIER)).andExpect(status().isBadRequest());
        });
    }

    @Test
    void unsignedUserIsSentToLogin() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            String location = mvc.perform(authorize()).andExpect(status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl();
            assertThat(location).startsWith("/login?returnTo=%2Fmcp%2Fauthorize%2Fcontinue%2F");
        });
    }

    @Test
    void incorrectPkceVerifierCannotExchangeCode() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            User user = new User();
            user.setId("oauth-test-user");
            String location = mvc.perform(authorize().cookie(
                            new Cookie(Constants.TOKEN_KEY, TokenUtils.createToken(user, 5))))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
            assertThat(code).isNotBlank();
            mvc.perform(exchange(code, "wrong-verifier-abcdefghijklmnopqrstuvwxyz0123456789"))
                    .andExpect(status().is4xxClientError());
        });
    }

    @Test
    void invalidCookieCannotAuthorizeClient() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            String location = mvc.perform(authorize().cookie(new Cookie(Constants.TOKEN_KEY, "invalid")))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            assertThat(location).startsWith("/login?returnTo=%2Fmcp%2Fauthorize%2Fcontinue%2F");
        });
    }

    @Test
    void loginContinuationSurvivesSessionReplacementAndPreservesPkce() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            var initial = mvc.perform(authorizeWithState("long-" + "x".repeat(600)))
                    .andExpect(status().is3xxRedirection()).andReturn();
            String login = initial.getResponse().getRedirectedUrl();
            assertThat(login).startsWith("/login?returnTo=");
            String resume = java.net.URLDecoder.decode(login.substring(login.indexOf('=') + 1),
                    StandardCharsets.UTF_8);
            assertThat(new info.wesite.web.auth.ReturnTargetService().resolve(resume)).isEqualTo(resume);
            Cookie binding = initial.getResponse().getCookie("mcp_oauth_continue");
            assertThat(binding).isNotNull();
            assertThat(binding.isHttpOnly()).isTrue();
            User user = new User();
            user.setId("returned-user");
            Cookie signedIn = new Cookie(Constants.TOKEN_KEY, TokenUtils.createToken(user, 5));
            mvc.perform(get(resume).cookie(signedIn)).andExpect(status().isBadRequest());
            mvc.perform(get(resume).cookie(binding)).andExpect(status().is3xxRedirection());
            // No original HTTP session: Google login invalidates it; email login may replace it.
            String target = mvc.perform(get(resume).cookie(binding, signedIn))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            assertThat(target).startsWith("/oauth2/authorize?");
            String callback = mvc.perform(get(java.net.URI.create(target)).cookie(signedIn)
                            .accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            var params = UriComponentsBuilder.fromUriString(callback).build().getQueryParams();
            assertThat(params.getFirst("error")).isNull();
            assertThat(params.getFirst("state")).isEqualTo("long-" + "x".repeat(600));
            mvc.perform(exchange(params.getFirst("code"), VERIFIER)).andExpect(status().isOk());
            mvc.perform(get(resume).cookie(binding, signedIn)).andExpect(status().isBadRequest());
        });
    }

    @Test
    void googleLoginWithBothSecurityConfigurationsIssuesCookieAndResumesMcpAuthorization() {
        GoogleIdentityParser parser = mock(GoogleIdentityParser.class);
        GoogleLoginService loginService = mock(GoogleLoginService.class);
        OidcUser oidcUser = mock(OidcUser.class);
        GoogleIdentity identity = new GoogleIdentity("google-id", "user@example.com", "User", true);
        User user = new User();
        user.setId("google-returned-user");
        when(parser.parse(oidcUser)).thenReturn(identity);
        when(loginService.authenticate(identity, null)).thenReturn(GoogleLoginResult.signedIn(user));
        GoogleAuthenticationSuccessHandler successHandler = new GoogleAuthenticationSuccessHandler(parser,
                mock(CurrentJwtUserResolver.class), loginService, new AuthCookieService(),
                new OAuthSessionCleaner(mock(OAuth2AuthorizedClientRepository.class)));
        runner.withUserConfiguration(SecurityConfig.class)
                .withBean(ReturnTargetService.class)
                .withBean(GoogleAuthenticationSuccessHandler.class, () -> successHandler)
                .withBean(GoogleAuthenticationFailureHandler.class, GoogleAuthenticationFailureHandler::new)
                .withPropertyValues("wesite.google-login.enabled=true", "wesite.google-login.client-id=google-client",
                        "wesite.google-login.client-secret=test-only", "wesite.public-base-url=https://whose.domains")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean(FilterChainProxy.class)).build();
                    var initial = mvc.perform(authorize()).andExpect(status().is3xxRedirection())
                            .andReturn().getResponse();
                    Cookie binding = initial.getCookie("mcp_oauth_continue");
                    String resume = binding.getPath();
                    var google = mvc.perform(get("/login/google").param("returnTo", resume))
                            .andExpect(status().is3xxRedirection()).andReturn();
                    assertThat(google.getResponse().getRedirectedUrl()).startsWith("https://accounts.google.com/");
                    String googleState = UriComponentsBuilder.fromUriString(google.getResponse().getRedirectedUrl())
                            .build().getQueryParams().getFirst("state");
                    googleState = java.net.URLDecoder.decode(googleState, StandardCharsets.UTF_8);
                    var oldSession = (MockHttpSession) google.getRequest().getSession(false);
                    var callbackRequest = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
                    callbackRequest.setSession(oldSession);
                    callbackRequest.addParameter("state", googleState);
                    var callbackResponse = new MockHttpServletResponse();
                    assertThat(context.getBean(GoogleOAuth2AuthorizationRequestRepository.class)
                            .removeAuthorizationRequest(callbackRequest, callbackResponse)).isNotNull();
                    // The external Google token exchange is stubbed; its real success handler and cleanup run.
                    successHandler.onAuthenticationSuccess(callbackRequest, callbackResponse,
                            new OAuth2AuthenticationToken(oidcUser, List.of(), "google"));
                    assertThat(oldSession.isInvalid()).isTrue();
                    assertThat(callbackResponse.getRedirectedUrl()).isEqualTo(resume);
                    Cookie siteCookie = callbackResponse.getCookie(Constants.TOKEN_KEY);
                    assertThat(siteCookie).isNotNull();
                    String target = mvc.perform(get(resume).cookie(binding, siteCookie))
                            .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
                    String clientCallback = mvc.perform(get(java.net.URI.create(target)).cookie(siteCookie)
                                    .accept(MediaType.TEXT_HTML))
                            .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
                    var params = UriComponentsBuilder.fromUriString(clientCallback).build().getQueryParams();
                    assertThat(params.getFirst("state")).isEqualTo("test-state");
                    String body = mvc.perform(exchange(params.getFirst("code"), VERIFIER))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    String token = new ObjectMapper().readTree(body).get("access_token").asText();
                    assertThat(context.getBean(JwtDecoder.class).decode(token).getSubject())
                            .isEqualTo("google-returned-user");
                });
    }

    @Test
    void customEndpointRejectsInvalidBearerTokens() {
        runner.withUserConfiguration(SecurityConfig.class)
                .withPropertyValues("wesite.mcp.endpoint=/custom-mcp").run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean(FilterChainProxy.class)).build();
                    mvc.perform(get("/custom-mcp").header("Authorization", "Bearer invalid"))
                            .andExpect(status().isUnauthorized());
                });
    }

    private MockHttpServletRequestBuilder authorize() throws Exception {
        return authorizeWithState("test-state");
    }

    private MockHttpServletRequestBuilder authorizeWithState(String state) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth2/authorize").accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code").queryParam("client_id", "chatgpt")
                .queryParam("redirect_uri", CALLBACK).queryParam("scope", "domain.read")
                .queryParam("state", state).queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256");
    }

    private MockHttpServletRequestBuilder exchange(String code, String verifier) {
        return post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code").param("client_id", "chatgpt")
                .param("redirect_uri", CALLBACK).param("code", code).param("code_verifier", verifier);
    }
}
