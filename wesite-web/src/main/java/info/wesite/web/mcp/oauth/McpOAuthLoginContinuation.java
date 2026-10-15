package info.wesite.web.mcp.oauth;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import info.wesite.web.auth.ReturnTargetService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded, single-use login continuations independent of the site's replaceable HTTP session. */
final class McpOAuthLoginContinuation implements AuthenticationEntryPoint {
    static final String PREFIX = "/mcp/authorize/continue/";
    static final String COOKIE = "mcp_oauth_continue";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int CAPACITY = 1000;
    private static final int SOURCE_LIMIT = 20;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final int RATE_CAPACITY = 2000;
    private final Map<String, CreationWindow> creationWindows = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final ReturnTargetService returnTargets = new ReturnTargetService();

    McpOAuthLoginContinuation() {
        this(Clock.systemUTC());
    }

    McpOAuthLoginContinuation(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        protectResponse(response);
        if (!"GET".equals(request.getMethod()) || !"/oauth2/authorize".equals(request.getServletPath())
                && !"/oauth2/authorize".equals(request.getRequestURI())) {
            response.sendError(401);
            return;
        }
        String query = request.getQueryString();
        if (query == null || query.length() > 8192 || query.indexOf('\r') >= 0 || query.indexOf('\n') >= 0) {
            response.sendError(400, "Invalid authorization request. Restart the connection from your MCP client.");
            return;
        }
        String token = secret();
        String binding = secret();
        synchronized (pending) {
            Instant now = clock.instant();
            // The container resolves trusted proxy headers; never trust arbitrary X-Forwarded-For here.
            String source = request.getRemoteAddr();
            pending.values().removeIf(value -> !now.isBefore(value.expiresAt()));
            creationWindows.values().removeIf(value -> !now.isBefore(value.expiresAt()));
            CreationWindow window = creationWindows.get(source);
            if (pending.values().stream().filter(value -> source.equals(value.source())).count() >= SOURCE_LIMIT
                    || window != null && window.count() >= SOURCE_LIMIT) {
                response.sendError(429, "Too many connection attempts. Please retry later.");
                return;
            }
            if (pending.size() >= CAPACITY || window == null && creationWindows.size() >= RATE_CAPACITY) {
                response.sendError(503, "Too many pending connections. Please retry later.");
                return;
            }
            pending.put(token, new Pending("/oauth2/authorize?" + query, binding, now.plus(TTL), source));
            creationWindows.put(source, window == null
                    ? new CreationWindow(1, now.plus(RATE_WINDOW))
                    : new CreationWindow(window.count() + 1, window.expiresAt()));
        }
        String path = PREFIX + token;
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(binding, path, request.isSecure(), TTL).toString());
        redirect(response, returnTargets.loginUrl(path));
    }

    private void resume(HttpServletRequest request, HttpServletResponse response) throws IOException {
        protectResponse(response);
        if (!"GET".equals(request.getMethod())) {
            response.sendError(405);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String token = path.substring(PREFIX.length());
        String binding = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE.equals(cookie.getName())) {
                    binding = cookie.getValue();
                }
            }
        }
        Pending continuation;
        synchronized (pending) {
            continuation = pending.get(token);
            if (continuation != null && !clock.instant().isBefore(continuation.expiresAt())) {
                pending.remove(token);
                continuation = null;
            }
            if (continuation == null || binding == null || !MessageDigest.isEqual(
                    continuation.binding().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    binding.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                response.sendError(400, "Connection expired or browser changed. Restart from your MCP client.");
                return;
            }
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
                redirect(response, returnTargets.loginUrl(path));
                return;
            }
            pending.remove(token);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", path, request.isSecure(), Duration.ZERO).toString());
        redirect(response, continuation.target());
    }

    private String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ResponseCookie cookie(String value, String path, boolean secure, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value).path(path).httpOnly(true).secure(secure)
                .sameSite("Lax").maxAge(maxAge).build();
    }

    private static void protectResponse(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    private static void redirect(HttpServletResponse response, String target) {
        // Relative Location preserves the public HTTPS origin behind the reverse proxy.
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, target);
    }

    private record Pending(String target, String binding, Instant expiresAt, String source) { }

    private record CreationWindow(int count, Instant expiresAt) { }

    static final class ContinuationFilter extends OncePerRequestFilter {
        private final McpOAuthLoginContinuation continuations;

        ContinuationFilter(McpOAuthLoginContinuation continuations) {
            this.continuations = continuations;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (path.startsWith(PREFIX)) {
                continuations.resume(request, response);
            } else {
                chain.doFilter(request, response);
            }
        }
    }
}
