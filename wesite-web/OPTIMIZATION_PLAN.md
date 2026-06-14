# Whose.Domains - 用户增长优化方案 v2

> 创建日期：2026-04-21（v1）  
> 修订日期：2026-04-22（v2 — 基于实际代码现状重构）  
> 目标：通过 SEO 技术优化、新增工具页面、用户留存机制等手段持续获客与提升留存

---

## 现状盘点（v2 基于代码扫描）

### ✅ 已实现的工具页（`views/tools/*.html`，共 16 个）
`bulk-domain-search` · `competitor_analysis` · `dns_analyzer` · `domain-availability` · `domain-history` · `domain-score` · `domain_analyzer` · `json-formatter` · `my-ip-address` · `rdap-lookup` · `related-domains` · `reverse-ip` · `ssl_checker` · `timezone-converter` · `whois-compare` · `whois-lookup`

### ✅ 已实现的后端 API
`BulkDomainSearch` · `DomainAnalysis` · `DomainHistory`（含 snapshot/diff/latest-diff）· `DomainReport` · `DomainScore` · **`DomainWatch`（域名监控，含 list/add/update/delete）** · `ExpiringDomains` · `RelatedDomains` · `ReverseIp` · `SslChecker` · `WhoisCompare`

### ✅ 已存在的基础能力
- `Sitemap` 控制器（`/sitemap-20260413.xml`，**需要更新日期 + 补全工具页**）
- 用户体系（`UserController`、`UserHolder`、`@AccessControl(SESSION)`）
- 域名监控数据模型（`DomainWatch` 实体 + Service）
- 帮助中心（`help_center.html`）含 `_optimized` 版本

### ⚠️ 已规划但 **未实现** 的功能
- 域名估值工具（无 valuation 控制器）
- Email 验证工具
- 端口检测工具
- Ping / 可用性检测工具
- 域名监控的**前端管理页面**（API 已就绪，缺 UI）
- 监控**邮件提醒定时任务**
- 查询历史记录
- 批量查询导出 CSV
- Blog 模块
- TLD 详情页加入 sitemap
- 任何工具页的 `FAQPage` / `HowTo` / `WebApplication` Schema

---

## 设计原则（v2 新增）

1. **先收割已建设能力**：`DomainWatch` 已经有完整 API，缺的只是前端 → 工期最短，留存收益最大，**优先级提到 P0**
2. **量化投入产出比**：每项任务给出"流量潜力"评分（⭐~⭐⭐⭐⭐⭐）和"实现成本"
3. **不重复造轮子**：domain-history 的 diff/timeline 后端已实现，只需前端可视化
4. **复用现有架构**：所有工具页继承 `template.html`、API 走 `ResponseJson`、复用 `RandomUtils.generateId()` 等工具类
5. **SEO 优先于功能堆砌**：低成本高收益的 Schema、Sitemap、内链放最前

---

## 第一阶段（本周可上线）：低成本高收益 SEO

### 1. ⚡ Sitemap 全面更新（成本 0.5 天，流量 ⭐⭐⭐⭐）
- [✓] **更名/删除** `/sitemap-20260413.xml`，改为 `/sitemap.xml`（标准入口，避免日期硬编码）
- [✓] 自动列举 `views/tools/*.html` 全部 16 个工具页（用 `ResourcePatternResolver` 扫描）
- [✓] 各工具页设置 `<changefreq>weekly</changefreq>`、`<priority>0.8</priority>`
- [✓] 静态页（about/help/privacy/terms）`<priority>0.5</priority>`、`monthly`
- [✓] **TLD 详情页**（`/tld/{ext}`）使用分页 sitemap：`/sitemap-tld-1.xml` … `/sitemap-tld-N.xml`，每个 ≤ 5 万条，主入口用 sitemap index
- [✓] 在 `robots.txt` 中声明 `Sitemap: https://whose.domains/sitemap.xml`

### 2. ⚡ 三种 Schema Markup 通用模板（成本 1 天，流量 ⭐⭐⭐⭐）
- [✓] 在 `template.html` 中预留 `<th:block th:replace="${schemaFragment}">` 钩子
- [✓] 创建 `views/fragments/schema-tool.html` 片段，参数化输出 `WebApplication` + `FAQPage` + `BreadcrumbList`
- [✓] 16 个工具页统一改造，每页传入：`pageTitle`、`pageDescription`、`faqList`、`howToSteps`
- [✓] FAQ 至少 3 条/页，HowTo 至少 3 步/页（用于争取富媒体卡片）

