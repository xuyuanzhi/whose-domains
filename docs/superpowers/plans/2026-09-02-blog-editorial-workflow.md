# Blog Editorial Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 `wesite-admin` 中提供安全的博客草稿审核、HTML 源码编辑、预览、发布与撤回工作流，同时消除历史 HTML、后台鉴权和 SEO 修改时间问题。

**Architecture:** `wesite-core` 提供共享的 HTML 清洗器、编辑命令和事务服务；`wesite-web` 通过该服务创建 AI 草稿，并在公开渲染及 Sitemap/JSON-LD 中使用安全内容和编辑时间；`wesite-admin` 默认拒绝未授权请求，通过数据库复核管理员身份，并提供 JSON API 与 Layui 编辑界面。历史内容清洗由显式维护模式执行，不在普通应用启动时自动运行。

**Tech Stack:** Java 17、Spring Boot 3.5、MyBatis-Plus 3.5.12、jsoup 1.18.3、Fastjson2、Thymeleaf、Layui、JUnit 5、Mockito、MockMvc、Testcontainers MySQL 8.4。

**Spec:** `docs/superpowers/specs/2026-09-02-blog-editorial-workflow-design.md`

## Global Constraints

- 不改变公开 `wesite-web` 的 `/domain/*`、`/blog`、`/blog/category/{category}`、`/blog/{slug}` 路由。
- 已发布文章的 slug 永久只读；草稿 slug 规范为小写 ASCII 字母、数字和连字符，长度 1–200。
- 字段上限：标题和 meta title 300，摘要和 meta description 600，作者和分类 100，标签 300，HTML UTF-8 字节数不超过 1,048,576。
- 文章 HTML 在 AI 创建、后台保存、发布和公开渲染四个边界清洗；允许标签和协议必须与设计文档完全一致。
- Sitemap `lastmod` 与 BlogPosting `dateModified` 只使用 `CONTENT_UPDATED_AT`，回退到 `PUBLISH_DATE`，不得使用 `UPDATE_TIME`。
- `wesite-admin` 采用默认拒绝策略；仅 SPA 入口与登录接口显式公开，生产环境禁用 API 文档。
- 后台鉴权必须根据 JWT 的用户 ID 查询当前数据库用户，并验证未删除、已启用且 `USER_TYPE=ADMIN`。
- 不加入硬删除、版本历史、协作编辑、定时发布、媒体上传或新的前端框架。
- 工作区已有其他未提交改动；每次提交只能 `git add` 当前任务列出的文件，不得加入 `.claude/`、根目录 `sitemap_all.xml` 或无关改动。

---

### Task 0: 验证并分开提交当前已批准改动

**Files:**
- Existing change: `wesite-web/src/main/java/info/wesite/web/controller/tools/ViewController.java`
- Existing change: `wesite-web/src/test/java/info/wesite/web/controller/tools/ViewControllerSchemaTest.java`
- Existing change: `wesite-web/src/main/java/info/wesite/web/task/AiBlogTask.java`
- Existing change: `wesite-web/src/test/java/info/wesite/web/task/AiBlogTaskTest.java`
- Existing change: `wesite-web/src/main/java/info/wesite/web/task/SitemapTask.java`
- Existing change: `wesite-web/src/test/java/info/wesite/web/task/SitemapTaskTest.java`
- Existing change: `wesite-web/src/main/java/info/wesite/web/controller/api/HealthController.java`
- Existing change: `wesite-web/src/test/java/info/wesite/web/controller/api/HealthControllerTest.java`
- Existing change: `wesite-web/src/test/java/info/wesite/web/seo/CanonicalRedirectFilterTest.java`
- Existing change: `README.md`

**Interfaces:**
- Produces: 一个只剩明确排除的 `.claude/` 与根目录 `sitemap_all.xml` 的干净 tracked baseline。
- Preserves: 已实现的无虚假 AggregateRating、AI 默认草稿、无伪造 Sitemap 日期和 `/api/healthz`。

- [ ] **Step 1: 核对现有差异范围**

Run: `git status --short`

Run: `git diff -- README.md wesite-web/src/main/java/info/wesite/web/controller/tools/ViewController.java wesite-web/src/main/java/info/wesite/web/task/AiBlogTask.java wesite-web/src/main/java/info/wesite/web/task/SitemapTask.java wesite-web/src/test/java/info/wesite/web/seo/CanonicalRedirectFilterTest.java wesite-web/src/test/java/info/wesite/web/task/AiBlogTaskTest.java wesite-web/src/test/java/info/wesite/web/task/SitemapTaskTest.java`

Expected: tracked 差异仅为上述已批准改动；新文件为三个对应测试/健康控制器；`.claude/` 和根目录 `sitemap_all.xml` 保持未跟踪且不暂存。

- [ ] **Step 2: 验证并提交结构化数据评分修复**

Run: `mvn -pl wesite-web -am -Dtest=ViewControllerSchemaTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，工具 schema 不含 `aggregateRating`。

```bash
git add wesite-web/src/main/java/info/wesite/web/controller/tools/ViewController.java \
  wesite-web/src/test/java/info/wesite/web/controller/tools/ViewControllerSchemaTest.java
git commit -m "fix: remove unsupported tool ratings"
```

- [ ] **Step 3: 验证并提交 AI 默认草稿行为**

Run: `mvn -pl wesite-web -am -Dtest=AiBlogTaskTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，生成文章为 draft、author 与 publishDate 为 null。

```bash
git add wesite-web/src/main/java/info/wesite/web/task/AiBlogTask.java \
  wesite-web/src/test/java/info/wesite/web/task/AiBlogTaskTest.java
git commit -m "fix: save generated blog posts as drafts"
```

- [ ] **Step 4: 验证并提交 Sitemap 空日期行为**

Run: `mvn -pl wesite-web -am -Dtest=SitemapTaskTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，无内容日期的博客 URL 不输出伪造 `lastmod`。

```bash
git add wesite-web/src/main/java/info/wesite/web/task/SitemapTask.java \
  wesite-web/src/test/java/info/wesite/web/task/SitemapTaskTest.java
git commit -m "fix: omit unknown sitemap modification dates"
```

- [ ] **Step 5: 验证并提交健康检查**

Run: `mvn -pl wesite-web -am -Dtest=HealthControllerTest,CanonicalRedirectFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，`/api/healthz` 返回 uncached `{"status":"UP"}` 且不参与 canonical redirect。

```bash
git add README.md \
  wesite-web/src/main/java/info/wesite/web/controller/api/HealthController.java \
  wesite-web/src/test/java/info/wesite/web/controller/api/HealthControllerTest.java \
  wesite-web/src/test/java/info/wesite/web/seo/CanonicalRedirectFilterTest.java
git commit -m "feat: add lightweight health endpoint"
```

- [ ] **Step 6: 确认 tracked baseline 干净**

Run: `git status --short`

Expected: 不再有 tracked 文件改动；仅允许出现未跟踪的 `.claude/` 和根目录 `sitemap_all.xml`。

### Task 1: 博客编辑时间字段与数据库迁移

**Files:**
- Modify: `wesite-core/src/main/java/info/wesite/core/entity/BlogPost.java`
- Modify: `doc/create.sql:365-389`
- Create: `doc/alter_blog_editorial_workflow.sql`
- Create: `wesite-core/src/test/java/info/wesite/core/entity/BlogPostEditorialTimestampTest.java`
- Create: `wesite-web/src/test/java/info/wesite/web/seo/BlogEditorialMigrationMySqlTest.java`

**Interfaces:**
- Produces: `BlogPost#getContentUpdatedAt()` 与 `BlogPost#setContentUpdatedAt(Date)`。
- Produces: 可重复执行的 `alter_blog_editorial_workflow.sql`，新增列并只回填 `CONTENT_UPDATED_AT IS NULL` 的已发布文章。

- [ ] **Step 1: 写实体与新建库结构的失败测试**

