# Task 4 实施报告：顶级域名与二级保留域名页面修复

## 状态

已完成。变更基于 `60ae16272548515b924dfd8ecf3f6807b6c392b8`，范围仅包含 `wesite-admin` 及本报告；未修改 `wesite-web` 的公开 `/domain/*` 页面或 SEO。

## 实现摘要

- `DomainController` 改为构造器注入，并保留原有六个管理端 API 路径。
- TLD/SLD 的 ID、SLD 名称在校验前去除首尾空白；SLD 名称统一使用 `Locale.ROOT` 转为小写。
- 保留 SLD 重名检查和所属 TLD 存在性检查；新增记录强制默认为启用，编辑记录只接受启用/禁用状态。
- 所有控制器失败响应改为一致的中文提示，没有新增删除接口。
- 重做 TLD/SLD 管理模板：统一使用 `/domain/tld/*`、`/domain/sld/*`，修复 toolbar/table filter 与刷新目标。
- 表格单元格和服务端反馈动态文本均转义；详情值通过 `form.val` 写入表单，移除模板值插值。
- 提交按钮使用 busy 状态防止重复请求；编辑详情使用请求序列隔离旧响应；弹窗保存绑定发起弹窗的索引，避免并发弹窗错关。
- 移除 `/listing/*`、`LAY-user-manage`、`url: 'xxx'`、假上传端点和上传脚本；弹窗尺寸改为百分比响应式尺寸。

## TDD 证据

### 控制器 RED → GREEN

- RED：`DomainControllerTest` 10 个测试全部按预期失败，失败原因包括缺少构造器、英文错误信息、ID/名称未 trim、SLD 未规范化及非法编辑状态未拒绝。
- GREEN：同一命令运行 10 个测试，0 failure / 0 error。

命令：

```powershell
mvn -pl wesite-admin -am -Dtest=DomainControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### 模板 RED → GREEN

- RED：`AdminDomainTemplateTest` 8 个测试全部按预期失败，暴露错误端点、事件 ID、无动态转义/无 busy，以及弹窗索引和详情竞态问题。
- GREEN：同一命令运行 8 个测试，0 failure / 0 error。
- 动态反馈文本转义另做一次小循环：新增断言后 1 个预期失败，修复后 8 个全部通过。

命令：

```powershell
mvn -pl wesite-admin -am -Dtest=AdminDomainTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### 联合与完整验证

- 联合测试：18 tests，0 failure / 0 error。
- 完整 reactor：`wesite-core` 56 tests、`wesite-admin` 75 tests，共 131 tests，0 failure / 0 error；BUILD SUCCESS。

```powershell
mvn -pl wesite-admin -am -Dtest=DomainControllerTest,AdminDomainTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl wesite-admin -am test
```

## 自审

- API：仅保留简报指定的 list/detail/save 路径，未添加删除端点。
- 范围：`git status` 未显示任何 `wesite-web` 变更。
- 数据规则：SLD 标准化、重名、所属 TLD、新增默认状态、编辑状态白名单均有控制器测试。
- 前端安全与并发：API/事件 ID、转义、busy、弹窗索引和过期详情响应均有模板契约测试。
- 已知错误端点：模板契约确认 `/listing/*`、`LAY-user-manage`、`url: 'xxx'`、`res/json/upload` 和 U+FFFD 字符均不存在。

## Concerns

无功能性遗留 concern。完整测试输出仍包含仓库已有的 Bean Validation provider 提示及故意触发的错误日志，但所有测试均通过，且与本任务变更无关。

## Fix round 1（2026-09-05）

### 修复内容与文件

- `wesite-admin/src/main/java/info/wesite/admin/controller/DomainController.java`
  - TLD 保存增加 active/inactive 白名单校验，并把状态写入实际传给 `updateById` 的持久化对象。
  - SLD 在 trim/lower 后校验总长度 3..10；要求至少两个非空标签，标签仅允许小写字母、数字和连字符，且首尾必须是字母或数字。
  - TLD 归属查询改为读取匹配列表：0 条返回“不存在”，多条返回“顶级域名数据重复，请先修复”；未使用 MySQL `LIMIT`。
- `wesite-admin/src/main/resources/static/layuiadmin/views/domain/sld/index.html`
  - 打开新增弹窗时递增 `editRequestSequence`，使已有 edit detail 请求失效。
- `wesite-admin/src/test/java/info/wesite/admin/controller/DomainControllerTest.java`
  - 新增 TLD 状态持久化/非法状态、SLD 非法格式/超长/合法 `com.cn`、重复 TLD 数据测试。
- `wesite-admin/src/test/java/info/wesite/admin/view/AdminDomainTemplateTest.java`
  - 新增 edit→add 后旧详情不得打开编辑弹窗的 Node 行为测试。
  - Node 行为探针执行前先检查 `node --version`；Node 不可用时仅跳过行为探针。路由、ID、转义、busy 和错误端点等静态高风险契约是独立测试，仍会执行。Task 7 将统一处理工具链审计。

### RED / GREEN 命令与关键输出

```powershell
mvn -pl wesite-admin -am -Dtest=DomainControllerTest,AdminDomainTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- RED：27 tests，8 failures，0 errors。失败分别命中 TLD 状态未写回/未校验、SLD 格式与长度未校验、重复 TLD 未受控处理，以及 edit→add 旧详情仍打开编辑弹窗。
- 首次 GREEN 验证：26 个行为断言通过；1 个测试因直接解析 MyBatis Lambda SQL 且无元数据缓存而报错。移除该方言实现细节断言，保留重复数据的真实响应行为契约。
- GREEN：27 tests，0 failures，0 errors，0 skipped；BUILD SUCCESS。

```powershell
mvn -pl wesite-admin -am test
```

- 完整 reactor：`wesite-core` 56 tests、`wesite-admin` 84 tests，共 140 tests；0 failures，0 errors，0 skipped；BUILD SUCCESS。

### Fix round 1 自审

- 格式边界：`.cn`、`foo..cn`、`foo_1.cn`、11 字符名称均被拒绝；`com.cn` 通过；同一校验位于新增/编辑分支之前。
- 状态边界：TLD 与 SLD 编辑均只接受 active/inactive；测试验证 TLD 实际更新对象的状态。
- 数据异常：TLD 重复记录转为中文可操作错误，不再依赖 `getOne` 或方言限定语句。
- 并发边界：新增弹窗可废弃在途 SLD 编辑详情，且原有 edit→edit、弹窗索引和 busy 行为测试保持通过。
- CI 边界：无 Node 环境仅跳过 5 个 Node 行为探针；4 个静态模板契约不依赖 Node，仍强制执行。
- 范围：未修改数据库 schema、未新增删除 API、未修改 `wesite-web`。
- Concerns：Node 缺失时无法执行浏览器脚本行为探针，此限制已通过 JUnit assumption 显式呈现，静态高风险契约仍提供基础保护。
