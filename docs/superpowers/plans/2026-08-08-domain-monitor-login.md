# 域名监控登录续接实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在域名监控专用弹窗中同时提供 Google 与 Email Link 登录，并在任一方式登录成功返回后自动将当前域名加入 Watchlist。

**Architecture:** 保留 `domain_detail.html` 中的专用弹窗，用当前页面域名生成 `/domain/{domain}?monitor=pending` 回跳地址，分别交给 Google 链接和 Email Link 请求。页面返回后由一个幂等的前端续接状态机先确认登录与现有监控状态，再调用现有 Watch API，最后清理 URL 标记；不再使用 `localStorage`。

**Tech Stack:** Spring Boot 3、Thymeleaf、原生 JavaScript、Node.js `node:test`、JUnit 5、Maven

## Global Constraints

- 保留域名监控专用弹窗，不替换为全局标准登录框。
- 弹窗顺序固定为 Google 登录、Google 状态区、分隔线、Email Link、Email 状态区。
- Google 与 Email Link 错误必须写入各自独立的状态区。
- Google 登录不可用时不渲染 Google 区域和分隔线。
- 两种登录方式成功后都必须自动监控当前页面域名，无需再次点击。
- 当前域名只取自服务端渲染的 `data-domain`，不得取自查询参数或本地存储。
- 回跳目标必须继续经过现有 `ReturnTargetService` 校验。
- 不新增服务端持久化状态、接口或依赖。
- 不改动已登录用户点击按钮时直接添加的行为。
- 所有生产代码必须先有可观察到的失败测试。

---

## 文件结构

- 修改 `wesite-web/src/main/resources/views/domain_detail.html`：专用弹窗结构、两种登录方式、回跳地址生成与自动监控续接状态机。
- 修改 `wesite-web/src/test/java/info/wesite/web/view/DomainDetailMonitorUiTest.java`：直接读取生产模板，验证结构、可访问性和旧本地存储逻辑已删除。
- 新建 `wesite-web/src/test/js/domain-detail-monitor-runtime.test.js`：提取并执行生产脚本，验证登录回跳与自动监控运行时行为。

### Task 1：重构监控弹窗的双登录结构

**Files:**
- Modify: `wesite-web/src/main/resources/views/domain_detail.html:56-65`
- Modify: `wesite-web/src/test/java/info/wesite/web/view/DomainDetailMonitorUiTest.java`

**Interfaces:**
- Consumes: Thymeleaf 布尔变量 `${_googleLoginEnabled}`；现有全局样式类 `auth-google-button`、`auth-google-icon`、`auth-login-divider`、`auth-method-message`。
- Produces: DOM 元素 `monitorGoogleSection`、`monitorGoogleLogin`、`monitorGoogleMessage`、`monitorEmail`、`sendMonitorLink`、`monitorEmailMessage`，供 Tasks 2–4 的生产脚本使用。

- [ ] **Step 1: 编写失败的模板结构测试**

把 `DomainDetailMonitorUiTest` 改为复用 `template()` 方法，并新增以下测试：

```java
@Test
void monitorDialogSeparatesGoogleAndEmailLoginMethods() throws IOException {
    String template = template();

    assertTrue(template.contains("id=\"monitorGoogleLogin\""));
    assertTrue(template.contains("class=\"auth-google-icon\""));
    assertTrue(template.contains("id=\"monitorGoogleMessage\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
    assertTrue(template.contains("th:if=\"${_googleLoginEnabled}\""));
    assertTrue(template.contains("class=\"auth-login-divider monitor-login-divider\""));
    assertTrue(template.contains("id=\"monitorEmailMessage\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
    assertFalse(template.contains("id=\"monitorMessage\""));
}

private String template() throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/views/domain_detail.html")) {
        assertNotNull(input);
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
}
```