```java
class BlogPostEditorialTimestampTest {
    @Test
    void exposesEditorialTimestampAndFreshInstallSchemaContainsColumn() throws Exception {
        Date value = new Date(1_800_000_000_000L);
        BlogPost post = new BlogPost();
        post.setContentUpdatedAt(value);
        assertSame(value, post.getContentUpdatedAt());

        String ddl = Files.readString(Path.of("..", "doc", "create.sql"));
        assertTrue(ddl.contains("`CONTENT_UPDATED_AT` datetime"));
    }
}
```

- [ ] **Step 2: 运行测试并确认因属性和列缺失而失败**

Run: `mvn -pl wesite-core -Dtest=BlogPostEditorialTimestampTest test`

Expected: 编译失败或断言失败，指出 `contentUpdatedAt`/`CONTENT_UPDATED_AT` 尚不存在。

- [ ] **Step 3: 添加实体属性和新建库字段**

```java
/** 最后一次实质性编辑时间；浏览次数更新不得修改它。 */
private Date contentUpdatedAt;
```

在 `PUBLISH_DATE` 后加入：

```sql
`CONTENT_UPDATED_AT` datetime NULL COMMENT 'Last material editorial content update',
```

- [ ] **Step 4: 写迁移行为的失败测试**

```java
@Test
void migrationAddsColumnBackfillsPublishedRowsAndCanRunTwice() throws Exception {
    executeMigration();
    assertEquals(publishedAt, readContentUpdatedAt("published"));
    assertNull(readContentUpdatedAt("draft"));
    executeMigration();
    assertEquals(publishedAt, readContentUpdatedAt("published"));
}
```

测试使用 `@Testcontainers(disabledWithoutDocker = true)`、MySQL 8.4，并在测试连接 URL 增加 `allowMultiQueries=true`。测试表至少包含 `ID`、`STATUS`、`DELETED`、`PUBLISH_DATE`。

- [ ] **Step 5: 编写幂等迁移脚本**

脚本使用 `information_schema.COLUMNS` 与 prepared statement，仅在列不存在时执行 `ALTER TABLE`，随后执行：

```sql
UPDATE WEB_BLOG_POST
SET CONTENT_UPDATED_AT = PUBLISH_DATE
WHERE STATUS = 1
  AND DELETED = 0
  AND PUBLISH_DATE IS NOT NULL
  AND CONTENT_UPDATED_AT IS NULL;
```

- [ ] **Step 6: 运行实体及迁移测试**

Run: `mvn -pl wesite-core -Dtest=BlogPostEditorialTimestampTest test`

Run: `mvn -pl wesite-web -am -Dtest=BlogEditorialMigrationMySqlTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 实体测试通过；Docker 可用时 MySQL 测试通过，不可用时仅该容器测试明确跳过。

- [ ] **Step 7: 提交任务**

```bash
git add wesite-core/src/main/java/info/wesite/core/entity/BlogPost.java \
  wesite-core/src/test/java/info/wesite/core/entity/BlogPostEditorialTimestampTest.java \
  wesite-web/src/test/java/info/wesite/web/seo/BlogEditorialMigrationMySqlTest.java \
  doc/create.sql doc/alter_blog_editorial_workflow.sql
git commit -m "feat: add blog editorial timestamp"
```

### Task 2: 共享 HTML 清洗器

**Files:**
- Create: `wesite-core/src/main/java/info/wesite/core/blog/BlogHtmlSanitizer.java`
- Create: `wesite-core/src/test/java/info/wesite/core/blog/BlogHtmlSanitizerTest.java`

**Interfaces:**
- Produces: `String BlogHtmlSanitizer.sanitize(String html)`；null/空白输入返回空字符串。
- Produces: `boolean BlogHtmlSanitizer.hasVisibleContent(String sanitizedHtml)`，仅含空标签或空白时返回 false。

- [ ] **Step 1: 写允许结构、相对链接和危险输入的失败测试**

```java
class BlogHtmlSanitizerTest {
    private final BlogHtmlSanitizer sanitizer = new BlogHtmlSanitizer();

    @Test
    void preservesArticleMarkupAndRelativeLinks() {
        String safe = sanitizer.sanitize("<h2>DNS</h2><p>Use <a href='/tools/dns-analyzer' title='DNS'>this</a></p><table><tr><td>A</td></tr></table>");
        Document doc = Jsoup.parseBodyFragment(safe);
        assertEquals("/tools/dns-analyzer", doc.selectFirst("a").attr("href"));
        assertEquals(3, doc.select("h2,table,td").size());
    }

    @Test
    void removesExecutableMarkupAndUnsafeProtocols() {
        String safe = sanitizer.sanitize("<p onclick='x()' style='color:red'>ok</p><script>x()</script><iframe src='x'></iframe><a href='javascript:x()'>bad</a>");
        assertFalse(safe.contains("script"));
        assertFalse(safe.contains("iframe"));
        assertFalse(safe.contains("onclick"));
        assertFalse(safe.contains("style="));
        assertFalse(safe.contains("javascript:"));
    }
}
```

再覆盖 `https`、`http`、`mailto` 可用，`data`、`file`、`vbscript` 不可用，以及 `<p><br></p>` 没有可发布内容。

- [ ] **Step 2: 运行测试并确认类不存在**

Run: `mvn -pl wesite-core -Dtest=BlogHtmlSanitizerTest test`

Expected: FAIL，`BlogHtmlSanitizer` 尚不存在。

- [ ] **Step 3: 用 jsoup Safelist 实现最小清洗器**

```java
@Component
public class BlogHtmlSanitizer {
    private static final Safelist ALLOWED = new Safelist()
        .addTags("p", "h2", "h3", "h4", "ul", "ol", "li", "strong", "em",
                 "code", "pre", "blockquote", "br", "a", "table", "thead",
                 "tbody", "tr", "th", "td")
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https", "mailto");

    public String sanitize(String html) {
        if (StringUtils.isBlank(html)) return "";
        return Jsoup.clean(html, "", ALLOWED,
            new Document.OutputSettings().prettyPrint(false));
    }
}
```

配置保留相对链接，不把 `/tools/...` 改写为绝对地址；`hasVisibleContent` 使用清洗后 body 的文本或允许的非空结构判断。

- [ ] **Step 4: 运行清洗器测试**

Run: `mvn -pl wesite-core -Dtest=BlogHtmlSanitizerTest test`

Expected: PASS。

- [ ] **Step 5: 提交任务**

```bash
git add wesite-core/src/main/java/info/wesite/core/blog/BlogHtmlSanitizer.java \
  wesite-core/src/test/java/info/wesite/core/blog/BlogHtmlSanitizerTest.java
git commit -m "feat: sanitize blog html"
```

### Task 3: 编辑命令与 AI 草稿创建服务

**Files:**
- Create: `wesite-core/src/main/java/info/wesite/core/blog/BlogDraftCommand.java`
- Create: `wesite-core/src/main/java/info/wesite/core/blog/BlogEditCommand.java`
- Create: `wesite-core/src/main/java/info/wesite/core/blog/BlogEditorialException.java`
- Create: `wesite-core/src/main/java/info/wesite/core/blog/BlogTimeProvider.java`
- Create: `wesite-core/src/main/java/info/wesite/core/blog/BlogEditorialService.java`
- Create: `wesite-core/src/test/java/info/wesite/core/blog/BlogEditorialServiceTest.java`

**Interfaces:**
- Produces: `BlogPost createAiDraft(BlogDraftCommand command)`。
- Produces: `String sanitizePreview(String html)`。
- Produces for Task 4: `BlogEditCommand` 包含 `id, slug, title, summary, content, author, category, tags, metaTitle, metaDescription`。
- Consumes: `BlogHtmlSanitizer`、`BlogPostMapper`、`BlogTimeProvider.now()`。

- [ ] **Step 1: 定义命令签名并写 AI 草稿创建失败测试**

```java
public record BlogDraftCommand(
    String slug, String title, String summary, String content,
    String category, String tags, String metaTitle, String metaDescription) {}

public record BlogEditCommand(
    String id, String slug, String title, String summary, String content,
    String author, String category, String tags,
    String metaTitle, String metaDescription) {}

