package info.wesite.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.test.util.ReflectionTestUtils;

import info.wesite.core.entity.User;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.TokenUtils;

class AuthCookieServiceTest {

    @Test
    void createsThirtyDaySecureApplicationCookie() {
        AuthCookieService service = new AuthCookieService();
        ReflectionTestUtils.setField(service, "authCookieSecure", true);
        ReflectionTestUtils.setField(TokenUtils.class, "secret", "test-secret");
        ReflectionTestUtils.setField(TokenUtils.class, "issuer", "test-issuer");
        User user = activeUser("u1", "person");

        ResponseCookie cookie = service.create(user);

        assertEquals(Constants.TOKEN_KEY, cookie.getName());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Lax", cookie.getSameSite());
        assertEquals("/", cookie.getPath());
        assertEquals(Duration.ofDays(30), cookie.getMaxAge());
    }

    private User activeUser(String id, String name) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setSecureKey("secure-key");
        user.setStatus(User.STATUS_ACTIVE);
        return user;
    }
}
