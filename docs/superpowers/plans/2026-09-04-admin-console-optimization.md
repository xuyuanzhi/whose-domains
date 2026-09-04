# Whose.Domains Admin Console Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不更换 LayuiAdmin 技术栈的前提下，完成安全可用的管理员登录、真实菜单、业务功能闭环和中文工作台。

**Architecture:** 保留 Thymeleaf SPA 宿主与 Layui Hash 路由，独立登录视图和所有后台业务视图继续从 `static/layuiadmin/views` 加载。Spring MVC 控制器提供统一 `ResponseJson` 接口，用户和工作台数据通过后台专用 DTO 返回，避免暴露实体敏感字段。

**Tech Stack:** Java 17、Spring Boot 3.5、MyBatis-Plus、Thymeleaf、LayuiAdmin、JUnit 5、Mockito、AssertJ

**Spec:** `docs/superpowers/specs/2026-09-04-admin-console-optimization-design.md`

## Global Constraints

- 保留现有 LayuiAdmin，不引入 Vue、React、Node.js 构建或新的前端依赖。
- 所有有效管理员显示同一套菜单，本期不增加 RBAC 表或权限配置页面。
- 保持现有管理员密码散列兼容，不修改正式环境账号数据。
- 不修改 `wesite-web` 的 `/domain/*`、`/blog/*` URL 和 SEO 行为。
- 所有新增及修改的文本文件使用 UTF-8。
- 不直接向前端返回包含密码、secureKey 等敏感字段的 `User` 实体。
- 每个行为变更遵循测试先行：先看到目标测试因缺少行为而失败，再写最小实现。

---

### Task 1: 管理员认证响应与登录页

**Files:**
- Create: `wesite-admin/src/main/java/info/wesite/admin/view/AdminSessionView.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/controller/MainControllerTest.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/view/AdminLoginTemplateTest.java`
- Create: `wesite-admin/src/main/resources/static/layuiadmin/views/user/login.html`
- Modify: `wesite-admin/src/main/java/info/wesite/admin/controller/MainController.java`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/adminui/src/modules/view.js`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/adminui/dist/modules/view.js`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/config.js`

**Interfaces:**
- Produces: `AdminSessionView(String id, String name, String phoneNo)` returned by `GET /userInfo`.
- Produces: `POST /login` success data with `token` and `name`; all authentication failures use `用户名或密码错误` except blank-field validation.
- Produces: client helper `view.clearSession()` used by 401 handling and logout.

- [ ] **Step 1: Write failing controller tests**

Create tests that instantiate `MainController` with a mocked `UserService` and verify:

```java
@Test
void loginDoesNotRevealWhetherAdministratorIsMissingOrDisabled() {
    when(users.getOne(any())).thenReturn(null, disabledAdmin());
    assertThat(controller.login(login("admin", "wrong")).getMsg()).isEqualTo("用户名或密码错误");
    assertThat(controller.login(login("admin", "wrong")).getMsg()).isEqualTo("用户名或密码错误");
}

@Test
void userInfoReturnsOnlySafeSessionFields() {
    UserHolder.set(activeAdmin("admin-1"));
    ResponseJson<AdminSessionView> response = controller.userInfo();
    assertThat(response.getData().id()).isEqualTo("admin-1");
    assertThat(response.getData().name()).isEqualTo("Administrator");
}
```

Use reflection or a package-private constructor to inject `UserService`; clean `UserHolder` in `@AfterEach`.

- [ ] **Step 2: Run the controller test and verify RED**

Run:

```powershell
mvn -pl wesite-admin -am -Dtest=MainControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation or assertion failure because `AdminSessionView` and the safe `userInfo` response do not exist.

- [ ] **Step 3: Implement the safe session response and uniform login failures**

Create:

```java
public record AdminSessionView(String id, String name, String phoneNo) {
    public static AdminSessionView from(User user) {
        return new AdminSessionView(user.getId(), user.getName(), user.getPhoneNo());
    }
}
```

Update `MainController` to use constructor injection, reject deleted/non-admin/inactive users with the uniform message, compare the legacy MD5 value null-safely, and return `ResponseJson<AdminSessionView>` from `userInfo`.

- [ ] **Step 4: Run the controller test and verify GREEN**

Run the command from Step 2. Expected: `MainControllerTest` passes.

- [ ] **Step 5: Write a failing static login contract test**

The test must read the UTF-8 login template and assert it contains `lay-filter="admin-login-submit"`, `/login`, `type="password"`, an accessible password label, a show-password control, busy-state handling, and redirect sanitization rejecting `//`.

- [ ] **Step 6: Run the login template test and verify RED**

Run:

