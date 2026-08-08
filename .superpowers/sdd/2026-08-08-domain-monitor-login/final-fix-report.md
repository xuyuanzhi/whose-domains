# 域名监控登录最终集中修复报告

## 范围与根因

修复基线：`13952b8271875e9fc9c5b2c807aa18f4ad0b149f`。

本轮只修改最终审查清单涉及的生产代码、对应测试与本报告。根因如下：

1. `DomainWatchController.watchDomain()` 把已存在且 active 的记录作为业务失败返回；此外，`WEB_DOMAIN_WATCH` 的 `(USER_ID, DOMAIN_NAME)` 唯一键使两个同时通过首次查询的请求仍可能在 `save()` 处发生重复键异常。
2. 自动续接仅由 `monitorContinuationStarted` 防止自身重复执行，手动点击入口没有复用同一个进行中 Promise，因此 pending session 尚未返回时可启动第二条请求链。
3. `checkMonitoringState()` 把 watched 以外的所有结果都压成 `false`，HTTP、业务、JSON 与网络失败会被误判为 not-watched 并继续 POST。
4. `UserController.googleBindingFailure()` 不读取当前 session 的 `PendingGoogleBinding.returnTo()`，所有邮件确认绑定异常都固定返回 Watchlist。
5. Monitor 弹窗缺少 dialog 语义、持久输入名称、Escape 关闭和关闭后的焦点恢复；说明文案只描述 Email。

## TDD：RED 与 mutation 证据

- 首轮 Node RED：`node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js`
  - 31 项中 26 通过、5 失败。
  - 失败分别捕获：pending 与重复点击未共享 in-flight、失败未释放 busy/不可重试、check 普通错误错误落入 POST、check 认证错误错误落入 POST、关闭未恢复焦点/Escape 无处理。
- 首轮 Java RED：
  - `mvn -pl wesite-web -am '-Dtest=DomainWatchControllerTest,UserControllerGoogleBindingTest,DomainDetailMonitorUiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
  - 28 项中 3 失败：active 重复 POST 返回 400、合法 Monitor binding 异常固定返回 Watchlist、弹窗缺少可访问性契约。
- 并发幂等 mutation RED：
  - `mvn -pl wesite-web -am '-Dtest=DomainWatchControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
  - 2 项中新增并发碰撞用例以 `DuplicateKeyException` ERROR，证明仅修改 active 分支不足以覆盖同时插入。
- 测试 mutation 自审：
  - 把 active 分支改回 failure，会使重复 POST 用例失败。
  - 删除重复键后二次查询，会使并发碰撞用例报错。
  - 删除 `watchInFlight`、`finally` 或把任一 check 失败改回 `not-watched`，延迟 Promise、释放/重试和 no-POST 断言会失败。
  - 忽略 pending returnTo 或放宽为外部/普通路径，合法 Monitor、恶意/普通 target 与无 session MVC 断言会失败。
  - 删除 dialog 属性、Email 名称、Escape 或焦点恢复，相应模板/真实生产脚本运行时断言会失败。

## GREEN 与最终验证

- `node --test wesite-web/src/test/js/domain-detail-monitor-runtime.test.js`：31/31 通过。
- `node --test wesite-web/src/test/js/auth-modal-runtime.test.js`：19/19 通过。
- `node --test wesite-web/src/test/js/domain-watch-runtime.test.js`：11/11 通过。
- 三组 Node 合计：61 项，0 failure/cancel/skip。
- 受影响 Java：
  - `mvn -pl wesite-web -am '-Dtest=DomainWatchControllerTest,UserControllerGoogleBindingTest,DomainDetailMonitorUiTest,ReturnTargetServiceTest,LoginControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
  - 77 项，0 failure/error/skip。
- 完整 reactor：`mvn -pl wesite-web -am test`
  - `wesite-core` 5 项，`wesite-web` 261 项，共 266 项，0 failure/error/skip，BUILD SUCCESS。
  - Testcontainers 的 MySQL 并发测试实际启动并通过。

## 生产变更

- `/api/domain-watch/watch` 对 active existing 返回 `ResponseJson.success(..., existing)`；并在唯一键竞争时仅当二次查询确认 active winner 才收敛为幂等成功，其他完整性异常继续抛出。
- 自动续接与手动点击共用 `watchInFlight`；进行中禁用按钮并设置 `aria-busy=true`，在 `finally` 中释放，Monitoring 状态继续保持 disabled。
- check 返回 `watched`、`not-watched`、`failed` 三态；只有严格的 HTTP 成功、业务成功且 `data === false` 才允许 POST；认证错误打开登录弹窗，其他错误显示可见消息并停止。
- Google 邮件确认异常从当前 session 读取类型安全的 `PendingGoogleBinding`，仅对 `ReturnTargetService` 验证过的 domain monitor continuation 附加白名单错误码；恶意、普通或缺失 target 保持固定 Watchlist 回跳。
- Monitor 弹窗增加 dialog/标题/描述关联、Email 可访问名称、Google 禁用时 Email 初始焦点、Escape/关闭/遮罩后的触发按钮焦点恢复，并更新为同时适用于 Google 与 Email 的说明文案。

## 自审

- Important 1：覆盖顺序重复 POST、唯一键并发碰撞以及真实前端 existing 成功响应；失败弹窗不会打开。
- Important 2：延迟 session Promise 下多次点击只产生一次 session/check/watch；普通失败、认证失败和成功均经过 `finally`，失败后可重试。
- Important 3：HTTP、业务、JSON、网络及认证失败均断言不 POST；严格 false 是唯一添加分支。
- Important 4：六个 Google 错误码全部覆盖合法 Monitor 回跳；外部 target、普通内部 target、无 session 保持固定安全回跳。
- Minor：静态模板契约与生产脚本运行时共同覆盖 dialog 语义、通用文案、Google 禁用焦点回退、Email callback 对称性、continuation 防重、Escape 与焦点恢复。
- 未修改审查清单外的生产文件。

## 残余风险

- 本轮未执行真实浏览器、真实 Google OAuth 或真实邮件投递人工验收；覆盖来自直接执行生产脚本的 Node 测试及 MVC/服务边界测试。
- 域名监控唯一键竞争通过 Spring `DuplicateKeyException` 语义做单元模拟；完整 Maven 中实际 MySQL 并发套件通过，但没有为 Domain Watch 另建专用 MySQL 并发集成测试。
- Maven 测试仍输出项目既有的 Bean Validation provider INFO、预期安全失败 WARN/ERROR；最终结果为 0 failure/error/skip。
