package info.wesite.web.auth.google;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public final class GoogleOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String RETURN_TARGET_ATTRIBUTE = GoogleOAuth2AuthorizationRequestRepository.class.getName()
            + ".RETURN_TARGET";
    static final String CURRENT_RETURN_TARGET_ATTRIBUTE = GoogleOAuth2AuthorizationRequestRepository.class.getName()
            + ".CURRENT_RETURN_TARGET";

    private static final String AUTHORIZATION_REQUESTS_SESSION_ATTRIBUTE =
            GoogleOAuth2AuthorizationRequestRepository.class.getName() + ".AUTHORIZATION_REQUESTS";
    private static final int MAX_AUTHORIZATION_REQUESTS = 16;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Assert.notNull(request, "request cannot be null");
        String state = request.getParameter("state");
        if (!StringUtils.hasText(state)) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        synchronized (session) {
            return authorizationRequests(session).get(state);
        }
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request, HttpServletResponse response) {
        Assert.notNull(request, "request cannot be null");
        Assert.notNull(response, "response cannot be null");
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        Assert.hasText(authorizationRequest.getState(), "authorizationRequest.state cannot be empty");

        HttpSession session = request.getSession(true);
        synchronized (session) {
            LinkedHashMap<String, OAuth2AuthorizationRequest> requests = authorizationRequests(session);
            requests.put(authorizationRequest.getState(), authorizationRequest);
            trimOldestRequests(requests);
            session.setAttribute(AUTHORIZATION_REQUESTS_SESSION_ATTRIBUTE, requests);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        Assert.notNull(request, "request cannot be null");
        Assert.notNull(response, "response cannot be null");
        String state = request.getParameter("state");
        if (!StringUtils.hasText(state)) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }

        OAuth2AuthorizationRequest authorizationRequest;
        synchronized (session) {
            LinkedHashMap<String, OAuth2AuthorizationRequest> requests = authorizationRequests(session);
            authorizationRequest = requests.remove(state);
            if (requests.isEmpty()) {
                session.removeAttribute(AUTHORIZATION_REQUESTS_SESSION_ATTRIBUTE);
            } else {
                session.setAttribute(AUTHORIZATION_REQUESTS_SESSION_ATTRIBUTE, requests);
            }
        }
        if (authorizationRequest != null) {
            Object returnTo = authorizationRequest.getAttribute(RETURN_TARGET_ATTRIBUTE);
            if (returnTo instanceof String target) {
                request.setAttribute(CURRENT_RETURN_TARGET_ATTRIBUTE, target);
            }
        }
        return authorizationRequest;
    }

    static Optional<String> consumeReturnTarget(HttpServletRequest request) {
        Object candidate = request.getAttribute(CURRENT_RETURN_TARGET_ATTRIBUTE);
        request.removeAttribute(CURRENT_RETURN_TARGET_ATTRIBUTE);
        return candidate instanceof String target ? Optional.of(target) : Optional.empty();
    }

    private LinkedHashMap<String, OAuth2AuthorizationRequest> authorizationRequests(HttpSession session) {
        LinkedHashMap<String, OAuth2AuthorizationRequest> requests = new LinkedHashMap<>();
        Object value = session.getAttribute(AUTHORIZATION_REQUESTS_SESSION_ATTRIBUTE);
        if (!(value instanceof Map<?, ?> stored)) {
            return requests;
        }
        stored.forEach((state, authorizationRequest) -> {
            if (state instanceof String stateValue
                    && authorizationRequest instanceof OAuth2AuthorizationRequest requestValue) {
                requests.put(stateValue, requestValue);
            }
        });
        return requests;
    }

    private void trimOldestRequests(LinkedHashMap<String, OAuth2AuthorizationRequest> requests) {
        Iterator<String> states = requests.keySet().iterator();
        while (requests.size() > MAX_AUTHORIZATION_REQUESTS && states.hasNext()) {
            states.next();
            states.remove();
        }
    }
}