同步加入静态导入：

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
```

- [ ] **Step 2: 运行模板测试并确认 RED**

Run:

```powershell
mvn -pl wesite-web -am -Dtest=DomainDetailMonitorUiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，缺少 `monitorGoogleLogin`、两个独立状态区和分隔线。

- [ ] **Step 3: 实现最小双登录弹窗结构**

在原弹窗标题与说明下方放入以下结构；Google SVG 路径必须复用 `template.html` 现有四色图标，不新建图片资源：

```html
<div id="monitorGoogleSection" class="auth-method monitor-google-section" th:if="${_googleLoginEnabled}">
    <a id="monitorGoogleLogin" class="auth-google-button" href="/login/google">
        <svg class="auth-google-icon" aria-hidden="true" viewBox="0 0 18 18" focusable="false">
            <path fill="#4285F4" d="M17.64 9.205c0-.638-.057-1.252-.164-1.841H9v3.482h4.844a4.14 4.14 0 0 1-1.797 2.715v2.258h2.909c1.702-1.567 2.684-3.874 2.684-6.614Z"/>
            <path fill="#34A853" d="M9 18c2.43 0 4.467-.806 5.956-2.181l-2.909-2.258c-.806.54-1.835.859-3.047.859-2.344 0-4.328-1.585-5.037-3.714H.956v2.332A9 9 0 0 0 9 18Z"/>
            <path fill="#FBBC05" d="M3.963 10.706A5.41 5.41 0 0 1 3.682 9c0-.592.102-1.168.281-1.706V4.962H.956A9 9 0 0 0 0 9c0 1.452.347 2.827.956 4.038l3.007-2.332Z"/>
            <path fill="#EA4335" d="M9 3.58c1.321 0 2.507.454 3.441 1.346l2.581-2.581C13.463.892 11.426 0 9 0A9 9 0 0 0 .956 4.962l3.007 2.332C4.672 5.165 6.656 3.58 9 3.58Z"/>
        </svg>
        <span>Continue with Google</span>
    </a>
    <div id="monitorGoogleMessage" role="status" aria-live="polite" aria-atomic="true" class="auth-method-message"></div>
</div>
<div class="auth-login-divider monitor-login-divider" th:if="${_googleLoginEnabled}"><span>or</span></div>
<div class="auth-method monitor-email-section">
    <input id="monitorEmail" type="email" autocomplete="email" placeholder="you@example.com">
    <button type="button" id="sendMonitorLink">Email me a sign-in link</button>
    <div id="monitorEmailMessage" role="status" aria-live="polite" aria-atomic="true" class="auth-method-message"></div>
</div>
```

保留现有弹窗尺寸、关闭按钮和域名说明；只增加与现有登录框一致的间距，不引入新的全局视觉体系。

- [ ] **Step 4: 运行模板测试并确认 GREEN**

Run:

```powershell
mvn -pl wesite-web -am -Dtest=DomainDetailMonitorUiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 5: 提交 Task 1**

```powershell
git add wesite-web/src/main/resources/views/domain_detail.html wesite-web/src/test/java/info/wesite/web/view/DomainDetailMonitorUiTest.java
git commit -m "feat: add Google login to domain monitor dialog"
```

### Task 2：让 Google 与 Email Link 使用同一监控回跳地址

**Files:**
- Modify: `wesite-web/src/main/resources/views/domain_detail.html:230-330`
- Create: `wesite-web/src/test/js/domain-detail-monitor-runtime.test.js`
- Modify: `wesite-web/src/test/java/info/wesite/web/view/DomainDetailMonitorUiTest.java`

**Interfaces:**
- Consumes: Task 1 的 `monitorGoogleLogin`、`monitorGoogleMessage`、`monitorEmailMessage`；浏览器 `location.pathname`、`location.search`、`URLSearchParams`。
- Produces: `monitorReturnTo(): string`、`setMonitorMessage(target, text, color): void`；Google 链接 `/login/google?returnTo=...`；Email 请求体 `{email, returnTo}`。

- [ ] **Step 1: 创建生产脚本运行时测试骨架并写失败测试**

新建 `domain-detail-monitor-runtime.test.js`，读取 `domain_detail.html`，定位包含 `var button = document.getElementById('monitorDomainBtn')` 的脚本，并用 `vm.runInNewContext` 执行。测试夹具至少提供 `monitorDomainBtn`、专用弹窗元素、Google 链接、Email 输入和按钮、两个状态区、`document.body.appendChild`、`fetch` 调用记录、`location`、`history`、`URLSearchParams`、`encodeURIComponent`。

加入两个测试：

```javascript
test('Google monitor login carries the current domain continuation target', () => {
    const harness = createHarness({ pathname: '/domain/example.com', search: '?source=lookup' }).run();

    assert.equal(
        harness.googleLink.href,
        '/login/google?returnTo=' + encodeURIComponent('/domain/example.com?source=lookup&monitor=pending')
    );
});

