# 技术 SEO 收录基础实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 修复 Whose.Domains 的 sitemap、robots、重复 URL、canonical 和薄弱域名报告索引问题，使所有提交给 Google 的 URL 都指向唯一、可索引且返回 HTTP 200 的规范页面。

**架构：** 新增独立的 SEO 包，集中负责规范 URL 和域名报告索引资格；现有拦截器只把这些结果传给 Thymeleaf，现有控制器只提供评估所需的数据。Sitemap 继续由 `SitemapTask` 生成 `/sitemap_all.xml`，但固定路由改为与实际控制器一致，并通过输出文件测试和线上契约脚本验证。

**技术栈：** Java 17、Spring Boot 3.5、Spring MVC、Thymeleaf、JUnit 5、Mockito、MockHttpServletRequest/Response、Maven、PowerShell。

## 全局约束

- 唯一生产 Origin 是 `https://whose.domains`。
- 首页规范路径是 `/`；其他 HTML 页面规范路径不带尾斜杠。
- `/sitemap_all.xml` 保持为唯一 sitemap 文件名。
- Sitemap 只包含预期返回 HTTP 200、允许索引并 canonical 指向自身的页面。
- HTTP、`www` 和非首页尾斜杠变体使用 HTTP 301；查询参数必须保留。
- API、静态资源、认证回调、XML、TXT、ICO 和 Web App Manifest 不执行 HTML 尾斜杠重定向。
- Canonical 重定向只在生产配置显式开启，本地和测试默认关闭。
- 动态域名报告数据不足时输出 `noindex,follow`，但仍允许用户查看已有数据。
- 不新增第三方依赖。
- 每个任务必须先写失败测试，再做最小实现，并单独提交。

---

## 文件结构

本计划新增或修改以下文件：

- `wesite-web/src/main/java/info/wesite/web/seo/CanonicalUrlService.java`：唯一负责路径规范化、canonical URL 和重定向目标计算。
- `wesite-web/src/main/java/info/wesite/web/seo/CanonicalRedirectFilter.java`：生产环境 GET/HEAD 永久重定向，不包含业务判断。
- `wesite-web/src/main/java/info/wesite/web/seo/DomainReportIndexPolicy.java`：根据域名和 DNS 数据判断报告是否达到索引门槛。
- `wesite-web/src/main/java/info/wesite/web/task/SitemapTask.java`：继续生成 sitemap，只修正并验证固定路由。
- `wesite-web/src/main/java/info/wesite/web/interceptor/WebInterceptor.java`：把规范 URL 注入模板，并让面包屑复用规范 URL。
- `wesite-web/src/main/java/info/wesite/web/controller/MainController.java`：把域名报告索引决策写入模型。
- `wesite-web/src/main/resources/views/template.html`：动态输出 robots、canonical、alternate 和 Open Graph URL。
- `wesite-web/src/main/resources/static/robots.txt`：声明 `/sitemap_all.xml`。
- `wesite-web/src/main/resources/application.properties`：重定向默认关闭。
- `wesite-web/src/main/resources/application-prod.properties.example`：生产示例显式开启重定向。
- `scripts/check-seo.ps1`：部署后验证 sitemap、robots、canonical 和重定向。
- 对应测试文件分别放在 `wesite-web/src/test/java/info/wesite/web/seo`、`task` 和 `view` 包。

---

### 任务 1：修复 Sitemap 路由与 Robots 声明

**文件：**

- 修改：`wesite-web/src/main/java/info/wesite/web/task/SitemapTask.java:36-59`
- 修改：`wesite-web/src/main/resources/static/robots.txt:1-4`
- 新建：`wesite-web/src/test/java/info/wesite/web/task/SitemapTaskTest.java`
- 新建：`wesite-web/src/test/java/info/wesite/web/view/RobotsFileTest.java`

**接口：**

- 输入：`SitemapTask.createFile()` 使用 `sitemapRoot` 和 `BlogPostService`。
- 输出：`${sitemapRoot}/sitemap_all.xml`，固定工具 URL 使用公开的连字符路由。
- 输出：生产 robots 资源包含 `Sitemap: https://whose.domains/sitemap_all.xml`。

