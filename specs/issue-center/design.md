# 问题中心技术设计（已审核，按用户授权继续实施）

## 1. 实施基础

需求见已确认的 `requirements.md`。保留 Spring Boot 3 / Java 17、MyBatis Plus、MySQL、Thymeleaf 和 Layui；采集及管理逻辑共用 `wesite-core`，分别在 `wesite-web` 和 `wesite-admin` 接入。不增加外部监控服务。

已核实：
- Web 的 `GlobalExceptionHandler` 已覆盖控制器异常，但 `/api/` 异常会返回 HTTP 200 和业务失败码，不能仅监听 HTTP 5xx。
- Web 存在 `/500` 错误转发，需要保留原请求路径并避免重复采集。
- 前台 `template.html` 已包装 fetch 注入页面令牌，新增采集必须兼容它。
- 后台采用 `AdminInterceptor` 验证管理员、Layui 菜单和 `ResponseJson` 响应。
- `BaseEntity.status` 是启用/禁用语义，问题实体独立定义处理状态，不复用该字段语义。

## 2. 模块职责

| 模块 | 职责 |
| --- | --- |
| wesite-core diagnostics 包 | 配置、事件 DTO、脱敏与路径规范化、指纹、有限队列、事务写入、请求采集过滤器、公共浏览器脚本 |
| wesite-web | 注册请求采集、全局异常及已捕获系统错误接入、公开上报接口、模板脚本、监控与通知任务接入 |
| wesite-admin | 注册请求采集与上报接口、问题管理 API、菜单与页面、域名/DNS 任务接入、数据保留清理 |
| doc/alter_issue_center.sql | 增量建表和索引，不改业务表；配套上线与回退说明 |

共享脚本作为 core classpath 静态资源发布，两端在业务脚本前加载；启用配置由各自服务端生成，不把环境或应用归属交给浏览器决定。

## 3. 服务端与任务采集

### HTTP 请求

新增过滤器为请求生成服务端 UUID，写入请求属性和 `X-Request-ID` 响应头，不信任客户端指定的 ID。过滤器保存安全元数据，不缓存请求/响应正文。

异常处理器调用 recorder 记录异常类型和栈帧；过滤器在响应完成时补获未显式记录的 5xx。通过请求属性避免同一异常在异常处理器、过滤器及 ERROR 转发中重复入队；异步请求在完成回调中采集。路由优先取 Spring 匹配模板，错误转发沿用原路由，未知路径使用固定 `unmatched`，不落盘原始路径。

显式捕获并返回 HTTP 200 的系统异常在 catch 中调用 recorder；不扫描所有 `ResponseJson.failure`，以免把正常业务拒绝记录为系统问题。公开响应不增加异常原文或调用栈。

### 首版任务接入清单

- admin `RefreshDomainTask.refreshDoamins`：刷新任务入口和逐项异常。
- admin `DnsTask.refreshDomainDns` / `refreshSiteDns`：任务入口和工作线程异常。
- web `DomainWatchTask.checkDomainExpiry`：任务入口、领取/持久化等系统异常。域名正常过期、DNS 无记录、远端探测超时等监控结果仍属于监控业务，不统一记录为系统故障。
- web `NotificationDeliveryTask`：即时、每日/每周摘要、恢复重试入口，以及投递、领取、最终状态持久化失败。主动取消、无待发数据、正常租约竞争不采集。
- 请求路径中的主域名/子域名 DNS 刷新 catch 同步接入。

同一失败向上抛出时复用事件标识；每次独立的投递重试失败是新的发生。所有任务名称使用固定标识，不携带用户、邮箱或域名。实现时逐项列出实际改动的 catch，新增测试确保覆盖。

## 4. 浏览器采集与关联

监听 `error`、`unhandledrejection`，仅接收能定位到自有脚本的异常；忽略无位置的跨域 `Script error.`。异常正文不上传，提取错误类型、自有脚本路径和有限的行列/栈帧；第三方脚本和广告错误过滤。

包装当前 fetch 与 XMLHttpRequest，覆盖 jQuery 请求。保持响应、异常、请求头和调用行为；不读取业务响应体。只采集同源 5xx、网络失败和显式系统错误标记；主动 AbortError 不作为故障。HTTP 200 内的系统异常由服务端记录。采集 URL 排除自身上报端点。

