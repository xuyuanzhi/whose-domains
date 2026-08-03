package info.wesite.web.auth.google;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.TokenUtils;
import jakarta.servlet.http.Cookie;

class CurrentJwtUserResolverTest {

    private UserService userService;
    private CurrentJwtUserResolver resolver;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(TokenUtils.class, "secret", "current-jwt-user-resolver-test-secret");
        ReflectionTestUtils.setField(TokenUtils.class, "issuer", "current-jwt-user-resolver-test-issuer");
        userService = Mockito.mock(UserService.class);
        resolver = new CurrentJwtUserResolver(userService);
    }

    @Test
    void returnsEmptyWhenNoAccessTokenCookieIsPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertFalse(resolver.resolve(request).isPresent());

        verify(userService, never()).getById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsEmptyForAnInvalidJwtCookie() {
        MockHttpServletRequest request = requestWithToken("not-a-project-jwt");

        assertFalse(resolver.resolve(request).isPresent());

        verify(userService, never()).getById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsEmptyWhenTheJwtUserNoLongerExists() {
        User tokenUser = tokenUser("missing-user");
        when(userService.getById("missing-user")).thenReturn(null);

        assertFalse(resolver.resolve(requestWithToken(TokenUtils.createToken(tokenUser, 5))).isPresent());
    }

    @Test
    void returnsEmptyWhenTheReloadedUserIsInactive() {
        User tokenUser = tokenUser("inactive-user");
        User inactive = tokenUser("inactive-user");
        inactive.setStatus(BaseEntity.STATUS_INACTIVE);
        when(userService.getById("inactive-user")).thenReturn(inactive);

        assertFalse(resolver.resolve(requestWithToken(TokenUtils.createToken(tokenUser, 5))).isPresent());
    }

    @Test
    void returnsTheReloadedActiveUserForAValidJwtCookie() {
        User tokenUser = tokenUser("active-user");
        User active = tokenUser("active-user");
        active.setEmail("active@example.com");
        when(userService.getById("active-user")).thenReturn(active);

        User resolved = resolver.resolve(requestWithToken(TokenUtils.createToken(tokenUser, 5))).orElseThrow();

        assertSame(active, resolved);
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(Constants.TOKEN_KEY, token));
        return request;
    }

    private User tokenUser(String id) {
        User user = new User();
        user.setId(id);
        user.setName("Test user");
        user.setStatus(BaseEntity.STATUS_ACTIVE);
        return user;
    }
}
