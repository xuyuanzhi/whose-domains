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
import org.springframework.ui.Model;
import org.springframework.web.method.HandlerMethod;

import info.wesite.core.service.ApiKeyService;
import info.wesite.core.utils.ApiTokenUtils;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.config.GoogleLoginProperties;
import info.wesite.web.controller.ApiKeyController;
import info.wesite.web.controller.MainController;
import info.wesite.web.controller.UserController;
import info.wesite.web.controller.api.NotificationController;
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

        assertFalse(interceptor().preHandle(request, response, protectedPageHandler()));

        assertEquals("/login?returnTo=%2Fuser%2Fapi-keys%3Ftab%3Dactive%26sort%3Dnew",
                response.getRedirectedUrl());
    }

    @Test
    void realRestControllerFetchWithBrowserDefaultHeadersKeepsExistingNoAuthResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/api-keys/list");
        request.addHeader("Accept", "*/*");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor().preHandle(request, response, apiKeyListHandler()));

        assertNull(response.getRedirectedUrl());
        assertEquals("application/json;charset=utf-8", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"code\":" + ResponseJson.CODE_NOAUTH));
    }

    @Test
    void realResponseBodyMethodWithBrowserDefaultHeadersKeepsExistingNoAuthResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/session");
        request.addHeader("Accept", "*/*");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor().preHandle(request, response, userSessionHandler()));

        assertNull(response.getRedirectedUrl());
        assertEquals("application/json;charset=utf-8", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"code\":" + ResponseJson.CODE_NOAUTH));
    }

    @Test
    void notificationApiWithoutASessionKeepsTheApiNoAuthResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notifications");
        request.addHeader("Accept", "*/*");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor().preHandle(request, response, notificationListHandler()));

        assertNull(response.getRedirectedUrl());
        assertEquals("application/json;charset=utf-8", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"code\":" + ResponseJson.CODE_NOAUTH));
    }

    private WebInterceptor interceptor() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        return new WebInterceptor(environment, new GoogleLoginProperties(false, "", ""), new CanonicalUrlService());
    }

    private HandlerMethod protectedPageHandler() throws NoSuchMethodException {
        Method method = MainController.class.getMethod("apiKeys", Model.class);
        return new HandlerMethod(new MainController(), method);
    }

    private HandlerMethod apiKeyListHandler() throws NoSuchMethodException {
        Method method = ApiKeyController.class.getMethod("list");
        return new HandlerMethod(new ApiKeyController(mock(ApiKeyService.class)), method);
    }

    private HandlerMethod userSessionHandler() throws NoSuchMethodException {
        Method method = UserController.class.getMethod("session");
        return new HandlerMethod(new UserController(), method);
    }

    private HandlerMethod notificationListHandler() throws NoSuchMethodException {
        Method method = NotificationController.class.getMethod("list", int.class, String.class);
        return new HandlerMethod(new NotificationController(), method);
    }
}
