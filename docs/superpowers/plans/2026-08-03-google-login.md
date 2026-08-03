# Google Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Google OpenID Connect login alongside the existing email magic-link flow while preserving the current JWT Cookie authorization model.

**Architecture:** Spring Security handles only the Google authorization and callback endpoints; an always-present pass-through chain prevents Boot's default security from taking over the application. Google accounts bind to `SYS_USER.GOOGLE_SUB`, while shared services issue the existing JWT Cookie and email links. Non-Google-managed email addresses require magic-link confirmation before creation or binding.

**Tech Stack:** Java 17, Spring Boot 3.5.0, Spring Security OAuth2 Client/OIDC, Spring MVC, MyBatis-Plus, MySQL/MariaDB, Thymeleaf, JUnit 5, Mockito.

## Global Constraints

- Keep email magic-link login as a peer sign-in method; Google login does not replace it.
- Keep `WebInterceptor + TokenUtils + ACCESSTOKEN` as the application authorization mechanism after login.
- Store only Google's stable `sub` in `SYS_USER.GOOGLE_SUB`; do not create a provider identity table.
- Never persist Google ID Tokens, Access Tokens, Refresh Tokens, authorization codes, or Client Secrets.
- Require `email_verified=true` and reject missing `sub` or email claims.
- Treat only `gmail.com`, or an email whose domain exactly matches a non-empty `hd` claim, as Google-managed.
- Require magic-link confirmation for every third-party email, including new users.
- Reject `STATUS_INACTIVE` and logically deleted users on every Google login path.
- Configure production callback URI as `https://whose.domains/login/oauth2/code/google`.
- Keep Google login disabled by default; no Google credentials are committed.
- Preserve HttpOnly, Secure, SameSite=Lax, Path=/, and 30-day lifetime for the application login Cookie.
- Preserve unrelated dirty-worktree changes and stage only files belonging to the current task.

## File Structure

- `wesite-core/src/main/java/info/wesite/core/entity/User.java`: add the persisted `googleSub` property.
- `doc/alter_google_login.sql`: preflight and migrate normalized email uniqueness plus `GOOGLE_SUB`.
- `doc/create.sql`: describe the final schema for fresh installations.
- `wesite-web/src/main/java/info/wesite/web/auth/AuthCookieService.java`: single source of truth for creating and clearing the application JWT Cookie.
- `wesite-web/src/main/java/info/wesite/web/auth/EmailLoginService.java`: normalize email, enforce link limits, persist a one-time link, and send it.
- `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleIdentity.java`: immutable validated Google identity.
- `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleIdentityParser.java`: validate OIDC claims and classify the email.
- `wesite-web/src/main/java/info/wesite/web/auth/google/PendingGoogleBinding.java`: minimal serializable state retained across email confirmation.
- `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginResult.java`: signed-in or pending-confirmation result.
- `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginException.java`: stable internal error code without provider response data.
- `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginService.java`: transactional account lookup, creation, binding, and confirmation.
- `wesite-web/src/main/java/info/wesite/web/auth/google/CurrentJwtUserResolver.java`: resolve the existing application Cookie to a live database user.
- `wesite-web/src/main/java/info/wesite/web/auth/google/OAuthSessionCleaner.java`: remove the authorized client and rotate/invalidate OAuth sessions.
- `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationSuccessHandler.java`: bridge validated Google identity into the application Cookie.
- `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationFailureHandler.java`: map failures to safe UI codes.
- `wesite-web/src/main/java/info/wesite/web/config/GoogleLoginProperties.java`: feature flag, credentials, and callback construction.
- `wesite-web/src/main/java/info/wesite/web/config/SecurityConfig.java`: OAuth chain and always-present pass-through chain.
- `wesite-web/src/main/java/info/wesite/web/controller/UserController.java`: delegate shared email/Cookie work and finish pending Google binding.
- `wesite-web/src/main/java/info/wesite/web/interceptor/WebInterceptor.java`: expose the enabled flag to Thymeleaf.
- `wesite-web/src/main/resources/views/template.html`: Google button, safe status messages, and finish-binding action.

---

### Task 1: Database schema and user model