同源故障响应携带 `X-Request-ID` 时，浏览器按该 ID 上报；无响应时使用独立客户端事件 UUID。前端路由只能提交固定路由标识，服务端再次映射验证，不能提交任意 URL。浏览器声称的异常、来源与请求 ID 均视为不可信；不能覆盖服务端已记录的路径、类型或栈。

默认队列最多 20 项，每批 10 项，每分钟最多 60 项，传输超时 5 秒，不无限重试。队列满或采集失败直接丢弃；不改变原业务请求的成败。

## 5. 数据模型与事务

使用四张独立表，ID 使用 UUID；时间以 UTC 存储、页面按本地时间展示。

| 表 | 主要字段与约束 |
| --- | --- |
| WEB_SYSTEM_ISSUE | id、app、environment、source、fingerprint、summary、route/task、exception_type、status、occurrence_count、first_seen_at、last_seen_at、last_release、resolved_release、version；唯一(app, environment, fingerprint) |
| WEB_SYSTEM_ISSUE_EVENT | id、issue_id、app、environment、occurrence_key、route、method、http_status、exception_type、safe_frames、request_id、release_name、occurred_at（client_reported 从 receipt 联表读取）；唯一(app, environment, occurrence_key)，索引(issue_id, occurred_at, id) |
| WEB_SYSTEM_ISSUE_NOTE | id、issue_id、actor_id、旧/新状态、note、resolved_release、created_at；索引(issue_id, created_at, id) |
| WEB_SYSTEM_ISSUE_RECEIPT | app、environment、occurrence_key、client_reported、created_at；唯一(app, environment, occurrence_key)，用于串行化同一次发生的并发写入，保留期与事件一致 |

来源取 `server_error`、`client_error`、`request_failure`、`task_error`。摘要由固定模板、异常类和规范化位置生成，不保存原始 Throwable.message。指纹使用应用、环境、来源、路由/任务、异常类和首个自有调用位置；不含动态 ID 或版本。Java 调用位置忽略行号，浏览器位置保留行列号以区分同一脚本中的不同错误；跨版本移动浏览器代码位置可能产生新问题。复发使用独立 resolved_at，仅进入 resolved 状态时更新，备注编辑不改变复发界限。

每次写入在独立事务执行：建立/锁定 receipt → 读取事件 → 建立/锁定问题 → 更新事件和计数。同一次 occurrence 只增加一次计数。不同 occurrence 聚合至同一问题时使用原子自增。仅对明确的唯一键竞争、死锁进行有限重试，不吞其他数据库错误。

处理前后端乱序（审核修订）：同一次服务端请求只由服务端建立问题并计数。携带服务端请求 ID 的浏览器报告只在 receipt 中标记 client_reported，不建立临时问题、不改变指纹、状态或计数；服务端事件晚到时读取该标记。无服务端请求 ID 的网络失败/代理5xx才创建独立客户端问题。避免临时问题迁移导致累计次数、人工备注与历史首末时间失真。receipt 的 UUID 不是授权凭据，客户端只能设置“客户端也观察到失败”布尔值，不能修改任何权威字段；接口不返回已有记录信息。异步队列或数据库故障可能令仅有关联 receipt 而无问题，符合尽力采集约定。

浏览器自有脚本位置必须来自服务器发布资源清单（含HTML模板路径），不接受任意文件路径或栈字符串。服务端只存自有 Java 类/方法/文件/行号；SQL和异常消息不入库。

管理更新使用 version 乐观锁；并发状态变更返回冲突提示，要求刷新，避免管理员保存覆盖刚发生的复发。备注和状态变更在同一事务中保存，操作人来自管理员会话。

## 6. API 合同

所有业务 API 沿用 ResponseJson 外层。管理端校验复用 AdminInterceptor，读写接口均验证管理员身份。

| 接口 | 功能 |
| --- | --- |
| POST /diagnostics/events（两端） | 有限批量上报，成功返回 accepted 数；只允许白名单字段 |
| GET /admin/issues/list | page=1、size=20（最大100），status、app、source、from、to、keyword；返回分页结果和服务端 environment |
| GET /admin/issues/{id} | 问题摘要、处理状态和版本 |
| GET /admin/issues/{id}/events | 独立分页事件，时间/id 稳定排序 |
| GET /admin/issues/{id}/notes | 独立分页处理记录 |
| POST /admin/issues/{id}/status | status、note（最多1000字）、resolvedRelease（最多64字符）、version；事务保存 |

