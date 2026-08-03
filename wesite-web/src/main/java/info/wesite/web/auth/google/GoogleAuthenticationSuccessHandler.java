package info.wesite.web.auth.google;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.User;
import info.wesite.web.auth.AuthCookieService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class GoogleAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final String SUCCESS_REDIRECT = "/user/watchlist?login=success";
    private static final String PENDING_REDIRECT = "/?login=google_check_email";

    private final GoogleIdentityParser identityParser;
    private final CurrentJwtUserResolver currentUserResolver;
    private final GoogleLoginService googleLoginService;
    private final AuthCookieService authCookieService;
    private final OAuthSessionCleaner sessionCleaner;

    @Autowired
    public GoogleAuthenticationSuccessHandler(CurrentJwtUserResolver currentUserResolver,
            GoogleLoginService googleLoginService, AuthCookieService authCookieService, OAuthSessionCleaner sessionCleaner) {
        this(new GoogleIdentityParser(), currentUserResolver, googleLoginService, authCookieService, sessionCleaner);
    }

    public GoogleAuthenticationSuccessHandler(GoogleIdentityParser identityParser,
            CurrentJwtUserResolver currentUserResolver, GoogleLoginService googleLoginService,
            AuthCookieService authCookieService, OAuthSessionCleaner sessionCleaner) {
        this.identityParser = identityParser;
        this.currentUserResolver = currentUserResolver;
        this.googleLoginService = googleLoginService;
        this.authCookieService = authCookieService;
        this.sessionCleaner = sessionCleaner;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        OAuth2AuthenticationToken googleAuthentication;
        try {
            googleAuthentication = requiredGoogleAuthentication(authentication);
        } catch (RuntimeException exception) {
            GoogleAuthenticationFailureHandler.redirect(response, exception);
            return;
        }

        GoogleLoginResult result;
        try {
            OidcUser oidcUser = (OidcUser) googleAuthentication.getPrincipal();
            GoogleIdentity identity = identityParser.parse(oidcUser);
            User currentUser = currentUserResolver.resolve(request).orElse(null);
            result = googleLoginService.authenticate(identity, currentUser);
        } catch (RuntimeException exception) {
            clearAfterFailedLogin(request, response, googleAuthentication);
            GoogleAuthenticationFailureHandler.redirect(response, exception);
            return;
        }

        try {
            if (result.status() == GoogleLoginResult.Status.SIGNED_IN) {
                sessionCleaner.clear(request, response, googleAuthentication);
                response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.create(result.user()).toString());
                response.sendRedirect(SUCCESS_REDIRECT);
                return;
            }

            sessionCleaner.rotateToPending(request, response, googleAuthentication, result.pendingBinding());
            response.sendRedirect(PENDING_REDIRECT);
        } catch (RuntimeException exception) {
            GoogleAuthenticationFailureHandler.redirect(response, exception);
        }
    }

    private void clearAfterFailedLogin(HttpServletRequest request, HttpServletResponse response,
            OAuth2AuthenticationToken googleAuthentication) {
        try {
            sessionCleaner.clear(request, response, googleAuthentication);
        } catch (RuntimeException ignored) {
            // OAuthSessionCleaner has already cleared the security context and invalidated the session in its finally block.
        }
    }

    private OAuth2AuthenticationToken requiredGoogleAuthentication(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken googleAuthentication)
                || !(googleAuthentication.getPrincipal() instanceof OidcUser)) {
            throw new GoogleLoginException(GoogleLoginException.Code.INVALID_IDENTITY);
        }
        return googleAuthentication;
    }
}
