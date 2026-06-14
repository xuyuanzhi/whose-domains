# wesite-web 功能优化清单

> 项目：Whose.Domains (wesite-parent/wesite-web)  
> 创建日期：2026-03-29  
> 状态说明：✅ 已完成 | 🔲 待实施

---

## 🔴 高优先级（P0）— 核心价值提升

### ✅ 1. 真实实现 DNS/SEO/安全分析 API（消除假数据）
- **文件**: `DomainAnalysisApiController.java`
- **完成内容**:
  - 使用 dnsjava 真实查询 A、AAAA、MX、NS、TXT、CNAME、SOA 记录
  - Jsoup 抓取页面进行 SEO 分析（title、meta description、heading 结构、图片 alt、内外链、Open Graph、Twitter Card、结构化数据等）
  - 真实检测安全响应头（X-Frame-Options、HSTS、CSP 等），计算安全评分（0-100）
  - Socket 连接真实检测端口（80/443/21/22/25/53/8080）
  - SSL 证书信息获取（颁发者、有效期、剩余天数）
  - DNS 问题自动检测（缺失 SPF/DMARC/IPv6/NS 冗余等）
  - technical/SEO/security 三大分析使用 CompletableFuture 并行执行

### ✅ 2. 批量域名查询 API（补齐前端功能闭环）
- **新增文件**: `BulkDomainSearchController.java`
- **修改文件**: `bulk-domain-search.html`（移除 mock 数据，调用真实 API）
- **完成内容**:
  - 支持 availability/whois/dns 三种批量查询模式
  - 最多 100 个域名并行查询，10 线程并发，60 秒超时
  - 先查数据库 → 再查 DNS → 判断可用性
  - 速率限制：每分钟最多 3 次批量查询

### ✅ 3. 域名过期监控 & 提醒（提升用户粘性）
- **新增文件（core 层）**: `DomainWatch.java`、`DomainWatchMapper.java`、`DomainWatchService.java`、`DomainWatchServiceImpl.java`
- **新增文件（web 层）**: `DomainWatchController.java`、`DomainWatchTask.java`
- **建表 SQL**: `doc/alter_domain_watch_snapshot.sql`
- **完成内容**:
  - REST API：关注/取关/列表/更新/检查
  - 每用户最多关注 50 个域名
  - 支持 7天/30天/两者 通知类型
  - 定时任务每日凌晨3点检查过期域名
  - 自动从数据库同步最新过期日期

### ✅ 4. WHOIS 历史快照 & Diff 对比（差异化竞争力）
- **新增文件（core 层）**: `DomainSnapshot.java`、`DomainSnapshotMapper.java`、`DomainSnapshotService.java`、`DomainSnapshotServiceImpl.java`
- **新增文件（web 层）**: `DomainHistoryController.java`
- **修改文件**: `MainController.java`（详情页自动保存快照）、`domain-history.html`（调用真实 API + Diff UI）
- **完成内容**:
  - 域名详情页查看时自动保存 WHOIS 快照（仅在数据变化时）
  - 快照列表 API、快照详情 API
  - 两个快照逐字段 Diff 对比 API（16 个关键字段）
  - 最新变更快捷对比 API
  - 前端支持勾选两条快照进行可视化对比

---

## 🟡 中优先级（P1）— 用户体验提升

### 🔲 5. 用户功能完善
- **涉及文件**: `UserController.java`、`User.java`
- **待实施**:
  - 完成短信/邮件验证码发送功能（当前 `sendVcode` 是 TODO 状态）
  - 增加用户个人中心页面（查询历史、收藏域名、API Key 管理）
  - 增加 OAuth2 社交登录（Google、GitHub）
  - 增加密码找回/重置功能
  - 增加邮箱字段和邮箱验证

### 🔲 6. RESTful API 开放平台
- **涉及文件**: 新增 `ApiKeyController.java`、`ApiKey.java` 实体
- **待实施**:
  - 建立完整的 REST API（域名查询、DNS 查询、SSL 检查、IP 地理位置）
  - 为注册用户提供 API Key + 调用频次限制
  - 完善 Swagger/OpenAPI 文档注解（已有 springdoc 依赖）
  - 增加 API 调用统计面板
  - 区分免费/付费 API 套餐

