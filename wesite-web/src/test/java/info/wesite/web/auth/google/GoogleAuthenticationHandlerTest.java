package info.wesite.web.auth.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import info.wesite.core.entity.User;
import info.wesite.web.auth.AuthCookieService;

class GoogleAuthenticationHandlerTest {

    private GoogleIdentityParser identityParser;
    private CurrentJwtUserResolver currentUserResolver;
    private GoogleLoginService googleLoginService;
    private AuthCookieService authCookieService;
    private OAuthSessionCleaner sessionCleaner;
    private OidcUser oidcUser;
    private OAuth2AuthenticationToken authentication;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        identityParser = mock(GoogleIdentityParser.class);
        currentUserResolver = mock(CurrentJwtUserResolver.class);
        googleLoginService = mock(GoogleLoginService.class);
        authCookieService = mock(AuthCookieService.class);
        sessionCleaner = mock(OAuthSessionCleaner.class);
        oidcUser = mock(OidcUser.class);
        authentication = new OAuth2AuthenticationToken(oidcUser,
                List.of(new SimpleGrantedAuthority("ROLE_USER")), "google");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    void signedInGoogleResultWritesOneApplicationCookieClearsOAuthStateAndRedirects() throws Exception {
        GoogleIdentity identity = new GoogleIdentity("google-subject", "person@example.com", "Person", true);
        User user = user("user-1");
        ResponseCookie cookie = ResponseCookie.from("wesite-auth", "application-token").path("/").build();
        when(identityParser.parse(oidcUser)).thenReturn(identity);
        when(currentUserResolver.resolve(request)).thenReturn(Optional.empty());
        when(googleLoginService.authenticate(identity, null)).thenReturn(GoogleLoginResult.signedIn(user));
        when(authCookieService.create(user)).thenReturn(cookie);
        GoogleAuthenticationSuccessHandler handler = new GoogleAuthenticationSuccessHandler(identityParser,
                currentUserResolver, googleLoginService, authCookieService, sessionCleaner);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals(List.of(cookie.toString()), List.copyOf(response.getHeaders("Set-Cookie")));
        assertEquals("/user/watchlist?login=success", response.getRedirectedUrl());
        verify(sessionCleaner).clear(request, response, authentication);
    }

    @Test
    void pendingGoogleResultDoesNotWriteApplicationCookieRotatesOAuthStateAndRedirects() throws Exception {
        GoogleIdentity identity = new GoogleIdentity("google-subject", "person@example.com", "Person", false);
        PendingGoogleBinding pending = new PendingGoogleBinding("user-1", "google-subject", "person@example.com",
                Instant.parse("2030-01-01T00:00:00Z"));
        when(identityParser.parse(oidcUser)).thenReturn(identity);
        when(currentUserResolver.resolve(request)).thenReturn(Optional.empty());
        when(googleLoginService.authenticate(identity, null)).thenReturn(GoogleLoginResult.pending(pending));
        GoogleAuthenticationSuccessHandler handler = new GoogleAuthenticationSuccessHandler(identityParser,
                currentUserResolver, googleLoginService, authCookieService, sessionCleaner);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertFalse(response.containsHeader("Set-Cookie"));
        assertEquals("/?login=google_check_email", response.getRedirectedUrl());
        verify(sessionCleaner).rotateToPending(request, response, authentication, pending);
        verify(sessionCleaner, never()).clear(request, response, authentication);
        verify(authCookieService, never()).create(user("user-1"));
    }

    @Test
    void failureRedirectUsesOnlyFixedCodesAndNeverReflectsProviderSecrets() throws Exception {
        GoogleAuthenticationFailureHandler handler = new GoogleAuthenticationFailureHandler();
        String providerSecret = "person@example.com/google-subject/auth-code/access-token";
        AuthenticationServiceException internalFailure = new AuthenticationServiceException(providerSecret,
                new GoogleLoginException(GoogleLoginException.Code.ACCOUNT_CONFLICT));

        handler.onAuthenticationFailure(request, response, internalFailure);

        assertEquals("/?login=google_conflict", response.getRedirectedUrl());
        assertFalse(response.getRedirectedUrl().contains(providerSecret));
        assertFalse(response.getRedirectedUrl().contains("person@example.com"));
        assertFalse(response.getRedirectedUrl().contains("google-subject"));
        assertFalse(response.getRedirectedUrl().contains("auth-code"));
        assertFalse(response.getRedirectedUrl().contains("access-token"));
    }

    @Test
    void unrecognizedFailureUsesTheGenericFixedRedirect() throws Exception {
        GoogleAuthenticationFailureHandler handler = new GoogleAuthenticationFailureHandler();
        AuthenticationServiceException providerFailure = new AuthenticationServiceException(
                "person@example.com/google-subject/auth-code/access-token");

        handler.onAuthenticationFailure(request, response, providerFailure);

        assertEquals("/?login=google_error", response.getRedirectedUrl());
    }

