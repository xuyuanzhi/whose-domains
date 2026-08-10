# wesite-web Quality Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复监控快照 JSON 往返、自动博客旧链接、旧工具路由兼容、历史博客数据和帮助中心乱码，同时保持 AI 博客每三天自动发布。

**Architecture:** 保持 `MonitorState` 公共类型和 Fastjson2 调用方式，通过运行时集合规范化解决反序列化问题。新增无状态工具路由规范组件，由独立重定向控制器和 `AiBlogTask` 共用；历史内容通过幂等 SQL 修复。

**Tech Stack:** Java 17、Spring Boot 3.5、Fastjson2 2.0.53、JUnit 5、Mockito、MockMvc、MySQL、Thymeleaf、Maven。

## Global Constraints

- AI 博客继续使用 `0 0 2 */3 * ?` 的三天发布周期。
- 不修改现有 sitemap、robots、canonical 元数据或生产基础设施。
- 不增加第三方依赖。
- 每项生产代码修改必须先有能复现缺陷的失败测试。
- 旧工具 URL 只兼容设计规格列出的四个明确路径。

---

## File Structure

- `MonitorState.java` 与新建 `MonitorStateJsonTest.java`：负责监控状态 JSON 往返。
- 新建 `CanonicalToolRoutes.java` 与测试：集中维护旧/新路由及 HTML 链接规范化。
- 新建 `LegacyToolRedirectController.java` 与测试：只负责四个旧路径的 301。
- `AiBlogTask.java` 与新建测试：负责提示词和保存前内容规范化。
- 新建 `doc/alter_blog_canonical_tool_links.sql` 与契约测试：修复历史正文。
- 帮助中心模板与新建内容质量测试：修复并防止乱码。

---

### Task 1: MonitorState JSON Round Trip

**Files:**
- Create: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorStateJsonTest.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorState.java:20-65`

**Interfaces:**
- Consumes: `JSON.toJSONString(Object)`、`JSON.parseObject(String, MonitorState.class)`。
- Produces: 保持 `MonitorState(..., Map<String, Set<String>> dnsRecords, ...)` 不变。

- [ ] **Step 1: 写入失败的 JSON 往返测试**

```java
@Test
void restoresDnsArraysAsCanonicalSets() {
    MonitorState source = new MonitorState(
        " Example.COM ", Set.of(" ok "), LocalDate.of(2027, 1, 2),
        LocalDate.of(2026, 12, 3),
        Map.of("a", Set.of(" 203.0.113.2 ", "203.0.113.1")), true, 2);

    MonitorState restored = JSON.parseObject(JSON.toJSONString(source), MonitorState.class);

    assertEquals("example.com", restored.domain());
    assertEquals(Set.of("ok"), restored.domainStatuses());
    assertEquals(Map.of("A", Set.of("203.0.113.1", "203.0.113.2")), restored.dnsRecords());
    assertThrows(UnsupportedOperationException.class,
        () -> restored.dnsRecords().get("A").add("203.0.113.3"));
}
```

- [ ] **Step 2: 运行红灯测试**

Run: `mvn -pl wesite-web -am "-Dtest=MonitorStateJsonTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 因 `JSONArray cannot be cast to Set` 失败。

- [ ] **Step 3: 最小修改 DNS 运行时规范化**

`canonicalDnsRecords` 先将输入赋给 `Map<?, ?> rawRecords` 再遍历，使值保持运行时类型；`canonicalSet` 改为接收 `Collection<?>`，`canonicalString` 接收 `Object` 并通过 `toString().trim()` 规范化。非集合单值包装为单元素集合，`null` 作为空集合处理。

```java
Map<?, ?> rawRecords = value;
rawRecords.forEach((recordType, records) -> {
    String type = canonicalString(recordType).toUpperCase(Locale.ROOT);
    Set<String> entries = records instanceof Collection<?> collection
        ? canonicalSet(collection)
        : canonicalSet(records == null ? List.of() : List.of(records));
    if (!type.isEmpty()) {
        normalized.merge(type, entries, MonitorState::union);
    }
});
```

- [ ] **Step 4: 验证直接测试和真实调用链**