**Files:**
- Modify: `wesite-core/src/main/java/info/wesite/core/entity/User.java`
- Create: `doc/alter_google_login.sql`
- Modify: `doc/create.sql:15-34`
- Test: `wesite-core/src/test/java/info/wesite/core/entity/UserGoogleSubjectTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/schema/GoogleLoginSchemaTest.java`

**Interfaces:**
- Produces: `User#getGoogleSub()` and `User#setGoogleSub(String)`.
- Produces: nullable, unique, case-sensitive `SYS_USER.GOOGLE_SUB varchar(255)`.
- Produces: normalized, case-insensitive uniqueness for `SYS_USER.EMAIL`.

- [ ] **Step 1: Write failing model and schema tests**

```java
// UserGoogleSubjectTest.java
@Test
void storesGoogleSubject() {
    User user = new User();
    user.setGoogleSub("10769150350006150715113082367");
    assertEquals("10769150350006150715113082367", user.getGoogleSub());
}
```

```java
// GoogleLoginSchemaTest.java
@Test
void migrationNormalizesEmailAndAddsUniqueGoogleSubject() throws IOException {
    String sql = Files.readString(Path.of("..", "doc", "alter_google_login.sql"));
    assertTrue(sql.contains("LOWER(TRIM(`EMAIL`))"));
    assertTrue(sql.contains("GOOGLE_SUB"));
    assertTrue(sql.contains("UNIQUE KEY `IDX_USER_GOOGLE_SUB`"));
}
```

- [ ] **Step 2: Run tests and verify the missing property/script failures**

Run:

```powershell
mvn -pl wesite-web -am -Dtest=UserGoogleSubjectTest,GoogleLoginSchemaTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `User.googleSub` is absent and the migration file is missing.

- [ ] **Step 3: Add the model field and explicit migration**

Add to `User`:

```java
private String googleSub;
```

Create `alter_google_login.sql` with an operator-visible preflight query followed by the migration statements:

```sql
SELECT LOWER(TRIM(`EMAIL`)) AS NORMALIZED_EMAIL, COUNT(*) AS USER_COUNT
FROM `SYS_USER`
WHERE `EMAIL` IS NOT NULL AND TRIM(`EMAIL`) <> ''
GROUP BY LOWER(TRIM(`EMAIL`))
HAVING COUNT(*) > 1;

-- Stop here and resolve every returned row before executing the statements below.
UPDATE `SYS_USER`
SET `EMAIL` = LOWER(TRIM(`EMAIL`))
WHERE `EMAIL` IS NOT NULL;

ALTER TABLE `SYS_USER`
  DROP INDEX `IDX_USER_EMAIL`,
  MODIFY COLUMN `EMAIL` varchar(255) COLLATE utf8mb4_unicode_ci NULL,
  ADD UNIQUE KEY `IDX_USER_EMAIL` (`EMAIL`),
  ADD COLUMN `GOOGLE_SUB` varchar(255) COLLATE utf8mb4_bin NULL AFTER `EMAIL`,
  ADD UNIQUE KEY `IDX_USER_GOOGLE_SUB` (`GOOGLE_SUB`);
```

Apply the same final column definitions and indexes to the `SYS_USER` block in `doc/create.sql`.

- [ ] **Step 4: Run the focused tests**

```powershell
mvn -pl wesite-web -am -Dtest=UserGoogleSubjectTest,GoogleLoginSchemaTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 5: Commit only the schema/model slice**

```powershell
git add -- wesite-core/src/main/java/info/wesite/core/entity/User.java wesite-core/src/test/java/info/wesite/core/entity/UserGoogleSubjectTest.java wesite-web/src/test/java/info/wesite/web/schema/GoogleLoginSchemaTest.java doc/alter_google_login.sql doc/create.sql
git commit -m "feat: add Google subject to users"
```