@Test
void createsSanitizedUnownedDraftWithAuditAndEditorialTime() {
    Date now = new Date(1_800_000_000_000L);
    when(time.now()).thenReturn(now);
    when(mapper.selectCount(any())).thenReturn(0L);
    when(mapper.insert(any())).thenReturn(1);

    BlogPost result = service.createAiDraft(new BlogDraftCommand(
        " DNS Guide ", "DNS Guide", "summary", "<p>safe</p><script>x()</script>",
        "dns", "dns,security", "DNS Guide", "description"));

    assertEquals("dns-guide", result.getSlug());
    assertEquals(BlogPost.POST_STATUS_DRAFT, result.getStatus());
    assertNull(result.getAuthor());
    assertNull(result.getPublishDate());
    assertEquals("ai", result.getCreateBy());
    assertEquals(now, result.getContentUpdatedAt());
    assertFalse(result.getContent().contains("script"));
}
```

增加空 slug、重复 slug、清洗后空正文、字段超长和 UTF-8 HTML 超过 1 MiB 的拒绝测试；断言 mapper 未插入。

- [ ] **Step 2: 运行测试并确认服务不存在**

Run: `mvn -pl wesite-core -Dtest=BlogEditorialServiceTest test`

Expected: FAIL，命令和服务类型尚不存在。

- [ ] **Step 3: 实现命令、异常、时间提供者和创建逻辑**

```java
@Component
public class BlogTimeProvider {
    public Date now() { return new Date(); }
}

@Transactional
public BlogPost createAiDraft(BlogDraftCommand command) {
    Date now = time.now();
    String slug = normalizeSlug(command.slug());
    String content = sanitizer.sanitize(command.content());
    validateDraft(command, slug, content);
    ensureSlugAvailable(slug, null);
    BlogPost post = toDraft(command, slug, content, now);
    try {
        if (mapper.insert(post) != 1) throw new BlogEditorialException("Failed to create draft");
    } catch (DuplicateKeyException ex) {
        throw new BlogEditorialException("Slug already exists", ex);
    }
    return post;
}
```

字符上限使用 Unicode code point 数；HTML 上限使用 `content.getBytes(StandardCharsets.UTF_8).length`。ID 使用现有 `RandomUtils.generateId()` 或与现有 32 字符主键兼容的 UUID。可选空白字段统一为 null，AI 作者始终为 null。

- [ ] **Step 4: 实现预览方法并覆盖空内容**

```java
public String sanitizePreview(String html) {
    validateHtmlSize(html);
    return sanitizer.sanitize(html);
}
```

- [ ] **Step 5: 运行服务测试**

Run: `mvn -pl wesite-core -Dtest=BlogEditorialServiceTest test`

Expected: PASS。

- [ ] **Step 6: 提交任务**

```bash
git add wesite-core/src/main/java/info/wesite/core/blog \
  wesite-core/src/test/java/info/wesite/core/blog/BlogEditorialServiceTest.java
git commit -m "feat: create validated blog drafts"
```

### Task 4: 保存、发布、撤回与行锁状态转换

**Files:**
- Modify: `wesite-core/src/main/java/info/wesite/core/mapper/BlogPostMapper.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/blog/BlogEditorialService.java`
- Modify: `wesite-core/src/test/java/info/wesite/core/blog/BlogEditorialServiceTest.java`
- Create: `wesite-core/src/test/java/info/wesite/core/mapper/BlogPostMapperLockContractTest.java`

**Interfaces:**
- Produces: `BlogPost BlogPostMapper.selectByIdForUpdate(String id)`。
- Produces: `BlogPost save(BlogEditCommand command, String actorId)`。
- Produces: `BlogPost publish(String id, String actorId)`。
- Produces: `BlogPost unpublish(String id, String actorId)`。

- [ ] **Step 1: 写状态转换和行锁失败测试**

```java
@Test
void publishedPostRejectsSlugChange() {
    BlogPost stored = new BlogPost();
    stored.setId("post-1");
    stored.setSlug("fixed-slug");
    stored.setTitle("Title");
    stored.setSummary("Summary");
    stored.setContent("<p>Body</p>");
    stored.setMetaDescription("Description");
    stored.setStatus(BlogPost.POST_STATUS_PUBLISHED);
    when(mapper.selectByIdForUpdate(stored.getId())).thenReturn(stored);
    BlogEditCommand edit = new BlogEditCommand(
        "post-1", "changed-slug", "Title", "Summary", "<p>Body</p>",
        null, null, null, null, "Description");
    assertThrows(BlogEditorialException.class,
        () -> service.save(edit, "admin-1"));
    verify(mapper, never()).updateById(any());
}

@Test
void firstPublishSetsDateAndRepublishPreservesIt() {
    Date firstNow = new Date(1_800_000_000_000L);
    Date secondNow = new Date(1_800_000_100_000L);
    BlogPost stored = new BlogPost();
    stored.setId("post-2");
    stored.setSlug("publish-me");
    stored.setTitle("Publish Me");
    stored.setSummary("Summary");
    stored.setContent("<p>Body</p>");
    stored.setMetaDescription("Description");
    stored.setStatus(BlogPost.POST_STATUS_DRAFT);
    when(mapper.selectByIdForUpdate("post-2")).thenReturn(stored);
    when(mapper.updateById(any())).thenReturn(1);
    when(time.now()).thenReturn(firstNow, secondNow);

    BlogPost first = service.publish("post-2", "admin-1");
    Date originalPublishDate = first.getPublishDate();
    BlogPost second = service.publish("post-2", "admin-1");

    assertEquals(firstNow, originalPublishDate);
    assertEquals(originalPublishDate, second.getPublishDate());
}

@Test
void unpublishPreservesPublicationAndEditorialDates() {
    Date publishedAt = new Date(1_790_000_000_000L);
    Date editedAt = new Date(1_795_000_000_000L);
    BlogPost stored = new BlogPost();
    stored.setId("post-3");
    stored.setStatus(BlogPost.POST_STATUS_PUBLISHED);
    stored.setPublishDate(publishedAt);
    stored.setContentUpdatedAt(editedAt);
    when(mapper.selectByIdForUpdate("post-3")).thenReturn(stored);
    when(mapper.updateById(any())).thenReturn(1);

    BlogPost result = service.unpublish("post-3", "admin-1");

    assertEquals(BlogPost.POST_STATUS_DRAFT, result.getStatus());
    assertEquals(publishedAt, result.getPublishDate());
    assertEquals(editedAt, result.getContentUpdatedAt());
}
```

`BlogPostMapperLockContractTest` 通过反射读取 `@Select`，断言 SQL 包含 `DELETED = 0` 和 `FOR UPDATE`。

- [ ] **Step 2: 运行测试并确认缺少接口/行为**

Run: `mvn -pl wesite-core -Dtest=BlogEditorialServiceTest,BlogPostMapperLockContractTest test`

Expected: FAIL，锁查询和状态方法尚不存在。

- [ ] **Step 3: 添加锁查询**

```java
@Select("SELECT * FROM WEB_BLOG_POST WHERE ID = #{id} AND DELETED = 0 FOR UPDATE")
BlogPost selectByIdForUpdate(@Param("id") String id);
```

- [ ] **Step 4: 实现保存事务**

```java
@Transactional
public BlogPost save(BlogEditCommand command, String actorId) {
    BlogPost stored = requireLocked(command.id());
    NormalizedEdit edit = normalizeAndValidate(command);
    if (stored.getStatus() == BlogPost.POST_STATUS_PUBLISHED
            && !stored.getSlug().equals(edit.slug())) {
        throw new BlogEditorialException("Published slug cannot be changed");
    }
    ensureSlugAvailable(edit.slug(), stored.getId());
    boolean materialChange = differsFromPersisted(stored, edit);
    applyEditableFields(stored, edit);
    if (materialChange) stored.setContentUpdatedAt(time.now());
    setUpdateAudit(stored, actorId);
    requireSingleUpdate(stored);
    return stored;
}

