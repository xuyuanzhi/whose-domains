# 认证操作与 Watchlist 未登录状态实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 为 Google 登录按钮增加官方图标，以站内确认框保护退出操作，并把 Watchlist 未登录区域改为单焦点登录卡。

**架构：** 共享认证结构、样式和退出状态机继续放在现有 `template.html` 与 `common.css` 中；现有可执行 Node DOM 测试直接提取并运行模板中的真实脚本。Watchlist 未登录结构和状态切换留在 `domain-watch.html`，新增专用模板测试和可执行 DOM 测试，避免把页面行为复制进测试。

**技术栈：** Thymeleaf HTML、原生 JavaScript/Pointer & Keyboard Events、CSS、Node 24 内置 `node:test`/`vm`、Java 17、JUnit 5、Maven。

## 全局约束

- 不改变认证接口、登录结果码、会话行为和登录后的 Watchlist 功能。
- Google 图标使用内联官方四色 SVG，不新增资源文件、网络请求或依赖，并设置 `aria-hidden="true"`。
- `Finish with Google` 只替换文字元素，不能覆盖图标。
- 退出确认框必须支持 Escape、点击遮罩、Cancel、焦点锁定和焦点恢复。
- 打开退出确认框时默认聚焦 `Cancel`。
- 退出失败不得刷新页面；成功响应后才刷新。
- 同一时间最多发送一个退出请求。
- Watchlist 未登录卡只有一个 `Sign In` 主操作。
- 删除快捷域名/邮箱登录表单、重复的 Create Account、相关消息区及本地存储流程。
- 未认证时隐藏加载状态和 `watchlistUI`，再显示登录卡。
- 保留现有深蓝、青色、玻璃边框和圆角视觉体系，并适配移动端。

---

## 文件结构

- `wesite-web/src/main/resources/views/template.html`：Google 图标、退出确认框结构和退出状态机。
- `wesite-web/src/main/resources/static/style/common.css`：Google 图标间距、退出确认框视觉和响应式操作布局。
- `wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java`：共享认证模板结构与可访问性契约。
- `wesite-web/src/test/js/auth-modal-runtime.test.js`：直接运行共享模板真实脚本，验证退出状态机和图标文案更新。
- `wesite-web/src/main/resources/views/user/domain-watch.html`：Watchlist 单焦点未登录卡和安全状态切换。
- `wesite-web/src/test/java/info/wesite/web/view/DomainWatchTemplateTest.java`：Watchlist 新结构和旧流程移除契约。
- `wesite-web/src/test/js/domain-watch-runtime.test.js`：运行 Watchlist 真实脚本，验证未认证状态切换。

### 任务 1：Google 登录按钮图标与稳定的文字更新

**文件：**
- 修改：`wesite-web/src/main/resources/views/template.html:226-233,429-432`
- 修改：`wesite-web/src/main/resources/static/style/common.css:110-126`
- 测试：`wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java`
- 测试：`wesite-web/src/test/js/auth-modal-runtime.test.js`

**接口：**
- 输入：现有 `googleLoginButton` 和 `google_bind_required` 登录结果。
- 输出：装饰性 SVG `.auth-google-icon`、文字节点 `googleLoginButtonText`，以及只更新文字节点的结果处理逻辑。

- [ ] **步骤 1：先写失败的模板测试**

在 `AuthModalTemplateTest` 增加：

```java
@Test
void googleButtonHasDecorativeProviderIconAndIndependentText() throws IOException {
    String template = template();

    assertTrue(template.contains("class=\"auth-google-icon\" aria-hidden=\"true\""));
    assertTrue(template.contains("id=\"googleLoginButtonText\">Continue with Google</span>"));
    assertTrue(template.contains("document.getElementById('googleLoginButtonText')"));
    assertTrue(template.contains("buttonText.textContent='Finish with Google'"));
}
```

同时把现有断言 `button.textContent='Finish with Google'` 改为新的文字节点断言。

- [ ] **步骤 2：运行聚焦测试并确认 RED**

