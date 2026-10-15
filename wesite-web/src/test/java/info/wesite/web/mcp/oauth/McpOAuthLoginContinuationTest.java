package info.wesite.web.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.http.Cookie;

class McpOAuthLoginContinuationTest {
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-15T00:00:00Z");
    private final McpOAuthLoginContinuation continuations = new McpOAuthLoginContinuation(clock);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void expiresAtTenMinutesAndCannotBeRevived() throws Exception {
        var initial = start("state=original");
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        assertThat(resume(initial, initial.getCookie(McpOAuthLoginContinuation.COOKIE)).getStatus()).isEqualTo(400);
        when(clock.instant()).thenReturn(now);
        assertThat(resume(initial, initial.getCookie(McpOAuthLoginContinuation.COOKIE)).getStatus()).isEqualTo(400);
    }

    @Test
    void wrongBrowserCannotConsumeAnotherBrowsersContinuation() throws Exception {
        var initial = start("state=original");
        assertThat(resume(initial, new Cookie(McpOAuthLoginContinuation.COOKIE, "forged")).getStatus())
                .isEqualTo(400);
        var valid = resume(initial, initial.getCookie(McpOAuthLoginContinuation.COOKIE));
        assertThat(valid.getRedirectedUrl()).isEqualTo("/oauth2/authorize?state=original");
        assertThat(valid.getCookie(McpOAuthLoginContinuation.COOKIE).getMaxAge()).isZero();
        assertThat(valid.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(valid.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void parallelLoginAttemptsHaveIndependentCookiesAndParameters() throws Exception {
        var first = start("state=one&redirect_uri=https%3A%2F%2Fclient.example%2Fcallback");
        var second = start("state=two");
        var firstCookie = first.getCookie(McpOAuthLoginContinuation.COOKIE);
        var secondCookie = second.getCookie(McpOAuthLoginContinuation.COOKIE);
        assertThat(firstCookie.getPath()).isNotEqualTo(secondCookie.getPath());
        assertThat(firstCookie.getSecure()).isTrue();
        assertThat(first.getHeader("Set-Cookie")).contains("HttpOnly", "SameSite=Lax", "Max-Age=600");
        assertThat(resume(first, secondCookie).getStatus()).isEqualTo(400);
        assertThat(resume(second, secondCookie).getRedirectedUrl()).isEqualTo("/oauth2/authorize?state=two");
        assertThat(resume(first, firstCookie).getRedirectedUrl())
                .isEqualTo("/oauth2/authorize?state=one&redirect_uri=https%3A%2F%2Fclient.example%2Fcallback");
    }

    @Test
    void rejectsNonAuthorizationRequestsAndOversizedQueries() throws Exception {
        when(clock.instant()).thenReturn(now);
        var request = new MockHttpServletRequest("GET", "/oauth2/token");
        request.setQueryString("redirect_uri=https://evil.example");
        var response = new MockHttpServletResponse();
        continuations.commence(request, response, null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(start("x".repeat(8193)).getStatus()).isEqualTo(400);
    }

    @Test
    void capacityIsBoundedAndExpiredEntriesAreReclaimed() throws Exception {
        var first = start("state=first");
        for (int i = 1; i < 1000; i++) {
            assertThat(start("state=" + i, "203.0." + (i / 250) + "." + (i % 250 + 1)).getStatus()).isEqualTo(302);
        }
        assertThat(start("state=overflow", "198.51.100.1").getStatus()).isEqualTo(503);
        assertThat(resume(first, first.getCookie(McpOAuthLoginContinuation.COOKIE)).getStatus()).isEqualTo(302);
        assertThat(start("state=freed").getStatus()).isEqualTo(302);
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setQueryString("state=after-expiry");
        var response = new MockHttpServletResponse();
        continuations.commence(request, response, null);
        assertThat(response.getStatus()).isEqualTo(302);
    }

    @Test
    void oneSourceCannotExhaustCapacityAndItsSlotsRecoverAfterExpiry() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(start("state=" + i).getStatus()).isEqualTo(302);
        }
        assertThat(start("state=blocked").getStatus()).isEqualTo(429);
        assertThat(start("state=other", "198.51.100.11").getStatus()).isEqualTo(302);
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setRemoteAddr("198.51.100.10");
        request.setQueryString("state=still-full");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        var response = new MockHttpServletResponse();
        continuations.commence(request, response, null);
        assertThat(response.getStatus()).isEqualTo(429);
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        response = new MockHttpServletResponse();
        continuations.commence(request, response, null);
        assertThat(response.getStatus()).isEqualTo(302);
    }

    @Test
    void consumingContinuationsDoesNotResetCreationRateLimit() throws Exception {
        for (int i = 0; i < 20; i++) {
            var initial = start("state=" + i);
            assertThat(initial.getStatus()).isEqualTo(302);
            resume(initial, initial.getCookie(McpOAuthLoginContinuation.COOKIE));
        }
        assertThat(start("state=too-fast").getStatus()).isEqualTo(429);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setRemoteAddr("198.51.100.10");
        request.setQueryString("state=next-window");
        var response = new MockHttpServletResponse();
        continuations.commence(request, response, null);
        assertThat(response.getStatus()).isEqualTo(302);
    }

    private MockHttpServletResponse start(String query) throws Exception {
        return start(query, "198.51.100.10");
    }

    private MockHttpServletResponse start(String query, String ip) throws Exception {
        when(clock.instant()).thenReturn(now);
        var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setRemoteAddr(ip);
        request.setSecure(true);
        request.setQueryString(query);
        var response = new MockHttpServletResponse();
        continuations.commence(request, response, null);
        return response;
    }

    private MockHttpServletResponse resume(MockHttpServletResponse initial, Cookie cookie) throws Exception {
        String path = initial.getCookie(McpOAuthLoginContinuation.COOKIE).getPath();
        var request = new MockHttpServletRequest("GET", path);
        request.setSecure(true);
        request.setCookies(cookie);
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "n/a", List.of()));
        new McpOAuthLoginContinuation.ContinuationFilter(continuations).doFilter(request, response,
                (req, res) -> { throw new AssertionError("Continuation must be handled before MVC"); });
        return response;
    }
}