private record NormalizedEdit(
    String slug, String title, String summary, String content,
    String author, String category, String tags,
    String metaTitle, String metaDescription) {}
```

先清洗、trim 和 null-normalize，再比较实际持久化值；仅修改 status 或重复保存不得推进 `CONTENT_UPDATED_AT`。

- [ ] **Step 5: 实现发布和撤回事务**

```java
@Transactional
public BlogPost publish(String id, String actorId) {
    BlogPost stored = requireLocked(id);
    String sanitized = sanitizer.sanitize(stored.getContent());
    validatePublishable(stored, sanitized);
    Date now = time.now();
    if (!Objects.equals(sanitized, stored.getContent())) stored.setContentUpdatedAt(now);
    stored.setContent(sanitized);
    if (stored.getPublishDate() == null) stored.setPublishDate(now);
    if (stored.getContentUpdatedAt() == null) stored.setContentUpdatedAt(now);
    stored.setStatus(BlogPost.POST_STATUS_PUBLISHED);
    setUpdateAudit(stored, actorId);
    requireSingleUpdate(stored);
    return stored;
}
```

`unpublish` 锁行、要求当前为 published、设置 draft，并保留 `publishDate/contentUpdatedAt`。所有 actor ID 必须非空。

- [ ] **Step 6: 运行状态转换测试**

Run: `mvn -pl wesite-core -Dtest=BlogEditorialServiceTest,BlogPostMapperLockContractTest test`

Expected: PASS。

- [ ] **Step 7: 提交任务**

```bash
git add wesite-core/src/main/java/info/wesite/core/mapper/BlogPostMapper.java \
  wesite-core/src/main/java/info/wesite/core/blog/BlogEditorialService.java \
  wesite-core/src/test/java/info/wesite/core/blog/BlogEditorialServiceTest.java \
  wesite-core/src/test/java/info/wesite/core/mapper/BlogPostMapperLockContractTest.java
git commit -m "feat: add blog editorial state transitions"
```

### Task 5: 后台默认拒绝与数据库管理员复核

**Files:**
- Modify: `wesite-admin/src/main/java/info/wesite/admin/interceptor/AdminInterceptor.java`
- Modify: `wesite-admin/src/main/java/info/wesite/admin/config/InterceptorConfig.java`
- Modify: `wesite-admin/src/main/java/info/wesite/admin/controller/MainController.java`
- Modify: `wesite-admin/src/main/resources/application.properties`
- Create: `wesite-admin/src/main/resources/application-dev.properties`
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/config.js:38-43`
- Create: `wesite-admin/src/test/java/info/wesite/admin/interceptor/AdminInterceptorTest.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/config/AdminSecurityConfigurationTest.java`

**Interfaces:**
- Produces: 构造器 `AdminInterceptor(UserService userService)`。
- Produces: 未标注 admin HandlerMethod 默认要求 active、non-deleted、`TYPE_ADMIN` 用户。
- Produces: JSON 未授权响应为 HTTP 200、`ResponseJson.code=401`；Layui `logout=401`。

- [ ] **Step 1: 写默认拒绝、角色复核和公开入口失败测试**

使用 `MockMvcBuilders.standaloneSetup(new ProbeController()).addInterceptors(interceptor)`，并用 `MockedStatic<TokenUtils>` 控制 JWT 身份：

```java
@Test
void unannotatedJsonHandlerRejectsMissingTokenWithExpectedEnvelope() throws Exception {
    mvc.perform(get("/protected"))
       .andExpect(status().isOk())
       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
       .andExpect(jsonPath("$.code").value(401));
}

@Test
void validPersonTokenIsRejectedAndActiveAdminFromDatabaseIsAccepted() throws Exception {
    User identity = new User();
    identity.setId("user-1");
    User person = new User();
    person.setId("user-1");
    person.setUserType(User.TYPE_PERSON);
    person.setStatus(User.STATUS_ACTIVE);
    User admin = new User();
    admin.setId("user-1");
    admin.setUserType(User.TYPE_ADMIN);
    admin.setStatus(User.STATUS_ACTIVE);
    try (MockedStatic<TokenUtils> tokens = mockStatic(TokenUtils.class)) {
        tokens.when(() -> TokenUtils.verifyToken("signed-token")).thenReturn(identity);
        when(users.getById("user-1")).thenReturn(person, admin);
        mvc.perform(get("/protected").header(Constants.TOKEN_KEY, "signed-token"))
           .andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/protected").header(Constants.TOKEN_KEY, "signed-token"))
           .andExpect(status().isOk())
           .andExpect(content().string("ok"));
    }
}

@AccessControl(level = AccessControl.Level.NONE)
@GetMapping("/public")
String publicEndpoint() { return "ok"; }
```

再断言 inactive/deleted admin 被拒绝、拒绝后 `UserHolder` 已清理、成功请求完成后 ThreadLocal 已清理。

- [ ] **Step 2: 写生产 API 文档关闭和 Layui 响应码失败测试**

```java
@Test
void apiDocsAreDisabledByDefaultAndLayuiUsesCoreNoAuthCode() throws Exception {
    Properties props = load("src/main/resources/application.properties");
    assertEquals("false", props.getProperty("springdoc.api-docs.enabled"));
    assertEquals("false", props.getProperty("springdoc.swagger-ui.enabled"));
    assertTrue(Files.readString(Path.of("src/main/resources/static/layuiadmin/config.js"))
        .contains("logout: 401"));
}
```

- [ ] **Step 3: 运行后台安全测试并确认失败**

Run: `mvn -pl wesite-admin -am -Dtest=AdminInterceptorTest,AdminSecurityConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，现有拦截器默认公开、没有数据库复核且前端仍期待 1001。

- [ ] **Step 4: 将拦截器改为 Spring Bean 和默认拒绝**

```java
@Component
public class AdminInterceptor implements HandlerInterceptor {
    private final UserService userService;

    public AdminInterceptor(UserService userService) {
        this.userService = userService;
    }
}
```

`preHandle` 开始先 `UserHolder.remove()`；先解析 HandlerMethod 的访问级别，未标注时默认为 `SESSION`。仅受保护请求验证 JWT，再按 ID 调用 `userService.getById`，验证 status、deleted、userType 后才设置 `UserHolder`。所有拒绝分支显式清理 ThreadLocal。JSON handler 通过 `@RestController` 或 `@ResponseBody` 判断，不依赖 `X-Requested-With`。

- [ ] **Step 5: 更新注册方式和显式公开入口**

```java
public InterceptorConfig(AdminInterceptor adminInterceptor) {
    this.adminInterceptor = adminInterceptor;
}

registry.addInterceptor(adminInterceptor)
    .addPathPatterns("/**")
    .excludePathPatterns("/static/**", "/error");
```

给 `MainController#index` 和 `MainController#login` 添加 `@AccessControl(level = NONE)`；`userInfo` 和 `logout` 保持受保护。删除 Swagger exclusions。

- [ ] **Step 6: 禁用生产 API 文档并对齐前端**

在 `application.properties` 默认设置：

```properties
springdoc.api-docs.enabled=false
springdoc.swagger-ui.enabled=false
```

`application-dev.properties` 显式设为 true；把 Layui 的 `logout: 1001` 改为 `logout: 401`。

- [ ] **Step 7: 运行安全测试和 admin 模块测试**

Run: `mvn -pl wesite-admin -am -Dtest=AdminInterceptorTest,AdminSecurityConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `mvn -pl wesite-admin -am test`

Expected: PASS；现有后台控制器编译通过。

- [ ] **Step 8: 提交任务**

```bash
git add wesite-admin/src/main/java/info/wesite/admin/interceptor/AdminInterceptor.java \
  wesite-admin/src/main/java/info/wesite/admin/config/InterceptorConfig.java \
  wesite-admin/src/main/java/info/wesite/admin/controller/MainController.java \
  wesite-admin/src/main/resources/application.properties \
  wesite-admin/src/main/resources/application-dev.properties \
  wesite-admin/src/main/resources/static/layuiadmin/config.js \
  wesite-admin/src/test/java/info/wesite/admin/interceptor/AdminInterceptorTest.java \
  wesite-admin/src/test/java/info/wesite/admin/config/AdminSecurityConfigurationTest.java