- [ ] **步骤 1：编写 Sitemap 失败测试**

```java
package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import info.wesite.core.service.BlogPostService;

class SitemapTaskTest {

    @TempDir
    Path output;

    @Test
    void generatedSitemapUsesOnlyCanonicalToolRoutes() throws Exception {
        BlogPostService posts = mock(BlogPostService.class);
        when(posts.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        SitemapTask task = new SitemapTask();
        ReflectionTestUtils.setField(task, "sitemapRoot", output.toString());
        ReflectionTestUtils.setField(task, "blogPostService", posts);

        task.createFile();

        String xml = Files.readString(output.resolve("sitemap_all.xml"));
        assertTrue(xml.contains("https://whose.domains/tools/domain-analyzer"));
        assertTrue(xml.contains("https://whose.domains/tools/dns-analyzer"));
        assertTrue(xml.contains("https://whose.domains/tools/ssl-checker"));
        assertTrue(xml.contains("https://whose.domains/tools/competitor-analysis"));
        assertFalse(xml.contains("/tools/domain_analyzer"));
        assertFalse(xml.contains("/tools/dns_analyzer"));
        assertFalse(xml.contains("/tools/ssl_checker"));
        assertFalse(xml.contains("/tools/competitor_analysis"));
    }
}
```

- [ ] **步骤 2：编写 Robots 失败测试**

```java
package info.wesite.web.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RobotsFileTest {

    @Test
    void robotsDeclaresTheProductionSitemap() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/static/robots.txt")) {
            String robots = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            long directives = robots.lines()
                    .filter(line -> line.equals("Sitemap: https://whose.domains/sitemap_all.xml"))
                    .count();
            assertEquals(1, directives);
        }
    }
}
```

- [ ] **步骤 3：运行测试并确认失败原因准确**

运行：

```powershell
mvn -pl wesite-web -am -Dtest=SitemapTaskTest,RobotsFileTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`SitemapTaskTest` 因 sitemap 仍含下划线路径失败；`RobotsFileTest` 因 robots 仍指向 `/sitemap.xml` 失败。

- [ ] **步骤 4：进行最小修复**

在 `SitemapTask.TOOL_PAGES` 中仅替换四个错误路径：

```java
"/tools/domain-analyzer",
"/tools/dns-analyzer",
"/tools/ssl-checker",
"/tools/competitor-analysis",
```

将 robots 声明改为：

```text
Sitemap: https://whose.domains/sitemap_all.xml
```

- [ ] **步骤 5：运行测试并提交**

运行同一步骤 3，预期两个测试全部通过。然后提交：

```powershell
git add wesite-web/src/main/java/info/wesite/web/task/SitemapTask.java wesite-web/src/main/resources/static/robots.txt wesite-web/src/test/java/info/wesite/web/task/SitemapTaskTest.java wesite-web/src/test/java/info/wesite/web/view/RobotsFileTest.java
git commit -m "fix: publish canonical sitemap routes"
```

---

### 任务 2：集中生成规范 URL 与页面元数据

**文件：**

- 新建：`wesite-web/src/main/java/info/wesite/web/seo/CanonicalUrlService.java`
- 新建：`wesite-web/src/test/java/info/wesite/web/seo/CanonicalUrlServiceTest.java`
- 修改：`wesite-web/src/main/java/info/wesite/web/interceptor/WebInterceptor.java:35-58,156-260`
- 修改：`wesite-web/src/main/resources/views/template.html:10-29`
- 修改：`wesite-web/src/test/java/info/wesite/web/view/PositioningTemplateTest.java`

**接口：**

- 产生：`String CanonicalUrlService.normalizePath(String requestUri)`。
- 产生：`String CanonicalUrlService.canonicalUrl(String requestUri)`。
- 模型属性：`canonicalUrl`、`canonicalPath`。
- 模型属性：`_page_robots`，没有控制器覆盖时默认为完整的 index 指令。

- [ ] **步骤 1：编写 URL 服务失败测试**

```java
package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CanonicalUrlServiceTest {

    private final CanonicalUrlService service = new CanonicalUrlService();

    @Test
    void normalizesRootAndHtmlTrailingSlashes() {
        assertEquals("/", service.normalizePath("/"));
        assertEquals("/tools/whois-lookup", service.normalizePath("/tools/whois-lookup/"));
        assertEquals("/tools/whois-lookup", service.normalizePath("//tools//whois-lookup//"));
    }

    @Test
    void buildsCanonicalUrlsFromTheSingleProductionOrigin() {
        assertEquals("https://whose.domains/", service.canonicalUrl("/"));
        assertEquals("https://whose.domains/info/what-is-whois",
                service.canonicalUrl("/info/what-is-whois/"));
    }
}
```

- [ ] **步骤 2：运行测试确认类尚不存在**

```powershell
mvn -pl wesite-web -am -Dtest=CanonicalUrlServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，提示 `CanonicalUrlService` 不存在。