```powershell
mvn -pl wesite-web -am "-Dtest=AuthModalTemplateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：因 `.auth-google-icon`、`googleLoginButtonText` 和独立文字更新逻辑尚不存在而失败。

- [ ] **步骤 3：实现 Google 图标和独立文字节点**

把 Google 链接内容改为：

```html
<a id="googleLoginButton" class="auth-google-button" href="/oauth2/authorization/google">
    <svg class="auth-google-icon" aria-hidden="true" viewBox="0 0 18 18" focusable="false">
        <path fill="#4285F4" d="M17.64 9.205c0-.638-.057-1.252-.164-1.841H9v3.482h4.844a4.14 4.14 0 0 1-1.797 2.715v2.258h2.909c1.702-1.567 2.684-3.874 2.684-6.614Z"/>
        <path fill="#34A853" d="M9 18c2.43 0 4.467-.806 5.956-2.181l-2.909-2.258c-.806.54-1.835.859-3.047.859-2.344 0-4.328-1.585-5.037-3.714H.956v2.332A9 9 0 0 0 9 18Z"/>
        <path fill="#FBBC05" d="M3.963 10.706A5.41 5.41 0 0 1 3.682 9c0-.592.102-1.168.281-1.706V4.962H.956A9 9 0 0 0 0 9c0 1.452.347 2.827.956 4.038l3.007-2.332Z"/>
        <path fill="#EA4335" d="M9 3.58c1.321 0 2.507.454 3.441 1.346l2.581-2.581C13.463.892 11.426 0 9 0A9 9 0 0 0 .956 4.962l3.007 2.332C4.672 5.165 6.656 3.58 9 3.58Z"/>
    </svg>
    <span id="googleLoginButtonText">Continue with Google</span>
</a>
```

结果处理改为：

```javascript
var buttonText=document.getElementById('googleLoginButtonText');
if(buttonText)buttonText.textContent='Finish with Google';
```

在 `common.css` 增加：

```css
.auth-google-icon { width: 18px; height: 18px; flex: 0 0 auto; }
```

- [ ] **步骤 4：增加并运行可执行文案更新测试**

在 `auth-modal-runtime.test.js` 的 harness 注册 `googleLoginButtonText`，并增加：

```javascript
test('Google binding result changes only the button label and keeps the icon node', () => {
    const harness = createHarness({ loginCode: 'google_bind_required' });
    assert.equal(harness.googleButtonText.textContent, 'Finish with Google');
    assert.ok(harness.googleIcon);
});
```

运行：

```powershell
node --test wesite-web/src/test/js/auth-modal-runtime.test.js
mvn -pl wesite-web -am "-Dtest=AuthModalTemplateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：Node 与 Java 聚焦测试全部通过。

- [ ] **步骤 5：提交 Google 图标**

```powershell
git add -- wesite-web/src/main/resources/views/template.html wesite-web/src/main/resources/static/style/common.css wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java wesite-web/src/test/js/auth-modal-runtime.test.js
git commit -m "feat: add Google sign-in icon"
```

### 任务 2：站内退出确认框与可靠退出状态机

**文件：**
- 修改：`wesite-web/src/main/resources/views/template.html:195-244,366-447`
- 修改：`wesite-web/src/main/resources/static/style/common.css:36-170`
- 测试：`wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java`
- 测试：`wesite-web/src/test/js/auth-modal-runtime.test.js`

**接口：**
- 输入：导航中的 Sign Out 链接和 `/user/logout` POST 接口。
- 输出：`openLogoutConfirm(trigger)`、`closeLogoutConfirm()`、`confirmLogout()`、`setLogoutLoading(loading)`、`handleLogoutDialogKeydown(event)`；DOM 节点 `logoutConfirmModal`、`logoutConfirmDialog`、`logoutCancelButton`、`logoutConfirmButton`、`logoutConfirmMsg`。

- [ ] **步骤 1：先写失败的结构测试**

在 `AuthModalTemplateTest` 增加：

```java
@Test
void signOutUsesAnAccessibleConfirmationDialog() throws IOException {
    String template = template();

    assertTrue(template.contains("onclick=\"openLogoutConfirm(this)\""));
    assertTrue(template.contains("id=\"logoutConfirmModal\" aria-hidden=\"true\""));
    assertTrue(template.contains("id=\"logoutConfirmDialog\" role=\"dialog\" aria-modal=\"true\""));
    assertTrue(template.contains("aria-labelledby=\"logoutConfirmTitle\""));
    assertTrue(template.contains("id=\"logoutConfirmMsg\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""));
    assertTrue(template.contains("id=\"logoutCancelButton\""));
    assertTrue(template.contains("id=\"logoutConfirmButton\""));
}
```