git commit -m "fix: require database-backed admin authorization"
```

### Task 6: 后台博客 JSON API

**Files:**
- Create: `wesite-admin/src/main/java/info/wesite/admin/blog/BlogAdminModels.java`
- Create: `wesite-admin/src/main/java/info/wesite/admin/controller/BlogAdminController.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/controller/BlogAdminControllerTest.java`

**Interfaces:**
- Produces: `/blog/list`, `/blog/detail`, `/blog/save`, `/blog/preview`, `/blog/publish`, `/blog/unpublish` 六个 POST JSON 接口。
- Consumes: `BlogPostService` 用于只读查询，`BlogEditorialService` 用于预览与写操作，`UserHolder.get().getId()` 作为 actor。

- [ ] **Step 1: 写 DTO 和列表/详情失败测试**

`BlogAdminModels` 用嵌套 records 定义：

```java
public record ListRequest(Integer page, Integer limit, String keyword, Integer status) {}
public record IdRequest(String id) {}
public record SaveRequest(String id, String slug, String title, String summary,
    String content, String author, String category, String tags,
    String metaTitle, String metaDescription) {}
public record PreviewRequest(String content) {}
public record PreviewResponse(String html) {}
public record ListItemResponse(
    String id, String slug, String title, String summary, String author,
    String category, String tags, Integer status,
    Date publishDate, Date contentUpdatedAt) {}
public record DetailResponse(
    String id, String slug, String title, String summary, String content,
    String author, String category, String tags, String metaTitle,
    String metaDescription, Integer status,
    Date publishDate, Date contentUpdatedAt) {}
```

列表响应 record 不包含 `content`；详情响应包含所有可编辑字段、状态、发布时间和编辑时间。

```java
@Test
void listBoundsPaginationAndDoesNotExposeBody() throws Exception {
    mvc.perform(post("/blog/list").contentType(APPLICATION_JSON)
        .content("{\"page\":1,\"limit\":20,\"status\":0}"))
       .andExpect(jsonPath("$.code").value(0))
       .andExpect(jsonPath("$.data[0].content").doesNotExist());
}
```

增加 page=0、limit=101、非法 status、超长 keyword 的 failure envelope 测试。

- [ ] **Step 2: 写保存、预览、发布和撤回委托失败测试**

```java
@Test
void saveMapsOnlyDtoFieldsAndUsesCurrentAdminAsActor() {
    User admin = new User();
    admin.setId("admin-1");
    admin.setUserType(User.TYPE_ADMIN);
    UserHolder.set(admin);
    SaveRequest request = new SaveRequest(
        "post-1", "draft-slug", "Title", "Summary", "<p>Body</p>",
        null, "dns", "dns,security", "Meta title", "Meta description");
    controller.save(request);
    verify(editorial).save(any(BlogEditCommand.class), eq("admin-1"));
}

@Test
void previewReturnsOnlySanitizedHtml() {
    when(editorial.sanitizePreview(anyString())).thenReturn("<p>safe</p>");
    ResponseJson<?> response = controller.preview(new PreviewRequest("<script>x()</script><p>safe</p>"));
    assertEquals(0, response.getCode());
}
```

每个测试结束调用 `UserHolder.remove()`；异常映射为 `ResponseJson.failure`，不得泄露堆栈或 SQL。

- [ ] **Step 3: 运行 API 测试并确认控制器不存在**

Run: `mvn -pl wesite-admin -am -Dtest=BlogAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，DTO 和控制器尚不存在。

- [ ] **Step 4: 实现受保护控制器和只读查询**

```java
@RestController
@RequestMapping("/blog")
@AccessControl(level = AccessControl.Level.SESSION)
public class BlogAdminController {
    private final BlogPostService posts;
    private final BlogEditorialService editorial;
}
```

列表默认 page=1、limit=20，limit 最大 100；keyword 最长 200，对 title、slug、summary 做参数化 `LIKE`；status 只允许 0/1；按 `publishDate DESC, createTime DESC` 排序。返回 `ResponseJson.success(items, total)`。

- [ ] **Step 5: 实现写操作与错误映射**

将 `SaveRequest` 显式转换为 `BlogEditCommand`。`save/publish/unpublish` 仅传入 ID 和当前管理员 ID；`preview` 返回 `new PreviewResponse(editorial.sanitizePreview(content))`。捕获 `BlogEditorialException` 返回可读 failure；未知异常记录服务端日志并返回通用错误。

- [ ] **Step 6: 运行 API 测试**

Run: `mvn -pl wesite-admin -am -Dtest=BlogAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 7: 提交任务**

```bash
git add wesite-admin/src/main/java/info/wesite/admin/blog/BlogAdminModels.java \
  wesite-admin/src/main/java/info/wesite/admin/controller/BlogAdminController.java \
  wesite-admin/src/test/java/info/wesite/admin/controller/BlogAdminControllerTest.java
git commit -m "feat: add admin blog editorial api"
```

### Task 7: Layui 博客列表、HTML 编辑与安全预览

**Files:**
- Modify: `wesite-admin/src/main/resources/static/layuiadmin/json/menu.js`
- Create: `wesite-admin/src/main/resources/static/layuiadmin/views/blog/list.html`
- Create: `wesite-admin/src/main/resources/static/layuiadmin/views/blog/edit.html`
- Create: `wesite-admin/src/test/java/info/wesite/admin/view/BlogAdminTemplateTest.java`

**Interfaces:**
- Consumes: Task 6 的六个 JSON 接口与 `ResponseJson` envelope。
- Produces: 菜单路由 `blog/list`、编辑弹层 `blog/edit`、空 sandbox iframe 预览。

- [ ] **Step 1: 写菜单、操作按钮和 sandbox 失败测试**

```java
@Test
void menuAndViewsExposeEditorialWorkflowWithoutScriptEnabledPreview() throws Exception {
    String menu = read("static/layuiadmin/json/menu.js");
    String list = read("static/layuiadmin/views/blog/list.html");
    Document edit = Jsoup.parse(read("static/layuiadmin/views/blog/edit.html"));

    assertTrue(menu.contains("博客管理"));
    assertTrue(menu.contains("blog/list"));
    assertTrue(list.contains("/blog/publish"));
    assertTrue(list.contains("/blog/unpublish"));
    Element preview = edit.selectFirst("iframe#blog-preview");
    assertNotNull(preview);
    assertTrue(preview.hasAttr("sandbox"));
    assertFalse(preview.attr("sandbox").contains("allow-scripts"));
}
```

再断言列表使用 `layui.util.escape`/DOM text API、编辑器通过 `.val()` 填充正文、发布状态令 slug readonly、按钮有请求中禁用逻辑。

- [ ] **Step 2: 运行模板测试并确认文件缺失**

Run: `mvn -pl wesite-admin -am -Dtest=BlogAdminTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，菜单项和视图尚不存在。

- [ ] **Step 3: 添加博客菜单和列表视图**

菜单使用：

```javascript
{
  "name": "blog",
  "title": "博客管理",
  "icon": "layui-icon-read",
  "jump": "blog/list"
}
```

列表提供 keyword、status 筛选、分页、状态徽章，以及 edit/publish/unpublish/public-view。所有标题、slug 和消息通过 `layui.util.escape` 或 `textContent` 渲染；公开地址使用 `encodeURIComponent(slug)`。操作期间禁用对应按钮，完成后恢复并刷新表格。

- [ ] **Step 4: 添加编辑视图和安全预览**

编辑视图先调用 `/blog/detail`，再用 `.val()` 设置普通字段和 HTML textarea，禁止把响应内容拼接进 HTML。若 status=1，对 slug input 设置 `readonly`。

```javascript
admin.req({
  type: 'post',
  url: '/blog/preview',
  contentType: 'application/json;charset=UTF-8',
  data: JSON.stringify({content: $('#blog-content').val()}),
  done: function(resp) {
    document.getElementById('blog-preview').srcdoc = resp.data.html;
  }
});
```