### Task 2: Shared email-link and application Cookie services

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/auth/AuthCookieService.java`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/EmailLoginService.java`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/EmailLoginRequestResult.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/UserController.java`
- Test: `wesite-web/src/test/java/info/wesite/web/auth/AuthCookieServiceTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/auth/EmailLoginServiceTest.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/controller/UserControllerEmailLoginTest.java`

**Interfaces:**
- Produces: `ResponseCookie AuthCookieService.create(User user)`.
- Produces: `ResponseCookie AuthCookieService.clear()`.
- Produces: `EmailLoginRequestResult EmailLoginService.request(String rawEmail, String redirectPath)`.
- `redirectPath` is limited inside the service to `/user/watchlist?login=success` or `/user/watchlist?login=google_bind_required`.

- [ ] **Step 1: Write failing tests for shared Cookie attributes and redirect-path rejection**

```java
@Test
void createsThirtyDaySecureApplicationCookie() {
    User user = activeUser("u1", "person");
    ResponseCookie cookie = service.create(user);
    assertEquals(Constants.TOKEN_KEY, cookie.getName());
    assertTrue(cookie.isHttpOnly());
    assertTrue(cookie.isSecure());
    assertEquals("Lax", cookie.getSameSite());
    assertEquals("/", cookie.getPath());
    assertEquals(Duration.ofDays(30), cookie.getMaxAge());
}
```

Extend `UserControllerEmailLoginTest` so the controller delegates to `EmailLoginService.request(email, "/user/watchlist?login=success")`, and add a service test that rejects `https://evil.example/` with `IllegalArgumentException`.

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
mvn -pl wesite-web -am -Dtest=AuthCookieServiceTest,EmailLoginServiceTest,UserControllerEmailLoginTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the shared services do not exist.

- [ ] **Step 3: Extract the existing behavior without changing messages or limits**

Implement:

```java
public record EmailLoginRequestResult(boolean success, String message) {
    public static EmailLoginRequestResult success(String message) {
        return new EmailLoginRequestResult(true, message);
    }
    public static EmailLoginRequestResult failure(String message) {
        return new EmailLoginRequestResult(false, message);
    }
}
```

Move email normalization, validation, the 2-minute cooldown, 10-per-day limit, hashed token persistence, and mail sending from `UserController.requestEmailLogin` into `EmailLoginService.request`. Store the validated fixed redirect path in `EmailLoginLink.redirectPath`. Move the private `authCookie` method into `AuthCookieService`, inject both services into `UserController`, and keep current API response strings unchanged.

- [ ] **Step 4: Run existing and new email-login tests**

```powershell
mvn -pl wesite-web -am -Dtest=AuthCookieServiceTest,EmailLoginServiceTest,UserControllerEmailLoginTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 5: Commit the behavior-preserving refactor**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/auth wesite-web/src/main/java/info/wesite/web/controller/UserController.java wesite-web/src/test/java/info/wesite/web/auth wesite-web/src/test/java/info/wesite/web/controller/UserControllerEmailLoginTest.java
git commit -m "refactor: share email and cookie authentication services"
```

### Task 3: Validated Google identity parsing

**Files:**
- Modify: `wesite-web/pom.xml`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleIdentity.java`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleIdentityParser.java`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginException.java`
- Test: `wesite-web/src/test/java/info/wesite/web/auth/google/GoogleIdentityParserTest.java`

**Interfaces:**
- Produces: `GoogleIdentity GoogleIdentityParser.parse(OidcUser user)`.
- Produces: `record GoogleIdentity(String subject, String email, String displayName, boolean googleManagedEmail)`.
- Produces: `GoogleLoginException.Code` values `INVALID_IDENTITY`, `UNVERIFIED_EMAIL`, `ACCOUNT_CONFLICT`, `INACTIVE_USER`, `EMAIL_CONFIRMATION_UNAVAILABLE`, `EXPIRED_FLOW`.

- [ ] **Step 1: Write failing claim-validation tests**

Use a mocked `OidcUser` and cover these exact cases:

```java
@Test
void classifiesWorkspaceOnlyWhenHostedDomainMatchesEmail() {
    when(user.getSubject()).thenReturn("sub-1");
    when(user.getEmail()).thenReturn(" Person@Example.com ");
    when(user.getEmailVerified()).thenReturn(true);
    when(user.getClaimAsString("hd")).thenReturn("example.com");
    when(user.getFullName()).thenReturn("Person Name");

    GoogleIdentity identity = parser.parse(user);

    assertEquals("person@example.com", identity.email());
    assertTrue(identity.googleManagedEmail());
}
```

Also test Gmail, mismatched `hd`, missing `sub`, missing email, and `email_verified=false`.

- [ ] **Step 2: Add the OAuth2 Client dependency required by the OIDC parser**

Add only to `wesite-web/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

