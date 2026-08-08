# Protected Page Login Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route unauthenticated protected-page visitors through a standalone sign-in page and safely return them to their original page after email-link or Google authentication.

**Architecture:** Add one `ReturnTargetService` as the only validator, encoder, and fallback source for post-login paths. `WebInterceptor` redirects browser requests to `LoginController`; email authentication persists the target in `EmailLoginLink`, while Google authentication carries it in the OAuth session and `PendingGoogleBinding`. Existing modal authentication omits the gateway target and keeps its Watchlist defaults.

**Tech Stack:** Java 17, Spring MVC, Spring Security OAuth2 Client, Thymeleaf, vanilla JavaScript, CSS, JUnit 5, Spring mock servlet utilities, Mockito, Maven.

## Global Constraints

- Keep the navigation sign-in modal and its `/user/watchlist?login=success` default unchanged.
- Redirect only non-AJAX browser requests for `@AccessControl(level = SESSION)` handlers.
- Keep API/AJAX unauthenticated responses in the existing JSON shape.
- Accept only local targets beginning with exactly one `/`, with no scheme, authority, backslash, CR/LF, malformed URI, or `/login` loop.
- Invalid or absent targets fall back to `/user/watchlist?login=success`.
- Add no dependency and do not change authentication-cookie semantics.
- Preserve unrelated working-tree and index changes.

---

### Task 1: Central Return-Target Validation

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/auth/ReturnTargetService.java`
- Create: `wesite-web/src/test/java/info/wesite/web/auth/ReturnTargetServiceTest.java`

**Interfaces:**
- Produces: `String resolve(String candidate)`, `Optional<String> validated(String candidate)`, `String loginUrl(String target)`, and `String loginFailureUrl(String code, String target)`.
- Produces: `DEFAULT_TARGET` and `GOOGLE_RETURN_TARGET_SESSION_KEY` constants.

- [ ] **Step 1: Write failing validation and encoding tests**

```java
class ReturnTargetServiceTest {
    private final ReturnTargetService service = new ReturnTargetService();

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/", "//evil.example/", "/\\evil", "/login",
            "/login?returnTo=/user/api-keys", "/login/google", "/%2F%2Fevil.example/", "/%5Cevil",
            "/%6Cogin", "/bad\r\nLocation:https://evil.example"})
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
```

- [ ] **Step 2: Verify RED**

Run `mvn -pl wesite-web -am -Dtest=ReturnTargetServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`.

Expected: test compilation fails because `ReturnTargetService` is absent.

- [ ] **Step 3: Implement the minimal component**

```java
@Component
public class ReturnTargetService {
    public static final String DEFAULT_TARGET = "/user/watchlist?login=success";
    public static final String GOOGLE_RETURN_TARGET_SESSION_KEY = "LOGIN_RETURN_TO";

    public String resolve(String candidate) {
        return validated(candidate).orElse(DEFAULT_TARGET);
    }