### 🔲 7. 域名对比功能（竞争分析）
- **涉及文件**: `competitor_analysis.html`（前端已有）、新增后端 API
- **待实施**:
  - 并排对比 2-5 个域名的注册信息、DNS、技术栈
  - 域名年龄、SSL 状态、响应速度对比表
  - 支持导出对比报告（PDF/CSV）

### 🔲 8. RDAP 查询结果优化
- **涉及文件**: `domain_detail.html`、`RdapUtils.java`
- **待实施**:
  - 解析 RDAP JSON 提取结构化信息友好展示（当前只展示原始 JSON）
  - 增加 RDAP 结果可视化图表（域名生命周期时间线）
  - 缓存优化：RDAP 服务器经常超时，增加 Redis 缓存 + 智能重试策略

---

## 🟢 中低优先级（P2）— 扩展功能

### 🔲 9. 网站技术栈检测（Technology Detection）
- **涉及文件**: 新增 `TechDetectionController.java`、`TechDetectionService.java`
- **待实施**:
  - 利用 Jsoup 解析 HTML 检测前端框架（React/Vue/Angular/jQuery）
  - 检测 CMS（WordPress/Shopify/Wix/Drupal）
  - 检测 CDN 提供商（Cloudflare/AWS CloudFront/Akamai）
  - 检测 Analytics 工具（Google Analytics/Matomo）
  - 检测广告平台（Google Ads/Facebook Pixel）
  - 已有 `serverName`（Web 服务器），可扩展为更全面的技术栈报告

### 🔲 10. 域名价值评估工具
- **涉及文件**: 新增 `DomainValuationController.java`
- **待实施**:
  - 基于已有数据维度建立简单评估模型
  - 评估因素：域名长度、注册年龄、TLD 类型、是否含常见关键词
  - 是否有历史流量（可结合外部数据源）
  - 提供参考估值区间（低/中/高）

### 🔲 11. IP 反查功能增强
- **涉及文件**: `MainController.java` 的 `ipDetail` 方法、`ip.html`
- **待实施**:
  - IP 反查关联域名列表（同 IP 站点查询，当前 `domainList` 仅 like 匹配）
  - IP 黑名单/信誉查询（Spamhaus、AbuseIPDB 等外部 API）
  - 端口扫描结果展示
  - IP WHOIS 信息（RIR 数据：ARIN/RIPE/APNIC）

### 🔲 12. SEO 优化（站内 SEO 提升）
- **涉及文件**: 模板文件 `template.html`、各详情页 HTML
- **待实施**:
  - 域名详情页增加结构化数据（JSON-LD Schema.org 标记）
  - 增加面包屑导航
  - 域名详情页增加"相关域名推荐"（同 TLD / 同注册商）
  - 完善 Open Graph 和 Twitter Card 元数据
  - 增加 canonical URL 标签

---

## 🔵 技术层面优化（P3）

### 🔲 13. 查询性能优化
- **涉及文件**: `MainController.java` 的 `getDomainByName()`、`getDomainSiteByName()`
- **待实施**:
  - 当前同步执行 RDAP + DNS + Web 抓取，非常耗时 → 拆分为异步任务，先返回基础信息，后台刷新其余数据
  - 域名查询结果增加 Redis 缓存层（已有 Redis 依赖和 `RedisConfig.java`，但未充分利用）
  - DNS 查询用 CompletableFuture 并行执行多种记录类型查询
  - 考虑为热门域名增加预热缓存机制

### 🔲 14. Rate Limiting 统一化
- **涉及文件**: `RateLimitUtils.java`、各 Controller
- **待实施**:
  - 当前 `RateLimitUtils` 基于内存的简单实现 → 改为基于 Redis 的分布式限流（滑动窗口算法）
  - 区分匿名用户/注册用户/API 用户的不同限流策略
  - 对不同接口设置不同的限流规则（如批量查询 vs 单条查询）
  - 考虑使用 Spring AOP 注解化限流