test('email monitor login sends the same continuation target and uses only its own message region', async () => {
    const harness = createHarness({ pathname: '/domain/example.com', search: '' }).run();
    harness.email.value = 'person@example.com';
    harness.sendLinkButton.dispatch('click');
    await harness.flushPromises();

    const request = harness.fetchCalls.find(call => call.url === '/user/email-login');
    assert.deepEqual(JSON.parse(request.options.body), {
        email: 'person@example.com',
        returnTo: '/domain/example.com?monitor=pending'
    });
    assert.equal(harness.emailMessage.textContent, 'Check your inbox.');
    assert.equal(harness.googleMessage.textContent, '');
});
```

- [ ] **Step 2: 运行 Node 测试并确认 RED**

Run:

```powershell
node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
```

Expected: FAIL，Google 链接没有 `returnTo`，Email 请求体没有 `returnTo`，旧脚本仍引用单一 `monitorMessage`。

- [ ] **Step 3: 实现共享回跳地址和独立消息区**

在生产脚本中加入：

```javascript
var googleLink = document.getElementById('monitorGoogleLogin');
var googleMessage = document.getElementById('monitorGoogleMessage');
var emailMessage = document.getElementById('monitorEmailMessage');

function monitorReturnTo() {
    var params = new URLSearchParams(location.search);
    params.delete('login');
    params.set('monitor', 'pending');
    return location.pathname + '?' + params.toString();
}

function setMonitorMessage(target, text, color) {
    if (!target) return;
    target.textContent = text;
    target.style.color = color;
    target.style.display = text ? 'block' : 'none';
}

if (googleLink) {
    googleLink.href = '/login/google?returnTo=' + encodeURIComponent(monitorReturnTo());
}
```

Email 请求体必须改为：

```javascript
body: JSON.stringify({email: value, returnTo: monitorReturnTo()})
```

空 Email、发送结果、限流和网络错误全部调用 `setMonitorMessage(emailMessage, ...)`；不得写入 Google 状态区。删除：

```javascript
localStorage.setItem('pendingDomainWatch', domain);
```

- [ ] **Step 4: 增加模板契约测试，禁止旧本地状态回归**

在 `DomainDetailMonitorUiTest` 加入：

```java
@Test
void monitorLoginUsesValidatedReturnTargetsWithoutLocalStorage() throws IOException {
    String template = template();

    assertTrue(template.contains("/login/google?returnTo="));
    assertTrue(template.contains("JSON.stringify({email: value, returnTo: monitorReturnTo()})"));
    assertFalse(template.contains("pendingDomainWatch"));
    assertFalse(template.contains("localStorage"));
}
```

- [ ] **Step 5: 运行 Task 2 测试并确认 GREEN**

Run:

```powershell
node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
mvn -pl wesite-web -am -Dtest=DomainDetailMonitorUiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 两条 Node 测试和全部模板测试 PASS。

- [ ] **Step 6: 提交 Task 2**