- [ ] **Step 3: Run the parser test and verify failure**

```powershell
mvn -pl wesite-web -am -Dtest=GoogleIdentityParserTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the Google identity types do not exist.

- [ ] **Step 4: Implement minimal immutable identity parsing**

Normalize using `trim().toLowerCase(Locale.ROOT)`. Mark an email Google-managed only when its domain is `gmail.com`, or when `hd` is non-blank and exactly equals the normalized email domain. Use the email local part when `OidcUser.getFullName()` is blank. Throw `GoogleLoginException` without embedding claim values or provider responses in its public message.

- [ ] **Step 5: Run the parser tests**

```powershell
mvn -pl wesite-web -am -Dtest=GoogleIdentityParserTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 6: Commit identity validation**

```powershell
git add -- wesite-web/pom.xml wesite-web/src/main/java/info/wesite/web/auth/google/GoogleIdentity.java wesite-web/src/main/java/info/wesite/web/auth/google/GoogleIdentityParser.java wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginException.java wesite-web/src/test/java/info/wesite/web/auth/google/GoogleIdentityParserTest.java
git commit -m "feat: validate Google OIDC identities"
```

### Task 4: Transactional Google account matching and binding

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/PendingGoogleBinding.java`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginResult.java`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginService.java`
- Test: `wesite-web/src/test/java/info/wesite/web/auth/google/GoogleLoginServiceTest.java`

**Interfaces:**
- Produces: `GoogleLoginResult GoogleLoginService.authenticate(GoogleIdentity identity, User currentUser)`.
- Produces: `User GoogleLoginService.completeConfirmedBinding(User emailUser, PendingGoogleBinding pending)`.
- Produces: `record PendingGoogleBinding(String userId, String subject, String email, Instant expiresAt) implements Serializable`.
- Produces: `GoogleLoginResult.signedIn(User)` and `GoogleLoginResult.pending(PendingGoogleBinding)`.

Use this exact result shape:

```java
public record GoogleLoginResult(Status status, User user, PendingGoogleBinding pendingBinding) {
    public enum Status { SIGNED_IN, EMAIL_CONFIRMATION_REQUIRED }

    public boolean requiresEmailConfirmation() {
        return status == Status.EMAIL_CONFIRMATION_REQUIRED;
    }
}
```

- [ ] **Step 1: Write failing service tests for every matching branch**

Cover: existing `GOOGLE_SUB`; inactive subject user; current JWT user with matching email; Gmail existing user; Gmail new user; third-party existing user; third-party new user; conflicting subject; conditional update race; duplicate-email create retry; expired pending binding.

The third-party new-user assertion must be:

```java
GoogleLoginResult result = service.authenticate(thirdPartyIdentity, null);

assertTrue(result.requiresEmailConfirmation());
verify(userService, never()).save(any(User.class));
assertNull(result.pendingBinding().userId());
verify(emailLoginService).request(
        "person@outside.example",
        "/user/watchlist?login=google_bind_required");
```

- [ ] **Step 2: Run the service test and verify failure**

```powershell
mvn -pl wesite-web -am -Dtest=GoogleLoginServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the service/result types are absent.

- [ ] **Step 3: Implement the ordered matching algorithm**

Use `UserService` queries in this order: `GOOGLE_SUB`, current JWT user's matching normalized email, then `EMAIL`. Reject any matched user whose status is not `BaseEntity.STATUS_ACTIVE`. For authoritative email, create or conditionally bind immediately. For third-party email, call `EmailLoginService.request` and return a 15-minute `PendingGoogleBinding` without creating a new user.

Use an update wrapper with both identity and state guards:

```java
boolean updated = userService.update(null, new UpdateWrapper<User>()
        .set("GOOGLE_SUB", subject)
        .set("UPDATE_TIME", new Date())
        .eq("ID", user.getId())
        .eq("STATUS", BaseEntity.STATUS_ACTIVE)
        .isNull("GOOGLE_SUB"));
```