上报接口无需登录，兼容登录页故障；只开放采集能力，不允许读数据或修改问题。每请求最大16KB、最多10事件，每实例按来源 IP 限制每分钟60事件，并设有界全局队列；原始 IP 只用于内存限流。生产反向代理同样限制请求大小和速率。Origin/Fetch-Metadata 按同源策略校验，不开启跨域上报。客户端 requestId 必须格式有效，查找/补充限定同应用和环境；不存在的 ID 不能改动任何已有事件。

## 7. 后台界面规范

采用 ui-design 技能，按现有项目设计系统覆盖其默认字体、布局和框架建议。

- 用途：管理员快速发现高频异常，进入详情定位，再记录处理结果。
- 方向：工业工具型（Industrial/utilitarian），沿用 Layui 的紧凑列表和表单。
- 配色：白色 #FFFFFF、页面灰 #F2F2F2、正文 #333333、主操作绿 #009688、错误红 #FF5722；状态同时显示文字。
- 字体：继承现有 Layui 字体栈，不新增在线字体；本项目一致性优先于技能默认字体禁用规则。
- 布局：现有左侧导航新增“问题中心”，主区顶部标题/环境/刷新，中部筛选栏，下方全宽分页表格；右侧详情弹层（窄屏全宽），避免另建仪表盘。
- 列表：摘要与位置、应用/来源、状态、次数、首次/最近时间。默认待处理，最近发生倒序；可切换全部。
- 详情：摘要、错误位置与请求 ID、事件分页、状态/修复版本/备注表单、处理记录分页。
- 状态：加载、空结果、请求失败重试、登录失效、保存中、并发冲突均有明确反馈。输出以文本转义，栈帧可换行，禁止原始 HTML 注入。
- 本阶段仅定义界面，不编写页面代码；实现沿用 web-development 技能与现有 Layui 组件。

## 8. 隔离、容量与保留

配置前缀 `wesite.diagnostics`：enabled、environment、release、queue-capacity（默认500）、event-retention-days（30）、issue-retention-days（180），以及采集/数据库操作超时与限流设置。测试、正式沿用独立数据库；应用标签服务端固定为 web/admin。独立有限线程池写入，禁止队列满时在业务线程同步执行；数据库调用设置有限超时。

采集失败通过有节流的本地日志记录，不再次进入诊断。采集是尽力而为：进程退出、数据库不可用、队列满、浏览器离线时可能丢失，不承诺数据库故障期间仍可落库。

清理仅在 admin 调度，以批次删除30天前事件及 receipt；删除最近发生和最近管理操作均超过180天的问题及备注。持续复发或近期处理的问题保留；统计次数为摘要生命周期累计次数，明细为保留期内样本，UI 明确区分。清理与写入遵守一致锁顺序。

## 9. 验证与发布

- 单元测试：脱敏、路由规范化、指纹稳定、第三方过滤、容量限制、采集不递归。
- MySQL 集成测试：真实唯一索引、并发计数、前后端乱序归并、重复上报、复发、状态竞争与保留清理；不能以 mock 测试替代这些验证。
- MVC 测试：控制器异常、HTTP 200 包装系统错误、过滤器5xx、错误转发、上报大小及字段验证、后台权限。
- JS 测试：fetch/XHR 行为保持、网络失败、主动取消、5xx、错误监听、自身排除。
- 浏览器联调：从模拟域名查询故障到后台详情、处理及复发；不把故障注入接口部署到生产。桌面/窄屏验证列表、详情、分页和错误反馈。
- 保留并重跑已有域名查询500回归测试；完成各模块必要构建与检查。

交付迁移脚本和上线说明。先迁移，再部署关闭采集的应用，核对配置后启用并冒烟；回退可先关闭采集，保留数据表。此阶段不执行生产迁移或部署。

## 10. 下一阶段

用户授权“审核文档，如果没问题继续”。已自行审核并修正乱序归并、客户端信任边界、保留与并发规则，继续任务拆解与实现，不重复请求阶段确认。

审核补充：复发按事件发生时间判断。解决前发生但因队列延迟才入库的事件只更新次数，不重新打开；重复上报同样不重开。