### 3. ⚡ 内链优化（成本 1 天，流量 ⭐⭐⭐）
- [✓] 创建公共片段 `views/fragments/related-tools.html`：根据当前页 slug 渲染 5~6 个相关工具卡片
- [✓] 在 `domain_detail.html` 详情页的 WHOIS / DNS / SSL 区块旁，分别加一个"打开高级工具"小按钮 → 对应 `/tools/whois-lookup?d=xxx`、`/tools/dns-analyzer?d=xxx`、`/tools/ssl-checker?d=xxx`（带参数自动填值）
- [✓] 工具页底部统一接入 `related-tools.html`
- [✓] 每个工具页 `<head>` 中加 `rel="canonical"` 防止参数 URL 重复收录

### 4. ⚡ Meta + OG 全站审计（成本 0.5 天，流量 ⭐⭐⭐）
- [✓] 在 `template.html` 中统一渲染：`<title>`、`<meta description>`、`og:title/og:description/og:image/og:url`、`twitter:card`
- [✓] 工具页统一图：`/static/images/og/tools/{slug}.png`（设计同事补 16 张，可先用通用图过渡）
- [✓] 用脚本审计现有 16 个工具页是否每个都有独立 description（已确认：20 个工具页均有独立描述）

---

## 第二阶段：用户留存 & 转化（直接放大现有投入）

### 5. ⭐⭐ **域名监控前端页面**（成本 1.5 天，留存 ⭐⭐⭐⭐⭐）⭐ 提到 P0
> **后端 `DomainWatchController` 已就绪**，仅缺前端，性价比最高
- [✓] 新增 `views/user/domain-watch.html`：列表 + 添加表单 + 编辑 + 删除
- [✓] 在 `domain_detail.html` 任意域名详情页右上角加 "🔔 Monitor this domain"，未登录跳登录
- [✓] 在 `whois-lookup` / `domain-availability` 结果区也加同款按钮
- [✓] 在用户头像下拉菜单加入口 "My Watchlist"
- [✓] 上限校验已在 API 里（`MAX_WATCH_PER_USER = 50`），前端弹友好提示

### 6. ⭐⭐ 监控**邮件提醒定时任务**（成本 1.5 天，留存 ⭐⭐⭐⭐⭐）
- [✓] 新增 `info.wesite.web.task.DomainWatchNotifyTask` / `DomainWatchTask`，`@Scheduled(cron = "0 0 8 * * ?")` 每日 8:00
- [✓] 查询所有 `DomainWatch.status=ACTIVE` 且 `expiryDate - now <= notifyDays` 且 `lastNotifyDate is null OR != today` 的记录
- [✓] **wesite 内部独立邮件能力（SMTP 实现，不依赖 commerce-sendmail）**：
  - 引入 `org.springframework.boot:spring-boot-starter-mail`（封装 JavaMail，使用最广泛、最稳定）
  - 新增 `info.wesite.core.mail.MailSender` 接口 + `SmtpMailSender` 实现，内部用 Spring 的 `JavaMailSender` + `MimeMessageHelper`（支持 HTML 正文 + 附件 + 多收件人）
  - 配置项（标准 Spring Mail 配置 + 业务自定义）：
    ```yaml
    spring.mail:
      host: smtp.sendgrid.net          # 或 smtp.gmail.com / smtp.exmail.qq.com / 自建
      port: 587                         # 587=STARTTLS（推荐），465=SSL，25=明文（云环境多被封禁）
      username: apikey                  # SendGrid 固定填 apikey；其他服务填邮箱账号
      password: ${SMTP_PASSWORD}        # 走环境变量，不入库不入 git
      protocol: smtp
      properties:
        mail.smtp.auth: true
        mail.smtp.starttls.enable: true
        mail.smtp.starttls.required: true
        mail.smtp.connectiontimeout: 5000
        mail.smtp.timeout: 5000
        mail.smtp.writetimeout: 5000
    wesite.mail:
      from: alerts@whose.domains
      from-name: Whose.Domains
    ```
  - **端口选择建议**：优先 587 (STARTTLS) > 465 (SSL/TLS)；25 端口在阿里云/AWS/腾讯云出站默认封禁，**禁用**
