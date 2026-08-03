package info.wesite.web.auth.google;

import java.util.Objects;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Component
public class OAuthSessionCleaner {

    private static final String GOOGLE_REGISTRATION_ID = "google";

    private final OAuth2AuthorizedClientRepository authorizedClientRepository;

    public OAuthSessionCleaner(OAuth2AuthorizedClientRepository authorizedClientRepository) {
        this.authorizedClientRepository = authorizedClientRepository;
    }

    public void clear(HttpServletRequest request, HttpServletResponse response,
            OAuth2AuthenticationToken authentication) {
        authorizedClientRepository.removeAuthorizedClient(GOOGLE_REGISTRATION_ID, authentication, request, response);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public void rotateToPending(HttpServletRequest request, HttpServletResponse response,
            OAuth2AuthenticationToken authentication, PendingGoogleBinding pending) {
        Objects.requireNonNull(pending, "pending binding must not be null");
        clear(request, response, authentication);
        request.getSession(true).setAttribute(PendingGoogleBinding.SESSION_KEY, pending);
    }
}