iframe 固定为 `<iframe id="blog-preview" sandbox></iframe>`，不设置 `allow-scripts` 或 `allow-same-origin`。发布按钮先保存；保存成功后才调用 publish。

- [ ] **Step 5: 运行模板测试**

Run: `mvn -pl wesite-admin -am -Dtest=BlogAdminTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 6: 提交任务**

```bash
git add wesite-admin/src/main/resources/static/layuiadmin/json/menu.js \
  wesite-admin/src/main/resources/static/layuiadmin/views/blog/list.html \
  wesite-admin/src/main/resources/static/layuiadmin/views/blog/edit.html \
  wesite-admin/src/test/java/info/wesite/admin/view/BlogAdminTemplateTest.java
git commit -m "feat: add blog editor to admin ui"
```

### Task 8: AI 生成任务接入统一草稿服务并删除公开触发接口

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/task/AiBlogTask.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/BlogController.java`
- Delete: `wesite-web/src/main/java/info/wesite/web/controller/api/AiTaskController.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/task/AiBlogTaskTest.java`
- Create: `wesite-web/src/test/java/info/wesite/web/controller/BlogGenerationEndpointRemovalTest.java`

**Interfaces:**
- Consumes: `BlogEditorialService.createAiDraft(BlogDraftCommand)`。
- Preserves: `BlogPostService` 仅用于读取最近标题；不再直接 count/save。

- [ ] **Step 1: 修改 AI 测试使其要求统一服务**

```java
@Test
void canonicalizesLinksThenCreatesDraftThroughEditorialService() {
    task.generateBlogPost();
    ArgumentCaptor<BlogDraftCommand> command = ArgumentCaptor.forClass(BlogDraftCommand.class);
    verify(editorial).createAiDraft(command.capture());
    assertTrue(command.getValue().content().contains("/tools/dns-analyzer"));
    assertFalse(command.getValue().content().contains("/tools/dns_analyzer"));
    verify(posts, never()).save(any());
}
```

增加服务拒绝空 slug/重复 slug 时任务记录失败且不抛出调度线程的测试。

- [ ] **Step 2: 写接口移除行为测试**

```java
@Test
void removedGenerationRoutesCannotTriggerPosts() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new BlogController()).build();
    mvc.perform(post("/blog/internal/generate")).andExpect(status().isMethodNotAllowed());
    mvc.perform(post("/api/admin/ai/blog/generate")).andExpect(status().isNotFound());
}
```

- [ ] **Step 3: 运行测试并确认仍直接保存且接口仍存在**

Run: `mvn -pl wesite-web -am -Dtest=AiBlogTaskTest,BlogGenerationEndpointRemovalTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL。

- [ ] **Step 4: 重构 AI 任务**

保留链接 canonicalization 和最近标题查询，将最终实体构造替换为：

```java
editorial.createAiDraft(new BlogDraftCommand(
    slug, topic.title, summary, content, topic.category, topic.tags,
    topic.title + " | Whose.Domains Blog", metaDescription));
```

移除任务中的重复 count、ID、status、日期和直接 save 逻辑，让核心服务成为唯一写入口。

- [ ] **Step 5: 删除两个手动生成映射**

从 `BlogController` 删除 `manualGenerate` 以及不再使用的 `AiBlogTask`、`Environment`、POST/ResponseEntity imports；删除整个 `AiTaskController.java`。

- [ ] **Step 6: 运行 AI 与路由测试**

Run: `mvn -pl wesite-web -am -Dtest=AiBlogTaskTest,BlogGenerationEndpointRemovalTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS；内部路由 405，旧 API 路由 404。

- [ ] **Step 7: 提交任务**

```bash
git add wesite-web/src/main/java/info/wesite/web/task/AiBlogTask.java \
  wesite-web/src/main/java/info/wesite/web/controller/BlogController.java \
  wesite-web/src/main/java/info/wesite/web/controller/api/AiTaskController.java \
  wesite-web/src/test/java/info/wesite/web/task/AiBlogTaskTest.java \
  wesite-web/src/test/java/info/wesite/web/controller/BlogGenerationEndpointRemovalTest.java
git commit -m "fix: route ai blog drafts through editorial service"
```

### Task 9: 公开博客安全渲染与 BlogPosting JSON-LD

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/BlogController.java`
- Modify: `wesite-web/src/main/resources/views/blog/detail.html:44-90`
- Create: `wesite-web/src/test/java/info/wesite/web/controller/BlogControllerSeoTest.java`
- Create: `wesite-web/src/test/java/info/wesite/web/view/BlogDetailTemplateTest.java`

**Interfaces:**
- Consumes: `BlogHtmlSanitizer.sanitize` 与 `BlogPost.contentUpdatedAt`。
- Produces: model attribute `_blogSchema`，内容为 Fastjson2 `JSONWriter.Feature.BrowserSecure` 序列化结果。

- [ ] **Step 1: 写公开渲染和日期失败测试**

```java
@Test
void detailSanitizesHistoricalBodyAndUsesEditorialDateInSchema() {
    BlogPost post = new BlogPost();
    post.setId("post-1");
    post.setSlug("safe-post");
    post.setTitle("Safe Post");
    post.setSummary("Summary");
    post.setStatus(BlogPost.POST_STATUS_PUBLISHED);
    post.setPublishDate(Date.from(LocalDate.parse("2026-08-01").atStartOfDay(ZoneOffset.UTC).toInstant()));
    post.setContent("<p>safe</p><script>alert(1)</script>");
    post.setContentUpdatedAt(Date.from(LocalDate.parse("2026-08-20").atStartOfDay(ZoneOffset.UTC).toInstant()));
    post.setUpdateTime(Date.from(LocalDate.parse("2026-09-02").atStartOfDay(ZoneOffset.UTC).toInstant()));
    when(posts.getOne(any())).thenReturn(post);

    controller.detail(post.getSlug(), model, request);

    BlogPost rendered = (BlogPost) model.getAttribute("post");
    assertFalse(rendered.getContent().contains("script"));
    String schema = (String) model.getAttribute("_blogSchema");
    assertTrue(schema.contains("2026-08-20"));
    assertFalse(schema.contains("2026-09-02"));
}
```

增加 blank author 产生 Organization、非空 author 产生 Person，以及标题 `</script><script>` 被 BrowserSecure 转义、schema 中没有原始 `</script>` 的断言。

- [ ] **Step 2: 写模板失败测试**

断言 detail 模板不再手工拼接 `post.author` 或引用 `post.updateTime`，只通过 `_blogSchema` 输出 `application/ld+json`。

- [ ] **Step 3: 运行测试并确认当前模板仍使用 updateTime 和字符串拼接**

Run: `mvn -pl wesite-web -am -Dtest=BlogControllerSeoTest,BlogDetailTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL。

- [ ] **Step 4: 在 controller 构建安全结构化数据**

在把文章放入 model 前设置清洗后的 body；用 `JSONObject` 嵌套对象构建 BlogPosting。日期规则：

```java
Date modified = post.getContentUpdatedAt() != null
    ? post.getContentUpdatedAt()
    : post.getPublishDate();
```

作者空白时创建 Organization，否则创建 Person。最终使用：

```java
String schema = JSON.toJSONString(root, JSONWriter.Feature.BrowserSecure);
model.addAttribute("_blogSchema", schema);
```

- [ ] **Step 5: 简化模板输出**

```html
<script type="application/ld+json" th:utext="${_blogSchema}"></script>
```

正文继续使用 `th:utext`，但其输入必须是 controller 刚清洗过的 fragment。

- [ ] **Step 6: 运行公开博客测试**

Run: `mvn -pl wesite-web -am -Dtest=BlogControllerSeoTest,BlogDetailTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 7: 提交任务**

```bash
git add wesite-web/src/main/java/info/wesite/web/controller/BlogController.java \
  wesite-web/src/main/resources/views/blog/detail.html \
  wesite-web/src/test/java/info/wesite/web/controller/BlogControllerSeoTest.java \
  wesite-web/src/test/java/info/wesite/web/view/BlogDetailTemplateTest.java
