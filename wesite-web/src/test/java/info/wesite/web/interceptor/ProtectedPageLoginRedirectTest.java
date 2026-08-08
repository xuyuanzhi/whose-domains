package info.wesite.web.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import info.wesite.core.config.AccessControl;
import info.wesite.core.utils.ApiTokenUtils;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.config.GoogleLoginProperties;
import info.wesite.web.seo.CanonicalUrlService;

class ProtectedPageLoginRedirectTest {

    @BeforeAll
    static void initializeApiTokenSecret() {
        ApiTokenUtils apiTokenUtils = new ApiTokenUtils(mock(Environment.class));
        ReflectionTestUtils.setField(apiTokenUtils, "jwtSecret", "test-secret");
        apiTokenUtils.init();
    }

    @Test
    void browserRequestRedirectsWithOriginalQuery() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/api-keys");
        request.setQueryString("tab=active&sort=new");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor().preHandle(request, response, protectedHandler()));

        assertEquals("/login?returnTo=%2Fuser%2Fapi-keys%3Ftab%3Dactive%26sort%3Dnew",
                response.getRedirectedUrl());
    }

    @Test
    void jsonRequestKeepsExistingNoAuthResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/api-keys/list");
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor().preHandle(request, response, protectedHandler()));

        assertNull(response.getRedirectedUrl());
        assertEquals("application/json;charset=utf-8", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"code\":" + ResponseJson.CODE_NOAUTH));
    }

    private WebInterceptor interceptor() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        return new WebInterceptor(environment, new GoogleLoginProperties(false, "", ""), new CanonicalUrlService());
    }

    private HandlerMethod protectedHandler() throws NoSuchMethodException {
        Method method = ProtectedHandler.class.getDeclaredMethod("protectedEndpoint");
        return new HandlerMethod(new ProtectedHandler(), method);
    }

    static class ProtectedHandler {

        @AccessControl(level = AccessControl.Level.SESSION)
        void protectedEndpoint() {
        }
    }
}