- [ ] **步骤 3：实现最小 URL 服务**

```java
package info.wesite.web.seo;

import org.springframework.stereotype.Service;

@Service
public class CanonicalUrlService {

    public static final String ORIGIN = "https://whose.domains";

    public String normalizePath(String requestUri) {
        String path = requestUri == null || requestUri.isBlank() ? "/" : requestUri;
        path = path.replaceAll("/{2,}", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    public String canonicalUrl(String requestUri) {
        return ORIGIN + normalizePath(requestUri);
    }
}
```

- [ ] **步骤 4：让拦截器和模板复用服务**

给 `WebInterceptor` 构造器增加 `CanonicalUrlService`，在 `preHandle` 中设置：

```java
String canonicalPath = canonicalUrlService.normalizePath(request.getRequestURI());
request.setAttribute("requestURI", canonicalPath);
request.setAttribute("canonicalPath", canonicalPath);
request.setAttribute("canonicalUrl", canonicalUrlService.canonicalUrl(canonicalPath));
request.setAttribute("_page_robots",
        "index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1");
```

面包屑中的页面 URL 改用 `canonicalUrlService.canonicalUrl(uri)`。模板改为：

```html
<meta name="robots" th:content="${_page_robots}">
<meta name="citation_public_url" th:content="${canonicalUrl}">
<link rel="canonical" th:href="${canonicalUrl}">
<link rel="alternate" hreflang="en" th:href="${canonicalUrl}">
<link rel="alternate" hreflang="x-default" th:href="${canonicalUrl}">
<meta property="og:url" th:content="${canonicalUrl}">
```

在 `PositioningTemplateTest` 增加精确断言，确认模板不再出现 `'https://whose.domains' + ${requestURI}`。

- [ ] **步骤 5：运行相关测试并提交**

```powershell
mvn -pl wesite-web -am -Dtest=CanonicalUrlServiceTest,PositioningTemplateTest,GoogleLoginVisibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全部通过。提交：

```powershell
git add wesite-web/src/main/java/info/wesite/web/seo/CanonicalUrlService.java wesite-web/src/main/java/info/wesite/web/interceptor/WebInterceptor.java wesite-web/src/main/resources/views/template.html wesite-web/src/test/java/info/wesite/web/seo/CanonicalUrlServiceTest.java wesite-web/src/test/java/info/wesite/web/view/PositioningTemplateTest.java
git commit -m "fix: centralize canonical page URLs"
```

---

### 任务 3：生产环境永久重定向到唯一 URL

**文件：**

- 新建：`wesite-web/src/main/java/info/wesite/web/seo/CanonicalRedirectFilter.java`
- 新建：`wesite-web/src/test/java/info/wesite/web/seo/CanonicalRedirectFilterTest.java`
- 修改：`wesite-web/src/main/resources/application.properties`
- 修改：`wesite-web/src/main/resources/application-prod.properties.example`
- 修改：`wesite-web/src/test/java/info/wesite/web/config/ProductionSecurityConfigurationTest.java`

**接口：**

- 输入：由受信任代理处理后的 `HttpServletRequest.scheme`、`serverName`、`requestURI`、`queryString`。
- 输出：规范请求继续过滤器链；非规范 GET/HEAD 返回 HTTP 301 和完整 `Location`。
- 配置：`wesite.seo.canonical-redirect-enabled=false|true`。

- [ ] **步骤 1：编写过滤器失败测试**

使用 `MockHttpServletRequest`、`MockHttpServletResponse` 和记录调用次数的 `MockFilterChain`，覆盖以下精确行为：

```java
@Test
void redirectsWwwAndTrailingSlashAndPreservesQuery() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tools/whois-lookup/");
    request.setScheme("https");
    request.setServerName("www.whose.domains");
    request.setQueryString("d=example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(301, response.getStatus());
    assertEquals("https://whose.domains/tools/whois-lookup?d=example.com",
            response.getHeader("Location"));
}