Run: `mvn -pl wesite-web -am "-Dtest=MonitorStateJsonTest,MonitorEventPublisherTest,DomainWatchTaskTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 三个测试类全部通过，不再出现 JSON 或类型转换异常。

- [ ] **Step 5: 提交监控修复**

```powershell
git add wesite-web/src/main/java/info/wesite/web/monitor/MonitorState.java wesite-web/src/test/java/info/wesite/web/monitor/MonitorStateJsonTest.java
git commit -m "fix: restore monitor state dns records"
```

---

### Task 2: Canonical Tool Route Component

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/seo/CanonicalToolRoutes.java`
- Create: `wesite-web/src/test/java/info/wesite/web/seo/CanonicalToolRoutesTest.java`

**Interfaces:**
- Produces: `Optional<String> canonicalFor(String path)`。
- Produces: `String canonicalizeInternalLinks(String html)`。
- Produces: `boolean containsLegacyInternalLink(String html)`。
- Produces: 四组 `public static final String` 旧路径与规范路径常量。

- [ ] **Step 1: 写入失败的路径与 HTML 测试**

```java
@ParameterizedTest
@CsvSource({
    "/tools/domain_analyzer,/tools/domain-analyzer",
    "/tools/dns_analyzer,/tools/dns-analyzer",
    "/tools/ssl_checker,/tools/ssl-checker",
    "/tools/competitor_analysis,/tools/competitor-analysis"
})
void resolvesEachLegacyPath(String legacy, String canonical) {
    assertEquals(canonical, CanonicalToolRoutes.canonicalFor(legacy).orElseThrow());
}

@Test
void rewritesOnlyRelativeInternalHrefValuesAndPreservesSuffixes() {
    String html = "<a href=\"/tools/dns_analyzer?d=example.com#records\">DNS</a>"
        + "<a href='https://example.com/tools/dns_analyzer'>external</a>";
    String normalized = CanonicalToolRoutes.canonicalizeInternalLinks(html);
    assertTrue(normalized.contains("/tools/dns-analyzer?d=example.com#records"));
    assertTrue(normalized.contains("https://example.com/tools/dns_analyzer"));
    assertFalse(CanonicalToolRoutes.containsLegacyInternalLink(normalized));
}
```

- [ ] **Step 2: 运行红灯测试**

Run: `mvn -pl wesite-web -am "-Dtest=CanonicalToolRoutesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败，提示组件不存在。

- [ ] **Step 3: 实现最小组件**

用 `Map.of(...)` 保存四组映射。改写方法逐组匹配 `href` 的单/双引号相对路径，只有路径后为引号、`?` 或 `#` 时替换；`null` 和空字符串原样返回，外部主机 URL 不修改。

```java
public static Optional<String> canonicalFor(String path) {
    return Optional.ofNullable(LEGACY_TO_CANONICAL.get(path));
}

public static String canonicalizeInternalLinks(String html) {
    String normalized = html;
    for (var route : LEGACY_TO_CANONICAL.entrySet()) {
        normalized = rewriteHref(normalized, route.getKey(), route.getValue());
    }
    return normalized;
}
```

- [ ] **Step 4: 运行绿灯测试**

Run: `mvn -pl wesite-web -am "-Dtest=CanonicalToolRoutesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 路径、查询、锚点、单双引号和外链场景全部通过。

- [ ] **Step 5: 提交路由组件**

```powershell
git add wesite-web/src/main/java/info/wesite/web/seo/CanonicalToolRoutes.java wesite-web/src/test/java/info/wesite/web/seo/CanonicalToolRoutesTest.java
git commit -m "feat: centralize legacy tool routes"
```

---

### Task 3: Permanent Redirects and Safe AI Publishing

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/controller/tools/LegacyToolRedirectController.java`
- Create: `wesite-web/src/test/java/info/wesite/web/controller/tools/LegacyToolRedirectControllerTest.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/task/AiBlogTask.java:50-105,170-225`
- Create: `wesite-web/src/test/java/info/wesite/web/task/AiBlogTaskTest.java`

**Interfaces:**
- Consumes: `CanonicalToolRoutes`。
- Produces: 四个旧 GET/HEAD 路由的 301 和规范 `Location`。
- Produces: 保存前已规范化的 `BlogPost.content`。