- [✓] 模板：`templates/email/domain-expire-notify.html`（用 Thymeleaf `TemplateEngine` 渲染为字符串，再塞进 `MimeMessageHelper.setText(html, true)`）
  - 变量：`domainName / expiryDate / daysLeft / dashboardUrl / unsubscribeUrl`
- [✓] 邮件中带"立即查看 / 关闭此监控 / 调整提醒天数"三个深链接（带 HMAC 签名 token 免登录操作，token 7 天过期）
- [✓] 发送成功后写入 `last_notify_date`，避免重复轰炸
- [✓] 新增 `domain_watch_notify_log(id, watch_id, sent_at, status, error_msg, retry_count)` 审计表
- [✓] **失败重试**：发送失败时 `status=FAIL` 写日志，下次定时任务扫到 `retry_count < 3` 的记录自动补发
- [✓] **批量发送限速**：每秒 ≤ 10 封（避免被 SMTP 服务商限流），用 `RateLimiter` 简单控制
- [✓] **可扩展性**：`MailSender` 接口预留，未来要切换 Mailgun/SES 等只需加新实现而不改业务代码；如有大体量需求再加 `RestApiMailSender`（HTTP API 通常比 SMTP 吞吐高）

### 7. ⭐⭐ 查询历史记录（成本 1 天，留存 ⭐⭐⭐⭐）
- [✓] 新表 `user_query_history(id, user_id, query_type, query_value, result_summary, insert_date)`
- [✓] 在 `WhoisLookup` / `DomainAvailability` / `BulkDomainSearch` / `RdapLookup` 等接口拦截器统一写入（异步，`@Async`）
- [✓] 前端 `views/user/query-history.html`，按 type 分组、支持分页和"再次查询"按钮
- [✓] 自动保留最近 50 条/用户，旧记录定时清理（task）

### 8. ⭐⭐ 批量查询 CSV / JSON 导出（成本 0.5 天，转化 ⭐⭐⭐⭐）
- [✓] 在 `api/bulk-domain-search/export?queryId=xxx&format=csv|json`
- [✓] 用 `OpenCSV`（轻量）或纯 `PrintWriter` 输出（避免引入 POI 大依赖）
- [✓] 前端 `bulk-domain-search.html` 结果表格上方加 "📥 Export CSV / JSON" 按钮
- [✓] 同步在 `whois-compare`、`reverse-ip`、`expiring-domains` 三个产生表格的工具加同款按钮

---

## 第三阶段：新工具（按 ROI 排序）

### 9. ⭐⭐⭐ Email 验证工具 `/tools/email-checker`（成本 1.5 天，搜索量 ⭐⭐⭐⭐⭐）
- [✓] `EmailCheckerController` 提供 `GET /api/tools/email-check`
- [✓] 校验维度：语法、MX 记录、域名 A/AAAA、一次性邮箱黑名单
- [✓] 速率限制：未登录 IP 每分钟 10 次
- [✓] 前端 `views/tools/email-checker.html`

### 10. ⭐⭐⭐ 域名估值工具 `/tools/domain-valuation`（成本 2 天，搜索量 ⭐⭐⭐⭐⭐）
- [✓] `DomainValuationController` 提供 `GET /api/tools/valuation/{domain}`
- [✓] 估值因子（长度/TLD/字典词/域名年龄/无特殊字符/市场基准）
- [✓] 输出区间值 + 评分（0~100）+ 详细维度因子分
- [✓] 前端 `views/tools/domain-valuation.html`（含分数条和因子卡片）

### 11. ⭐⭐ 端口检测工具 `/tools/port-checker`（成本 1 天，技术流量 ⭐⭐⭐⭐）
- [✓] `PortCheckerController` 提供 `POST /api/tools/port-check`
- [✓] 后端用 `Socket` + 2 秒超时，并发 8 端口
- [✓] 风控：禁止私有/回环地址，单次最多 20 个端口，同 IP 每分钟 10 次
- [✓] 预设端口组：Web / DB / Remote / Mail + 自定义输入
- [✓] 前端 `views/tools/port-checker.html`

