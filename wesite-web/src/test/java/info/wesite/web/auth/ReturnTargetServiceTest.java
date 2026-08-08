package info.wesite.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @ParameterizedTest
    @ValueSource(strings = {"/login/", "/login;foo", "/login;foo/google",
            "/oauth2/authorization/google", "/oauth2/authorization/google/callback", "/oauth2;foo/authorization/google",
            "/login/oauth2/code/google", "/user/verify-email?token=attacker-token", "/user/verify-email/",
            "/user/verify-email;foo?token=attacker-token", "/user/email-login", "/user/email-login/again",
            "/user/logout", "/user/logout/", "/user/session"})
    void authenticationAndSessionEndpointsCannotBeReturnTargets(String candidate) {
        assertTrue(service.validated(candidate).isEmpty());
        assertEquals(ReturnTargetService.DEFAULT_TARGET, service.resolve(candidate));
    }

    @Test
    void validTargetAndQueryArePreserved() {
        assertEquals(Optional.of("/user/api-keys?tab=active&sort=new"),
                service.validated("/user/api-keys?tab=active&sort=new"));
    }

    @Test
    void acceptsAtMostFiveHundredJavaCharactersIncludingMultibyteCharacters() {
        String target = "/" + "界".repeat(499);

        assertEquals(500, target.length());
        assertEquals(Optional.of(target), service.validated(target));
    }

    @Test
    void rejectsFiveHundredAndOneJavaCharacters() {
        String target = "/" + "界".repeat(500);

        assertEquals(501, target.length());
        assertTrue(service.validated(target).isEmpty());
    }

    @Test
    void gatewayUrlsEncodeTargetExactlyOnce() {
        assertEquals("/login?returnTo=%2Fuser%2Fapi-keys%3Ftab%3Dactive%26sort%3Dnew",
                service.loginUrl("/user/api-keys?tab=active&sort=new"));
        assertEquals("/login?login=google_error&returnTo=%2Fuser%2Fapi-keys",
                service.loginFailureUrl("google_error", "/user/api-keys"));
    }

    @Test
    void failureCodeCannotInjectAnotherQueryParameter() {
        String url = service.loginFailureUrl("google_error&returnTo=https://evil.example", "/user/api-keys");

        assertEquals("/login?login=google_error%26returnTo%3Dhttps%3A%2F%2Fevil.example&returnTo=%2Fuser%2Fapi-keys",
                url);
        assertFalse(url.contains("&returnTo=https://evil.example"));
    }

    @Test
    void recognizesOnlyValidatedDomainMonitorContinuations() {
        assertTrue(service.isDomainMonitorContinuation("/domain/example.com?monitor=pending"));
        assertFalse(service.isDomainMonitorContinuation("/user/watchlist?monitor=pending"));
        assertFalse(service.isDomainMonitorContinuation("https://evil.example/domain/example.com?monitor=pending"));
    }

    @Test
    void appendsEncodedLoginResultToMonitorContinuation() {
        assertEquals(
                "/domain/example.com?source=lookup&monitor=pending&login=google_error",
                service.monitorLoginResultUrl(
                        "/domain/example.com?source=lookup&monitor=pending", "google_error"));
    }

    @Test
    void monitorLoginResultCodeCannotInjectAnotherQueryParameter() {
        String target = "/domain/example.com?source=lookup&monitor=pending";

        assertEquals(target, service.monitorLoginResultUrl(target, "google_error&source=attacker"));
    }
}