```powershell
git add wesite-web/src/main/resources/views/domain_detail.html wesite-web/src/test/js/domain-detail-monitor-runtime.test.js wesite-web/src/test/java/info/wesite/web/view/DomainDetailMonitorUiTest.java
git commit -m "feat: preserve domain monitor intent through login"
```

### Task 3：把登录失败结果返回监控专用弹窗

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/auth/ReturnTargetService.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/LoginController.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/auth/ReturnTargetServiceTest.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/controller/LoginControllerTest.java`
- Modify: `wesite-web/src/main/resources/views/domain_detail.html:230-350`
- Modify: `wesite-web/src/test/js/domain-detail-monitor-runtime.test.js`

**Interfaces:**
- Consumes: Task 2 生成的 `/domain/{domain}?monitor=pending` 回跳地址；现有 Google/Email 登录错误码。
- Produces: `ReturnTargetService.isDomainMonitorContinuation(String): boolean`、`ReturnTargetService.monitorLoginResultUrl(String, String): String`；域名页查询参数 `login`；前端 `showMonitorLoginResult(): boolean`。

- [ ] **Step 1: 编写失败的回跳服务测试**

在 `ReturnTargetServiceTest` 中加入：

```java
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
```

- [ ] **Step 2: 编写失败的 LoginController 测试**

在 `LoginControllerTest` 中加入：

```java
@Test
void loginFailureForMonitorContinuationReturnsToDomainDialog() {
    ExtendedModelMap model = new ExtendedModelMap();

    String view = controller.login(
            "/domain/example.com?monitor=pending", "google_error", model);

    assertEquals(
            "redirect:/domain/example.com?monitor=pending&login=google_error",
            view);
}

@Test
void ordinaryLoginFailureStillRendersLoginGateway() {
    ExtendedModelMap model = new ExtendedModelMap();

    String view = controller.login("/user/api-keys", "google_error", model);

    assertEquals("login", view);
}
```

更新其他测试对 `controller.login(...)` 的调用，第二个参数传 `null`。

- [ ] **Step 3: 运行聚焦 Java 测试并确认 RED**

Run:

```powershell
mvn -pl wesite-web -am -Dtest=ReturnTargetServiceTest,LoginControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，缺少两个 `ReturnTargetService` 方法，`LoginController.login` 也尚未接收 `login` 参数。

- [ ] **Step 4: 实现安全的监控错误回跳**

在 `ReturnTargetService` 中加入：

```java
private static final Pattern LOGIN_RESULT = Pattern.compile("[a-z_]{1,40}");

public boolean isDomainMonitorContinuation(String target) {
    return validated(target).map(candidate -> {
        URI uri = URI.create(candidate);
        if (!uri.getPath().startsWith("/domain/")) {
            return false;
        }
        return UriComponentsBuilder.fromUriString(candidate).build()
                .getQueryParams().getOrDefault("monitor", List.of()).contains("pending");
    }).orElse(false);
}

public String monitorLoginResultUrl(String target, String code) {
    String resolved = resolve(target);
    if (!isDomainMonitorContinuation(resolved) || code == null || !LOGIN_RESULT.matcher(code).matches()) {
        return resolved;
    }
    return UriComponentsBuilder.fromUriString(resolved)
            .replaceQueryParam("login", code)
            .build(true).toUriString();
}
```

加入所需的 `java.util.List`、`java.util.regex.Pattern` 导入。

把 `LoginController.login` 签名改为：

```java
public String login(
        @RequestParam(required = false) String returnTo,
        @RequestParam(required = false) String login,
        Model model) {
    String target = returnTargets.resolve(returnTo);
    if (UserHolder.get() != null) {
        return "redirect:" + target;
    }
    if (StringUtils.hasText(login) && returnTargets.isDomainMonitorContinuation(target)) {
        return "redirect:" + returnTargets.monitorLoginResultUrl(target, login);
    }
    model.addAttribute(Constants.PAGE_TITLE, "Sign In - Whose.Domains");
    model.addAttribute(Constants.PAGE_META_DESC, "Sign in securely to continue to your account.");
    model.addAttribute("returnTo", target);
    return "login";
}
```

