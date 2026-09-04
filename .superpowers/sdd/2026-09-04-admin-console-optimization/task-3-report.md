# Task 3 Report: 用户管理 API 与页面闭环

## Status

完成。基于 `1488b9459b0b30874a2729245f5dd61c5792d046` 实现，未修改 `wesite-web`，保留 LayuiAdmin，未改变密码算法或现有认证模型，也未为后台新增用户生成默认密码。

## Implementation

- `UserController` 改为构造器注入，并保留 `POST /user/list`、`/user/detail`、`/user/save`、`/user/delete`。
- 列表始终附加 `USER_TYPE = PERSON`，关键词经 trim 后同时匹配名称和手机号。
- 列表与详情统一转换成 `AdminUserView`；该 record 只包含 `id`、`name`、`phoneNo`、`status`、`statusText`、`createTimeText`、`updateTimeText`。
- 新建用户只复制名称、标准化手机号和受支持状态，服务端生成 ID，强制 `TYPE_PERSON`，记录创建人和创建时间；不写入密码或 secure key。
- 编辑先读取现有记录并验证 `TYPE_PERSON`，只更新名称、标准化手机号、状态及更新审计字段；请求中的密码、secure key 和 user type 均被忽略。
- 详情、编辑、删除均拒绝管理员及其他非普通用户记录。
- 手机号 trim 后不能为空，并在保存前全局检查重复；编辑检查排除当前记录。
- 状态只接受 `User.STATUS_ACTIVE` / `User.STATUS_INACTIVE`。
- 用户列表、添加和编辑页面全部改用真实 `/user/*` API；编辑前请求详情，删除需要确认，保存和删除期间锁定操作按钮，成功后只 reload `LAY-user-manage`。
- 表格中的远端动态文本统一经过 `layui.util.escape`，编辑数据使用 `form.val` 赋值；提交 payload 仅含允许编辑字段。

## Files

- Created `wesite-admin/src/main/java/info/wesite/admin/view/AdminUserView.java`
- Created `wesite-admin/src/test/java/info/wesite/admin/controller/UserControllerTest.java`
- Created `wesite-admin/src/test/java/info/wesite/admin/view/AdminUserTemplateTest.java`
- Modified `wesite-admin/src/main/java/info/wesite/admin/controller/UserController.java`
- Modified `wesite-admin/src/main/resources/static/layuiadmin/views/person/list.html`
- Modified `wesite-admin/src/main/resources/static/layuiadmin/views/person/add.html`
- Modified `wesite-admin/src/main/resources/static/layuiadmin/views/person/edit.html`

## TDD Evidence

### Controller RED

Command:

```powershell
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\Users\Yuz\.m2\repository'
mvn -pl wesite-admin -am '-Dtest=UserControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: `Tests run: 8, Failures: 8, Errors: 0`. Failures showed missing constructor injection, null name/status writes, entity responses instead of safe DTOs, administrator deletion, missing duplicate-phone rejection, and unsupported-status persistence.

### Controller GREEN

Same command after the minimal controller/DTO implementation.

Result: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`; reactor `BUILD SUCCESS`.

### Template RED

Command:

```powershell
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\Users\Yuz\.m2\repository'
mvn -pl wesite-admin -am '-Dtest=AdminUserTemplateTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: `Tests run: 4, Failures: 4, Errors: 0`. Failures covered real user routes, editable field semantics, escaping/value assignment, and pending-action locking/placeholder removal.

### Controller + Template GREEN

Command:

```powershell
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\Users\Yuz\.m2\repository'
mvn -pl wesite-admin -am '-Dtest=UserControllerTest,AdminUserTemplateTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`; reactor `BUILD SUCCESS`.

## Full Verification

Command:

```powershell
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\Users\Yuz\.m2\repository'
mvn -pl wesite-admin -am test
```

Result: reactor `BUILD SUCCESS`; `wesite-core` 56 tests and `wesite-admin` 55 tests passed, totaling 111 tests with zero failures, errors, or skips.

Additional checks:

- Extracted the executable script from `person/list.html` and ran `node --check -`: exit 0.
- `git diff --check`: no whitespace errors.
- Search confirmed the three user templates contain none of `/website/`, `monitor/`, fake upload paths, sensitive field names, or placeholder endpoints.
- `git diff --name-only -- wesite-web`: empty.

## Self-review

- Ordinary-user boundary is enforced in list, detail, edit, and delete paths; administrator request fields cannot change record type or credentials.
- API output has an explicit seven-field allowlist, so persistence-entity growth cannot accidentally expose credential fields.
- Page rendering does not interpolate remote data into HTML templates; table values are escaped and form values are assigned through DOM/form APIs.
- Repeated form-handler registration was avoided by registering handlers once in the list view; pending requests lock their initiating controls and restore them in `complete` callbacks.
- No cosmetic snapshot assertions were added; tests cover routes, fields, safety boundaries, escaping, and removal of known broken endpoints.

## Concerns

- No blocking concerns. Duplicate-phone validation is enforced at the application layer as requested; concurrent writes still depend on the database schema's uniqueness constraint for race-proof enforcement, and adding a schema migration was outside this task's scope.
- The full suite emits pre-existing informational/warning logs for the deliberately missing Bean Validation provider and simulated readiness/blog failure cases, but all assertions pass.
