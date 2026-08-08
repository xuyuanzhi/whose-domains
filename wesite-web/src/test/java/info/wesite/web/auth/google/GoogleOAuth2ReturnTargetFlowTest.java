package info.wesite.web.auth.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import info.wesite.web.auth.ReturnTargetService;

class GoogleOAuth2ReturnTargetFlowTest {

    private GoogleOAuth2AuthorizationRequestResolver resolver;
    private GoogleOAuth2AuthorizationRequestRepository repository;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        ClientRegistration registration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId("client-id")
                .clientSecret("client-secret")
                .redirectUri("https://whose.domains/login/oauth2/code/{registrationId}")
                .build();
        resolver = new GoogleOAuth2AuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration), new ReturnTargetService());
        repository = new GoogleOAuth2AuthorizationRequestRepository();
        session = new MockHttpSession();
    }

    @Test
    void abandonedGatewayStateCannotContaminateALaterModalState() throws Exception {
        OAuth2AuthorizationRequest gateway = start("/user/api-keys");
        OAuth2AuthorizationRequest modal = start(null);

        assertNotEquals(gateway.getState(), modal.getState());
        MockHttpServletRequest modalCallback = callback(modal.getState());
        assertSame(modal, repository.removeAuthorizationRequest(modalCallback, new MockHttpServletResponse()));
        assertEquals(Optional.empty(), GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(modalCallback));

        MockHttpServletResponse failureResponse = new MockHttpServletResponse();
        new GoogleAuthenticationFailureHandler().onAuthenticationFailure(modalCallback, failureResponse,
                new AuthenticationServiceException("modal provider failure"));
        assertEquals("/?login=google_error", failureResponse.getRedirectedUrl());

        MockHttpServletRequest gatewayCallback = callback(gateway.getState());
        assertSame(gateway, repository.removeAuthorizationRequest(gatewayCallback, new MockHttpServletResponse()));
        assertEquals(Optional.of("/user/api-keys"),
                GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(gatewayCallback));
    }

    @Test
    void failedGatewayStateCannotContaminateALaterModalState() throws Exception {
        OAuth2AuthorizationRequest gateway = start("/user/api-keys");
        MockHttpServletRequest gatewayCallback = callback(gateway.getState());
        assertSame(gateway, repository.removeAuthorizationRequest(gatewayCallback, new MockHttpServletResponse()));

        MockHttpServletResponse gatewayFailure = new MockHttpServletResponse();
        new GoogleAuthenticationFailureHandler().onAuthenticationFailure(gatewayCallback, gatewayFailure,
                new AuthenticationServiceException("gateway provider failure"));
        assertEquals("/login?login=google_error&returnTo=%2Fuser%2Fapi-keys",
                gatewayFailure.getRedirectedUrl());

        OAuth2AuthorizationRequest modal = start(null);
        MockHttpServletRequest modalCallback = callback(modal.getState());
        assertSame(modal, repository.removeAuthorizationRequest(modalCallback, new MockHttpServletResponse()));

        MockHttpServletResponse modalFailure = new MockHttpServletResponse();
        new GoogleAuthenticationFailureHandler().onAuthenticationFailure(modalCallback, modalFailure,
                new AuthenticationServiceException("modal provider failure"));
        assertEquals("/?login=google_error", modalFailure.getRedirectedUrl());
    }

    @Test
    void twoConcurrentGatewayStatesRetainOnlyTheirOwnTargets() {
        OAuth2AuthorizationRequest first = start("/user/api-keys");
        OAuth2AuthorizationRequest second = start("/user/watchlist?tab=active");

        MockHttpServletRequest secondCallback = callback(second.getState());
        assertSame(second, repository.removeAuthorizationRequest(secondCallback, new MockHttpServletResponse()));
        assertEquals(Optional.of("/user/watchlist?tab=active"),
                GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(secondCallback));

        MockHttpServletRequest firstCallback = callback(first.getState());
        assertSame(first, repository.removeAuthorizationRequest(firstCallback, new MockHttpServletResponse()));
        assertEquals(Optional.of("/user/api-keys"),
                GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(firstCallback));
    }

    @Test
    void forgedStateCannotReadOrRemoveAnotherStatesTarget() {
        OAuth2AuthorizationRequest gateway = start("/user/api-keys");

        MockHttpServletRequest forgedCallback = callback("attacker-chosen-state");
        assertNull(repository.removeAuthorizationRequest(forgedCallback, new MockHttpServletResponse()));
        assertEquals(Optional.empty(), GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(forgedCallback));

        MockHttpServletRequest realCallback = callback(gateway.getState());
        assertSame(gateway, repository.removeAuthorizationRequest(realCallback, new MockHttpServletResponse()));
        assertEquals(Optional.of("/user/api-keys"),
                GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(realCallback));
    }

    @Test
    void matchingStateAndTargetAreConsumedOnlyOnce() {
        OAuth2AuthorizationRequest gateway = start("/user/api-keys");
        MockHttpServletRequest callback = callback(gateway.getState());

        assertSame(gateway, repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        assertEquals(Optional.of("/user/api-keys"),
                GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(callback));
        assertEquals(Optional.empty(), GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(callback));

        MockHttpServletRequest replay = callback(gateway.getState());
        assertNull(repository.removeAuthorizationRequest(replay, new MockHttpServletResponse()));
        assertFalse(GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(replay).isPresent());
    }

    @Test
    void unsafeGatewayTargetIsNormalizedBeforeItIsBoundToState() {
        OAuth2AuthorizationRequest gateway = start("/user/verify-email?token=attacker-token");
        MockHttpServletRequest callback = callback(gateway.getState());

        repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());

        assertEquals(Optional.of(ReturnTargetService.DEFAULT_TARGET),
                GoogleOAuth2AuthorizationRequestRepository.consumeReturnTarget(callback));
    }

    private OAuth2AuthorizationRequest start(String returnTo) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/google");
        request.setServletPath("/login/google");
        request.setSession(session);
        if (returnTo != null) {
            request.addParameter("returnTo", returnTo);
        }
        OAuth2AuthorizationRequest authorizationRequest = resolver.resolve(request);
        repository.saveAuthorizationRequest(authorizationRequest, request, new MockHttpServletResponse());
        return authorizationRequest;
    }

    private MockHttpServletRequest callback(String state) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setServletPath("/login/oauth2/code/google");
        request.setSession(session);
        request.addParameter("state", state);
        return request;
    }
}