### 12. ⭐⭐ Ping / 可用性检测 `/tools/ping-test`（成本 1 天，搜索量 ⭐⭐⭐⭐）
- [✓] `InetAddress.isReachable()` + HTTP HEAD 双探测，私有地址风控
- [✓] 输出 ICMP / HTTP 响应时间 + 速率评级（Excellent/Good/Fair/Slow）
- [✓] 速率限制：同 IP 每分钟 10 次
- [✓] 前端 `views/tools/ping-test.html`

### 13. ⭐ WHOIS 历史可视化升级（成本 1 天，差异化 ⭐⭐⭐⭐）
- [✓] 升级 `domain-history.html`：纯 CSS 时间轴，按时间排列所有 snapshot
- [✓] 节点上展示重大变更标签：注册人变更 / 状态变更 / DNS 变更 / 续费
- [✓] 每个节点加"Select for diff"按钮，选 2 个自动触发 diff（接 `/api/domain-history/diff`）
- [✓] 在 `domain_detail.html` 上加"View History Timeline"入口

---

## 第四阶段：内容 SEO（长期收益）

### 14. ⭐ TLD 内容页深化（成本 3 天，长期 ⭐⭐⭐⭐）
- [✓] 改造 `domain_tld.html` / `domain_tldext.html`，每个 TLD 渲染独立内容
- [✓] 新增数据库表 `tld_content(tld, intro_html, history_html, use_cases_html, famous_sites_json, register_requirements_html, updated_date)`
- [✓] 优先填充 30 个主流 TLD（.com/.net/.org/.io/.ai/.co/.app/.dev/.xyz/.me/.info/.biz/.online/.store/.tech/.site/.shop/.club/.blog/.news/.live/.world/.tv/.us/.eu/.cc/.in/.de/.uk — SQL: `doc/data_tld_content.sql`）
- [✓] 每页加 H1/H2/H3 层级 + 内链到同 TLD 的"名人网站案例"
- [ ] 关键词覆盖：`{ext} domain meaning` / `{ext} domain price` / `{ext} domain history` / `who owns {famous-site}.{ext}`

### 15. ⭐ Blog 模块 `/blog`（成本 3~5 天，长期 ⭐⭐⭐⭐）
- [✓] `BlogController` + 实体 `BlogPost(id, slug, title, summary, content, cover, author, tags, publish_date, view_count, status)`
- [✓] 前端 `views/blog/index.html` + `views/blog/detail.html` + 分类路由 `/blog/category/{cat}`
- [✓] **内容结构化**：每篇 `Article` Schema、`BreadcrumbList` Schema
- [✓] 自动生成路由入口；在导航栏和 footer 加 Blog 链接
- [✓] 自动生成 `/blog/sitemap.xml` 子站点地图（待 SitemapController 扩展）
- [✓] 首批文章（结合现有工具引流，SQL: `doc/data_blog_posts.sql`）：
  - "How to check if a domain is expired in 2026" → 链 `/tools/domain-availability`
  - "Best free WHOIS lookup tools" → 链 `/tools/whois-lookup`
  - "How to find domain owner information" → 链 `/tools/whois-lookup`
  - "What is DNS, A/AAAA/MX/TXT records explained" → 链 `/tools/dns-analyzer`
  - "How to transfer a domain name without downtime"
  - "Domain name valuation guide" → 链 `/tools/domain-valuation`
  - "How to check email deliverability" → 链 `/tools/email-checker`
  - "SSL Certificate explained for non-developers" → 链 `/tools/ssl-checker`

---

## 第五阶段：性能 & 体验

### 16. 静态资源优化（成本 1 天）
- [✓] Spring Boot 静态资源开启 gzip、版本号缓存（`spring.web.resources.cache.cachecontrol.max-age=31536000`）
- [ ] CSS/JS 用 Maven `frontend-maven-plugin` 接 esbuild/swc 压缩（不要 webpack，过重）
- [✓] 大图改 `loading="lazy"` + WebP 输出（blog/index.html、blog/detail.html 已加 `loading="lazy"`）

### 17. 工具页性能（成本 1 天）
- [✓] 替换 spinner 为骨架屏（whois-lookup / dns_analyzer / ssl_checker 三个高流量工具已接入 `.skeleton-card` 骨架屏，CSS 已在 common.css）
- [✓] AJAX 工具结果接入 `localStorage` 缓存最近 5 次查询，30 分钟内同 query 直出（`ToolCache` 已在 common.js，已接入 whois-lookup / dns_analyzer / ssl_checker）

