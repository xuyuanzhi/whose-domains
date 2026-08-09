# Whose.Domains 留存通知中心终审集中修复报告

## 结论与范围

- 终审输入基线：`c85bf9334ae568e309f8b1551cb43afb0dbe57ae`
- 实现提交：`b21a092`（`fix: harden retention notification center`）
- 工作范围：仅修改 `retention-notification-center` 工作树；未创建额外分支或工作树；未扩展产品范围。
- 结果：CRITICAL、IMPORTANT 1–13 及 MINOR 指定项均已落实到实现、迁移、验证器、README 和回归测试。
- 交付模型：站内通知与邮件投递继续采用至少一次语义；已经被发送工作者 CLAIM 的 SMTP 尝试是明确且文档化的不可召回边界。

## CRITICAL：旧 DomainWatch 通知契约兼容

1. **watch 级优先级与旧字段生效**
   - `NotificationDispatcher` 在事务内锁定并重读当前 `WEB_DOMAIN_WATCH`，不再信任扫描阶段的陈旧 watch 副本。
   - `NOTIFY_NONE` 永久禁止该 watch 的邮件路由；仍可保留站内记录。watch 设置变更或取消监控会取消尚未发送的旧邮件工作。
   - `NOTIFY_7_DAYS` / `NOTIFY_30_DAYS` 只对相应精确到期阈值生成邮件候选，1 日阈值不会被错误扩大为邮件。
   - 现有 `NOTIFY_EMAIL` 是唯一邮件收件来源；服务端和浏览器使用一致的长度、语法、去空白和换行注入校验。没有任何 `SYS_USER.EMAIL` 回退。

2. **全局偏好覆盖与取消语义**
   - 全局 `IN_APP_ONLY` 覆盖 watch 邮件设置，并立即取消 `QUEUED` / `FAILED` 的未发送通知或批次。
   - 已 `CLAIMED` 的 SMTP 尝试作为安全边界：若邮件已成功交给 SMTP，则记录 `SENT`；若失败或 lease 过期，则终态化为 `CANCELLED`，不再重试。
   - 偏好 GET 与 `NotificationPreferenceResolver` 使用同一默认契约：缺省为 DAILY 摘要，高/严重风险即时；显式的分类和全局关闭均优先。

3. **冻结收件人、UI/API 和编辑持久化**
   - 收件人在通知入队/批次创建时冻结；即时投递、摘要、重试均读取冻结值，不在发送时重新解析账号或 watch 邮箱。
   - 批次唯一键包含冻结收件人，避免同一用户不同 watch 邮箱被错误合并。
   - 新建、重新启用、编辑接口均真正保存或清空 `NOTIFY_EMAIL`；编辑路由/邮箱时取消旧的未发送邮件工作。
   - watch 列表和全局通知设置页明确展示“watch 门控优先、全局偏好再覆盖”的一致规则。

4. **旧数据迁移**
   - 新增从真实 `e73ff4d` 四表通知基线到当前结构的完整增量 SQL。
   - 精确的 `e73ff4d` fixture 不虚构 `NOTIFY_EMAIL`；另有一个仅增加该历史运营字段的兼容 fixture。
   - 无旧邮箱字段时不扩大收件人，旧邮件工作安全转为站内/取消；有字段时仅保留通过校验的 watch 邮箱，非法类型或地址 fail closed。
   - 控制器、dispatcher、偏好、SQL PathB、精确 legacy 和 legacy-with-email 均有回归覆盖。

## IMPORTANT 逐条映射

### 1. Episode 幂等与 canonical 唯一性

- `MonitorFingerprint` 改为长度前缀的规范字段编码，消除使用管道字符拼接时的歧义。
- episode 键包含前一快照锚点/事件边界；同一 scan 重放仍命中同一幂等键，而 `DOWN → RECOVERED → DOWN` 及 `A → B → A → B` 会生成新的 episode 事件。
- 测试覆盖管道歧义、同 scan 重放、恢复后复发和重复状态往返。

### 2. 首次部分成功的 source-aware baseline

- 首次 `anySucceeded` 会写 active 基线，但只标记成功来源为已观测；失败来源保持未观测。
- 之后成功的其他来源可以与自身首次有效观测建立基线，不会被先前部分成功吞掉变化。
- 单元测试覆盖首次部分成功和后续其余来源恢复。

### 3. 到期/SSL 跨阈值

