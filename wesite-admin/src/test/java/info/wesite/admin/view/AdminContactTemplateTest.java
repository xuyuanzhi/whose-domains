package info.wesite.admin.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AdminContactTemplateTest {

    private static final String CONTACT_LIST = "static/layuiadmin/views/contact/list.html";
    private static final String LEGACY_DASHBOARD = "templates/admin/dashboard.html";
    private static final String OLD_TEMPLATE = "templates/admin/contacts.html";
    private static final String OLD_SCRIPT = "static/js/admin-contacts.js";

    @Test
    void spaViewExposesStatsTableAndContactActions() throws Exception {
        String source = read(CONTACT_LIST);
        Document document = Jsoup.parse(source);

        assertNotNull(document.selectFirst("[data-stat=total]"));
        assertNotNull(document.selectFirst("[data-stat=pending]"));
        assertNotNull(document.selectFirst("[data-stat=processed]"));
        assertNotNull(document.selectFirst("table#LAY-contact-list[lay-filter=LAY-contact-list]"));
        assertNotNull(document.selectFirst("[lay-event=detail]"));
        assertNotNull(document.selectFirst("[lay-event=status]"));
        assertNotNull(document.selectFirst("[lay-event=delete]"));
        assertNotNull(document.selectFirst("[lay-event=deleteSelected]"));
    }

    @Test
    void viewUsesExactContactApiMethodsAndLayuiPaginationNames() throws Exception {
        String source = read(CONTACT_LIST);

        assertTrue(source.contains("url: '/admin/contacts/list'"));
        assertTrue(source.contains("method: 'get'"));
        assertTrue(source.contains("pageName: 'page'"));
        assertTrue(source.contains("limitName: 'size'"));
        assertTrue(source.contains("url: '/admin/contacts/stats'"));
        assertTrue(source.contains("type: 'get'"));
        assertTrue(source.contains("url: '/admin/contacts/' + encodeURIComponent(data.id)"));
        assertTrue(source.contains("url: '/admin/contacts/status'"));
        assertTrue(source.contains("type: 'post'"));
        assertTrue(source.contains("url: '/admin/contacts/delete'"));
        assertTrue(source.contains("type: 'delete'"));
        assertFalse(source.contains("/{id}/status"));
    }

    @Test
    void everyVisitorControlledValueIsEscapedBeforeEnteringTheDom() throws Exception {
        String source = read(CONTACT_LIST);

        assertTrue(source.contains("layui.util.escape"));
        assertTrue(source.contains("safeText(data.name)"));
        assertTrue(source.contains("safeText(data.email)"));
        assertTrue(source.contains("safeText(data.subject)"));
        assertTrue(source.contains("safeText(data.message)"));
        assertTrue(source.contains("setSafeText(content, 'name', detail.name)"));
        assertTrue(source.contains("setSafeText(content, 'email', detail.email)"));
        assertTrue(source.contains("setSafeText(content, 'subject', detail.subject)"));
        assertTrue(source.contains("setSafeText(content, 'message', detail.message)"));
        assertTrue(source.contains("safeText(response.msg)"));
        assertFalse(source.contains("${contact."));
        assertFalse(source.contains("content: '<"));
    }

    @Test
    void orphanServerRenderedResourcesAreRemoved() {
        assertFalse(Files.exists(sourceResource(OLD_TEMPLATE)));
        assertFalse(Files.exists(sourceResource(OLD_SCRIPT)));
    }

    @Test
    void survivingDashboardLinksContactManagementThroughTheSpaShell() throws Exception {
        Document dashboard = Jsoup.parse(read(LEGACY_DASHBOARD));

        assertNotNull(dashboard.selectFirst("a.action-card[href=\"/#/contact/list\"]"));
        assertNull(dashboard.selectFirst("a.action-card[href=\"/admin/contacts\"]"));
    }

    @Test
    void survivingDashboardUsesTheCurrentThreeContactStats() throws Exception {
        String source = read(LEGACY_DASHBOARD);
        Document dashboard = Jsoup.parse(source);

        assertNotNull(dashboard.selectFirst("#totalContacts"));
        assertNotNull(dashboard.selectFirst("#pendingContacts"));
        assertNotNull(dashboard.selectFirst("#processedContacts"));
        assertTrue(source.contains("response.data.pending"));
        assertTrue(source.contains("response.data.processed"));
        assertFalse(source.contains("response.data.today"));
    }

    @Test
    void survivingDashboardWritesAllThreeCurrentStatsAtRuntime() throws Exception {
        String output = runDashboardScript("""
            console.log(JSON.stringify(values));
            """);

        assertEquals("{\"totalContacts\":9,\"pendingContacts\":4,\"processedContacts\":5}", output);
    }

    @Test
    void tableRuntimeMapsLayuiPageAndLimitToApiPageAndSize() throws Exception {
        String output = runListScript("""
            console.log(JSON.stringify({
              url: tableOptions.url,
              method: tableOptions.method,
              pageName: tableOptions.request.pageName,
              limitName: tableOptions.request.limitName
            }));
            """);

        assertEquals("{\"url\":\"/admin/contacts/list\",\"method\":\"get\",\"pageName\":\"page\",\"limitName\":\"size\"}", output);
    }

    @Test
    void repeatedStatusActionCreatesOnlyOneRequest() throws Exception {
        String output = runListScript("""
            const rowButton = makeElement('status-button');
            const event = {event: 'status', data: {id: 'contact-1', status: 1}, tr: {find: () => rowButton}};
            callbacks.table['tool(LAY-contact-list)'](event);
            callbacks.table['tool(LAY-contact-list)'](event);
            console.log(requests.filter(request => request.url === '/admin/contacts/status').length);
            """);

        assertEquals("1", output);
    }

    @Test
    void staleDetailResponseCannotOpenOrOverwriteTheLatestDetail() throws Exception {
        String output = runListScript("""
            callbacks.table['tool(LAY-contact-list)']({
              event: 'detail', data: {id: 'contact-a'}, tr: {find: () => makeElement('a')}
            });
            callbacks.table['tool(LAY-contact-list)']({
              event: 'detail', data: {id: 'contact-b'}, tr: {find: () => makeElement('b')}
            });
            const details = requests.filter(request => request.url.startsWith('/admin/contacts/contact-'));
            details[1].success({code: 0, data: {id: 'contact-b', name: 'B', email: 'b@example.test', subject: 'B', message: 'B'}});
            details[0].success({code: 0, data: {id: 'contact-a', name: 'A', email: 'a@example.test', subject: 'A', message: 'A'}});
            console.log(JSON.stringify(opened.map(item => item.contactId)));
            """);

        assertEquals("[\"contact-b\"]", output);
    }

    @Test
    void repeatedBatchDeleteUsesOneActualDeleteRequest() throws Exception {
        String output = runListScript("""
            selectedData = [{id: 'contact-1'}, {id: 'contact-2'}];
            callbacks.table['toolbar(LAY-contact-list)']({event: 'deleteSelected'});
            callbacks.table['toolbar(LAY-contact-list)']({event: 'deleteSelected'});
            const deletes = requests.filter(request => request.url === '/admin/contacts/delete');
            console.log(JSON.stringify({count: deletes.length, type: deletes[0].type}));
            """);

        assertEquals("{\"count\":1,\"type\":\"delete\"}", output);
    }

    private static String runListScript(String scenario) throws Exception {
        String source = executableScript(read(CONTACT_LIST));
        String harness = """
            const callbacks = {table: {}};
            const requests = [];
            const opened = [];
            let selectedData = [];
            let tableOptions;

            function makeElement(name) {
              const values = {};
              return {
                name,
                data(key, value) {
                  if (arguments.length === 1) return values[key];
                  values[key] = value;
                  return this;
                },
                prop() { return this; },
                toggleClass() { return this; },
                html() { return this; },
                text() { return this; },
                clone() { return makeElement(name + '-clone'); },
                first() { return this; },
                children() { return makeElement(name + '-child'); },
                find() { return makeElement(name + '-field'); }
              };
            }
            const elements = {};
            function jquery(value) {
              if (value && typeof value.data === 'function') return value;
              const key = String(value);
              elements[key] = elements[key] || makeElement(key);
              return elements[key];
            }
            const table = {
              render(options) { tableOptions = options; },
              reload() {},
              checkStatus() { return {data: selectedData}; },
              on(name, callback) { callbacks.table[name] = callback; }
            };
            const admin = {req(options) { requests.push(options); }};
            const layer = {
              close() {}, msg() {},
              confirm(message, options, yes) { yes(1); },
              open(options) { opened.push({contactId: options.contactId, options}); }
            };
            globalThis.layer = layer;
            globalThis.layui = {
              $: jquery, admin, table,
              util: {escape: value => String(value)},
              use(dependencies, callback) { callback(); }
            };
            """ + source + scenario;
        return runNode(harness);
    }

    private static String runDashboardScript(String scenario) throws Exception {
        String source = executableScript(read(LEGACY_DASHBOARD));
        String harness = """
            const values = {};
            globalThis.document = {};
            function jquery(value) {
              if (value === document) {
                return {ready(callback) { callback(); }};
              }
              const id = String(value).replace(/^#/, '');
              return {text(text) { values[id] = text; }};
            }
            jquery.get = function(url, callback) {
              callback({code: 0, data: {total: 9, pending: 4, processed: 5, today: 99}});
            };
            globalThis.$ = jquery;
            """ + source + scenario;
        return runNode(harness);
    }

    private static String executableScript(String source) {
        int start = source.lastIndexOf("<script>");
        int end = source.indexOf("</script>", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("Missing executable contact list script");
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
            "Node.js is unavailable; static contact template contracts still execute");
    }

    private static String read(String resource) throws IOException {
        ClassLoader loader = AdminContactTemplateTest.class.getClassLoader();
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