```powershell
mvn -pl wesite-admin -am -Dtest=AdminLoginTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `views/user/login.html` is absent.

- [ ] **Step 7: Build the login view and session cleanup**

Implement a branded Chinese login card. Submit JSON with `admin.req`, disable the button while pending, store only `access_token`, and accept a redirect only when:

```javascript
function safeRedirect(value) {
  return /^\/(?!\/)/.test(value || '') ? value : '/';
}
```

Add `view.clearSession()` that removes the token, cached administrator data, and Layui tab state. Make `view.exit()` call it before routing to `/user/login`. Keep `src` and `dist` module behavior identical. Set `indPage: ['/user/login']`, product name `Whose.Domains Admin`, `debug: false`, and a distinct storage table name in `config.js`.

- [ ] **Step 8: Run authentication tests**

Run both test classes from Steps 2 and 6. Expected: all pass.

### Task 2: 后台骨架、真实菜单与退出流程

**Files:**
- Create: `wesite-admin/src/test/java/info/wesite/admin/view/AdminNavigationTemplateTest.java`
- Modify: `wesite-admin/src/main/resources/views/index.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/layout.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/json/menu.js`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/adminui/src/css/admin.css`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/adminui/dist/css/admin.css`

**Interfaces:**
- Consumes: `GET /userInfo` safe fields and `view.clearSession()` from Task 1.
- Produces: routes `/`, `/person/list`, `/domain/tld`, `/domain/sld`, `/blog/list`, `/contact/list`.

- [ ] **Step 1: Write the failing navigation contract test**

Assert the menu contains exactly the six real destinations, does not contain `senior`, `template`, `app`, `component`, `www.baidu.com`, or demo system panels, and the layout invokes `/logout` before local cleanup.

- [ ] **Step 2: Run the navigation test and verify RED**

Run:

```powershell
mvn -pl wesite-admin -am -Dtest=AdminNavigationTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the demo menu and Baidu link remain.

- [ ] **Step 3: Replace the menu and simplify the shell**

Use stable menu names and icons:

```javascript
{name: 'home', title: '工作台', icon: 'layui-icon-home', jump: '/'},
{name: 'users', title: '用户管理', icon: 'layui-icon-user', jump: 'person/list'},
{name: 'domains', title: '域名管理', icon: 'layui-icon-website', list: [...]},
{name: 'content', title: '内容管理', icon: 'layui-icon-read', list: [...]},
{name: 'service', title: '客户服务', icon: 'layui-icon-dialogue', list: [...]}
```

Update the product title, front-site URL, administrator display, logout request, toolbar controls, focus styles, narrow-screen layout and brand colors. Remove note/theme/about/demo actions.

- [ ] **Step 4: Run the navigation test and verify GREEN**

Run the command from Step 2. Expected: pass.

### Task 3: 用户管理 API 与页面闭环

**Files:**
- Create: `wesite-admin/src/main/java/info/wesite/admin/view/AdminUserView.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/controller/UserControllerTest.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/view/AdminUserTemplateTest.java`
- Modify: `wesite-admin/src/main/java/info/wesite/admin/controller/UserController.java`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/person/list.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/person/add.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/person/edit.html`

**Interfaces:**
- Produces: `AdminUserView` fields `id`, `name`, `phoneNo`, `status`, `statusText`, `createTimeText`, `updateTimeText`.
- Keeps: `POST /user/list`, `/user/detail`, `/user/save`, `/user/delete`.

- [ ] **Step 1: Write failing user-controller tests**

Cover new user field copying, edit field copying, keyword search by both name and phone, rejection of admin detail/delete, duplicate phone rejection, and safe DTO responses. The core regression assertion is:

```java
verify(users).saveOrUpdate(argThat(saved ->
    "Alice".equals(saved.getName())
        && "13800000000".equals(saved.getPhoneNo())
        && User.STATUS_ACTIVE == saved.getStatus()
        && User.TYPE_PERSON.equals(saved.getUserType())));
```

- [ ] **Step 2: Run the user-controller test and verify RED**

Run:

```powershell
mvn -pl wesite-admin -am -Dtest=UserControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the current implementation writes null name/status and returns `User` entities.

- [ ] **Step 3: Implement safe user operations**

Use constructor injection. On create set ID, name, normalized phone, active/inactive status, `TYPE_PERSON`, audit fields and timestamps. On edit load the existing row and require `TYPE_PERSON`. Search name or phone. Reject phone duplicates. Convert list/detail results to `AdminUserView`; never update password, secure key, type or administrator rows from this endpoint.

- [ ] **Step 4: Run user-controller tests and verify GREEN**

Run the command from Step 2. Expected: pass.

- [ ] **Step 5: Write and run a failing user-template contract test**