- 阈值计算分别使用 `previousExpiry + previousCheckedAt` 与 `currentExpiry + currentCheckedAt`，不再拿当前检查日套两份日期。
- 覆盖日期缩短、延长、跨 30/7/1 日阈值以及 previous/current 为 null 的行为。

### 4. 网站可用性观测和 GET fallback

- DNS 失败、连接拒绝、连接/读取 timeout 及网络 I/O 失败现在都是 availability observation，可进入连续失败累计。
- 解析器自身的内部执行/配置故障仍单独分类为 collector error，不伪造目标不可用。
- HEAD 返回 405/501 时执行一次有界 GET fallback，复用超时/大小边界。
- 测试分别覆盖 DNS、refused、timeout、内部解析错误和两种 fallback 状态码。

### 5. DomainWatchTask 多实例互斥

- 为 watch 增加数据库 claim token、到期时间和 CAS 更新；过期 claim 可恢复。
- 成功 claim 后重新加载 watch，再判断是否仍到期，避免两个工作者持有陈旧分页副本造成重复扫描。
- 最新 snapshot 查询增加 `ID DESC` tie-break。
- MySQL 并发 fixture 证明两个工作者对同一 watch 只有一个赢得 claim，并覆盖过期恢复。

### 6. 删除与 CLAIMED/FAILED 批次

- 删除通知只隐藏用户的站内视图；已经固定到 delivery batch 的 member 查询不受删除标记影响。
- 预分配仍排除已删除通知，固定成员则保持不可漂移。
- FAILED 可按策略重试；取消请求会使未 claim 工作立即取消，claim 中的工作在失败或 lease 过期时终态化。
- MySQL 并发测试覆盖双 worker 单摘要、全员删除后的固定成员、rollback、retry 和取消恢复。

### 7. 偏好读取、立即取消和收件人冻结

- 偏好 GET、resolver 默认完全一致。
- 切换 `IN_APP_ONLY` 同时取消通知级 `QUEUED/FAILED` 和批次级失败/未 claim 工作，并标记已 claim 边界。
- `DeliveryBatchClaim` 携带冻结收件人；重试不再重新读取 watch 或用户邮箱。
- 同一时间窗内不同冻结邮箱生成不同批次。

### 8. 摘要常数查询与 lease

- digest claim 先批量 `listByIds`，再按固定 member ID 顺序恢复通知，不再逐通知查询。
- 用户/冻结收件人候选以一次 join/keyset 查询取得，避免按用户或通知数量线性放大 SQL 次数。
- 发送 claim 使用 30 分钟安全 lease，恢复任务每 5 分钟运行；过期 claim 的取消语义可终态化。

### 9. 完整增量 SQL 与 verifier

- `doc/alter_retention_notification_center_from_e73ff4d.sql` 包含从旧四表基线到当前五张通知/投递表及 watch claim、冻结收件人、activity fact 的完整升级。
- 当前 baseline 和 legacy increment 均校验/标准化旧 `NOTIFY_TYPE`、`NOTIFY_EMAIL`，不会默默扩大发送范围。
- verifier 检查五张核心表、22 个关键 pipeline 列、watch claim/recipient 列、activity 列、批次唯一键顺序和 recipient 非空约束。
- `LegacyUpgrade` 实际运行精确无邮箱字段的旧 fixture，以及历史运营邮箱字段变体。

### 10. 认证状态持久与 JS 晚加载

- 认证结果持久到 `window.WhoseAuthState`，后加载的 `notifications.js` 仍可读取状态并初始化铃铛。
- 运行时 JS 测试覆盖认证事件先发生、脚本后加载的顺序。

### 11. latest event risk 命名

- mapper model、API DTO、模板和 JS 全部改为 `latestEventRisk` / “最新事件风险”。
- watch 列表不再把历史最新事件冒充 current health；“safe/current health” 表述已移除。

### 12. IPv6 globally reachable 默认拒绝

- 目标策略采用表驱动范围判定；IPv4 兼容映射单独处理。
- 对 IPv6 采用已知全局分配范围 allowlist + 其余默认拒绝，覆盖 IPv4-mapped、6to4、NAT64、Teredo、benchmark、documentation、deprecated ORCHID、ORCHIDv2、DET 及未分配地址。
- 补充 deprecated `192.88.99.0/24` 兼容拒绝测试。

### 13. 日级认证活动和隐私门槛