    public Optional<String> validated(String candidate) {
        if (!StringUtils.hasText(candidate) || !candidate.startsWith("/") || candidate.startsWith("//")
                || candidate.indexOf('\\') >= 0 || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(candidate);
            String path = uri.getRawPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || !StringUtils.hasText(path)
                    || path.indexOf('%') >= 0
                    || "/login".equals(path) || path.startsWith("/login/")) {
                return Optional.empty();
            }
            return Optional.of(path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String loginUrl(String target) {
        return UriComponentsBuilder.fromPath("/login").queryParam("returnTo", resolve(target))
                .build().encode().toUriString();
    }

    public String loginFailureUrl(String code, String target) {
        return UriComponentsBuilder.fromPath("/login").queryParam("login", code)
                .queryParam("returnTo", resolve(target)).build().encode().toUriString();
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command. Expected: all `ReturnTargetServiceTest` cases pass.

- [ ] **Step 5: Commit**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/auth/ReturnTargetService.java wesite-web/src/test/java/info/wesite/web/auth/ReturnTargetServiceTest.java
git commit -m "feat: validate post-login return targets"
```

---

### Task 2: Protected-Page Redirect and Gateway Routes

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/interceptor/WebInterceptor.java`
- Create: `wesite-web/src/main/java/info/wesite/web/controller/LoginController.java`
- Create: `wesite-web/src/test/java/info/wesite/web/interceptor/ProtectedPageLoginRedirectTest.java`
- Create: `wesite-web/src/test/java/info/wesite/web/controller/LoginControllerTest.java`

**Interfaces:**
- Consumes: Task 1's `loginUrl`, `resolve`, and Google session key.
- Produces: `GET /login`, `GET /login/google`, and model attribute `returnTo`.

- [ ] **Step 1: Write failing interceptor tests using a real protected `HandlerMethod`**

```java
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
```

Construct the real interceptor with mocked `Environment`, disabled `GoogleLoginProperties`, real `CanonicalUrlService`, and real `ReturnTargetService`. The test fixture method itself carries `@AccessControl(level = SESSION)`.

- [ ] **Step 2: Write failing controller tests**

```java
@Test
void loginPageExposesValidatedTarget() {
    ExtendedModelMap model = new ExtendedModelMap();
    assertEquals("login", controller.login("/user/api-keys", model));
    assertEquals("/user/api-keys", model.get("returnTo"));
}

@Test
void signedInVisitorSkipsGateway() {
    UserHolder.set(new User());
    try {
        assertEquals("redirect:/user/api-keys", controller.login("/user/api-keys", new ExtendedModelMap()));
    } finally {
        UserHolder.remove();
    }
}

@Test
void googleStartStoresTarget() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    assertEquals("redirect:/oauth2/authorization/google", controller.google("/user/api-keys", request));
    assertEquals("/user/api-keys", request.getSession().getAttribute("LOGIN_RETURN_TO"));
}
```

- [ ] **Step 3: Verify RED**

Run `mvn -pl wesite-web -am '-Dtest=ProtectedPageLoginRedirectTest,LoginControllerTest' -Dsurefire.failIfNoSpecifiedTests=false test`.

Expected: the controller is missing and the interceptor still redirects to `/`.

- [ ] **Step 4: Redirect browser requests in `WebInterceptor`**

Inject `ReturnTargetService`, preserving both existing constructor call sites through delegation. Replace only the non-AJAX branch:

```java
String target = request.getRequestURI();
if (StringUtils.isNotBlank(request.getQueryString())) {
    target += "?" + request.getQueryString();
}
response.sendRedirect(returnTargetService.loginUrl(target));
```

- [ ] **Step 5: Implement `LoginController`**

```java
@Controller
public class LoginController {
    private final ReturnTargetService returnTargets;

    public LoginController(ReturnTargetService returnTargets) {
        this.returnTargets = returnTargets;
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String returnTo, Model model) {
        String target = returnTargets.resolve(returnTo);
        if (UserHolder.get() != null) return "redirect:" + target;
        model.addAttribute(Constants.PAGE_TITLE, "Sign In - Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Sign in securely to continue to your account.");
        model.addAttribute("returnTo", target);
        return "login";
    }

    @GetMapping("/login/google")
    public String google(@RequestParam(required = false) String returnTo, HttpServletRequest request) {
        request.getSession(true).setAttribute(ReturnTargetService.GOOGLE_RETURN_TARGET_SESSION_KEY,
                returnTargets.resolve(returnTo));
        return "redirect:/oauth2/authorization/google";
    }
}
```

- [ ] **Step 6: Verify GREEN and commit**

Run the Step 3 command, then:

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/interceptor/WebInterceptor.java wesite-web/src/main/java/info/wesite/web/controller/LoginController.java wesite-web/src/test/java/info/wesite/web/interceptor/ProtectedPageLoginRedirectTest.java wesite-web/src/test/java/info/wesite/web/controller/LoginControllerTest.java
git commit -m "feat: route protected pages through sign in"
```

---

### Task 3: Email-Link Return Flow

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/auth/EmailLoginRequest.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/UserController.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/auth/EmailLoginService.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/controller/UserControllerEmailLoginTest.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/auth/EmailLoginServiceTest.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/controller/UserControllerGoogleBindingTest.java`

**Interfaces:**
- Consumes: Task 1's `resolve` and `validated`.
- Produces: JSON record `EmailLoginRequest(String email, String returnTo)`.
- Preserves: `EmailLoginService.request(String rawEmail, String redirectPath)` for Google binding callers.

- [ ] **Step 1: Write failing gateway and modal request tests**

```java
@Test
void gatewayRequestUsesRequestedTarget() {
    controller.requestEmailLogin(new EmailLoginRequest("person@example.com", "/user/api-keys"));
    verify(emailLoginService).request("person@example.com", "/user/api-keys");
}

@Test
void modalRequestKeepsWatchlistDefault() {
    controller.requestEmailLogin(new EmailLoginRequest("person@example.com", null));
    verify(emailLoginService).request("person@example.com", "/user/watchlist?login=success");
}
```

- [ ] **Step 2: Write failing persistence and final-redirect tests**

Use the real email service with mocked repository/mail transport, capture the saved link, and assert `getRedirectPath()` equals `/user/api-keys?tab=active`. Keep the existing external-URL rejection assertion. In the verification controller fixture, persist a valid non-binding link with `/user/api-keys` and assert `redirectedUrl("/user/api-keys")` plus the authentication cookie.

Add an expired-link case whose stored target is `/user/api-keys` and assert the response is `/login?login=invalid&returnTo=%2Fuser%2Fapi-keys`. A nonexistent or blank token has no trustworthy stored origin and retains the current fixed invalid-link fallback.

- [ ] **Step 3: Verify RED**

Run `mvn -pl wesite-web -am '-Dtest=UserControllerEmailLoginTest,EmailLoginServiceTest,UserControllerGoogleBindingTest' -Dsurefire.failIfNoSpecifiedTests=false test`.

Expected: the request record is missing, arbitrary valid local targets are rejected, and verification falls back to Watchlist.

- [ ] **Step 4: Implement request binding and service validation**

```java
public record EmailLoginRequest(String email, String returnTo) {}
```

Inject `ReturnTargetService` into `UserController` and `EmailLoginService`. `requestEmailLogin` calls `resolve(param.returnTo())`. `EmailLoginService.request` accepts the existing Google-binding sentinel exactly; every other redirect must satisfy `validated`, otherwise throw `IllegalArgumentException`.

- [ ] **Step 5: Use the persisted safe target after verification**

```java
private String safeRedirectPath(String redirectPath) {
    return GOOGLE_BIND_REQUIRED_REDIRECT.equals(redirectPath)
            ? GOOGLE_BIND_REQUIRED_REDIRECT
            : returnTargets.resolve(redirectPath);
}
```

When a link record exists but is expired or loses the consume race, use `returnTargets.loginFailureUrl("invalid", link.getRedirectPath())` if its stored target validates; otherwise retain the current fixed invalid-link fallback. Do not alter the `invalid` result code or after-commit cookie behavior.

- [ ] **Step 6: Verify GREEN and commit**

Run the Step 3 command, then:

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/auth/EmailLoginRequest.java wesite-web/src/main/java/info/wesite/web/controller/UserController.java wesite-web/src/main/java/info/wesite/web/auth/EmailLoginService.java wesite-web/src/test/java/info/wesite/web/controller/UserControllerEmailLoginTest.java wesite-web/src/test/java/info/wesite/web/auth/EmailLoginServiceTest.java wesite-web/src/test/java/info/wesite/web/controller/UserControllerGoogleBindingTest.java
git commit -m "feat: return email sign-ins to requested page"
```

---

### Task 4: Google Return Flow and Pending Binding

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/auth/google/PendingGoogleBinding.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationSuccessHandler.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationFailureHandler.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/UserController.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/auth/google/GoogleAuthenticationHandlerTest.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/controller/UserControllerGoogleBindingTest.java`

**Interfaces:**
- Produces: `PendingGoogleBinding.returnTo()` and `withReturnTo(String)`, retaining the four-argument constructor.
- Preserves: modal success uses Watchlist and modal failure uses `/?login=<fixed-code>`.

- [ ] **Step 1: Write failing handler tests**

Put `/user/api-keys` in the mock session under `LOGIN_RETURN_TO`. Assert signed-in success redirects to `/user/api-keys`; provider failure redirects to `/login?login=google_error&returnTo=%2Fuser%2Fapi-keys`. Keep existing modal-default and secret-leak tests unchanged.

- [ ] **Step 2: Write failing pending-binding tests**

Capture the binding passed to `sessionCleaner.rotateToPending` and assert `returnTo()` is `/user/api-keys`. In the controller transaction fixture, use a five-argument pending binding with `/user/api-keys` and assert final redirect there; a legacy four-argument binding must still redirect to Watchlist.

- [ ] **Step 3: Verify RED**

Run `mvn -pl wesite-web -am '-Dtest=GoogleAuthenticationHandlerTest,UserControllerGoogleBindingTest' -Dsurefire.failIfNoSpecifiedTests=false test`.

Expected: the record has no return target and handlers use fixed redirects.

- [ ] **Step 4: Extend the pending record compatibly**

```java
public record PendingGoogleBinding(String userId, String subject, String email, Instant expiresAt, String returnTo)
        implements Serializable {
    public static final String SESSION_KEY = "GOOGLE_PENDING_BINDING";

    public PendingGoogleBinding(String userId, String subject, String email, Instant expiresAt) {
        this(userId, subject, email, expiresAt, null);
    }

    public PendingGoogleBinding withReturnTo(String target) {
        return new PendingGoogleBinding(userId, subject, email, expiresAt, target);
    }
}
```

- [ ] **Step 5: Carry the target through handlers**

Read and validate the session target before OAuth cleanup. Signed-in success redirects to it or the existing default. Pending success calls `pending.withReturnTo(target)` before `rotateToPending`. Failure uses `loginFailureUrl` only when the gateway session attribute is present and valid; otherwise it retains `/?login=<fixed-code>`. Never put provider exception text in a URL.

- [ ] **Step 6: Use pending target after binding**

After successful pending binding completion, return `redirect:` plus `returnTargets.resolve(pending.returnTo())`. Missing or expired pending state retains existing fixed errors.

- [ ] **Step 7: Verify GREEN and commit**

Run the Step 3 command, then:

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/auth/google/PendingGoogleBinding.java wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationSuccessHandler.java wesite-web/src/main/java/info/wesite/web/auth/google/GoogleAuthenticationFailureHandler.java wesite-web/src/main/java/info/wesite/web/controller/UserController.java wesite-web/src/test/java/info/wesite/web/auth/google/GoogleAuthenticationHandlerTest.java wesite-web/src/test/java/info/wesite/web/controller/UserControllerGoogleBindingTest.java
git commit -m "feat: return Google sign-ins to requested page"
```

---

### Task 5: Standalone Login Page and Regression Verification

**Files:**
- Create: `wesite-web/src/main/resources/views/login.html`
- Modify: `wesite-web/src/main/resources/static/style/common.css`
- Create: `wesite-web/src/test/java/info/wesite/web/view/LoginPageTemplateTest.java`

**Interfaces:**
- Consumes: model `returnTo` and request attribute `_googleLoginEnabled`.
- Calls: `GET /login/google?returnTo=...` and `POST /user/email-login` with `{email, returnTo}`.

- [ ] **Step 1: Write the failing template test**

```java
assertTrue(template.contains("<h1 id=\"loginGatewayTitle\">Sign In</h1>"));
assertTrue(template.contains("th:if=\"${_googleLoginEnabled}\""));
assertTrue(template.contains("th:href=\"@{/login/google(returnTo=${returnTo})}\""));
assertTrue(template.contains("id=\"loginEmail\""));
assertTrue(template.contains("id=\"googleLoginMsg\" role=\"status\" aria-live=\"polite\""));
assertTrue(template.contains("id=\"emailLoginMsg\" role=\"status\" aria-live=\"polite\""));
assertTrue(template.contains("JSON.stringify({email:email,returnTo:returnTo})"));
```

Also assert the `.login-gateway-card` rule has `max-width: 520px`, mobile-safe width, and the gateway focus selector uses `outline: 2px solid var(--primary)`.

- [ ] **Step 2: Verify RED**

Run `mvn -pl wesite-web -am -Dtest=LoginPageTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test`.

Expected: the template resource is missing.

- [ ] **Step 3: Build the semantic page**

Use shared head/header/footer fragments. Include a single labelled `Sign In` card, conditional Google link, labelled email input, submit button, and separate polite status regions. The script obtains `returnTo` from a JSON-safe Thymeleaf JavaScript expression, POSTs `{email, returnTo}`, disables duplicate submissions, uses `textContent`, and maps fixed callback codes to the appropriate method region.

- [ ] **Step 4: Add scoped responsive CSS**

Use `.login-gateway*` selectors, existing color variables, `width: min(100% - 32px, 520px)`, `max-width: 520px`, mobile padding, and a visible `:focus-visible` outline. Do not alter modal behavior.

- [ ] **Step 5: Verify GREEN**

Run the Step 2 command. Expected: `LoginPageTemplateTest` passes.

- [ ] **Step 6: Run focused authentication regression tests**

Run `mvn -pl wesite-web -am '-Dtest=ReturnTargetServiceTest,ProtectedPageLoginRedirectTest,LoginControllerTest,UserControllerEmailLoginTest,EmailLoginServiceTest,GoogleAuthenticationHandlerTest,UserControllerGoogleBindingTest,LoginPageTemplateTest,AuthModalTemplateTest,ApiKeyPageAccessControlTest' -Dsurefire.failIfNoSpecifiedTests=false test`.

Expected: all focused tests pass and the existing modal-default assertions remain green.

- [ ] **Step 7: Run complete verification**

Run `mvn -pl wesite-web -am test`, then `git diff --check`.

Expected: Maven exits `0` and `git diff --check` reports no whitespace errors. Do not modify unrelated user changes to make verification pass; report any unrelated failure with its exact file and command output.

- [ ] **Step 8: Commit**

```powershell
git add -- wesite-web/src/main/resources/views/login.html wesite-web/src/main/resources/static/style/common.css wesite-web/src/test/java/info/wesite/web/view/LoginPageTemplateTest.java
git commit -m "feat: add protected-page sign-in gateway"
```