Assert the templates use `/user/save`, `/user/delete`, `person/add`, `person/edit`, correct table IDs, Chinese labels, and contain none of `/website/`, `monitor/`, `url: 'xxx'`, or fake upload URLs.

- [ ] **Step 6: Repair the user views**

Rebuild list/add/edit around real fields. Fetch detail before edit, prevent duplicate submits, confirm deletion, reload only `LAY-user-manage`, and escape rendered text.

- [ ] **Step 7: Run user controller and template tests**

Expected: both classes pass.

### Task 4: 顶级域名和二级保留域名页面修复

**Files:**
- Create: `wesite-admin/src/test/java/info/wesite/admin/controller/DomainControllerTest.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/view/AdminDomainTemplateTest.java`
- Modify: `wesite-admin/src/main/java/info/wesite/admin/controller/DomainController.java`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/domain/tld/index.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/domain/tld/edit.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/domain/sld/index.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/domain/sld/add.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/domain/sld/edit.html`

**Interfaces:**
- Keeps: `POST /domain/tld/list`, `/domain/tld/detail`, `/domain/tld/save`, `/domain/sld/list`, `/domain/sld/detail`, `/domain/sld/save`.

- [ ] **Step 1: Write failing domain validation tests**

Verify blank IDs/names return Chinese errors; SLD names are trimmed and lowercased; duplicate names and missing TLDs are rejected; new SLD status defaults active; edited status is honored.

- [ ] **Step 2: Run domain tests and verify RED**

Run:

```powershell
mvn -pl wesite-admin -am -Dtest=DomainControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: at least the Chinese-message and normalization assertions fail.

- [ ] **Step 3: Harden domain controller behavior**

Use constructor injection, trim inputs before validation, keep existing TLD/SLD data rules, and replace public English errors with consistent Chinese messages. Do not add delete endpoints.

- [ ] **Step 4: Run domain tests and verify GREEN**

Expected: pass.

- [ ] **Step 5: Write and run failing template contracts**

Assert only `/domain/tld/*` and `/domain/sld/*` APIs are used, toolbar/table filter IDs match, refresh reloads the correct table, and no `/listing/*`, `LAY-user-manage`, `url: 'xxx'`, fake upload, or mojibake replacement characters remain.

- [ ] **Step 6: Repair all domain views**

Use real endpoints, correct filter names, busy-state protection, Chinese feedback, escaped cell content, and responsive popup sizes. Preserve the existing editable TLD and SLD fields.

- [ ] **Step 7: Run domain controller and template tests**

Expected: both classes pass.

### Task 5: 联系消息整合与 XSS 防护

**Files:**
- Create: `wesite-admin/src/test/java/info/wesite/admin/controller/ContactControllerTest.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/view/AdminContactTemplateTest.java`
- Create: `wesite-admin/src/main/resources/static/layuiadmin/views/contact/list.html`
- Modify: `wesite-admin/src/main/java/info/wesite/admin/controller/ContactController.java`
- Delete: `wesite-admin/src/main/resources/templates/admin/contacts.html`
- Delete: `wesite-admin/src/main/resources/static/js/admin-contacts.js`

**Interfaces:**
- Produces: `GET /admin/contacts/list?page={page}&size={size}`.
- Produces: `GET /admin/contacts/{id}`, `POST /admin/contacts/status`, `DELETE /admin/contacts/delete`, and `GET /admin/contacts/stats`.
- Removes: server-rendered `GET /admin/contacts` page mapping.

- [ ] **Step 1: Write failing contact-controller tests**

Verify empty deletes fail, DELETE removes IDs, missing detail fails in Chinese, status accepts only defined values, and pending stats call `count` with the actual pending status condition rather than returning zero.

- [ ] **Step 2: Run contact tests and verify RED**

Run:

```powershell
mvn -pl wesite-admin -am -Dtest=ContactControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: pending-stat and response-message assertions fail.

- [ ] **Step 3: Normalize the contact API**

Convert the controller to `@RestController`, remove the Thymeleaf page method, use constructor injection, validate IDs/status, calculate real total/pending/processed counts, and return Chinese responses.

- [ ] **Step 4: Run contact tests and verify GREEN**

Expected: pass.

- [ ] **Step 5: Write and run a failing contact-template test**

Assert the SPA view exists, uses the exact API methods, calls `layui.util.escape` for every visitor-controlled string, has detail/status/delete actions, and the old direct template-string interpolation script is absent.

- [ ] **Step 6: Implement the contact SPA view**

Render stats and table through Layui, map its page/limit values to page/size, escape name/email/subject/message, display details in a modal, update status, and batch delete with confirmation and busy-state guards. Remove the orphan Thymeleaf template and script.

- [ ] **Step 7: Run contact controller and template tests**

Expected: both classes pass.

### Task 6: 中文工作台聚合接口与视图

**Files:**
- Create: `wesite-admin/src/main/java/info/wesite/admin/controller/DashboardController.java`
- Create: `wesite-admin/src/main/java/info/wesite/admin/view/DashboardSummaryView.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/controller/DashboardControllerTest.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/view/AdminDashboardTemplateTest.java`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/index.html`
- Delete: `wesite-admin/src/main/resources/templates/admin/dashboard.html`
- Delete: `wesite-admin/src/main/java/info/wesite/admin/controller/AdminController.java`