git commit -m "fix: safely render blog content and schema"
```

### Task 10: Sitemap 仅使用编辑时间

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/task/SitemapTask.java:207-229`
- Modify: `wesite-web/src/test/java/info/wesite/web/task/SitemapTaskTest.java`

**Interfaces:**
- Consumes: `BlogPost#getContentUpdatedAt()`。
- Produces: blog URL 的 `lastmod = contentUpdatedAt ?? publishDate`，两者都空时不输出。

- [ ] **Step 1: 扩充 Sitemap 失败测试**

```java
@Test
void blogLastmodUsesEditorialThenPublicationAndNeverGenericUpdateTime() throws Exception {
    BlogPost editorial = post("editorial", date("2026-08-20"), date("2026-08-01"), date("2026-09-02"));
    BlogPost fallback = post("fallback", null, date("2026-07-15"), date("2026-09-02"));
    BlogPost omitted = post("omitted", null, null, date("2026-09-02"));
    when(posts.list(any())).thenReturn(List.of(editorial, fallback, omitted));
    task.createFile();
    Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(output.resolve("sitemap_all.xml").toFile());

    assertEquals("2026-08-20", lastmod(xml, "editorial"));
    assertEquals("2026-07-15", lastmod(xml, "fallback"));
    assertNull(lastmod(xml, "omitted"));
    assertFalse(Files.readString(output.resolve("sitemap_all.xml")).contains("2026-09-02"));
}

private BlogPost post(String slug, Date editorial, Date published, Date updated) {
    BlogPost post = new BlogPost();
    post.setSlug(slug);
    post.setContentUpdatedAt(editorial);
    post.setPublishDate(published);
    post.setUpdateTime(updated);
    return post;
}

private Date date(String iso) {
    return Date.from(LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant());
}

private String lastmod(Document document, String slug) {
    for (int i = 0; i < document.getElementsByTagName("url").getLength(); i++) {
        Element url = (Element) document.getElementsByTagName("url").item(i);
        if (url.getElementsByTagName("loc").item(0).getTextContent().endsWith("/" + slug)) {
            NodeList values = url.getElementsByTagName("lastmod");
            return values.getLength() == 0 ? null : values.item(0).getTextContent();
        }
    }
    throw new AssertionError("Missing blog URL: " + slug);
}
```

- [ ] **Step 2: 运行测试并确认当前实现仍选择 updateTime**

Run: `mvn -pl wesite-web -am -Dtest=SitemapTaskTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL。

- [ ] **Step 3: 修改查询列和日期选择**

查询只选择 `slug, contentUpdatedAt, publishDate`，移除 `getUpdateTime`：

```java
Date lastModified = post.getContentUpdatedAt() != null
    ? post.getContentUpdatedAt()
    : post.getPublishDate();
if (lastModified != null) builder.lastMod(lastModified);
```

- [ ] **Step 4: 运行 Sitemap 测试**

Run: `mvn -pl wesite-web -am -Dtest=SitemapTaskTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 5: 提交任务**

```bash
git add wesite-web/src/main/java/info/wesite/web/task/SitemapTask.java \
  wesite-web/src/test/java/info/wesite/web/task/SitemapTaskTest.java
git commit -m "fix: use editorial dates in sitemap"
```

### Task 11: 历史 HTML 显式维护命令

**Files:**
- Modify: `wesite-core/src/main/java/info/wesite/core/mapper/BlogPostMapper.java`
- Create: `wesite-core/src/main/java/info/wesite/core/blog/BlogSanitizationReport.java`
- Create: `wesite-core/src/main/java/info/wesite/core/blog/BlogSanitizationMaintenanceService.java`
- Create: `wesite-core/src/test/java/info/wesite/core/blog/BlogSanitizationMaintenanceServiceTest.java`
- Modify: `wesite-admin/pom.xml`
- Create: `wesite-admin/src/main/java/info/wesite/admin/maintenance/BlogSanitizationRunner.java`
- Create: `wesite-admin/src/test/java/info/wesite/admin/maintenance/BlogSanitizationRunnerTest.java`

**Interfaces:**
- Produces: `List<BlogPost> BlogPostMapper.selectSanitizationBatch(String afterId, int limit)`，按 ID keyset 分页且只取未删除行。
- Produces: `BlogSanitizationReport run(boolean apply, int batchSize)`。
- Consumes: `BlogHtmlSanitizer`、`BlogTimeProvider` 与 `TransactionTemplate`；每个 apply 批次使用同一编辑时间并独立提交。
- Produces: 仅在 profile `blog-sanitize` 且 mode 为 `dry-run`/`apply` 时存在的维护 runner。
- Produces: 可通过 `java -jar wesite-admin/target/wesite-admin-1.0.0.jar` 启动的 Spring Boot admin 构建产物。

`BlogSanitizationReport` 的确定类型为：

```java
public record BlogSanitizationReport(long scanned, List<ChangedPost> changedPosts) {
    public long changed() { return changedPosts.size(); }
    public record ChangedPost(String id, String slug) {}
}
```

- [ ] **Step 1: 写 dry-run、apply、幂等和批次失败测试**

```java
@Test
void dryRunReportsChangesWithoutUpdatingRows() {
    BlogPost unsafe = new BlogPost();
    unsafe.setId("unsafe-id");
    unsafe.setSlug("unsafe-slug");
    unsafe.setContent("<p>safe</p><script>x()</script>");
    when(mapper.selectSanitizationBatch(null, 100)).thenReturn(List.of(unsafe));
    when(mapper.selectSanitizationBatch("unsafe-id", 100)).thenReturn(List.of());
    BlogSanitizationReport report = service.run(false, 100);
    assertEquals(List.of(new ChangedPost("unsafe-id", "unsafe-slug")), report.changedPosts());
    verify(mapper, never()).updateSanitizedContent(anyString(), anyString(), any(), anyString());
}

@Test
void applyUpdatesOnlyChangedRowsAndAdvancesEditorialTime() {
    Date now = new Date(1_800_000_000_000L);
    BlogPost safe = new BlogPost();
    safe.setId("safe-id");
    safe.setSlug("safe");
    safe.setContent("<p>safe</p>");
    BlogPost unsafe = new BlogPost();
    unsafe.setId("unsafe-id");
    unsafe.setSlug("unsafe");
    unsafe.setContent("<p>safe</p><script>x()</script>");
    when(time.now()).thenReturn(now);
    when(mapper.selectSanitizationBatch(null, 100)).thenReturn(List.of(safe, unsafe));
    when(mapper.selectSanitizationBatch("unsafe-id", 100)).thenReturn(List.of());
    when(mapper.updateSanitizedContent(anyString(), anyString(), any(), anyString())).thenReturn(1);

    BlogSanitizationReport report = service.run(true, 100);

    assertEquals(2, report.scanned());
    assertEquals(1, report.changed());
    verify(mapper).updateSanitizedContent("unsafe-id", "<p>safe</p>", now, "blog-sanitize");
    verify(mapper, never()).updateSanitizedContent(eq("safe-id"), anyString(), any(), anyString());
}
```

增加 batchSize 1–1000 校验和第二次 apply 零变更的测试。

- [ ] **Step 2: 写 runner 激活和模式边界失败测试**

使用 `ApplicationContextRunner` 验证普通 profile 没有 runner；`blog-sanitize` 加 `wesite.blog.sanitization.mode=dry-run` 时存在。直接构造 runner 并调用 `run`，断言 `dry-run` 传 `apply=false`、`apply` 传 `apply=true`、非法 mode 抛出 `IllegalArgumentException`，成功完成后关闭注入的 `ConfigurableApplicationContext`。

- [ ] **Step 3: 运行维护测试并确认类型不存在**

Run: `mvn -pl wesite-core -Dtest=BlogSanitizationMaintenanceServiceTest test`