### 18. 移动端体验（成本 1.5 天）
- [✓] `bulk-domain-search.html` 表单改为移动端单列布局（`.bulk-controls` 响应式 CSS 已加入 common.css）、`type="search"` + `inputmode="url"`
- [✓] 工具页输入框统一加 `autocomplete="off"` `autocapitalize="none"` `spellcheck="false"` `inputmode="url"`（全部 20 个工具页已更新）
- [✓] 顶部导航在 ≤768px 折叠为汉堡菜单（`nav-hamburger` + `toggleNav()` 已实现）

### 19. 工具结果分享（成本 1 天，自传播 ⭐⭐⭐）
- [✓] 工具结果页 URL 接受 `?d=...` 参数，访问即自动执行查询并展示（4 个主工具已实现）
- [✓] 结果区右上角"🔗 Share / 📋 Copy Link"按钮（4 个主工具已实现）
- [✓] 动态 OG Image 接口 `/og-image.png?title=...&subtitle=...&type=tool|blog|domain`（`OgImageController.java` 已实现，Java2D 生成 1200×630 PNG；全部 20 个工具页 + blog 详情页已接入 `_og_image_url`；`template.html` `og:image`/`twitter:image` 已改为动态）

---

## 第六阶段（可选）：API 商业化探索

### 20. 公开 API + 付费计划（成本 5 天，营收 ⭐⭐⭐⭐）
- [ ] 整理现有内部 API 为公开 RESTful 文档（继续完善 `api_docs.html`）
- [ ] 引入 API Key + 每日配额限流（`Bucket4j` + Redis）
- [ ] 计费层级：Free（100/day）/ Starter $9（10K/day）/ Pro $49（100K/day）
- [ ] 接入 Stripe 订阅
- 适用 API：WHOIS Lookup / Domain Availability / DNS Records / SSL Check / Domain Score

---

## 优先级 & 排期总览（v2 调整后）

| 优先级 | 任务 | 工期 | 流量/留存 | 状态 |
|--------|------|------|-----------|------|
| **P0** | Sitemap 全面更新 + robots.txt | 0.5d | ⭐⭐⭐⭐ | ✅ |
| **P0** | Schema Markup 通用片段 + 16 页接入 | 1d | ⭐⭐⭐⭐ | ✅ |
| **P0** | 域名监控前端页面（API 已就绪） | 1.5d | ⭐⭐⭐⭐⭐ | ✅ |
| **P0** | 域名监控邮件提醒任务（含独立 MailSender） | 1.5d | ⭐⭐⭐⭐⭐ | ✅ |
| **P0** | 工具页 Meta + OG 标签审计 | 0.5d | ⭐⭐⭐ | ✅ |
| **P1** | 内链优化（详情页跳工具 + 相关工具片段） | 1d | ⭐⭐⭐ | ✅ |
| **P1** | Email 验证工具 | 1.5d | ⭐⭐⭐⭐⭐ | ✅ |
| **P1** | 域名估值工具 | 2d | ⭐⭐⭐⭐⭐ | ✅ |
| **P1** | 查询历史记录 | 1d | ⭐⭐⭐⭐ | ✅ |
| **P1** | 批量查询 CSV/JSON 导出 | 0.5d | ⭐⭐⭐⭐ | ✅ |
| **P1** | 端口检测工具 | 1d | ⭐⭐⭐⭐ | ✅ |
| **P2** | Ping 可用性检测工具 | 1d | ⭐⭐⭐⭐ | ✅ |
| **P2** | WHOIS 历史可视化升级 | 1d | ⭐⭐⭐⭐ | ✅ |
| **P2** | 16 工具页 FAQ Schema + related-tools | 1d | ⭐⭐⭐⭐ | ✅ |
| **P2** | domain_detail 加 View History Timeline 入口 | 0.2d | ⭐⭐⭐ | ✅ |
| **P2** | whois-compare / reverse-ip / expiring-domains Export 按钮 | 0.3d | ⭐⭐⭐⭐ | ✅ |
| **P2** | TLD 内容页深化（框架+表+前端，内容待填充） | 1d | ⭐⭐⭐⭐ | ✅ |
| **P2** | Blog 模块（框架+前端+导航，内容待创作） | 1d | ⭐⭐⭐⭐ | ✅ |
| **P2** | 工具结果分享（?d= URL 参数 + Share 按钮） | 1d | ⭐⭐⭐ | ✅ |
| **P3** | 静态资源 gzip + 缓存 | 1d | ⭐⭐ | ✅ |
| **P3** | 移动端体验（汉堡菜单 + 输入框属性 + bulk 响应式） | 1.5d | ⭐⭐⭐ | ✅ |
| **P3** | 工具页骨架屏 + localStorage ToolCache（whois/dns/ssl） | 1d | ⭐⭐ | ✅ |
| **P3** | 动态 OG Image 接口（`/og-image.png`，全工具页 + blog） | 0.5d | ⭐⭐⭐ | ✅ |
| **P3** | TLD 关键词 meta + blog cover loading=lazy | 0.5d | ⭐⭐ | ✅ |
| **可选** | CSS/JS esbuild 压缩（frontend-maven-plugin） | 1d | ⭐ | ⬜ |
| **可选** | 公开 API + 付费计划 | 5d | 营收 ⭐⭐⭐⭐ | ⬜ |