加入 `org.springframework.util.StringUtils` 导入。普通登录目标不得改变现有行为。

- [ ] **Step 5: 编写失败的前端错误分区测试**

在 Node 测试加入：

```javascript
test('Google callback errors reopen the monitor dialog in the Google status region', async () => {
    const harness = createHarness({search: '?monitor=pending&login=google_error'}).run();
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.googleMessage.textContent, 'Google sign-in could not be completed. Please try again.');
    assert.equal(harness.emailMessage.textContent, '');
    assert.equal(harness.fetchCalls.some(call => call.url === '/api/domain-watch/watch'), false);
});

test('email link errors reopen the monitor dialog in the email status region', async () => {
    const harness = createHarness({search: '?monitor=pending&login=invalid'}).run();
    await harness.flushPromises();

    assert.equal(harness.emailMessage.textContent, 'This sign-in link is invalid or has expired.');
    assert.equal(harness.googleMessage.textContent, '');
});
```

- [ ] **Step 6: 运行前端测试并确认 RED**

Run:

```powershell
node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
```

Expected: FAIL，生产脚本尚未读取 `login` 或把错误写入对应状态区。

- [ ] **Step 7: 实现前端登录结果分区**

加入仅包含已知错误码的映射与处理函数：

```javascript
var monitorLoginMessages = {
    invalid: {target: 'email', text: 'This sign-in link is invalid or has expired.'},
    google_error: {target: 'google', text: 'Google sign-in could not be completed. Please try again.'},
    google_invalid: {target: 'google', text: 'Google did not provide the account details required to sign in.'},
    google_unverified: {target: 'google', text: 'Your Google email address must be verified before signing in.'},
    google_conflict: {target: 'google', text: 'This account is already connected to another Google identity.'},
    google_inactive: {target: 'google', text: 'This account is not active.'},
    google_email_unavailable: {target: 'google', text: 'We could not send the email needed to confirm this account.'},
    google_expired: {target: 'google', text: 'This Google sign-in attempt has expired. Please try again.'}
};

function showMonitorLoginResult() {
    var code = new URLSearchParams(location.search).get('login');
    var result = monitorLoginMessages[code];
    if (!result) return false;
    openMonitorModal(
        result.target === 'google' && googleMessage ? googleMessage : emailMessage,
        result.text);
    clearMonitorContinuation();
    return true;
}
```

`clearMonitorContinuation()` 在本任务中先实现为同时删除 `monitor` 和 `login`，并保留其他查询参数和 `location.hash`。初始化时先调用 `showMonitorLoginResult()`；返回 `true` 时不得发送 session、check 或 watch 请求。

- [ ] **Step 8: 运行 Task 3 测试并确认 GREEN**

Run:

```powershell
mvn -pl wesite-web -am -Dtest=ReturnTargetServiceTest,LoginControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
```

Expected: 全部 PASS。

- [ ] **Step 9: 提交 Task 3**

```powershell
git add wesite-web/src/main/java/info/wesite/web/auth/ReturnTargetService.java wesite-web/src/main/java/info/wesite/web/controller/LoginController.java wesite-web/src/test/java/info/wesite/web/auth/ReturnTargetServiceTest.java wesite-web/src/test/java/info/wesite/web/controller/LoginControllerTest.java wesite-web/src/main/resources/views/domain_detail.html wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
git commit -m "feat: return monitor login errors to domain dialog"
```

### Task 4：登录返回后自动完成监控

**Files:**
- Modify: `wesite-web/src/main/resources/views/domain_detail.html:230-350`
- Modify: `wesite-web/src/test/js/domain-detail-monitor-runtime.test.js`