@Test
void doesNotRedirectPostApiOrSitemapRequests() throws Exception {
    assertPassedThrough("POST", "/domain/example.com/search");
    assertPassedThrough("GET", "/api/tools/score/example.com");
    assertPassedThrough("GET", "/sitemap_all.xml");
}
```

另加测试：HTTP 页面、首页、规范 HTML 页面、静态资源、OAuth 回调和 HEAD 请求。

- [ ] **步骤 2：运行测试确认失败**

```powershell
mvn -pl wesite-web -am -Dtest=CanonicalRedirectFilterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，提示 `CanonicalRedirectFilter` 不存在。

- [ ] **步骤 3：实现最小过滤器**

过滤器使用 `OncePerRequestFilter` 和条件配置：

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(name = "wesite.seo.canonical-redirect-enabled", havingValue = "true")
public class CanonicalRedirectFilter extends OncePerRequestFilter {
    // 构造器注入 CanonicalUrlService
}
```

只处理 GET/HEAD。以下前缀或后缀直接放行：`/api/`、`/static/`、`/oauth2/`、`/login/`、`/.well-known/`、`.xml`、`.txt`、`.ico`、`.webmanifest`。其他请求只要 scheme 不是 HTTPS、host 不是 `whose.domains`，或非首页路径带尾斜杠，就返回：

```java
response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
response.setHeader("Location", target);
```

重定向判断使用容器已经解析的 scheme 与 host，不直接信任任意客户端传入的 `X-Forwarded-*`。生产示例中已有 `server.forward-headers-strategy=native` 和受信任代理范围。

- [ ] **步骤 4：增加配置保护测试**

`application.properties` 增加：

```properties
wesite.seo.canonical-redirect-enabled=false
```

`application-prod.properties.example` 增加：

```properties
wesite.seo.canonical-redirect-enabled=true
```

在 `ProductionSecurityConfigurationTest` 断言默认值为 `false`、生产示例为 `true`，并保留现有可信代理断言。

- [ ] **步骤 5：运行测试并提交**

```powershell
mvn -pl wesite-web -am -Dtest=CanonicalRedirectFilterTest,CanonicalUrlServiceTest,ProductionSecurityConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全部通过。提交：

```powershell
git add wesite-web/src/main/java/info/wesite/web/seo/CanonicalRedirectFilter.java wesite-web/src/test/java/info/wesite/web/seo/CanonicalRedirectFilterTest.java wesite-web/src/main/resources/application.properties wesite-web/src/main/resources/application-prod.properties.example wesite-web/src/test/java/info/wesite/web/config/ProductionSecurityConfigurationTest.java
git commit -m "fix: redirect production pages to canonical URLs"
```

---

### 任务 4：为动态域名报告增加索引质量门槛

**文件：**

- 新建：`wesite-web/src/main/java/info/wesite/web/seo/DomainReportIndexPolicy.java`
- 新建：`wesite-web/src/test/java/info/wesite/web/seo/DomainReportIndexPolicyTest.java`
- 修改：`wesite-web/src/main/java/info/wesite/web/controller/MainController.java:108-338`
- 修改：`wesite-web/src/test/java/info/wesite/web/view/PositioningTemplateTest.java`

**接口：**

- 产生：`boolean DomainReportIndexPolicy.isIndexable(Domain domain, List<DomainDns> dnsRecords)`。
- 产生：`String DomainReportIndexPolicy.robotsDirective(Domain domain, List<DomainDns> dnsRecords)`。
- 模型覆盖：`_page_robots`。

- [ ] **步骤 1：编写索引策略失败测试**

使用真实 `Domain` 对象，不启动 Spring：

