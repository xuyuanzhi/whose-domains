package info.wesite.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReturnTargetServiceTest {

    private final ReturnTargetService service = new ReturnTargetService();

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/", "//evil.example/", "/\\evil", "/login",
            "/login?returnTo=/user/api-keys", "/login/google", "/./login", "/x/../login",
            "/login/./google", "/x/../login/google", "/../login", "/a/../../login",
            "/%2F%2Fevil.example/", "/%5Cevil", "/%6Cogin", "/bad\r\nLocation:https://evil.example"})
    void unsafeOrLoopingTargetsFallBack(String candidate) {
        assertEquals("/user/watchlist?login=success", service.resolve(candidate));
        assertTrue(service.validated(candidate).isEmpty());
    }

    @Test
    void validTargetAndQueryArePreserved() {
        assertEquals(Optional.of("/user/api-keys?tab=active&sort=new"),
                service.validated("/user/api-keys?tab=active&sort=new"));
    }

    @Test
    void gatewayUrlsEncodeTargetExactlyOnce() {
        assertEquals("/login?returnTo=%2Fuser%2Fapi-keys%3Ftab%3Dactive%26sort%3Dnew",
                service.loginUrl("/user/api-keys?tab=active&sort=new"));
        assertEquals("/login?login=google_error&returnTo=%2Fuser%2Fapi-keys",
                service.loginFailureUrl("google_error", "/user/api-keys"));
    }
}