If the update loses a race, reload the user: the same subject is idempotent success; a different subject is `ACCOUNT_CONFLICT`. Catch `DuplicateKeyException` around create, reload by normalized email and subject, and reapply the same rules. Do not catch unrelated database exceptions.

- [ ] **Step 4: Implement confirmed binding**

`completeConfirmedBinding` must reject expired data, mismatched normalized email, a non-null pending `userId` different from `emailUser.id`, inactive users, and subject conflicts. For a new third-party user, `UserController` creates the email user first inside its existing transaction, then this method binds the subject before any Cookie is issued.

- [ ] **Step 5: Run the service tests**

```powershell
mvn -pl wesite-web -am -Dtest=GoogleLoginServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 6: Commit account matching**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/auth/google/PendingGoogleBinding.java wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginResult.java wesite-web/src/main/java/info/wesite/web/auth/google/GoogleLoginService.java wesite-web/src/test/java/info/wesite/web/auth/google/GoogleLoginServiceTest.java
git commit -m "feat: bind Google identities to users"
```

### Task 5: Existing JWT resolution and OAuth session cleanup

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/CurrentJwtUserResolver.java`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/OAuthSessionCleaner.java`
- Test: `wesite-web/src/test/java/info/wesite/web/auth/google/CurrentJwtUserResolverTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/auth/google/OAuthSessionCleanerTest.java`

**Interfaces:**
- Produces: `Optional<User> CurrentJwtUserResolver.resolve(HttpServletRequest request)`.
- Produces: `void OAuthSessionCleaner.clear(HttpServletRequest, HttpServletResponse, OAuth2AuthenticationToken)`.
- Produces: `void OAuthSessionCleaner.rotateToPending(HttpServletRequest, HttpServletResponse, OAuth2AuthenticationToken, PendingGoogleBinding)`.
- Defines: `PendingGoogleBinding.SESSION_KEY = "GOOGLE_PENDING_BINDING"`.

- [ ] **Step 1: Write failing resolver tests**

Test no Cookie, invalid JWT, valid JWT whose user is missing, inactive database user, and valid active database user. Initialize `TokenUtils` test secret/issuer and create a real project token rather than mocking the static verifier.

- [ ] **Step 2: Write failing session-rotation tests**

Build a `MockHttpServletRequest` with an existing session containing fake OAuth state and a pending binding. Verify `rotateToPending` invalidates the old session, calls `OAuth2AuthorizedClientRepository.removeAuthorizedClient("google", authentication, request, response)`, clears `SecurityContextHolder`, and creates a new session containing only `PendingGoogleBinding.SESSION_KEY`.

- [ ] **Step 3: Run focused tests and verify failure**

```powershell
mvn -pl wesite-web -am -Dtest=CurrentJwtUserResolverTest,OAuthSessionCleanerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because both helpers are absent.

- [ ] **Step 4: Implement Cookie resolution and minimal session rotation**

The resolver reads only `Constants.TOKEN_KEY`, calls `TokenUtils.verifyToken`, reloads by ID through `UserService`, and returns only `STATUS_ACTIVE` users. The cleaner removes the authorized client before invalidation. `clear` leaves no replacement session; `rotateToPending` copies only the supplied serializable pending record into a fresh session.

- [ ] **Step 5: Run focused tests**

```powershell
mvn -pl wesite-web -am -Dtest=CurrentJwtUserResolverTest,OAuthSessionCleanerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 6: Commit JWT/session helpers**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/auth/google/CurrentJwtUserResolver.java wesite-web/src/main/java/info/wesite/web/auth/google/OAuthSessionCleaner.java wesite-web/src/test/java/info/wesite/web/auth/google/CurrentJwtUserResolverTest.java wesite-web/src/test/java/info/wesite/web/auth/google/OAuthSessionCleanerTest.java
git commit -m "feat: isolate Google OAuth session state"
```

### Task 6: Google success and failure handlers

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationSuccessHandler.java`
- Create: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationFailureHandler.java`
- Test: `wesite-web/src/test/java/info/wesite/web/auth/google/GoogleAuthenticationHandlerTest.java`

**Interfaces:**
- Implements: Spring Security `AuthenticationSuccessHandler` and `AuthenticationFailureHandler`.
- Success redirect: `/user/watchlist?login=success`.
- Pending redirect: `/?login=google_check_email`.
- Failure redirect: `/?login=google_error` or one of the fixed mapped codes listed below.

- [ ] **Step 1: Write failing handler tests**

Test a signed-in result adds exactly one application `Set-Cookie`, invokes `OAuthSessionCleaner.clear`, and redirects to success. Test a pending result emits no application Cookie, invokes `rotateToPending`, and redirects to `google_check_email`. Test failure mapping never includes exception text, email, subject, authorization code, or token.

- [ ] **Step 2: Run the handler test and verify failure**

```powershell
mvn -pl wesite-web -am -Dtest=GoogleAuthenticationHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the handlers are absent.

