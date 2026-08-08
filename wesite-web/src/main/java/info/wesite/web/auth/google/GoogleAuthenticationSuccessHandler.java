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
import info.wesite.web.auth.ReturnTargetService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Component
public class GoogleAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final String PENDING_REDIRECT = "/?login=google_check_email";

    private final GoogleIdentityParser identityParser;
    private final CurrentJwtUserResolver currentUserResolver;
    private final GoogleLoginService googleLoginService;
    private final AuthCookieService authCookieService;
    private final OAuthSessionCleaner sessionCleaner;
    private final ReturnTargetService returnTargets;

    @Autowired
    public GoogleAuthenticationSuccessHandler(CurrentJwtUserResolver currentUserResolver,
            GoogleLoginService googleLoginService, AuthCookieService authCookieService, OAuthSessionCleaner sessionCleaner,
            ReturnTargetService returnTargets) {
        this(new GoogleIdentityParser(), currentUserResolver, googleLoginService, authCookieService, sessionCleaner,
                returnTargets);
    }

    public GoogleAuthenticationSuccessHandler(GoogleIdentityParser identityParser,
            CurrentJwtUserResolver currentUserResolver, GoogleLoginService googleLoginService,
            AuthCookieService authCookieService, OAuthSessionCleaner sessionCleaner) {
        this(identityParser, currentUserResolver, googleLoginService, authCookieService, sessionCleaner,
                new ReturnTargetService());
    }

    public GoogleAuthenticationSuccessHandler(GoogleIdentityParser identityParser,
            CurrentJwtUserResolver currentUserResolver, GoogleLoginService googleLoginService,
            AuthCookieService authCookieService, OAuthSessionCleaner sessionCleaner, ReturnTargetService returnTargets) {
        this.identityParser = identityParser;
        this.currentUserResolver = currentUserResolver;
        this.googleLoginService = googleLoginService;
        this.authCookieService = authCookieService;
        this.sessionCleaner = sessionCleaner;
        this.returnTargets = returnTargets;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        String returnTo = returnTarget(request);
        if (!(authentication instanceof OAuth2AuthenticationToken googleAuthentication)) {
            GoogleAuthenticationFailureHandler.redirect(response,
                    new GoogleLoginException(GoogleLoginException.Code.INVALID_IDENTITY), returnTargets, returnTo);
            return;
        }

        GoogleLoginResult result;
        try {
            OidcUser oidcUser = requiredOidcUser(googleAuthentication);
            GoogleIdentity identity = identityParser.parse(oidcUser);
            User currentUser = currentUserResolver.resolve(request).orElse(null);
            result = googleLoginService.authenticate(identity, currentUser);
        } catch (RuntimeException exception) {
            clearAfterFailedLogin(request, response, googleAuthentication);
            GoogleAuthenticationFailureHandler.redirect(response, exception, returnTargets, returnTo);
            return;
        }

        try {
            if (result.status() == GoogleLoginResult.Status.SIGNED_IN) {
                sessionCleaner.clear(request, response, googleAuthentication);
                response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.create(result.user()).toString());
                response.sendRedirect(returnTargets.resolve(returnTo));
                return;
            }

            sessionCleaner.rotateToPending(request, response, googleAuthentication,
                    result.pendingBinding().withReturnTo(returnTo));
            response.sendRedirect(PENDING_REDIRECT);
        } catch (RuntimeException exception) {
            GoogleAuthenticationFailureHandler.redirect(response, exception, returnTargets, returnTo);
        }
    }

    private String returnTarget(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object candidate = session.getAttribute(ReturnTargetService.GOOGLE_RETURN_TARGET_SESSION_KEY);
        return candidate instanceof String target ? returnTargets.validated(target).orElse(null) : null;
    }

    private void clearAfterFailedLogin(HttpServletRequest request, HttpServletResponse response,
            OAuth2AuthenticationToken googleAuthentication) {
        try {
            sessionCleaner.clear(request, response, googleAuthentication);
        } catch (RuntimeException ignored) {
            // OAuthSessionCleaner has already cleared the security context and invalidated the session in its finally block.
        }
    }

    private OidcUser requiredOidcUser(OAuth2AuthenticationToken googleAuthentication) {
        if (!(googleAuthentication.getPrincipal() instanceof OidcUser oidcUser)) {
            throw new GoogleLoginException(GoogleLoginException.Code.INVALID_IDENTITY);
        }
        return oidcUser;
    }
}