- [ ] **步骤 2：先写失败的可执行退出行为测试**

扩展 `auth-modal-runtime.test.js` 的 fake DOM、fetch 与 location harness，增加以下独立测试：

```javascript
test('opening sign-out confirmation focuses Cancel and sends no request', async () => {});
test('Cancel, Escape and backdrop close restore focus to Sign Out', async () => {});
test('Tab and Shift+Tab stay inside the sign-out confirmation', async () => {});
test('confirming sign-out sends exactly one POST and disables both actions', async () => {});
test('successful sign-out reloads the current page', async () => {});
test('failed sign-out stays open, restores actions and announces the error', async () => {});
```

每个测试必须运行从 `template.html` 提取的真实脚本，断言 DOM、fetch 调用参数与 reload 次数，不能在测试中复制状态机实现。

- [ ] **步骤 3：运行测试并确认 RED**

```powershell
node --test wesite-web/src/test/js/auth-modal-runtime.test.js
mvn -pl wesite-web -am "-Dtest=AuthModalTemplateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：因退出确认框节点和函数尚不存在而失败。

- [ ] **步骤 4：添加确认框结构**

把导航退出链接改为：

```html
<a href="javascript:void(0)" onclick="openLogoutConfirm(this)"><i class="fas fa-sign-out-alt"></i> Sign Out</a>
```

在认证弹窗之后增加：

```html
<div id="logoutConfirmModal" class="logout-confirm-modal" aria-hidden="true">
    <div id="logoutConfirmDialog" class="logout-confirm-dialog" role="dialog" aria-modal="true"
         aria-labelledby="logoutConfirmTitle" aria-describedby="logoutConfirmDescription" tabindex="-1">
        <div class="logout-confirm-icon" aria-hidden="true"><i class="fas fa-sign-out-alt"></i></div>
        <h2 id="logoutConfirmTitle">Sign out?</h2>
        <p id="logoutConfirmDescription">You’ll need to sign in again to access your Watchlist and account settings.</p>
        <div id="logoutConfirmMsg" role="status" aria-live="polite" aria-atomic="true"></div>
        <div class="logout-confirm-actions">
            <button id="logoutCancelButton" type="button" onclick="closeLogoutConfirm()">Cancel</button>
            <button id="logoutConfirmButton" type="button" onclick="confirmLogout()">Sign Out</button>
        </div>
    </div>
</div>
```

- [ ] **步骤 5：实现退出状态机**

用以下职责明确的状态和函数替换现有 `doLogout()`：

```javascript
var logoutConfirmModal=document.getElementById('logoutConfirmModal');
var logoutConfirmDialog=document.getElementById('logoutConfirmDialog');
var logoutConfirmTrigger=null;
var logoutRequestPending=false;