    @ParameterizedTest
    @MethodSource("googleLoginCodes")
    void failureMapsEachInternalCodeToItsFixedRedirect(GoogleLoginException.Code code, String loginCode)
            throws Exception {
        GoogleAuthenticationFailureHandler handler = new GoogleAuthenticationFailureHandler();

        handler.onAuthenticationFailure(request, response,
                new AuthenticationServiceException("provider response", new GoogleLoginException(code)));

        assertEquals("/?login=" + loginCode, response.getRedirectedUrl());
    }

    @Test
    void nonOidcAuthenticationUsesTheFixedInvalidRedirectWithoutWritingAnApplicationCookie() throws Exception {
        GoogleAuthenticationSuccessHandler handler = new GoogleAuthenticationSuccessHandler(identityParser,
                currentUserResolver, googleLoginService, authCookieService, sessionCleaner);

        handler.onAuthenticationSuccess(request, response,
                UsernamePasswordAuthenticationToken.authenticated("person@example.com", "secret", List.of()));

        assertEquals("/?login=google_invalid", response.getRedirectedUrl());
        assertFalse(response.containsHeader("Set-Cookie"));
        verify(authCookieService, never()).create(user("user-1"));
    }

    @Test
    void parserFailureClearsOAuthStateBeforeUsingItsFixedRedirect() throws Exception {
        GoogleLoginException failure = new GoogleLoginException(GoogleLoginException.Code.UNVERIFIED_EMAIL);
        doThrow(failure).when(identityParser).parse(oidcUser);
        GoogleAuthenticationSuccessHandler handler = new GoogleAuthenticationSuccessHandler(identityParser,
                currentUserResolver, googleLoginService, authCookieService, sessionCleaner);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(sessionCleaner).clear(request, response, authentication);
        assertEquals("/?login=google_unverified", response.getRedirectedUrl());
        assertFalse(response.containsHeader("Set-Cookie"));
    }

    @Test
    void loginServiceFailureClearsOAuthStateBeforeUsingItsFixedRedirect() throws Exception {
        GoogleIdentity identity = new GoogleIdentity("google-subject", "person@example.com", "Person", false);
        when(identityParser.parse(oidcUser)).thenReturn(identity);
        when(currentUserResolver.resolve(request)).thenReturn(Optional.empty());
        doThrow(new GoogleLoginException(GoogleLoginException.Code.ACCOUNT_CONFLICT))
                .when(googleLoginService).authenticate(identity, null);
        GoogleAuthenticationSuccessHandler handler = new GoogleAuthenticationSuccessHandler(identityParser,
                currentUserResolver, googleLoginService, authCookieService, sessionCleaner);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(sessionCleaner).clear(request, response, authentication);
        assertEquals("/?login=google_conflict", response.getRedirectedUrl());
        assertFalse(response.containsHeader("Set-Cookie"));
    }

    @Test
    void cleanupFailureDoesNotBlockTheOriginalFixedRedirectOrLeakProviderSecrets() throws Exception {
        String providerSecret = "person@example.com/google-subject/auth-code/access-token";
        doThrow(new GoogleLoginException(GoogleLoginException.Code.INVALID_IDENTITY)).when(identityParser).parse(oidcUser);
        doThrow(new RuntimeException(providerSecret)).when(sessionCleaner).clear(request, response, authentication);
        GoogleAuthenticationSuccessHandler handler = new GoogleAuthenticationSuccessHandler(identityParser,
                currentUserResolver, googleLoginService, authCookieService, sessionCleaner);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(sessionCleaner).clear(request, response, authentication);
        assertEquals("/?login=google_invalid", response.getRedirectedUrl());
        assertFalse(response.containsHeader("Set-Cookie"));
        assertFalse(response.getRedirectedUrl().contains(providerSecret));
    }

    private static Stream<Arguments> googleLoginCodes() {
        return Stream.of(
                Arguments.of(GoogleLoginException.Code.INVALID_IDENTITY, "google_invalid"),
                Arguments.of(GoogleLoginException.Code.UNVERIFIED_EMAIL, "google_unverified"),
                Arguments.of(GoogleLoginException.Code.ACCOUNT_CONFLICT, "google_conflict"),
                Arguments.of(GoogleLoginException.Code.INACTIVE_USER, "google_inactive"),
                Arguments.of(GoogleLoginException.Code.EMAIL_CONFIRMATION_UNAVAILABLE, "google_email_unavailable"),
                Arguments.of(GoogleLoginException.Code.EXPIRED_FLOW, "google_expired"));
    }

    private User user(String id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