- [ ] **Step 1: 写入失败的重定向测试**

```java
@ParameterizedTest
@CsvSource({
    "/tools/domain_analyzer,/tools/domain-analyzer",
    "/tools/dns_analyzer,/tools/dns-analyzer",
    "/tools/ssl_checker,/tools/ssl-checker",
    "/tools/competitor_analysis,/tools/competitor-analysis"
})
void permanentlyRedirectsLegacyToolPaths(String legacy, String canonical) throws Exception {
    mvc.perform(get(legacy)).andExpect(status().isMovedPermanently())
        .andExpect(header().string("Location", canonical));
    mvc.perform(head(legacy)).andExpect(status().isMovedPermanently())
        .andExpect(header().string("Location", canonical));
}
```

- [ ] **Step 2: 运行重定向红灯测试**

Run: `mvn -pl wesite-web -am "-Dtest=LegacyToolRedirectControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败，提示控制器不存在。

- [ ] **Step 3: 实现独立重定向控制器**

控制器使用 `@RequestMapping("/tools")` 和四个明确的 `@GetMapping` 值，从请求 URI 查询规范路径，返回 `ResponseEntity.status(MOVED_PERMANENTLY).location(URI.create(target)).build()`。注解映射的 GET 自动支持 HEAD。

- [ ] **Step 4: 写入失败的 AI 发布行为测试**

Mockito 模拟 `DeepSeekClient`：第一次返回唯一主题，第二次返回含 `/tools/dns_analyzer?d=example.com` 的完整分段文章。捕获 `blogPostService.save` 的 `BlogPost` 和正文生成调用的 system prompt：

```java
assertTrue(saved.getValue().getContent().contains("/tools/dns-analyzer?d=example.com"));
assertFalse(saved.getValue().getContent().contains("/tools/dns_analyzer"));
assertTrue(articlePrompt.contains("/tools/domain-analyzer"));
assertTrue(articlePrompt.contains("/tools/dns-analyzer"));
assertTrue(articlePrompt.contains("/tools/ssl-checker"));
```

- [ ] **Step 5: 运行 AI 红灯测试**

Run: `mvn -pl wesite-web -am "-Dtest=AiBlogTaskTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 当前正文和提示词仍含下划线路径，测试失败。

- [ ] **Step 6: 修改提示词和保存前处理**

将提示词中的三个旧路径改为连字符格式。在空正文检查之后执行：

```java
content = CanonicalToolRoutes.canonicalizeInternalLinks(content);
if (CanonicalToolRoutes.containsLegacyInternalLink(content)) {
    log.error("[AiBlogTask] Generated content still contains legacy tool links, skipping save.");
    return;
}
```

保持原 cron、主题生成、作者选择和保存字段不变。

- [ ] **Step 7: 验证重定向与自动发布**

Run: `mvn -pl wesite-web -am "-Dtest=CanonicalToolRoutesTest,LegacyToolRedirectControllerTest,AiBlogTaskTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 三个测试类全部通过。

- [ ] **Step 8: 提交路由和自动发布修复**

```powershell
git add wesite-web/src/main/java/info/wesite/web/controller/tools/LegacyToolRedirectController.java wesite-web/src/test/java/info/wesite/web/controller/tools/LegacyToolRedirectControllerTest.java wesite-web/src/main/java/info/wesite/web/task/AiBlogTask.java wesite-web/src/test/java/info/wesite/web/task/AiBlogTaskTest.java
git commit -m "fix: canonicalize generated blog links"
```

---

### Task 4: Historical Content Migration and Mojibake

**Files:**
- Create: `doc/alter_blog_canonical_tool_links.sql`
- Create: `wesite-web/src/test/java/info/wesite/web/seo/BlogCanonicalLinkMigrationTest.java`
- Modify: `wesite-web/src/main/resources/views/info/domain-lock-and-transfer-protection.html:190-197`
- Create: `wesite-web/src/test/java/info/wesite/web/view/HelpCenterContentQualityTest.java`

**Interfaces:**
- Produces: 幂等的 `WEB_BLOG_POST.CONTENT` 更新脚本。
- Produces: 八条以 `&#10004;` 开头的最佳实践。

