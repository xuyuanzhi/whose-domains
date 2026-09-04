package info.wesite.admin.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AdminDashboardTemplateTest {

    private static final String DASHBOARD = "static/layuiadmin/views/index.html";
    private static final String LEGACY_DASHBOARD = "templates/admin/dashboard.html";

    @Test
    void dashboardShowsAllBusinessMetricsAndRealQuickLinks() throws Exception {
        Document document = Jsoup.parse(read(DASHBOARD));

        Set<String> labels = document.select("[data-metric-label]").stream()
            .map(element -> element.text().trim())
            .collect(Collectors.toSet());
        assertEquals(Set.of(
            "普通用户", "顶级域名", "二级保留域名", "博客草稿", "已发布文章", "待处理留言"), labels);

        Set<String> quickLinks = document.select("[data-quick-link][lay-href]").stream()
            .map(element -> element.attr("lay-href"))
            .collect(Collectors.toSet());
        assertEquals(Set.of("person/list", "domain/tld", "domain/sld", "blog/list", "contact/list"), quickLinks);
    }

    @Test
    void dashboardLoadsSummaryAndEscapesAllRecentVisitorText() throws Exception {
        String source = read(DASHBOARD);

        assertTrue(source.contains("url: '/dashboard/summary'"));
        assertTrue(source.contains("type: 'get'"));
        assertTrue(source.contains("layui.util.escape"));
        assertTrue(source.contains("setSafeText(row, 'title', item.title)"));
        assertTrue(source.contains("setSafeText(row, 'updatedAtText', item.updatedAtText)"));
        assertTrue(source.contains("setSafeText(row, 'name', item.name)"));
        assertTrue(source.contains("setSafeText(row, 'subject', item.subject)"));
        assertTrue(source.contains("setSafeText(row, 'createTimeText', item.createTimeText)"));
        assertTrue(source.contains("safeText(response.msg)"));
    }

    @Test
    void dashboardProvidesRecoverableEmptyAndFailureStates() throws Exception {
        Document document = Jsoup.parse(read(DASHBOARD));

        assertNotNull(document.selectFirst("#dashboard-loading"));
        assertNotNull(document.selectFirst("#dashboard-error[role=alert]"));
        assertNotNull(document.selectFirst("#dashboard-retry[type=button]"));
        assertNotNull(document.selectFirst("#dashboard-empty-posts"));
        assertNotNull(document.selectFirst("#dashboard-empty-contacts"));
    }

    @Test
    void demoContentAndLegacyServerRenderedDashboardAreGone() throws Exception {
        String source = read(DASHBOARD);

        assertFalse(source.contains("Welcome"));
        assertFalse(source.contains("json/console"));
        assertFalse(source.contains("�"));
        assertFalse(Files.exists(sourceResource(LEGACY_DASHBOARD)));
    }

    @Test
    void runtimeEscapesRecentTextBeforeWritingHtml() throws Exception {
        String output = runDashboardScript("""
            requests[0].success({code: 0, data: {
              userCount: 1, tldCount: 2, sldCount: 3, draftPostCount: 4,
              publishedPostCount: 5, pendingContactCount: 6,
              recentPosts: [{title: '<img src=x>', updatedAtText: '<time>', status: 1}],
              recentContacts: [{name: '<b>name</b>', subject: '<svg>', createTimeText: '<time>', status: 1}]
            }});
            console.log(htmlWrites.some(value => /^</.test(String(value))));
            """);

        assertEquals("false", output);
    }

    @Test
    void retryAfterFailureStartsOneNewSummaryRequest() throws Exception {
        String output = runDashboardScript("""
            requests[0].success({code: 500, msg: '<failure>'});
            requests[0].complete();
            handlers['click:#dashboard-retry']();
            console.log(requests.length);
            """);

        assertEquals("2", output);
    }

    private static String runDashboardScript(String scenario) throws Exception {
        String source = executableScript(read(DASHBOARD));
        String harness = """
            const requests = [];
            const handlers = {};
            const htmlWrites = [];
            let cloneCounter = 0;
            globalThis.document = {};

            function element(name) {
              return {
                name,
                empty() { return this; },
                append() { return this; },
                addClass() { return this; },
                removeClass() { return this; },
                attr() { return this; },
                prop() { return this; },
                text() { return this; },
                html(value) { htmlWrites.push(value); return this; },
                find(selector) { return element(name + ' ' + selector); },
                children() { return this; },
                first() { return this; },
                clone() { return element(name + '-clone-' + (++cloneCounter)); },
                on(event, selector, callback) {
                  handlers[event + ':' + selector] = callback;
                  return this;
                }
              };
            }
            function jquery(value) { return element(String(value)); }
            const admin = {req(options) { requests.push(options); }};
            globalThis.layer = {msg() {}};
            globalThis.layui = {
              $: jquery,
              admin,
              util: {escape(value) {
                return String(value)
                  .replaceAll('&', '&amp;')
                  .replaceAll('<', '&lt;')
                  .replaceAll('>', '&gt;');
              }},
              use(dependencies, callback) { callback(); }
            };
            """ + source + scenario;
        return runNode(harness);
    }

    private static String executableScript(String source) {
        int start = source.lastIndexOf("<script>");
        int end = source.indexOf("</script>", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("Missing executable dashboard script");
        }
        return source.substring(start + "<script>".length(), end);
    }

    private static String runNode(String probe) throws Exception {
        assumeNodeAvailable();
        String encoded = Base64.getEncoder().encodeToString(probe.getBytes(StandardCharsets.UTF_8));
        String evaluation = "eval(Buffer.from('" + encoded + "', 'base64').toString('utf8'))";
        Process process = new ProcessBuilder("node", "--input-type=module", "--eval", evaluation)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static void assumeNodeAvailable() {
        boolean available = false;
        try {
            Process check = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true)
                .start();
            available = check.waitFor() == 0;
        } catch (IOException exception) {
            available = false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        Assumptions.assumeTrue(available,
            "Node.js is unavailable; static dashboard template contracts still execute");
    }

    private static String read(String resource) throws IOException {
        ClassLoader loader = AdminDashboardTemplateTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path sourceResource(String resource) {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path module = Files.isDirectory(workingDirectory.resolve("wesite-admin"))
            ? workingDirectory.resolve("wesite-admin")
            : workingDirectory;
        return module.resolve("src/main/resources").resolve(resource);
    }
}