**Interfaces:**
- Consumes: Task 2 的 `monitorReturnTo()`、`setMonitorMessage(...)`，Task 3 的 `clearMonitorContinuation()` 和登录结果分区；现有 `/user/session`、`/api/domain-watch/check/{domain}`、`/api/domain-watch/watch`。
- Produces: `openMonitorModal(messageTarget?, message?): void`、`checkMonitoringState(): Promise<boolean>`、`addWatch(): Promise<'added'|'auth-required'|'failed'>`、`clearMonitorContinuation(): void`、`continuePendingMonitor(): Promise<void>`。

- [ ] **Step 1: 编写自动续接失败测试**

在 Node 测试中加入以下行为测试；夹具的 `fetch` 按 URL 返回 `{ok, status, json()}`：

```javascript
test('pending continuation adds the current page domain once and clears only the monitor parameter', async () => {
    const harness = createHarness({
        pathname: '/domain/example.com',
        search: '?source=email&monitor=pending',
        hash: '#whois',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/api/domain-watch/watch': response(200, {code: 0})
        }
    }).run();
    await harness.flushPromises();

    const watchCalls = harness.fetchCalls.filter(call => call.url === '/api/domain-watch/watch');
    assert.equal(watchCalls.length, 1);
    assert.deepEqual(JSON.parse(watchCalls[0].options.body), {domainName: 'example.com', notifyType: 3});
    assert.equal(harness.button.textContent.includes('Monitoring'), true);
    assert.equal(harness.historyUrls.at(-1), '/domain/example.com?source=email#whois');
});

test('pending continuation reopens the monitor dialog when the session is still signed out', async () => {
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {'/user/session': response(401, {code: 401})}
    }).run();
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.fetchCalls.some(call => call.url === '/api/domain-watch/watch'), false);
});

test('pending continuation skips add when the domain is already watched', async () => {
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: true})
        }
    }).run();
    await harness.flushPromises();

    assert.equal(harness.button.textContent.includes('Monitoring'), true);
    assert.equal(harness.fetchCalls.some(call => call.url === '/api/domain-watch/watch'), false);
});

test('ordinary add failure opens the dialog and reports in the email status region', async () => {
    const harness = createHarness({
        search: '?monitor=pending',
        responses: {
            '/user/session': response(200, {code: 0}),
            '/api/domain-watch/check/example.com': response(200, {code: 0, data: false}),
            '/api/domain-watch/watch': response(400, {code: 500, msg: 'Watch limit reached.'})
        }
    }).run();
    await harness.flushPromises();

    assert.equal(harness.modal.style.display, 'flex');
    assert.equal(harness.emailMessage.textContent, 'Watch limit reached.');
});
```

- [ ] **Step 2: 运行 Node 测试并确认 RED**

Run:

```powershell
node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
```

Expected: 新增测试 FAIL，因为页面尚未读取 `monitor=pending`，也没有清理 URL 或串联 session/check/watch。

- [ ] **Step 3: 实现幂等自动续接状态机**

把旧的匿名弹窗显示操作集中为：

```javascript
function openMonitorModal(target, text) {
    modal.style.display = 'flex';
    if (text) setMonitorMessage(target || emailMessage, text, '#ff5252');
    var initialFocus = googleLink || email;
    if (initialFocus) initialFocus.focus();
}
```

让 `checkMonitoringState()` 返回 Promise，并只在 `response.ok && data.code === 0 && data.data === true` 时返回 `true` 和调用 `setMonitoringState()`。让 `addWatch()` 返回 Promise；仅 `response.ok && data.code === 0` 算成功，HTTP 401/403 或业务 `401/-401` 返回 `auth-required`，其他响应保留服务端消息并返回 `failed`。

加入 URL 清理：

```javascript
function clearMonitorContinuation() {
    var params = new URLSearchParams(location.search);
    params.delete('monitor');
    var query = params.toString();
    history.replaceState(null, '', location.pathname + (query ? '?' + query : '') + location.hash);
}
```