```java
class DomainReportIndexPolicyTest {

    private final DomainReportIndexPolicy policy = new DomainReportIndexPolicy();

    @Test
    void indexesARealReportWithAtLeastTwoEvidenceGroups() {
        Domain domain = new Domain();
        domain.setName("example.com");
        domain.setStatus(Domain.STATUS_ACTIVE);
        domain.setRegistrar("Example Registrar");
        domain.setRegistCreateDateText("1995-08-14");

        assertTrue(policy.isIndexable(domain, List.of()));
        assertEquals("index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1",
                policy.robotsDirective(domain, List.of()));
    }

    @Test
    void noindexesAReportThatOnlyHasANameAndStatus() {
        Domain domain = new Domain();
        domain.setName("thin.example");
        domain.setStatus(Domain.STATUS_ACTIVE);

        assertFalse(policy.isIndexable(domain, List.of()));
        assertEquals("noindex, follow", policy.robotsDirective(domain, List.of()));
    }
}
```

补充测试覆盖 RDAP-only、WHOIS-only、Nameserver、DNS 记录、空对象和无效名称。

- [ ] **步骤 2：运行测试确认策略类不存在**

```powershell
mvn -pl wesite-web -am -Dtest=DomainReportIndexPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，提示 `DomainReportIndexPolicy` 不存在。

- [ ] **步骤 3：实现明确的证据分组规则**

只有域名名称非空，并且以下四组证据中至少两组存在，报告才允许索引：

1. 注册身份：`registryDomainID` 或 `registrar` 非空；
2. 生命周期：`registCreateDateText` 或 `registExpiryDateText` 非空；
3. 基础设施：`nameServers` 非空或 `dnsRecords` 非空；
4. 协议原始数据：`finalWhoisText`、`rdapText` 或 `parentRdapText` 非空。

核心实现：

```java
public boolean isIndexable(Domain domain, List<DomainDns> dnsRecords) {
    if (domain == null || StringUtils.isBlank(domain.getName())) return false;
    int evidence = 0;
    if (StringUtils.isNotBlank(domain.getRegistryDomainID())
            || StringUtils.isNotBlank(domain.getRegistrar())) evidence++;
    if (StringUtils.isNotBlank(domain.getRegistCreateDateText())
            || StringUtils.isNotBlank(domain.getRegistExpiryDateText())) evidence++;
    if (StringUtils.isNotBlank(domain.getNameServers())
            || (dnsRecords != null && !dnsRecords.isEmpty())) evidence++;
    if (StringUtils.isNotBlank(domain.getFinalWhoisText())
            || StringUtils.isNotBlank(domain.getRdapText())
            || StringUtils.isNotBlank(domain.getParentRdapText())) evidence++;
    return evidence >= 2;
}
```

- [ ] **步骤 4：接入主域名报告模型**

向 `MainController` 注入 `DomainReportIndexPolicy`。完成 DNS 查询后，无论列表是否为空，都执行：

```java
mv.addObject("_page_robots", domainReportIndexPolicy.robotsDirective(domain, dnsList));
```

子域名详情不是本轮投资报告核心页面，明确设置：

```java
mv.addObject("_page_robots", "noindex, follow");
```

TLD 页面保持默认 index 行为。404 和异常响应继续使用现有错误处理，不转换成 HTTP 200。

- [ ] **步骤 5：运行测试并提交**

```powershell
mvn -pl wesite-web -am -Dtest=DomainReportIndexPolicyTest,PositioningTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全部通过。提交：

```powershell
git add wesite-web/src/main/java/info/wesite/web/seo/DomainReportIndexPolicy.java wesite-web/src/test/java/info/wesite/web/seo/DomainReportIndexPolicyTest.java wesite-web/src/main/java/info/wesite/web/controller/MainController.java wesite-web/src/test/java/info/wesite/web/view/PositioningTemplateTest.java
git commit -m "feat: noindex thin domain reports"
```

---

### 任务 5：增加部署后 SEO 契约检查

**文件：**

- 新建：`scripts/check-seo.ps1`
- 新建：`wesite-web/src/test/java/info/wesite/web/seo/SeoDeploymentScriptTest.java`
- 修改：`README.md`，在部署说明后增加 SEO 检查命令。

**接口：**