**Interfaces:**
- Produces: `GET /dashboard/summary` with numeric counts and bounded recent-item lists.
- Consumes: existing user, TLD, SLD, blog and contact services.

- [ ] **Step 1: Write a failing dashboard controller test**

Mock all five services and assert the returned summary contains user/TLD/SLD counts, draft/published counts, pending contacts, and at most five recent articles/messages ordered newest first.

- [ ] **Step 2: Run dashboard test and verify RED**

Run:

```powershell
mvn -pl wesite-admin -am -Dtest=DashboardControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation failure because the controller and view do not exist.

- [ ] **Step 3: Implement the summary DTO and controller**

Use immutable records for summary and recent rows. Query each count with service `count` and query only five rows for each recent list. Return no entity with sensitive fields.

- [ ] **Step 4: Run dashboard controller test and verify GREEN**

Expected: pass.

- [ ] **Step 5: Write and run a failing dashboard template test**

Assert the SPA dashboard loads `/dashboard/summary`, includes all metric labels and real quick links, escapes recent-row text, and contains no `Welcome` or demo JSON URL.

- [ ] **Step 6: Build the dashboard view**

Implement responsive metric cards, pending-work panel, recent articles/messages and quick actions. Show a retry action when summary loading fails. Delete the orphan Thymeleaf dashboard and controller mappings.

- [ ] **Step 7: Run dashboard controller and template tests**

Expected: both classes pass.

### Task 7: 博客页面一致性与全局回归

**Files:**
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/blog/list.html`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/views/blog/edit.html`
- Modify: `wesite-admin/src/test/java/info/wesite/admin/view/BlogAdminTemplateTest.java`
- Modify: `wesite-admin/src/test/java/info/wesite/admin/config/AdminSecurityConfigurationTest.java`

**Interfaces:**
- Keeps: existing `/blog/list`, `/blog/detail`, `/blog/save`, `/blog/preview`, `/blog/publish`, `/blog/unpublish` contracts.

- [ ] **Step 1: Extend the existing blog template test and verify RED**

Add assertions for Chinese UTF-8 text, consistent busy-state handling, escaped dynamic text, public URL opened with `noopener,noreferrer`, and absence of mojibake replacement sequences.

- [ ] **Step 2: Normalize blog list/editor presentation**

Keep all editorial semantics unchanged. Fix text encoding, align filters/buttons/status tags with other pages, preserve preview-before-publish messaging, and keep duplicate-submit protection.

- [ ] **Step 3: Run focused admin tests**

Run:

```powershell
mvn -pl wesite-admin -am -Dtest=MainControllerTest,AdminLoginTemplateTest,AdminNavigationTemplateTest,UserControllerTest,AdminUserTemplateTest,DomainControllerTest,AdminDomainTemplateTest,ContactControllerTest,AdminContactTemplateTest,DashboardControllerTest,AdminDashboardTemplateTest,BlogAdminTemplateTest,AdminInterceptorTest,AdminSecurityConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all focused tests pass with zero failures and zero errors.

- [ ] **Step 4: Run the full reactor test suite**

Run:

```powershell
mvn -pl wesite-admin -am test
```

Expected: reactor success; all `wesite-core` and `wesite-admin` tests pass.

- [ ] **Step 5: Inspect the final diff for scope and sensitive data**

Run:

```powershell
git diff --check
git status --short
rg -n "www\.baidu\.com|/website/|/listing/|url:\s*['\"]xxx|Welcome|secureKey|password" wesite-admin/src/main/resources wesite-admin/src/main/java
```

Expected: no whitespace errors; only intended admin files plus the previously existing development-configuration changes are present; no demo endpoints; password/secureKey appear only in legitimate authentication implementation and never in response DTOs/templates.

- [ ] **Step 6: Perform browser acceptance after starting the dev profile**

Start the admin app with its development configuration and validate: invalid login, valid login, direct protected route, all six menu destinations, user add/edit/delete, TLD edit, SLD add/edit, blog preview/publish/retract, contact detail/status/delete, logout, and narrow-screen sidebar. Record any environment dependency that prevents a scenario instead of silently skipping it.