加入只运行一次的续接函数：

```javascript
var monitorContinuationStarted = false;

function continuePendingMonitor() {
    var params = new URLSearchParams(location.search);
    if (params.get('monitor') !== 'pending' || monitorContinuationStarted) return Promise.resolve();
    monitorContinuationStarted = true;
    clearMonitorContinuation();
    return fetch('/user/session', {credentials: 'include'})
        .then(parseJsonResponse)
        .then(function (session) {
            if (!session.ok || session.data.code !== 0) {
                openMonitorModal();
                return null;
            }
            return checkMonitoringState().then(function (alreadyWatching) {
                return alreadyWatching ? null : addWatch();
            });
        })
        .catch(function () {
            openMonitorModal(emailMessage, 'Could not start monitoring. Please try again.');
        });
}

continuePendingMonitor();
```

其中 `parseJsonResponse(response)` 必须保留 HTTP 状态与 JSON：

```javascript
function parseJsonResponse(response) {
    return response.json().then(function (data) {
        return {ok: response.ok, status: response.status, data: data};
    });
}
```

现有按钮点击流程也复用 `openMonitorModal()` 和新的 `addWatch()`，不得保留直接 `modal.style.display='flex'` 的重复认证分支。

- [ ] **Step 4: 运行自动续接测试并确认 GREEN**

Run:

```powershell
node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
```

Expected: 全部 Node 测试 PASS，Watch API 在一次续接中最多调用一次。

- [ ] **Step 5: 运行相关回归测试**

Run:

```powershell
node --test wesite-web/src/test/js/auth-modal-runtime.test.js
node --test wesite-web/src/test/js/domain-watch-runtime.test.js
mvn -pl wesite-web -am -Dtest=DomainDetailMonitorUiTest,AuthModalTemplateTest,DomainWatchTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 全部 PASS。

- [ ] **Step 6: 提交 Task 4**

```powershell
git add wesite-web/src/main/resources/views/domain_detail.html wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
git commit -m "feat: auto-start domain monitoring after sign-in"
```

### Task 5：完整验证与人工验收

**Files:**
- Verify only; no planned production-file changes.

**Interfaces:**
- Consumes: Tasks 1–3 的最终实现。
- Produces: 可复跑的测试结果和人工验收记录。

- [ ] **Step 1: 运行全部相关 Node 测试**

```powershell
node --test wesite-web/src/test/js/auth-modal-runtime.test.js
node --test wesite-web/src/test/js/domain-watch-runtime.test.js
node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js
```

Expected: 全部 PASS，无失败、取消或跳过。

- [ ] **Step 2: 运行完整 Maven reactor**

```powershell
mvn -pl wesite-web -am test
```

Expected: `wesite-core` 与 `wesite-web` 均 `SUCCESS`，Failures 和 Errors 均为 0。

- [ ] **Step 3: 检查差异与工作树**

```powershell
git diff --check
git status --short
git log --oneline -4
```

Expected: `git diff --check` 无输出；状态中不出现未提交的本任务文件。

- [ ] **Step 4: 浏览器验收**

在可运行的本地环境验证：

1. 未登录点击“Monitor this domain”，专用弹窗显示 Google、分隔线和 Email Link。
2. Google 登录成功后返回同一域名页，自动显示“Monitoring”。
3. Email Link 在另一浏览器打开后返回同一域名页，自动显示“Monitoring”。
4. Google 错误不写入 Email 状态区，Email 错误不写入 Google 状态区。
5. 回跳完成后地址栏不再包含 `monitor=pending`，其他参数和片段保留。
6. 已登录用户点击按钮仍直接加入 Watchlist。

若本地环境缺少 DataSource 或 OAuth 配置，应如实记录阻断，不得把 Node 测试替代成浏览器通过证据。
