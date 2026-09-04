# Task 5 实施报告：联系消息整合与 XSS 防护

## 状态

已完成。变更基于 `d0147cccbcf06b03995507aec56161af7501acac`，产品代码范围仅包含 `wesite-admin`；未修改 `wesite-web`。

## 实现摘要

- `ContactController` 改为 `@RestController` 和构造器注入，移除旧 Thymeleaf 页面映射。
- 保留并规范化简报指定接口：`GET /admin/contacts/list`、`GET /admin/contacts/{id}`、`POST /admin/contacts/status`、`DELETE /admin/contacts/delete`、`GET /admin/contacts/stats`。
- 状态更新只接受 JSON `{id,status}`，其中 `BaseEntity.STATUS_ACTIVE` 表示待处理、`STATUS_INACTIVE` 表示已处理；空 ID、未知 ID 和非法状态均返回中文失败响应。
- 删除接口拒绝空/空白 ID 集合，并通过 DELETE 请求调用 `removeByIds`。
- 统计真实查询 total、pending、processed；pending/processed 分别使用 active/inactive 状态条件。
- 新增 `contact/list.html` Layui SPA：顶部展示三项真实统计，表格提供详情、状态切换、单条删除和批量删除。
- Layui 分页参数通过 `request.pageName/limitName` 映射为后端 `page/size`，并从 `IPage.records/total` 适配表格数据。
- name、email、subject、message 以及表格中其他动态文本均经 `layui.util.escape`；详情弹窗从静态 DOM 克隆，并以转义后的值写入固定节点，不拼接访客内容生成弹窗 HTML。
- 状态和删除操作具有 busy 锁；批量删除有确认流程；详情请求使用递增序列，旧响应不能覆盖或打开晚于最新请求的弹窗。
- 删除旧 `templates/admin/contacts.html` 和 `static/js/admin-contacts.js`。

## TDD 证据

### 控制器 RED → GREEN

- RED：旧实现下运行 9 个初始测试，9 failures / 0 errors。失败命中缺少构造器、英文响应、空 ID 未校验、旧状态路由、非法状态未拒绝、pending 固定为零及缺失 processed 统计。
- GREEN：最终控制器测试 10 tests，0 failures / 0 errors / 0 skipped。

```powershell
mvn -pl wesite-admin -am -Dtest=ContactControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### 模板 RED → GREEN

- RED：8 tests，1 failure / 7 errors。新 SPA 资源尚不存在，旧 Thymeleaf 模板和旧脚本仍存在。
- GREEN：8 tests，0 failures / 0 errors / 0 skipped。
- 其中 4 个 Node 行为探针实际验证分页参数映射、重复状态请求锁、旧详情响应隔离、批量删除仅发出一个真实 DELETE 请求；Node 不可用时这些探针以 JUnit assumption 显式 skip，4 个静态安全契约仍执行。

```powershell
mvn -pl wesite-admin -am -Dtest=AdminContactTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### 联合与完整验证

- 联合测试：18 tests，0 failures / 0 errors / 0 skipped；BUILD SUCCESS。
- 完整 reactor：`wesite-core` 56 tests、`wesite-admin` 102 tests，共 158 tests；0 failures / 0 errors / 0 skipped；BUILD SUCCESS。

```powershell
mvn -pl wesite-admin -am -Dtest=ContactControllerTest,AdminContactTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl wesite-admin -am test
```

## 自审

- API：状态端点仅为 `POST /admin/contacts/status` JSON 契约；旧 `/{id}/status` 和服务端页面映射已移除。
- 鉴权：控制器没有放宽访问级别，`@RestController` 仍受全局 `AdminInterceptor` 默认 SESSION 级别保护，并使未授权响应保持 JSON 语义。
- 状态与统计：active/inactive 两种状态均有更新测试；统计测试解析实际 MyBatis 查询条件并验证两种状态常量，而非只检查返回数字。
- XSS：四个访客可控字段在表格和详情两个渲染面均有静态安全契约；requestIp、时间和服务端反馈也走转义或数值收敛路径。
- 并发：状态重复点击、批量删除重复触发和详情乱序响应均由 Node 探针执行验证。
- HTTP：DELETE 的前端运行时方法和后端 `@DeleteMapping` 均被测试覆盖。
- 范围：`git diff --name-only` 未发现任何 `wesite-web` 变更；`git diff --check` 无空白错误。

## Concerns

无功能性遗留 concern。完整测试输出仍有仓库既有的 Bean Validation provider INFO、JVM CDS warning 和博客错误路径测试日志；所有测试均通过，且这些噪声与本任务无关。

## Fix round 1（2026-09-05）

### 审查问题与根因

- `AdminController` 的 `/admin` 和 `/admin/dashboard` 仍会渲染旧 `templates/admin/dashboard.html`，但 Task 5 已移除 `GET /admin/contacts`，导致旧 dashboard 的联系管理快捷入口成为死链。
- 同一模板仍读取已从统计契约移除的 `data.today`，同时没有把 `data.pending` 写入页面，也没有 processed 展示位。
- 根因是 Task 5 只清理了旧联系消息页面与脚本，没有把仍存活的 dashboard 消费方纳入联系统计和导航契约。

### 修复内容

- `wesite-admin/src/main/resources/templates/admin/dashboard.html`
  - 联系管理入口由 `/admin/contacts` 改为 SPA hash 入口 `/#/contact/list`。
  - 三项联系统计统一为 total、pending、processed，标签、DOM ID 和脚本赋值保持一致。
  - 移除 `data.today` 和旧 `todaysContacts`/`pendingReviews` 引用；未重构旧 dashboard 视觉，留待 Task 6 正式替换。
- `wesite-admin/src/test/java/info/wesite/admin/view/AdminContactTemplateTest.java`
  - 增加 SPA 链接可达性契约，防止恢复 `/admin/contacts` 死链。
  - 增加静态统计消费契约，确保三个 DOM 节点存在、pending/processed 被读取且 today 不再出现。
  - 增加 Node 运行时探针，以 `{total:9,pending:4,processed:5,today:99}` 响应验证页面实际只写入当前三项统计。Node 不可用时该探针显式 skip，前述静态契约仍运行。

### RED / GREEN 证据

```powershell
mvn -pl wesite-admin -am -Dtest=AdminContactTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- RED：11 tests，3 failures / 0 errors。失败分别命中缺失 `/#/contact/list`、缺失 pending/processed DOM，以及运行时仍写入 `{totalContacts:9,todaysContacts:99}`。

```powershell
mvn -pl wesite-admin -am -Dtest=ContactControllerTest,AdminContactTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- GREEN：21 tests，0 failures / 0 errors / 0 skipped；BUILD SUCCESS。

```powershell
mvn -pl wesite-admin -am test
```

- 完整 reactor：`wesite-core` 56 tests、`wesite-admin` 105 tests，共 161 tests；0 failures / 0 errors / 0 skipped；BUILD SUCCESS。

### Fix round 1 自审与 concerns

- 修复仅触及仍存活的旧 dashboard 模板和联系模板契约测试；没有恢复已删除的控制器页面映射，也没有修改联系 API 或 `wesite-web`。
- DOM、静态脚本字段和实际运行时赋值三层契约均覆盖；错误链接、旧 today 字段、缺失 pending/processed 任一回归都会失败。
- Task 6 计划正式移除旧 dashboard；本轮只保证其存活期间与 Task 5 的 SPA 和统计契约兼容，无新增功能性 concern。