### 🔲 15. 数据更新机制优化
- **涉及文件**: `DefaultTask.java`、`DomainServiceImpl.java`
- **待实施**:
  - 当前域名数据更新依赖用户访问触发 `isUpdatable()` → 增加主动更新定时任务
  - 扫描热门/即将过期域名并主动刷新
  - 增加 Webhook 通知机制：域名状态变更时主动通知关注用户
  - 优化 `DomainUtils.fillMainDomainInfo()` 的失败重试逻辑

### 🔲 16. 安全性增强
- **涉及文件**: `SecurityHeaderFilter.java`、`WebInterceptor.java`、`UserController.java`
- **待实施**:
  - 登录增加失败次数锁定（当前无限制）
  - 增加 CSRF 防护
  - JWT secret 硬编码 → 改为配置中心/环境变量管理
  - `sanitizeInput()` 方法使用 OWASP 推荐的转义库替代自定义正则
  - 增加请求日志审计

### 🔲 17. 国际化完善
- **涉及文件**: `i18n/message_*.properties`、各 HTML 模板
- **待实施**:
  - 当前有 `message.properties`、`message_en_US.properties`、`message_zh_CN.properties`，但大量页面文案未使用 i18n
  - 将所有 HTML 中的硬编码英文文案迁移到 i18n 配置
  - 增加语言切换功能
  - 错误信息也需要国际化

---

## 📋 已添加的页面路由（补齐缺失路由）

以下路由在 `ViewController.java` 中已补充添加：

| 路由 | 页面 | 状态 |
|------|------|------|
| `/tools/bulk-domain-search` | 批量域名查询 | ✅ 已添加 |
| `/tools/domain-availability` | 域名可用性检查 | ✅ 已添加 |
| `/tools/domain-history` | WHOIS 历史记录 | ✅ 已添加 |
| `/tools/whois-lookup` | WHOIS 查询 | ✅ 已添加 |
| `/tools/rdap-lookup` | RDAP 查询 | ✅ 已添加 |

---

## 📁 新增文件清单

### Core 层 (wesite-core)
| 文件 | 说明 |
|------|------|
| `entity/DomainWatch.java` | 域名监控实体 |
| `entity/DomainSnapshot.java` | WHOIS 快照实体 |
| `mapper/DomainWatchMapper.java` | 域名监控 Mapper |
| `mapper/DomainSnapshotMapper.java` | WHOIS 快照 Mapper |
| `service/DomainWatchService.java` | 域名监控 Service 接口 |
| `service/DomainSnapshotService.java` | WHOIS 快照 Service 接口 |
| `service/impl/DomainWatchServiceImpl.java` | 域名监控 Service 实现 |
| `service/impl/DomainSnapshotServiceImpl.java` | WHOIS 快照 Service 实现 |

### Web 层 (wesite-web)
| 文件 | 说明 |
|------|------|
| `controller/api/BulkDomainSearchController.java` | 批量域名查询 API |
| `controller/api/DomainWatchController.java` | 域名监控 REST API |
| `controller/api/DomainHistoryController.java` | WHOIS 历史快照 & Diff API |
| `task/DomainWatchTask.java` | 域名过期监控定时任务 |

### SQL
| 文件 | 说明 |
|------|------|
| `doc/alter_domain_watch_snapshot.sql` | WEB_DOMAIN_WATCH + WEB_DOMAIN_SNAPSHOT 建表 |

### 修改文件
| 文件 | 修改内容 |
|------|---------|
| `DomainAnalysisApiController.java` | 全部重写，替换假数据为真实实现 |
| `MainController.java` | 注入 DomainSnapshotService，详情页保存快照 |
| `ViewController.java` | 添加 6 个缺失的工具页面路由 |
| `bulk-domain-search.html` | 前端调用真实 API，移除 mock 函数 |
| `domain-history.html` | 前端调用真实 API，新增 Diff 对比 UI |