- 参数：`-BaseUrl`，默认 `https://whose.domains`。
- 成功：退出码 0，并输出 sitemap URL 数量。
- 失败：退出码 1，逐项输出不符合契约的 URL 和原因。

- [ ] **步骤 1：编写脚本文件契约的失败测试**

新建测试，先明确脚本必须具备的只读检查能力：

```java
package info.wesite.web.seo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SeoDeploymentScriptTest {

    @Test
    void deploymentScriptChecksEverySeoContractWithoutWritingRemoteState() throws Exception {
        String script = Files.readString(Path.of("..", "scripts", "check-seo.ps1"));

        assertTrue(script.contains("/robots.txt"));
        assertTrue(script.contains("/sitemap_all.xml"));
        assertTrue(script.contains("AllowAutoRedirect = $false"));
        assertTrue(script.contains("rel=[\"']canonical"));
        assertTrue(script.contains("https://www.whose.domains/"));
        assertTrue(script.contains("/tools/whois-lookup/"));
        assertTrue(script.contains("exit 1"));
        assertTrue(script.contains("exit 0"));
        assertTrue(!script.contains("Invoke-RestMethod -Method Post"));
    }
}
```

- [ ] **步骤 2：运行并确认失败**

```powershell
mvn -pl wesite-web -am -Dtest=SeoDeploymentScriptTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：测试因 `scripts/check-seo.ps1` 不存在而失败。

- [ ] **步骤 3：实现完整检查**

新建以下完整脚本。使用禁用自动跳转的 `.NET HttpClient` 获取原始状态；用 `[xml]` 解析 sitemap；用正则提取 HTML canonical。脚本只发送 GET，不修改远端状态：

```powershell
param([string]$BaseUrl = 'https://whose.domains')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$failures = [System.Collections.Generic.List[string]]::new()
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)

function Get-SeoResponse([string]$url) {
    try {
        return $client.GetAsync($url).GetAwaiter().GetResult()
    } catch {
        $failures.Add("GET failed: $url - $($_.Exception.Message)")
        return $null
    }
}

function Read-SeoBody($response) {
    if ($null -eq $response) { return '' }
    return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
}

$robotsResponse = Get-SeoResponse "$BaseUrl/robots.txt"
if ($null -eq $robotsResponse -or [int]$robotsResponse.StatusCode -ne 200) {
    $failures.Add('robots.txt must return HTTP 200')
} else {
    $robots = Read-SeoBody $robotsResponse
    if ($robots -notmatch '(?im)^Sitemap:\s+https://whose\.domains/sitemap_all\.xml\s*$') {
        $failures.Add('robots.txt does not declare the production sitemap')
    }
}

$sitemapResponse = Get-SeoResponse "$BaseUrl/sitemap_all.xml"
$locations = @()
if ($null -eq $sitemapResponse -or [int]$sitemapResponse.StatusCode -ne 200) {
    $failures.Add('sitemap_all.xml must return HTTP 200')
} else {
    try {
        [xml]$sitemap = Read-SeoBody $sitemapResponse
        $locations = @($sitemap.urlset.url.loc | ForEach-Object { [string]$_ })
    } catch {
        $failures.Add("sitemap_all.xml is not valid XML: $($_.Exception.Message)")
    }
}