- [ ] **Step 3: Implement the success bridge**

Require an `OAuth2AuthenticationToken` whose principal is `OidcUser`. Parse through `GoogleIdentityParser`, resolve the project JWT user through `CurrentJwtUserResolver`, and invoke `GoogleLoginService.authenticate`. Issue `AuthCookieService.create(result.user())` only for `SIGNED_IN`; rotate to a minimal pending session for `EMAIL_CONFIRMATION_REQUIRED`.

- [ ] **Step 4: Implement safe failure mapping**

Map internal codes to this fixed allowlist only:

```java
Map.of(
    INVALID_IDENTITY, "google_invalid",
    UNVERIFIED_EMAIL, "google_unverified",
    ACCOUNT_CONFLICT, "google_conflict",
    INACTIVE_USER, "google_inactive",
    EMAIL_CONFIRMATION_UNAVAILABLE, "google_email_unavailable",
    EXPIRED_FLOW, "google_expired"
)
```

All other exceptions redirect to `/?login=google_error`. Log exception class and a generated correlation ID, but do not log OAuth attributes or exception messages originating from the provider response.

- [ ] **Step 5: Run handler tests**

```powershell
mvn -pl wesite-web -am -Dtest=GoogleAuthenticationHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 6: Commit handlers**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationSuccessHandler.java wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationFailureHandler.java wesite-web/src/test/java/info/wesite/web/auth/google/GoogleAuthenticationHandlerTest.java
git commit -m "feat: bridge Google login to application sessions"
```

### Task 7: Conditional OAuth configuration and pass-through security

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/config/GoogleLoginProperties.java`
- Create: `wesite-web/src/main/java/info/wesite/web/config/SecurityConfig.java`
- Modify: `wesite-web/src/main/resources/application.properties`
- Modify: `wesite-web/src/main/resources/application-prod.properties.example`
- Test: `wesite-web/src/test/java/info/wesite/web/config/SecurityConfigTest.java`

**Interfaces:**
- Produces: always-present `@Order(2)` pass-through `SecurityFilterChain`.
- Produces: conditional `@Order(1)` OAuth `SecurityFilterChain` and `ClientRegistrationRepository`.
- Consumes: `WESITE_GOOGLE_LOGIN_ENABLED`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, and `wesite.public-base-url`.

- [ ] **Step 1: Write failing disabled-mode context tests**

Start a minimal Spring test context with `wesite.google-login.enabled=false` and a test-only `POST /security-probe` controller returning `200 OK`. Assert `/` and `/security-probe` do not redirect to `/login`, return Basic authentication, or fail CSRF. Assert no `ClientRegistrationRepository` exists but a `SecurityFilterChain` bean does.

- [ ] **Step 2: Write failing enabled-mode configuration tests**

With enabled=true, test credentials, and `wesite.public-base-url=https://whose.domains`, assert registration ID `google`, scopes `openid email profile`, and redirect URI `https://whose.domains/login/oauth2/code/{registrationId}`. Add tests that enabled mode with either credential blank fails context startup, and that production base URL using plain HTTP is rejected while `http://localhost:8080` is accepted outside production.

- [ ] **Step 3: Run the config test and verify failure**

