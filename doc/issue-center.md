# 问题中心

问题中心使用现有 MySQL，采集网站与管理后台的系统异常，在后台菜单“问题中心”中聚合查看与处理。

## 启用顺序

1. 在目标环境备份后应用 `doc/alter_issue_center.sql`。脚本只新建四张问题中心表。已有问题中心表的环境须在更新应用前执行 `doc/upgrade_issue_center_resolved_at.sql`（可重复执行），补充独立解决时间并从状态记录回填。
2. 部署 core、web、admin 对应的新构建；默认采集关闭，未迁移数据库时不会主动写入诊断表。
3. 给两端设置相同的 `WESITE_ENVIRONMENT`（例如 `production` 或 `staging`，使用各自独立数据库）。
4. 设置 `WESITE_DIAGNOSTICS_ENABLED=true` 并重启。后台会显示当前环境和采集开关状态。发布前检查 `/diagnostics/bootstrap.js` 返回的 enabled 配置。
5. 在测试环境验证一次异常采集、后台详情、状态保存及复发；生产不提供人为制造异常的接口。公开上报端点为 POST `/diagnostics/events`，不需要账户。

前置代理应保留 Host 并正确设置 X-Forwarded-Proto 与客户端地址。两端使用 Tomcat native forwarded-header 支持，必须只通过受信任代理访问后端，不信任外部直接提供的转发头。上报使用同源 Origin/Fetch-Metadata 校验；跨域被拒绝时先检查代理后端 scheme/host。代理也应限制该端点的 body 大小和速率。

没有执行生产数据库迁移或部署。关闭 `WESITE_DIAGNOSTICS_ENABLED` 后重启即可停采；回滚应用无需删除表。

## 数据与容量

- 环境/应用由服务端配置决定；浏览器不能覆盖这些字段。
- 默认单工作线程、500项队列；满队列直接丢弃，不切换到请求线程写库。SQL查询超时3秒，单次写入事务超时5秒，连接获取使用当前数据源连接池配置。
- 浏览器队列20项，每批10项，每分钟60事件，上报5秒超时，无无限重试。请求体最大16KB，服务器还限制每实例每IP的请求数和事件数。
- 记录异常类、自有代码调用位置、规范化路由、请求ID和时间；不保存异常消息、请求/响应正文、Cookie、凭据或查询字符串。
- 前端只接受允许的错误类型、部署脚本路径与后端路由模板。动态域名和用户ID不入库；第三方广告错误被过滤。
- 带服务端请求ID的前端失败只设置关联标记，同请求不重复计数。请求失败但无响应ID时作为独立前端问题。
- 并发写入通过数据库唯一键和事务锁完成；重复事件不重复计数，不重新打开已解决问题。
- 已解决问题的新发生会重新打开，已忽略问题维持忽略。保存时用版本号检查并发；发生冲突保留编辑草稿，刷新详情后重试。
- 采集是尽力而为；进程崩溃、离线、数据库不可用、队列满会丢失事件。浏览器报告先到而服务端记录丢失时，只保留关联receipt，不伪造完整服务端问题。

配置项（Spring properties）：

| 名称 | 默认值 |
| --- | --- |
| wesite.diagnostics.enabled | false |
| wesite.diagnostics.app | 两端分别为 web/admin |
| wesite.diagnostics.environment | WESITE_ENVIRONMENT，未设置为 local |
| wesite.diagnostics.queue-capacity | 500 |
| wesite.diagnostics.event-retention-days | 30 |
| wesite.diagnostics.issue-retention-days | 180，必须不少于明细保留期 |

管理员应用每分钟分批清理：每轮最多500条过期事件、500条receipt、100个过期问题；问题最近发生及最近人工处理都超过180天才删除。持续发生、近期处理的问题保留。大批积压需多轮清理；列表次数是累计次数，不等于当前保留的明细数。

## 实际覆盖入口

- HTTP过滤器：同步/异步异常、5xx、错误转发；不采集自身诊断端点。
- 网站全局异常处理：包括以 HTTP 200 返回的系统错误。
- 域名详情与首次查询：DNS刷新和WHOIS快照保存失败。
- 工具/API外层已记录error的异常：WHOIS/RDAP、SSL、域名分析、估值、评分、对比、报告、相关域名、即将过期列表、批量查询、反向IP；另接入公共API计量不可用异常。正常查询无结果、业务校验、权限及额度拒绝不主动采集。
- `RefreshDomainTask`、`DnsTask` 的逐项失败；定时任务顶层未捕获异常通过 scheduler ErrorHandler 记录。
- `DomainWatchTask` 的持久化/刷新异常；探测超时、域名过期等监控结果仍走原监控功能。
- `NotificationDeliveryTask` 的领取、发送、失败结果、恢复与状态持久化异常；不更改原有重试/取消规则。
- 公共页面模板及管理后台入口：fetch、XMLHttpRequest（含jQuery）、自有脚本error和unhandledrejection。只使用独立模板且未引入公共head的页面不自动覆盖；不保证捕获浏览器崩溃或所有已被业务代码消费的脚本异常。

## 本地验证

常规测试：

```sh
mvn -pl wesite-core,wesite-web,wesite-admin -am -Dtest=DiagnosticCaptureTest,DiagnosticIngestTest,IssueControllerTest,MainControllerDomainSearchTest,NotificationDeliveryTaskTest,DomainWatchTaskTest,AdminNavigationTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test wesite-core/src/test/js/diagnostics-runtime.test.js wesite-web/src/test/js/domain-search-runtime.test.js
```

真实数据库测试必须使用专用 `wesite_issue_center_test` 数据库，测试会删除其中的问题中心数据，不得指向业务数据库。`IssueStoreMySqlTest` 未提供 JDBC 参数时跳过：

```sh
mvn -pl wesite-core -Dtest=IssueStoreMySqlTest \
  -DissueTestJdbc='jdbc:mysql://127.0.0.1:13379/wesite_issue_center_test?useSSL=false&allowPublicKeyRetrieval=true' \
  -DissueTestUser=root test
```

浏览器测试使用安装了 Playwright 和 Chrome 的环境。可设置 `PLAYWRIGHT_MODULE` 为临时安装的 playwright 模块目录；默认按 Node 的标准模块规则加载。测试启动临时 localhost 服务，验证真实采集到临时数据库、关联、处理、复发、冲突、管理员权限和窄屏布局：

```sh
mvn -pl wesite-admin -am -Dtest=IssueBrowserTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DissueBrowser=true \
  -DissueTestJdbc='jdbc:mysql://127.0.0.1:13379/wesite_issue_center_test?useSSL=false&allowPublicKeyRetrieval=true' test
```

`IssueBrowserTest` 的故障接口仅在测试源码中，不进入正式构建。

审核补充：复发按事件发生时间判断。解决前发生但因队列延迟才入库的事件只更新次数，不重新打开；重复上报同样不重开。

复发比较事件发生时间与最近一次进入 resolved 的时间；补充备注或发布版本不改变该时间。异步请求在完成时采集最终响应状态。浏览器错误按脚本路径及行列位置聚合，避免同脚本不同错误混合；版本更新移动代码位置可能产生新问题。Java 错误按类与方法聚合，忽略行号变化。

问题中心无需配置发布版本。旧版数据库的版本字段仅为兼容保留，新采集写入空值，页面不再展示或要求填写版本；已有表无需迁移。