> P0 合计 ~5 天，可在两周内全部上线，立竿见影。

---

## 度量指标（v2 新增）

每次迭代后必须监控以下指标，否则盲目优化：

| 指标 | 工具 | 目标 |
|------|------|------|
| Google Search Console 收录页数 | GSC | 月环比 +20% |
| 自然搜索 CTR | GSC | Schema 上线后 +10~30% |
| 工具页 PV / UV | GA4 | 月环比 +30% |
| 注册转化率（访问→注册） | GA4 + DB | ≥ 1.5% |
| 域名监控创建数 | DB（`domain_watch` 表） | 周新增 +50% |
| 邮件提醒打开率 / 点击率 | SendGrid | ≥ 25% / ≥ 8% |
| 7 日 / 30 日复访率 | GA4 | 监控用户 vs 普通用户对比 |

---

## 技术栈（v2 修订）

- **后端**：Spring Boot + MyBatis-Plus + Thymeleaf + `@Scheduled`
- **前端**：原生 JS + AJAX；新增 Chart.js（估值雷达图）、Vis Timeline 或纯 CSS（历史时间轴）
- **数据库**：MySQL（新增 `user_query_history`、`tld_content`、`blog_post`、`domain_watch_notify_log` 等）
- **新增依赖**（按需引入，避免一次性全堆）：
  - `org.dnsjava:dnsjava` 或 `javax.naming` —— Email MX 检测
  - `com.opencsv:opencsv` —— CSV 导出（轻量，避免 POI）
  - `com.bucket4j:bucket4j-core` + 现有 Redis —— 速率限制
  - **邮件发送**：`spring-boot-starter-mail`（封装 JavaMail，业内最成熟方案）+ wesite 自研 `MailSender` 接口 + `SmtpMailSender` 实现，端口走 587 (STARTTLS) 或 465 (SSL)，**禁用 25 端口**（云服务商默认封禁）
- **明确不引入**：
  - ❌ commerce-sendmail（跨项目耦合，部署、升级、配置都得跟着走）
  - ❌ webpack/Vue/React（项目风格保持原生）
  - ❌ Apache POI（仅 CSV 用 OpenCSV）

---

## v1 → v2 主要变化总结

1. ❌ 删除"新建 user_domain_monitor 表"任务 —— **`domain_watch` 表已存在**
2. ❌ 删除"新建 MonitorController" —— **`DomainWatchController` 已实现完整 CRUD**
3. ✅ 新增"域名监控前端页面"独立任务并提到 P0 —— 性价比最高
4. ✅ 新增"邮件提醒定时任务"，明确 **wesite 内部独立实现** `MailSender` 接口 + `SmtpMailSender`（基于 `spring-boot-starter-mail`），**不依赖 commerce-sendmail**；走 587/465 端口，禁用 25 端口
5. ✅ Sitemap 任务细化到 **TLD 分页 sitemap + sitemap index + robots.txt 声明**
6. ✅ 估值算法因子表格化 + 单列方法论页面（SEO 长尾）
7. ✅ Email/Port/Ping 工具补充**风控限制**（避免被滥用导致 IP 被拉黑或被封）
8. ✅ 新增 **度量指标**章节，杜绝"做完不复盘"
9. ✅ 工期合计从 28~38 天压缩到 P0+P1 约 11 天可见效，剩余按数据驱动