```powershell
mvn -pl wesite-web -am -Dtest=SecurityConfigTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because OAuth2 Client and explicit chains are not configured.

- [ ] **Step 4: Add environment-backed properties**

Add properties with empty credentials and a false flag; never place secrets in either properties file. The OAuth2 Client dependency was added by Task 3 and must not be duplicated.

- [ ] **Step 5: Implement the two ordered chains**

Build the Google registration with `CommonOAuth2Provider.GOOGLE.getBuilder("google")`, explicit scopes, credentials, and the public-base URL redirect template. The OAuth chain matches `/oauth2/**` and `/login/oauth2/**`, permits those endpoints, and installs the custom handlers. The fallback chain matches all remaining requests, permits all, disables CSRF/form login/HTTP Basic/logout/request cache, uses `SessionCreationPolicy.STATELESS`, and uses `NullSecurityContextRepository`.

- [ ] **Step 6: Run disabled and enabled configuration tests**

```powershell
mvn -pl wesite-web -am -Dtest=SecurityConfigTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 7: Commit configuration**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/config/GoogleLoginProperties.java wesite-web/src/main/java/info/wesite/web/config/SecurityConfig.java wesite-web/src/main/resources/application.properties wesite-web/src/main/resources/application-prod.properties.example wesite-web/src/test/java/info/wesite/web/config/SecurityConfigTest.java
git commit -m "feat: configure conditional Google OAuth login"
```

### Task 8: Finish Google binding from email verification

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/UserController.java`
- Test: `wesite-web/src/test/java/info/wesite/web/controller/UserControllerGoogleBindingTest.java`

**Interfaces:**
- Consumes: `PendingGoogleBinding.SESSION_KEY` from the current HTTP Session.
- Consumes: `GoogleLoginService.completeConfirmedBinding(User, PendingGoogleBinding)`.
- Uses: `EmailLoginLink.redirectPath` to distinguish normal magic login from cross-browser Google confirmation.

- [ ] **Step 1: Write failing same-browser confirmation tests**

Create a valid unconsumed `EmailLoginLink`, a matching pending binding in `MockHttpSession`, and an existing active user. Assert the link is atomically consumed, `completeConfirmedBinding` runs, the application Cookie is issued, the session is invalidated, and redirect is `/user/watchlist?login=success`.

- [ ] **Step 2: Write failing new-user and cross-browser tests**

For a matching pending binding with no existing user, assert the controller creates the standard active personal user and binds before issuing the Cookie. Without the pending Session, assert a link whose stored redirect path is `/user/watchlist?login=google_bind_required` performs normal email login, does not bind Google, and redirects to that fixed path. Reject any unrecognized stored redirect path by falling back to `/user/watchlist?login=success`.

- [ ] **Step 3: Run the controller test and verify failure**

```powershell
mvn -pl wesite-web -am -Dtest=UserControllerGoogleBindingTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because verification does not inspect pending Google state.

- [ ] **Step 4: Extend verification inside the existing transaction**

Add `HttpServletRequest` to `verifyEmail`, consume the link first, resolve/create the email user, and inspect only `PendingGoogleBinding.SESSION_KEY`. If matching pending data exists, call `completeConfirmedBinding` before `AuthCookieService.create`. Invalidate the session after success. On binding conflict or expiry, do not issue a Cookie and redirect with the corresponding allowlisted Google error code.

- [ ] **Step 5: Run email and Google confirmation regression tests**

```powershell
mvn -pl wesite-web -am -Dtest=UserControllerEmailLoginTest,UserControllerGoogleBindingTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 6: Commit confirmation flow**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/controller/UserController.java wesite-web/src/test/java/info/wesite/web/controller/UserControllerGoogleBindingTest.java
git commit -m "feat: confirm Google binding by email"
```

### Task 9: Login modal, feature visibility, and safe result messages

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/interceptor/WebInterceptor.java`
- Modify: `wesite-web/src/main/resources/views/template.html`
- Modify: `wesite-web/src/main/resources/static/style/common.css`
- Modify: `wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/interceptor/GoogleLoginVisibilityTest.java`

**Interfaces:**
- Produces request attribute: `_googleLoginEnabled`.
- Google start endpoint: `/oauth2/authorization/google`.
- Recognized `login` UI codes are fixed in JavaScript; arbitrary query text is never rendered.

- [ ] **Step 1: Write failing template assertions**

Require a conditionally rendered Google anchor, `th:if="${_googleLoginEnabled}"`, the fixed OAuth path, an `or` divider, the existing email button, and a fixed-code message map. Require `google_bind_required` to change the button label to “Finish with Google”.

- [ ] **Step 2: Write a failing interceptor visibility test**

Instantiate `WebInterceptor` with `GoogleLoginProperties.enabled=false/true`, call `preHandle`, and assert the request attribute exactly follows the validated feature flag.

- [ ] **Step 3: Run focused UI tests and verify failure**

```powershell
mvn -pl wesite-web -am -Dtest=AuthModalTemplateTest,GoogleLoginVisibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the button, attribute, and message mapping are absent.

- [ ] **Step 4: Implement the modal and result behavior**

Place a full-width Google button above the email form, render the divider only when enabled, and keep the email form unchanged. Parse `new URLSearchParams(location.search).get('login')`, look up the value in a literal allowlist, and set messages using `textContent`. Open the modal for Google errors and `google_check_email`; for `google_bind_required`, open it and relabel the Google anchor to “Finish with Google”. Never write a raw query value with `innerHTML`.

- [ ] **Step 5: Add scoped responsive styles and run tests**

```powershell
mvn -pl wesite-web -am -Dtest=AuthModalTemplateTest,GoogleLoginVisibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 6: Commit UI integration**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/interceptor/WebInterceptor.java wesite-web/src/main/resources/views/template.html wesite-web/src/main/resources/static/style/common.css wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java wesite-web/src/test/java/info/wesite/web/interceptor/GoogleLoginVisibilityTest.java
git commit -m "feat: add Google sign-in to login modal"
```

### Task 10: Full regression and production-readiness verification

**Files:**
- Modify only if verification exposes a Google-login regression in files listed above.
- Test: all Maven module tests.

**Interfaces:**
- Verifies the complete feature against `docs/superpowers/specs/2026-08-03-google-login-design.md`.

- [ ] **Step 1: Scan for committed secrets and token logging**

```powershell
rg -n "GOOGLE_CLIENT_SECRET|client-secret" wesite-web pom.xml -g '!target/**'
rg -n "log\.(info|warn|error|debug).*?(idToken|accessToken|authorizationCode|OAuth2User)" wesite-web/src/main/java
```

Expected: credential configuration contains only environment-variable references or property names, and there are no token-bearing log calls.

- [ ] **Step 2: Run formatting and focused authentication tests**

```powershell
git diff --check
mvn -pl wesite-web -am -Dtest=UserControllerEmailLoginTest,UserControllerGoogleBindingTest,GoogleIdentityParserTest,GoogleLoginServiceTest,CurrentJwtUserResolverTest,OAuthSessionCleanerTest,GoogleAuthenticationHandlerTest,SecurityConfigTest,AuthModalTemplateTest,GoogleLoginVisibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS with zero failures and errors.

- [ ] **Step 3: Run the full repository test suite**

```powershell
mvn test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Perform disabled-mode smoke verification**

Build the executable jar, then start it without Google environment variables:

```powershell
mvn -pl wesite-web -am -DskipTests package
java -jar wesite-web/target/wesite-web-1.0.0.jar
```

In a second terminal:

```powershell
curl.exe -i http://localhost:8080/
curl.exe -i http://localhost:8080/user/session
```

Expected: the home page is not redirected to a Spring login page; `/user/session` keeps the existing JSON no-auth behavior; startup prints no generated security password.

- [ ] **Step 5: Review the final diff against the approved design**

Confirm every design section has an implementation and test: conditional security, fixed callback, identity validation, active-user checks, authoritative-email binding, third-party confirmation, minimal Session state, safe Cookie, safe errors, schema normalization, and disabled-mode rollback.

- [ ] **Step 6: Resolve verification failures in their owning task**

If verification changes a file, return to the Task 1-9 section that owns that file, rerun its focused test command, and use that task's explicit `git add` list and commit message. After all fixes, rerun Steps 1-5. Do not create an empty verification commit.