foreach ($location in $locations) {
    if (-not $location.StartsWith('https://whose.domains/')) {
        $failures.Add("Non-canonical sitemap origin or root slash: $location")
        continue
    }

    $response = Get-SeoResponse $location
    if ($null -eq $response) { continue }
    if ([int]$response.StatusCode -ne 200) {
        $failures.Add("Sitemap URL must directly return 200: $location returned $([int]$response.StatusCode)")
        continue
    }

    $mediaType = $response.Content.Headers.ContentType.MediaType
    if ($mediaType -eq 'text/html') {
        $html = Read-SeoBody $response
        $canonicalMatches = [regex]::Matches(
            $html,
            '<link[^>]+rel=["'']canonical["''][^>]+href=["'']([^"'']+)["''][^>]*>',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($canonicalMatches.Count -ne 1) {
            $failures.Add("Expected one canonical link: $location found $($canonicalMatches.Count)")
        } elseif ($canonicalMatches[0].Groups[1].Value -ne $location) {
            $failures.Add("Canonical mismatch: $location -> $($canonicalMatches[0].Groups[1].Value)")
        }
    }
}

$slashResponse = Get-SeoResponse "$BaseUrl/tools/whois-lookup/"
if ($null -eq $slashResponse -or [int]$slashResponse.StatusCode -ne 301
        -or [string]$slashResponse.Headers.Location -ne 'https://whose.domains/tools/whois-lookup') {
    $failures.Add('Trailing-slash tool URL must 301 to its canonical URL')
}

$wwwResponse = Get-SeoResponse 'https://www.whose.domains/'
if ($null -eq $wwwResponse -or [int]$wwwResponse.StatusCode -ne 301
        -or [string]$wwwResponse.Headers.Location -ne 'https://whose.domains/') {
    $failures.Add('www homepage must 301 to the canonical homepage')
}

$client.Dispose()
$handler.Dispose()

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}
Write-Host "SEO checks passed for $($locations.Count) sitemap URLs."
exit 0
```

- [ ] **步骤 4：对本地或线上环境运行**

先运行 Java 文件契约测试，预期通过：

```powershell
mvn -pl wesite-web -am -Dtest=SeoDeploymentScriptTest -Dsurefire.failIfNoSpecifiedTests=false test
```

再对生产环境执行：

```powershell
pwsh -File scripts/check-seo.ps1 -BaseUrl https://whose.domains
```

预期：代码尚未部署时，脚本以退出码 1 准确列出当前线上问题；部署后以退出码 0 完成。

- [ ] **步骤 5：记录命令并提交**

README 增加：

```markdown
### Post-deployment SEO contract

Run `pwsh -File scripts/check-seo.ps1 -BaseUrl https://whose.domains` after each production deployment.
```

提交：

```powershell
git add scripts/check-seo.ps1 README.md wesite-web/src/test/java/info/wesite/web/seo/SeoDeploymentScriptTest.java
git commit -m "test: add production SEO contract checks"
```

---

### 任务 6：全量回归与上线前证据

**文件：**

- 不新增生产文件。
- 如测试揭示本计划修改引起的回归，只修改相应任务已经触及的文件，并在原任务提交之后追加一个明确的修复提交。

**接口：**

- 输出：Maven 全量测试结果。
- 输出：本地打包结果。
- 输出：部署后 SEO 契约结果；未部署时明确记录为待部署验证，不宣称线上已修复。

- [ ] **步骤 1：运行 SEO 定向测试**

```powershell
mvn -pl wesite-web -am -Dtest=SitemapTaskTest,RobotsFileTest,CanonicalUrlServiceTest,CanonicalRedirectFilterTest,DomainReportIndexPolicyTest,PositioningTemplateTest,ProductionSecurityConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全部通过，无跳过的目标测试。

- [ ] **步骤 2：运行 web 模块全量测试**

```powershell
mvn -pl wesite-web -am test
```

预期：构建成功，现有测试无回归。

- [ ] **步骤 3：运行生产包构建**

```powershell
mvn -pl wesite-web -am -DskipTests package
```

预期：构建成功并生成 web 模块 JAR。

- [ ] **步骤 4：检查变更范围和提交历史**

```powershell
git diff --check
git status --short
git log --oneline -6
```

预期：`git diff --check` 无输出；仅本计划文件或用户原有未提交修改存在；本计划每个实现任务都有独立提交。

- [ ] **步骤 5：部署后运行只读检查**

```powershell
pwsh -File scripts/check-seo.ps1 -BaseUrl https://whose.domains
```

预期：全部通过后，才能宣称生产环境技术收录基础已经修复。随后在 Search Console 重新提交 `/sitemap_all.xml`，并抽查首页、工具页、知识页、可索引域名报告和 noindex 域名报告各一个 URL。

---

## 后续独立计划

本计划完成后，按顺序分别创建并执行：

1. 创建并执行域名投资评估报告实施计划；
2. 创建并执行投资候选清单与变化提醒实施计划；
3. 创建并执行内容整合、首页定位与站内导航实施计划。

这样每个阶段都能独立上线、验证和回滚，也能用 Search Console 与产品指标判断真实效果。
