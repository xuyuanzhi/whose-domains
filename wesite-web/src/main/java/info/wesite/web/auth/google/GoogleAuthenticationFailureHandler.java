package info.wesite.web.auth.google;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import info.wesite.web.auth.ReturnTargetService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Component
public class GoogleAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleAuthenticationFailureHandler.class);
    private static final Map<GoogleLoginException.Code, String> LOGIN_CODES = Map.of(
            GoogleLoginException.Code.INVALID_IDENTITY, "google_invalid",
            GoogleLoginException.Code.UNVERIFIED_EMAIL, "google_unverified",
            GoogleLoginException.Code.ACCOUNT_CONFLICT, "google_conflict",
            GoogleLoginException.Code.INACTIVE_USER, "google_inactive",
            GoogleLoginException.Code.EMAIL_CONFIRMATION_UNAVAILABLE, "google_email_unavailable",
            GoogleLoginException.Code.EXPIRED_FLOW, "google_expired");

    private final ReturnTargetService returnTargets;

    public GoogleAuthenticationFailureHandler() {
        this(new ReturnTargetService());
    }

    @Autowired
    public GoogleAuthenticationFailureHandler(ReturnTargetService returnTargets) {
        this.returnTargets = returnTargets;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        Object candidate = session == null ? null
                : session.getAttribute(ReturnTargetService.GOOGLE_RETURN_TARGET_SESSION_KEY);
        redirect(response, exception, returnTargets, candidate instanceof String target ? target : null);
    }

    static void redirect(HttpServletResponse response, Throwable exception) throws IOException {
        redirect(response, exception, new ReturnTargetService(), null);
    }

    static void redirect(HttpServletResponse response, Throwable exception, ReturnTargetService returnTargets,
            String returnTo) throws IOException {
        String correlationId = UUID.randomUUID().toString();
        LOGGER.warn("Google authentication failure type={} correlationId={}", exception.getClass().getName(),
                correlationId);
        String code = loginCode(exception);
        String target = returnTargets.validated(returnTo).orElse(null);
        response.sendRedirect(target == null ? "/?login=" + code : returnTargets.loginFailureUrl(code, target));
    }

    private static String loginCode(Throwable exception) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable candidate = exception; candidate != null && seen.put(candidate, Boolean.TRUE) == null;
                candidate = candidate.getCause()) {
            if (candidate instanceof GoogleLoginException googleLoginException) {
                return LOGIN_CODES.getOrDefault(googleLoginException.code(), "google_error");
            }
        }
        return "google_error";
    }
}