Run: `mvn -pl wesite-admin -am -Dtest=BlogSanitizationRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL。

- [ ] **Step 4: 添加 keyset mapper 方法和维护服务**

```java
@Select("SELECT ID, SLUG, CONTENT, CONTENT_UPDATED_AT FROM WEB_BLOG_POST "
      + "WHERE DELETED = 0 AND (#{afterId} IS NULL OR ID > #{afterId}) "
      + "ORDER BY ID LIMIT #{limit}")
List<BlogPost> selectSanitizationBatch(@Param("afterId") String afterId, @Param("limit") int limit);

@Update("UPDATE WEB_BLOG_POST SET CONTENT = #{content}, CONTENT_UPDATED_AT = #{updatedAt}, "
      + "UPDATE_BY = #{actor} WHERE ID = #{id} AND DELETED = 0")
int updateSanitizedContent(@Param("id") String id, @Param("content") String content,
    @Param("updatedAt") Date updatedAt, @Param("actor") String actor);
```

服务逐批扫描；dry-run 只比较和报告；apply 使用 `TransactionTemplate` 让每个批次独立提交，只更新清洗结果发生变化的行。返回 scanned、changed 和 changed IDs/slugs。

- [ ] **Step 5: 添加显式维护 runner**

```java
@Component
@Profile("blog-sanitize")
@ConditionalOnProperty(name = "wesite.blog.sanitization.mode")
public class BlogSanitizationRunner implements ApplicationRunner {
    private final BlogSanitizationMaintenanceService service;
    private final ConfigurableApplicationContext context;
    private final String mode;
    private final int batchSize;

    public BlogSanitizationRunner(
            BlogSanitizationMaintenanceService service,
            ConfigurableApplicationContext context,
            @Value("${wesite.blog.sanitization.mode}") String mode,
            @Value("${wesite.blog.sanitization.batch-size:100}") int batchSize) {
        this.service = service;
        this.context = context;
        this.mode = mode;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean apply = switch (mode) {
            case "dry-run" -> false;
            case "apply" -> true;
            default -> throw new IllegalArgumentException("Unsupported sanitization mode: " + mode);
        };
        BlogSanitizationReport report = service.run(apply, batchSize);
        log.info("Blog sanitization report: {}", JSON.toJSONString(report));
        context.close();
    }
}
```

生产运行必须同时传入 `--spring.main.web-application-type=none`；普通 `prod` 启动不注册 runner。成功后关闭 application context；异常向外抛出以产生非零进程退出状态。

- [ ] **Step 6: 将 admin 打包为可执行 Spring Boot jar**

在 `wesite-admin/pom.xml` 添加：

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
      <configuration>
        <mainClass>info.wesite.admin.App</mainClass>
      </configuration>
    </plugin>
  </plugins>
</build>
```

- [ ] **Step 7: 运行维护测试并验证可执行 jar**

Run: `mvn -pl wesite-core -Dtest=BlogSanitizationMaintenanceServiceTest test`

Run: `mvn -pl wesite-admin -am -Dtest=BlogSanitizationRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `mvn -pl wesite-admin -am -DskipTests package`

Run: `jar tf wesite-admin/target/wesite-admin-1.0.0.jar`

Expected: 测试 PASS；jar 中包含 Spring Boot loader 和 `BOOT-INF/classes/info/wesite/admin/App.class`。

- [ ] **Step 8: 提交任务**

```bash
git add wesite-core/src/main/java/info/wesite/core/mapper/BlogPostMapper.java \
  wesite-core/src/main/java/info/wesite/core/blog/BlogSanitizationReport.java \
  wesite-core/src/main/java/info/wesite/core/blog/BlogSanitizationMaintenanceService.java \
  wesite-core/src/test/java/info/wesite/core/blog/BlogSanitizationMaintenanceServiceTest.java \
  wesite-admin/pom.xml \
  wesite-admin/src/main/java/info/wesite/admin/maintenance/BlogSanitizationRunner.java \
  wesite-admin/src/test/java/info/wesite/admin/maintenance/BlogSanitizationRunnerTest.java
git commit -m "feat: add explicit blog sanitization maintenance mode"
```

### Task 12: 部署说明、完整验证与发布检查

**Files:**
- Modify: `README.md`
- Create: `wesite-admin/src/test/java/info/wesite/admin/maintenance/BlogEditorialDeploymentDocumentationTest.java`

**Interfaces:**
- Consumes: Task 1 的迁移、Task 11 的维护模式，以及 admin/web 构建产物。
- Produces: 可复制执行的备份、dry-run、apply、启动和验证命令。

- [ ] **Step 1: 写部署文档契约失败测试**

```java
@Test
void readmeDocumentsSafeEditorialDeploymentOrder() throws Exception {
    String readme = Files.readString(Path.of("..", "README.md"));
    int backup = readme.indexOf("Back up WEB_BLOG_POST");
    int migration = readme.indexOf("alter_blog_editorial_workflow.sql");
    int dryRun = readme.indexOf("wesite.blog.sanitization.mode=dry-run");
    int apply = readme.indexOf("wesite.blog.sanitization.mode=apply");
    int deploy = readme.indexOf("Deploy wesite-admin and wesite-web");
    assertTrue(backup >= 0 && backup < migration);
    assertTrue(migration < dryRun && dryRun < apply && apply < deploy);
    assertTrue(readme.contains("--spring.main.web-application-type=none"));
    assertTrue(readme.contains("CONTENT_UPDATED_AT"));
}
```

- [ ] **Step 2: 运行文档测试并确认说明尚缺失**

Run: `mvn -pl wesite-admin -am -Dtest=BlogEditorialDeploymentDocumentationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL。

- [ ] **Step 3: 更新 README 部署与回滚流程**

按以下严格顺序记录：数据库备份；执行字段迁移；使用新 admin jar 加 `prod,blog-sanitize`、non-web 和 dry-run 输出报告；人工检查报告；apply；部署两个服务；验证后台默认拒绝、slug 锁定、预览 sandbox、公开 HTML、JSON-LD 和 Sitemap。说明二进制回滚可保留 nullable 列，内容回滚必须按报告从备份恢复 content 与原 content timestamp。

示例维护命令必须包含：

```bash
java -jar wesite-admin/target/wesite-admin-1.0.0.jar \
  --spring.profiles.active=prod,blog-sanitize \
  --spring.main.web-application-type=none \
  --wesite.blog.sanitization.mode=dry-run \
  --wesite.blog.sanitization.batch-size=100
```

- [ ] **Step 4: 运行文档测试**

Run: `mvn -pl wesite-admin -am -Dtest=BlogEditorialDeploymentDocumentationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 5: 运行三个模块的完整测试**

Run: `mvn test`

Expected: reactor 中 `wesite-core`、`wesite-web`、`wesite-admin` 全部 `SUCCESS`，Failures/Errors 为 0；没有 Docker 时只允许标记为 `disabledWithoutDocker` 的 MySQL 集成测试跳过。

- [ ] **Step 6: 运行静态差异检查**

Run: `git diff --check`

Run: `git status --short`

Expected: `git diff --check` 无输出且退出码 0；状态中不包含意外暂存的 `.claude/` 或根目录 `sitemap_all.xml`。

- [ ] **Step 7: 人工执行本地后台冒烟检查**

启动本地 admin 后验证：无 token 调 `/blog/list` 得 envelope 401；PERSON token 被拒绝；ADMIN token 可列草稿；危险 HTML 预览没有脚本能力；已发布 slug input readonly；双击 publish 只保留首次发布日期。

- [ ] **Step 8: 提交文档任务**

```bash
git add README.md \
  wesite-admin/src/test/java/info/wesite/admin/maintenance/BlogEditorialDeploymentDocumentationTest.java
git commit -m "docs: add blog editorial deployment runbook"
```

- [ ] **Step 9: 最终提交范围审计**

Run: `git log --oneline --decorate 71974a1..HEAD`

Run: `git diff 71974a1..HEAD --name-status`

Expected: 从已复审设计提交 `71974a1` 之后，每个提交只包含对应任务文件；公开 `/domain/*` 和 `/blog/*` 路由未重命名；两个手动 AI POST 入口已删除；设计文档要求均可映射到以上任务和测试。
