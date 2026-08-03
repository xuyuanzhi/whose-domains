package info.wesite.web.auth.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class OAuthSessionCleanerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void clearRemovesAuthorizedClientInvalidatesTheExistingSessionAndClearsSecurityContext() {
        OAuth2AuthorizedClientRepository clients = mock(OAuth2AuthorizedClientRepository.class);
        OAuthSessionCleaner cleaner = new OAuthSessionCleaner(clients);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession oldSession = (MockHttpSession) request.getSession();
        oldSession.setAttribute("oauth-state", "old-state");
        OAuth2AuthenticationToken authentication = googleAuthentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);

        cleaner.clear(request, response, authentication);

        verify(clients).removeAuthorizedClient("google", authentication, request, response);
        assertThrows(IllegalStateException.class, () -> oldSession.getAttribute("oauth-state"));
        assertNull(request.getSession(false));
        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null);
    }

    @Test
    void rotateToPendingReplacesOAuthSessionWithOnlyTheSuppliedPendingBinding() {
        OAuth2AuthorizedClientRepository clients = mock(OAuth2AuthorizedClientRepository.class);
        OAuthSessionCleaner cleaner = new OAuthSessionCleaner(clients);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession oldSession = (MockHttpSession) request.getSession();
        oldSession.setAttribute("oauth-state", "old-state");
        oldSession.setAttribute(PendingGoogleBinding.SESSION_KEY,
                new PendingGoogleBinding("old-user", "old-subject", "old@example.com", Instant.now()));
        OAuth2AuthenticationToken authentication = googleAuthentication();
        PendingGoogleBinding pending = new PendingGoogleBinding("user", "subject", "person@example.com",
                Instant.parse("2030-01-01T00:00:00Z"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        cleaner.rotateToPending(request, response, authentication, pending);

        verify(clients).removeAuthorizedClient("google", authentication, request, response);
        assertThrows(IllegalStateException.class, () -> oldSession.getAttribute("oauth-state"));
        MockHttpSession replacement = (MockHttpSession) request.getSession(false);
        assertEquals(pending, replacement.getAttribute(PendingGoogleBinding.SESSION_KEY));
        assertEquals(List.of(PendingGoogleBinding.SESSION_KEY),
                java.util.Collections.list(replacement.getAttributeNames()));
        assertFalse(replacement.getId().equals(oldSession.getId()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void clearInvalidatesTheSessionAndClearsSecurityContextWhenAuthorizedClientRemovalFails() {
        OAuth2AuthorizedClientRepository clients = mock(OAuth2AuthorizedClientRepository.class);
        OAuthSessionCleaner cleaner = new OAuthSessionCleaner(clients);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession oldSession = (MockHttpSession) request.getSession();
        oldSession.setAttribute("oauth-state", "old-state");
        OAuth2AuthenticationToken authentication = googleAuthentication();
        RuntimeException failure = new RuntimeException("authorized client removal failed");
        doThrow(failure).when(clients).removeAuthorizedClient("google", authentication, request, response);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> cleaner.clear(request, response, authentication));

        assertSame(failure, thrown);
        assertThrows(IllegalStateException.class, () -> oldSession.getAttribute("oauth-state"));
        assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void rotateToPendingCreatesAnIsolatedPendingSessionThenRethrowsRemovalFailure() {
        OAuth2AuthorizedClientRepository clients = mock(OAuth2AuthorizedClientRepository.class);
        OAuthSessionCleaner cleaner = new OAuthSessionCleaner(clients);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession oldSession = (MockHttpSession) request.getSession();
        oldSession.setAttribute("oauth-state", "old-state");
        OAuth2AuthenticationToken authentication = googleAuthentication();
        PendingGoogleBinding pending = new PendingGoogleBinding("user", "subject", "person@example.com",
                Instant.parse("2030-01-01T00:00:00Z"));
        RuntimeException failure = new RuntimeException("authorized client removal failed");
        doThrow(failure).when(clients).removeAuthorizedClient("google", authentication, request, response);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> cleaner.rotateToPending(request, response, authentication, pending));

        assertSame(failure, thrown);
        assertThrows(IllegalStateException.class, () -> oldSession.getAttribute("oauth-state"));
        MockHttpSession replacement = (MockHttpSession) request.getSession(false);
        assertEquals(pending, replacement.getAttribute(PendingGoogleBinding.SESSION_KEY));
        assertEquals(List.of(PendingGoogleBinding.SESSION_KEY),
                java.util.Collections.list(replacement.getAttributeNames()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private OAuth2AuthenticationToken googleAuthentication() {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", "google-subject"), "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }
}