- 新增最小化 `WEB_AUTHENTICATED_ACTIVITY_DAILY`：只保存 `USER_ID + ACTIVITY_DATE`，唯一键按用户/日期去重，不保存 URL、域名或请求细节。
- 仅在认证 token 验证成功后 upsert；分析写入失败不破坏正常请求。
- 留存报表只依赖日级活动事实，不再依赖只保留 50 条的 history。
- 每日 cohort 小于 `k=5` 时不输出；汇总比较把多日 cohort 合并，并仅在合计达到 k 时输出。SQL 从不选择用户级字段。

## MINOR 映射

- 通知域名筛选改为相关 `EXISTS`/join，不再先拉取 watch ID 列表。
- `domain-watch.html` 删除 inline `onclick` 和动态 `innerHTML`；邮箱、域名及服务端文本通过 DOM text/value API 写入，避免注入。
- radio 组改为 `fieldset`/`legend`，保持键盘和读屏语义。
- domain detail 增加模块 anchor，CTA 发送经过 allowlist 的 `domain_detail_cta_clicked` analytics。
- 高风险 resolver 契约增加独立完整测试，覆盖默认、全局、分类以及非高风险路径。
- README 明确 `notification_opened` 只表示站内通知中心加载；真实邮件 open tracking 未实现，也不作已有能力宣称。

## TDD 与修复证据

- 新增 `successfulClaimReloadsTheWatchBeforeDecidingWhetherItIsStillDue` 时先看到失败，证明原任务会在 claim 后继续使用陈旧 watch；实现 claim 后重载后转绿。
- 新增 `192.88.99.0/24` 特殊用途地址用例时先失败；补全策略表后转绿。
- 第一次全量 Maven 运行发现旧 `DomainWatchTemplateTest` 仍要求 inline `onclick`：命令退出 1，`wesite-web` 425 个测试中 1 个失败。更新该过时契约并做定向验证后，第二次全量运行全部通过。

## 最终验证

所有下列最终命令均在实现提交前的完整工作树执行；实现提交后仅新增本报告。

| 命令 | Exit | 结果 |
| --- | ---: | --- |
| `$testFiles = (Get-ChildItem wesite-web/src/test/js -Filter *.test.js \| Sort-Object Name).FullName; node --test $testFiles` | 0 | 90 tests，90 pass，0 fail，0 skip |
| `mvn test` | 0 | 436 tests：`wesite-core` 11、`wesite-web` 425；0 failure/error/skip；Surefire 69 个 XML 文件；96.9s |
| `mvn -DskipTests package` | 0 | 4 个 reactor module 全部 SUCCESS；core/web/admin JAR 构建完成；14.5s |
| `powershell.exe -NoProfile -File scripts/verify-retention-rollout.ps1 -Fixture All` | 0 | Static、PathA、PathB、LegacyUpgrade 全部 PASS；63.9s |
| `git diff --cached --check`（实现提交前） | 0 | 无 whitespace error |

Rollout verifier 关键计数：

- PathA：tables=5、pipeline columns=22、watch columns=3、activity columns=2、audit columns=4、cleanup=0。
- PathB：同上；`PRESERVED=1`、`REJECTED=1`、cleanup=0。
- 精确 LegacyUpgrade：`WATCH_EMAIL=False`、`ALLOWED=0`、`CANCELLED=4`、cleanup=0。
- Legacy watch-email 变体：`WATCH_EMAIL=True`、`ALLOWED=1`、`CANCELLED=3`、cleanup=0。
- 最终输出：`PASS All`。

## 仍需知悉的边界（非本轮未修复项）

1. **邮件 open tracking**：按范围允许，未实现真实邮件打开追踪；README 已明确，分析事件不会冒充该能力。
2. **SMTP 至少一次语义**：SMTP 已接收而进程在写入 `SENT` 前崩溃时，lease 恢复后理论上可能重复发送；这是不可用本地事务消除的外部系统边界，README 已记录。
3. **CLAIMED 不可召回边界**：全局偏好或 watch 设置变化不能撤回正在进行的 SMTP 调用；成功则记 SENT，失败/过期则取消且不重试，避免设置变更后的新发送。
4. **IPv6 安全默认的运维代价**：未来 IANA 新增全局分配前缀时，allowlist 需要同步更新；未更新时表现为安全的 false negative，而不是 SSRF 放行。

除上述明确边界外，本轮终审列出的功能、可靠性、迁移、隐私、可访问性与注入风险项没有已知未解决项。
