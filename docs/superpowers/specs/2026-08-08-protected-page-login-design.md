# Protected Page Login Gateway Design

## Goal

Give unauthenticated visitors a clear sign-in experience when they request a protected page, then return them to that page after authentication. The existing navigation-bar sign-in modal remains unchanged and continues to use its current default redirect behavior.

## Scope

This change applies to normal browser requests handled by controllers annotated with `@AccessControl(level = SESSION)`. It introduces a standalone login gateway page and return-target handling for the existing email-link and Google authentication flows.

API and AJAX requests remain API-shaped: they continue to receive the existing unauthenticated JSON response instead of an HTML redirect. The change does not alter which controllers are protected, authentication cookies, account creation rules, API-key behavior, or the navigation sign-in modal.

## Selected Approach

Use a validated `returnTo` query parameter at the login gateway and carry the validated target through each authentication mechanism:

- Email-link authentication stores the target in the existing one-time email-login record, so the return works even when the link opens in a different browser or device.
- Google authentication stores the target in the OAuth HTTP session before starting authorization and reads it on completion.
- Authentication initiated from the existing modal does not supply a gateway return target and preserves the current Watchlist default.

This approach is preferred over session-only state, which cannot reliably cross browsers for email links, and over a fixed per-page mapping, which would require authentication changes whenever a protected page is added.

## Request and Redirect Flow

### Protected page request

1. `WebInterceptor` resolves the current user as it does today.
2. If a normal browser request targets a `SESSION`-protected handler without a valid user, the interceptor builds the original target from the request path and query string.
3. The target is encoded as the `returnTo` parameter of `/login`.
4. API and AJAX requests continue to receive the existing JSON unauthenticated response.

For example, an unauthenticated request for `/user/api-keys` redirects to `/login?returnTo=%2Fuser%2Fapi-keys`.

### Login gateway

`GET /login` renders a standalone page containing the same two authentication choices as the shared modal: Google when enabled, and passwordless email-link login. The page uses the site's existing dark visual system and shared header/footer where appropriate, but it is a page rather than a modal.

If an authenticated user requests `/login`, the controller redirects immediately to the validated target. If the target is missing or invalid, it uses the existing signed-in default `/user/watchlist?login=success`.

### Email-link authentication

The gateway's email request includes `returnTo`. The server validates it before passing it to `EmailLoginService`, and the service validates it again before persisting it in `EmailLoginLink.redirectPath`. When the one-time link is consumed successfully, `verifyEmail` redirects to that persisted target.

Email requests made by the existing modal omit `returnTo` and continue to store `/user/watchlist?login=success`. Existing Google account-binding email links retain their dedicated binding redirect and behavior.

### Google authentication

The gateway's Google action first calls a same-origin start endpoint with the validated `returnTo`. That endpoint stores the target in the OAuth session and redirects to the existing `/oauth2/authorization/google` endpoint.

On successful sign-in, `GoogleAuthenticationSuccessHandler` captures the validated target before OAuth cleanup invalidates the session, creates the authentication cookie, and redirects to that target. Google authentication initiated by the existing modal has no gateway target and retains the current Watchlist redirect.

If Google requires email confirmation before binding an existing account, the validated gateway target is copied into `PendingGoogleBinding`. The pending binding remains the authoritative state for that flow, and `verifyEmail` uses its target after binding completes. Existing fixed status codes remain supported. If pending-binding state is missing or expired, the flow uses its existing fixed error handling rather than trusting a target from the callback request.

## Return-Target Validation

Return targets are untrusted input and must be normalized by one shared server-side component before storage or redirection. A valid target:

- starts with exactly one `/`;
- has no scheme or authority;
- contains no backslash, carriage return, or line feed;
- parses as a local path with an optional query string;
- is not `/login` and does not otherwise point back to the login gateway.

Fragments are discarded because they are not sent to the server and are unnecessary for the current protected pages. Invalid, malformed, missing, or login-loop targets fall back to `/user/watchlist?login=success`.

The original protected-page target is built from the server request rather than trusting a client-provided referrer. Both authentication mechanisms validate again at their persistence and redirect boundaries to prevent open redirects even if callers bypass the gateway UI.

## Error Handling

- Invalid `returnTo` values never produce an external redirect; the server uses the signed-in default.
- Email validation, rate-limit, delivery, and network errors render in the login page's email status area while preserving the current target.
- Google callback failures return to `/login` with the existing fixed login result code and the retained valid target when available.
- Invalid or expired email links use the login gateway's error presentation when they originated there; existing modal-originated flows retain their current behavior.
- A failed authentication attempt must not grant access to or render the protected page.

## Accessibility and Presentation

The login gateway has one clear `Sign In` heading, labels for the email input, keyboard-operable controls, visible focus states, and polite live status regions separated by authentication method. Google controls are conditionally rendered using the existing `_googleLoginEnabled` request attribute.

The page reuses the site's current navy, cyan, and glass-border language and remains readable on mobile. It does not add draggable behavior because that interaction belongs only to the existing modal.

## Testing

Tests will cover:

- unauthenticated browser requests to `/user/api-keys` redirect to `/login` with the original path;
- original query parameters are preserved and encoded once;
- API and AJAX unauthenticated responses remain JSON and do not redirect;
- valid local targets pass validation, while schemes, authorities, protocol-relative paths, backslashes, control characters, malformed values, and login loops fall back safely;
- authenticated requests to `/login` return to a valid target;
- gateway email requests persist the target and successful verification returns to it;
- modal email requests retain the Watchlist default;
- gateway Google login returns to the stored target, including the pending-binding path;
- modal Google login retains the Watchlist default;
- the login page contains accessible Google and email controls and preserves method-specific status messages.

Focused tests will be run first during test-driven implementation, followed by the complete `wesite-web` reactor tests and `git diff --check`.