function openLogoutConfirm(trigger) {
    logoutConfirmTrigger=trigger&&typeof trigger.focus==='function'?trigger:null;
    setLogoutLoading(false);
    setLogoutMessage('');
    logoutConfirmModal.style.display='flex';
    logoutConfirmModal.setAttribute('aria-hidden','false');
    document.getElementById('logoutCancelButton').focus();
}
function closeLogoutConfirm() {
    if(logoutRequestPending)return;
    logoutConfirmModal.style.display='none';
    logoutConfirmModal.setAttribute('aria-hidden','true');
    if(logoutConfirmTrigger&&document.contains(logoutConfirmTrigger))logoutConfirmTrigger.focus();
    logoutConfirmTrigger=null;
}
function setLogoutLoading(loading) {
    logoutRequestPending=loading;
    var cancel=document.getElementById('logoutCancelButton');
    var confirm=document.getElementById('logoutConfirmButton');
    cancel.disabled=loading;
    confirm.disabled=loading;
    confirm.textContent=loading?'Signing out…':'Sign Out';
}
function setLogoutMessage(message) {
    var status=document.getElementById('logoutConfirmMsg');
    status.textContent=message;
    status.style.display=message?'block':'none';
}
function confirmLogout() {
    if(logoutRequestPending)return;
    setLogoutLoading(true);
    setLogoutMessage('');
    fetch('/user/logout',{method:'POST',credentials:'include'}).then(function(response){
        if(!response.ok)throw new Error('Logout failed');
        location.reload();
    }).catch(function(){
        setLogoutLoading(false);
        setLogoutMessage('Could not sign out. Please try again.');
        document.getElementById('logoutConfirmButton').focus();
    });
}
```

为遮罩点击和 document keydown 增加退出确认框分支：确认框打开时优先处理它；Escape 关闭；Tab/Shift+Tab 使用可见且未禁用的两个操作维持循环。请求进行中忽略关闭操作。

- [ ] **步骤 6：增加确认框样式**

在 `common.css` 增加 `.logout-confirm-modal`、`.logout-confirm-dialog`、`.logout-confirm-icon`、`.logout-confirm-actions` 和按钮状态样式。关键约束为：遮罩固定覆盖视口、卡片最大宽度 420px、错误状态使用 `var(--danger)`、Cancel 为玻璃边框次要按钮、Sign Out 为危险色按钮；`max-width:480px` 时操作区允许纵向排列。

- [ ] **步骤 7：验证 GREEN 并提交**

```powershell
node --test wesite-web/src/test/js/auth-modal-runtime.test.js
mvn -pl wesite-web -am "-Dtest=AuthModalTemplateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check
git add -- wesite-web/src/main/resources/views/template.html wesite-web/src/main/resources/static/style/common.css wesite-web/src/test/java/info/wesite/web/view/AuthModalTemplateTest.java wesite-web/src/test/js/auth-modal-runtime.test.js
git commit -m "feat: confirm account sign-out"
```

预期：所有退出结构和可执行行为测试通过，diff check 无输出。

### 任务 3：Watchlist 单焦点未登录卡与状态切换

**文件：**
- 修改：`wesite-web/src/main/resources/views/user/domain-watch.html:15-47,194-245`
- 新建：`wesite-web/src/test/java/info/wesite/web/view/DomainWatchTemplateTest.java`
- 新建：`wesite-web/src/test/js/domain-watch-runtime.test.js`

**接口：**
- 输入：`/api/domain-watch/list` 的 401/403、业务码 401/-401 或网络失败。
- 输出：`showLoginRequired()` 同时设置 `isLoggedIn=false`，隐藏 `listLoader` 和 `watchlistUI`，显示 `loginRequired`；唯一 Sign In 按钮调用 `openAuthModal(this)`。

- [ ] **步骤 1：先写失败的模板测试**

新建 `DomainWatchTemplateTest.java`：

```java
package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DomainWatchTemplateTest {
    @Test
    void signedOutStateIsASingleFocusedSignInCard() throws IOException {
        String template = template();
        assertTrue(template.contains("class=\"watchlist-signin-card\""));
        assertTrue(template.contains("Sign in to view your Watchlist"));
        assertTrue(template.contains("Expiry alerts"));
        assertTrue(template.contains("Up to 50 domains"));
        assertTrue(template.contains("onclick=\"openAuthModal(this)\""));
        assertFalse(template.contains("id=\"quickWatchDomain\""));
        assertFalse(template.contains("id=\"quickWatchEmail\""));
        assertFalse(template.contains("requestQuickWatch"));
        assertFalse(template.contains("addPendingWatch"));
        assertFalse(template.contains("pendingDomainWatch"));
        assertFalse(template.contains("Create Account"));
    }

    private String template() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/views/user/domain-watch.html")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **步骤 2：先写失败的运行时状态测试**

新建 `domain-watch-runtime.test.js`，从 `domain-watch.html` 提取真实 `<script>`，使用最小 fake DOM/fetch harness，覆盖：

```javascript
test('401 response hides loader and authenticated UI before showing sign-in card', async () => {});
test('business 401 code shows the same signed-out state', async () => {});
test('network failure shows the same signed-out state', async () => {});
test('successful list response hides signed-out state and shows Watchlist UI', async () => {});
```

断言 `loginRequired.style.display`、`listLoader.style.display`、`watchlistUI.style.display` 和 `isLoggedIn`，不复制 `showLoginRequired()` 的生产逻辑。

- [ ] **步骤 3：运行测试并确认 RED**

```powershell
node --test wesite-web/src/test/js/domain-watch-runtime.test.js
mvn -pl wesite-web -am "-Dtest=DomainWatchTemplateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：模板测试因新卡片不存在而失败；运行时测试因旧 `showLoginRequired()` 没有隐藏 `watchlistUI` 和重置登录状态而失败。

- [ ] **步骤 4：替换为单焦点登录卡**

把 `loginRequired` 内容替换为：

```html
<div id="loginRequired" class="watchlist-signed-out" style="display:none;">
    <div class="watchlist-signin-card">
        <div class="watchlist-signin-icon" aria-hidden="true"><i class="fas fa-lock"></i></div>
        <h2>Sign in to view your Watchlist</h2>
        <p>Your monitored domains and alert settings are saved securely to your account.</p>
        <div class="watchlist-signin-benefits" aria-label="Watchlist benefits">
            <span><i class="fas fa-check" aria-hidden="true"></i> Expiry alerts</span>
            <span><i class="fas fa-check" aria-hidden="true"></i> Up to 50 domains</span>
        </div>
        <button type="button" onclick="openAuthModal(this)" class="watch-btn-primary">Sign In</button>
        <small>New domains can be added after signing in.</small>
    </div>
</div>
```

删除 `requestQuickWatch()`、`addPendingWatch()`，并从登录成功分支删除 `addPendingWatch()` 调用。

- [ ] **步骤 5：实现安全状态切换和卡片样式**

把函数改为：

```javascript
function showLoginRequired() {
    isLoggedIn=false;
    document.getElementById('listLoader').style.display='none';
    document.getElementById('watchlistUI').style.display='none';
    document.getElementById('loginRequired').style.display='block';
}
```

登录成功分支在显示 `watchlistUI` 前隐藏 `loginRequired`。在页面内现有 `<style>` 增加 `.watchlist-signed-out`、`.watchlist-signin-card`、`.watchlist-signin-icon`、`.watchlist-signin-benefits`，使用现有 CSS 变量；卡片最大宽度 520px、居中、移动端 benefit 可换行。

- [ ] **步骤 6：验证 GREEN、运行完整套件并提交**

```powershell
node --test wesite-web/src/test/js/domain-watch-runtime.test.js
node --test wesite-web/src/test/js/auth-modal-runtime.test.js
mvn -pl wesite-web -am test
git diff --check
git add -- wesite-web/src/main/resources/views/user/domain-watch.html wesite-web/src/test/java/info/wesite/web/view/DomainWatchTemplateTest.java wesite-web/src/test/js/domain-watch-runtime.test.js
git commit -m "feat: simplify Watchlist signed-out state"
```

预期：两个 Node 测试文件、`wesite-core` 与 `wesite-web` 全部通过，diff check 无输出。

### 任务 4：浏览器视觉与交互验收

**文件：**
- 只验证：`wesite-web/src/main/resources/views/template.html`
- 只验证：`wesite-web/src/main/resources/static/style/common.css`
- 只验证：`wesite-web/src/main/resources/views/user/domain-watch.html`

**接口：**
- 输入：已完成的 Google 按钮、退出确认框和 Watchlist 未登录卡。
- 输出：验证证据，不新增生产接口。

- [ ] **步骤 1：启动本地站点**

在 `wesite-web` 目录运行：

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

打开 `http://localhost:80`。若 MySQL、Redis、配置或密钥不可用，记录准确启动失败，不修改应用配置；改用不提交的静态壳验证视觉，并以自动化测试作为运行时证据。

- [ ] **步骤 2：验证 Google 按钮与退出确认框**

确认：四色 G 图标清晰且与文字对齐；键盘聚焦 Google 按钮时焦点样式完整；Sign Out 只打开确认框；默认焦点在 Cancel；Cancel/Escape/遮罩恢复焦点；Tab 不离开确认框；失败状态可读；移动端按钮布局不拥挤。

- [ ] **步骤 3：验证 Watchlist 未登录状态**

确认：页面只显示一个居中的登录卡和一个 Sign In 操作；标题、说明、两个价值点和辅助文案层级清楚；旧输入框和重复操作不再出现；桌面和 480px 视口无横向滚动。

- [ ] **步骤 4：报告结果**

记录真实应用或静态壳的验证范围、viewport、控制台情况和受阻项。没有修复时不创建空提交。