- [ ] **Step 1: 写入失败的 SQL 契约测试**

读取 `../doc/alter_blog_canonical_tool_links.sql`，断言四组 `REPLACE` 源/目标、`WHERE` 和 `LOCATE` 存在；断言大写后的脚本不含 `UPDATE_TIME` 和 `PUBLISH_DATE`。

- [ ] **Step 2: 运行 SQL 红灯测试**

Run: `mvn -pl wesite-web -am "-Dtest=BlogCanonicalLinkMigrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: SQL 文件不存在，测试失败。

- [ ] **Step 3: 编写幂等 SQL**

```sql
UPDATE `WEB_BLOG_POST`
SET `CONTENT` = REPLACE(
    REPLACE(
        REPLACE(
            REPLACE(`CONTENT`, '/tools/domain_analyzer', '/tools/domain-analyzer'),
            '/tools/dns_analyzer', '/tools/dns-analyzer'),
        '/tools/ssl_checker', '/tools/ssl-checker'),
    '/tools/competitor_analysis', '/tools/competitor-analysis')
WHERE LOCATE('/tools/domain_analyzer', `CONTENT`) > 0
   OR LOCATE('/tools/dns_analyzer', `CONTENT`) > 0
   OR LOCATE('/tools/ssl_checker', `CONTENT`) > 0
   OR LOCATE('/tools/competitor_analysis', `CONTENT`) > 0;
```

- [ ] **Step 4: 写入失败的乱码模板测试**

读取 `/views/info/domain-lock-and-transfer-protection.html`，断言不包含 `�`，并使用固定计数辅助方法断言 `&#10004;` 恰好出现八次。

- [ ] **Step 5: 运行模板红灯测试**

Run: `mvn -pl wesite-web -am "-Dtest=HelpCenterContentQualityTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 检测到八个乱码且正确标记数为 0，测试失败。

- [ ] **Step 6: 修复模板**

将 190-197 行每个 `�?` 替换为 `&#10004;`，不改动正文、顺序和结构。

- [ ] **Step 7: 验证 SQL 和模板测试**

Run: `mvn -pl wesite-web -am "-Dtest=BlogCanonicalLinkMigrationTest,HelpCenterContentQualityTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 两个测试类全部通过。

- [ ] **Step 8: 提交数据和内容修复**

```powershell
git add doc/alter_blog_canonical_tool_links.sql wesite-web/src/test/java/info/wesite/web/seo/BlogCanonicalLinkMigrationTest.java wesite-web/src/main/resources/views/info/domain-lock-and-transfer-protection.html wesite-web/src/test/java/info/wesite/web/view/HelpCenterContentQualityTest.java
git commit -m "fix: repair legacy blog links and mojibake"
```

---

### Task 5: Full Verification

**Files:**
- Verify only; no planned production modifications.

**Interfaces:**
- Consumes all deliverables from Tasks 1-4。
- Produces可部署的测试和差异证据。

- [ ] **Step 1: 运行全部 Java 测试**

Run: `mvn -pl wesite-web -am test`

Expected: reactor `BUILD SUCCESS`，全部测试 0 failures、0 errors。

- [ ] **Step 2: 运行全部 JavaScript 测试**

Run: `node --test wesite-web/src/test/js/*.test.js`

Expected: 全部 JavaScript 测试通过。

- [ ] **Step 3: 运行离线 SEO 自测**

Run: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/check-seo.ps1 -SelfTest`

Expected: 输出 `SEO offline self-test passed.`，退出码 0。

- [ ] **Step 4: 检查残留和格式**

```powershell
rg -n '/tools/(domain_analyzer|dns_analyzer|ssl_checker|competitor_analysis)' wesite-web/src/main doc
git diff --check
git status --short
```

Expected: 旧路由只出现在兼容映射、重定向和迁移脚本中；diff 检查无输出。

- [ ] **Step 5: 审阅提交范围**

```powershell
git log --oneline -5
git diff HEAD~4..HEAD --stat
```

Expected: 设计文档及四组实现提交清晰分离，没有生产部署或无关文件修改。
