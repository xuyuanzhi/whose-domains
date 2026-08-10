# wesite-web 代码质量与 SEO 恢复设计

## 背景

`wesite-web` 当前存在四类仍需在仓库中修复的问题：监控快照中的 DNS 集合无法从 JSON 正确恢复；自动博客任务仍可能发布旧下划线路由；已经发布的文章包含旧工具链接；帮助中心页面包含用户可见乱码。生产环境中 sitemap、robots 和规范化重定向与当前仓库不一致的问题不在本次代码修改范围内，待当前修复部署后由生产检查脚本验证。

## 目标

- 保持 AI 博客每三天自动发布，不增加人工发布门槛。
- 新生成的文章只能保存规范的连字符工具路由。
- 旧下划线路由永久重定向到对应的规范路由，保留用户访问与搜索信号。
- 提供可重复执行的数据修复脚本，修正已经发布文章中的旧链接。
- 监控快照完成稳定的 JSON 序列化与反序列化往返。
- 清除帮助中心已知的乱码字符。
- 所有行为变更均有先失败、后通过的自动化回归测试。

## 非目标

- 不修改当前已经修正的 sitemap 路由清单、robots 文件或 canonical 元数据。
- 不直接操作生产数据库、Cloudflare、Nginx 或 Search Console。
- 不重写博客生成架构，也不引入新的 JSON 或 HTML 解析依赖。
- 不改变 AI 博客的三天发布周期。

## 设计

### 1. 监控状态 JSON 兼容

`MonitorState` 保持现有公共记录类型不变：`Map<String, Set<String>> dnsRecords`。紧凑构造器中的 DNS 规范化逻辑改为按运行时集合处理映射值，将 Fastjson2 产生的 `JSONArray`、普通 `List` 或 `Set` 都规范化为排序、去空白、去重且不可变的 `Set<String>`。

现有 `JSON.toJSONString(state)` 和 `JSON.parseObject(json, MonitorState.class)` 调用方式保持不变，避免在两个读取入口引入不同的编解码路径。新增一个直接的 JSON 往返测试，并以现有 `MonitorEventPublisherTest`、`DomainWatchTaskTest` 验证真实调用链。

### 2. 工具路由兼容与内容规范化

新增一个小型工具路由规范组件，集中维护以下映射：

- `/tools/domain_analyzer` → `/tools/domain-analyzer`
- `/tools/dns_analyzer` → `/tools/dns-analyzer`
- `/tools/ssl_checker` → `/tools/ssl-checker`
- `/tools/competitor_analysis` → `/tools/competitor-analysis`

该组件提供两项能力：按旧路径查询规范路径；将文章 HTML 中旧的站内工具路径替换为规范路径。它只替换完整的路径标记，保留查询参数和锚点，不改写外部 URL 或普通文本。

`ViewController` 增加一个覆盖四个旧路径的 GET/HEAD 入口，返回 HTTP 301 和规范的相对 `Location`。`AiBlogTask` 的工具提示改用规范路径，并在提取正文后、保存前执行规范化。规范化完成后如果正文仍包含已知旧路径，则本次文章不保存并记录错误，避免静默发布带坏链接的内容。

### 3. 历史博客数据修复

新增 `doc/alter_blog_canonical_tool_links.sql`。脚本使用嵌套 `REPLACE` 更新 `WEB_BLOG_POST.CONTENT` 中四个旧路径，并使用 `WHERE` 只触及包含旧路径的记录。脚本可重复执行；第二次执行不会产生额外变更。

数据库脚本测试验证：四组替换均存在、目标均为连字符路由、更新范围有限、脚本不修改发布时间或更新时间。生产部署时由维护者在备份后单独执行该脚本。

### 4. 页面乱码

将 `domain-lock-and-transfer-protection.html` 中八个 `�?` 前缀替换为 HTML 实体 `&#10004;`。模板测试验证该页面不再包含 Unicode replacement character，并且八条最佳实践仍保留稳定的可见标记。

## 错误处理

- 监控 JSON 中 DNS 值为 `null`、空集合或包含空白元素时，结果为空集合或过滤后的不可变集合。
- DNS 值不是集合时，不允许产生 `ClassCastException`；该条记录按单值字符串规范化，无法表示的空值被忽略。
- AI 生成内容为空时沿用现有跳过逻辑；规范化后仍含旧路由时记录错误并跳过保存。
- 旧路径重定向不保留任意未知路径；控制器只接受明确列出的四个路径。

## 测试策略

每项修改遵循红—绿循环：

1. 新增 `MonitorState` JSON 往返测试，先复现 `JSONArray` 到 `Set` 的异常。
2. 新增路由规范组件测试，覆盖四个路径、查询参数、锚点和外部 URL 不误改。
3. 新增控制器测试，验证四个旧路径返回 301 和正确 `Location`。
4. 新增 AI 博客模板/源码契约测试，确保提示词无旧路径且保存前执行规范化与残留校验。
5. 新增 SQL 契约测试和乱码模板测试。
6. 运行相关测试后，执行 `mvn -pl wesite-web -am test` 和 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/check-seo.ps1 -SelfTest`。

生产部署后的线上 SEO 合约检查仍使用：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/check-seo.ps1 -BaseUrl https://whose.domains
```

## 发布顺序

1. 备份生产数据库。
2. 部署包含本设计修复的新应用版本。
3. 执行 `doc/alter_blog_canonical_tool_links.sql`。
4. 更新由 Nginx 管理的 sitemap 与 robots 文件，并清理相关 CDN 缓存。
5. 运行线上 SEO 合约检查；全部通过后再在 Search Console 提交 sitemap。
