package info.wesite.web.auth.google;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import info.wesite.web.auth.ReturnTargetService;
import jakarta.servlet.http.HttpServletRequest;

public final class GoogleOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String DEFAULT_AUTHORIZATION_REQUEST_BASE_URI = "/oauth2/authorization";
    private static final String GOOGLE_REGISTRATION_ID = "google";
    private static final String GOOGLE_START_PATH = "/login/google";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final ReturnTargetService returnTargets;

    public GoogleOAuth2AuthorizationRequestResolver(ClientRegistrationRepository clientRegistrations,
            ReturnTargetService returnTargets) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrations,
                DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        this.returnTargets = returnTargets;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        if (isGoogleStart(request)) {
            return withReturnTarget(delegate.resolve(request, GOOGLE_REGISTRATION_ID), request);
        }
        return delegate.resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest authorizationRequest = delegate.resolve(request, clientRegistrationId);
        return isGoogleStart(request) ? withReturnTarget(authorizationRequest, request) : authorizationRequest;
    }

    private OAuth2AuthorizationRequest withReturnTarget(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request) {
        if (authorizationRequest == null || !request.getParameterMap().containsKey("returnTo")) {
            return authorizationRequest;
        }
        String returnTo = returnTargets.resolve(request.getParameter("returnTo"));
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .attributes(attributes -> attributes.put(
                        GoogleOAuth2AuthorizationRequestRepository.RETURN_TARGET_ATTRIBUTE, returnTo))
                .build();
    }

    private boolean isGoogleStart(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        String path = requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : requestUri;
        return GOOGLE_START_PATH.equals(path);
    }
}
