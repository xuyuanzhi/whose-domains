package info.wesite.admin.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class AdminDomainTemplateTest {

    private static final String TLD_LIST = "static/layuiadmin/views/domain/tld/index.html";
    private static final String TLD_EDIT = "static/layuiadmin/views/domain/tld/edit.html";
    private static final String SLD_LIST = "static/layuiadmin/views/domain/sld/index.html";
    private static final String SLD_ADD = "static/layuiadmin/views/domain/sld/add.html";
    private static final String SLD_EDIT = "static/layuiadmin/views/domain/sld/edit.html";

    @Test
    void tldListUsesOnlyItsRealRoutesAndMatchingTableEvents() throws Exception {
        String source = read(TLD_LIST);
        Document document = Jsoup.parse(source);

        assertNotNull(document.selectFirst("table#LAY-domain-tlds[lay-filter=LAY-domain-tlds]"));
        assertEquals(Set.of("/domain/tld/list", "/domain/tld/detail", "/domain/tld/save"), routes(source));
        assertTrue(source.contains("table.on('toolbar(LAY-domain-tlds)'"));
        assertTrue(source.contains("table.on('tool(LAY-domain-tlds)'"));
        assertTrue(source.contains("table.reload('LAY-domain-tlds'"));
        assertFalse(source.contains("LAY-user-manage"));
    }

    @Test
    void sldListUsesOnlyItsRealRoutesAndMatchingTableEvents() throws Exception {
        String source = read(SLD_LIST);
        Document document = Jsoup.parse(source);

        assertNotNull(document.selectFirst("table#LAY-domain-slds[lay-filter=LAY-domain-slds]"));
        assertEquals(Set.of("/domain/sld/list", "/domain/sld/detail", "/domain/sld/save"), routes(source));
        assertTrue(source.contains("table.on('toolbar(LAY-domain-slds)'"));
        assertTrue(source.contains("table.on('tool(LAY-domain-slds)'"));
        assertTrue(source.contains("table.reload('LAY-domain-slds'"));
        assertTrue(source.contains("render('domain/sld/add')"));
        assertTrue(source.contains("render('domain/sld/edit')"));
        assertFalse(source.contains("LAY-user-manage"));
    }

    @Test
    void remoteCellTextIsEscapedAndDetailValuesAreAssignedAsValues() throws Exception {
        String tld = read(TLD_LIST);
        String sld = read(SLD_LIST);

        assertTrue(tld.contains("layui.util.escape"));
        assertTrue(tld.contains("safeText(data.displayName)"));
        assertTrue(tld.contains("safeText(data.type)"));
        assertTrue(tld.contains("safeText(data.orgName)"));
        assertTrue(tld.contains("safeText(response.msg)"));
        assertTrue(tld.contains("form.val('domain-tld-edit-form'"));
        assertTrue(sld.contains("layui.util.escape"));
        assertTrue(sld.contains("safeText(data.name)"));
        assertTrue(sld.contains("safeText(data.tldName)"));
        assertTrue(sld.contains("safeText(data.countryName)"));
        assertTrue(sld.contains("safeText(data.note)"));
        assertTrue(sld.contains("safeText(response.msg)"));
        assertTrue(sld.contains("form.val('domain-sld-edit-form'"));
        assertFalse(read(TLD_EDIT).contains("{{ d.params"));
        assertFalse(read(SLD_EDIT).contains("{{ d.params"));
        assertFalse(read(SLD_ADD).contains("{{ d.params"));
    }

    @Test
    void submissionsAreLockedAndKnownWrongEndpointsAreGone() throws Exception {
        String all = read(TLD_LIST) + read(TLD_EDIT) + read(SLD_LIST) + read(SLD_ADD) + read(SLD_EDIT);

        assertTrue(all.contains(".data('busy')"));
        assertTrue(all.contains(".prop('disabled', busy)"));
        assertFalse(all.contains("/listing/"));
        assertFalse(all.contains("LAY-user-manage"));
        assertFalse(all.contains("url: 'xxx'"));
        assertFalse(all.contains("res/json/upload"));
        assertFalse(all.contains("�"));
    }

    @Test
    void repeatedSldSubmissionCreatesOnlyOneRequest() throws Exception {
        String output = runListScript(SLD_LIST, """
            callbacks.table['toolbar(LAY-domain-slds)']({event: 'add'});
            const submit = popups[0].submit;
            const event = {field: {name: 'gov.cn', countryName: '中国', note: ''}, elem: submit};
            callbacks.form['submit(domain-sld-add-submit)'](event);
            callbacks.form['submit(domain-sld-add-submit)'](event);
            console.log(requests.filter(request => request.url === '/domain/sld/save').length);
            """);

        assertEquals("1", output);
    }

    @Test
    void delayedSldSaveClosesOnlyThePopupThatSubmittedIt() throws Exception {
        String output = runListScript(SLD_LIST, """
            callbacks.table['toolbar(LAY-domain-slds)']({event: 'add'});
            const firstSubmit = popups[0].submit;
            callbacks.form['submit(domain-sld-add-submit)']({
              field: {name: 'gov.cn', countryName: '中国', note: ''}, elem: firstSubmit
            });
            callbacks.table['toolbar(LAY-domain-slds)']({event: 'add'});
            requests.find(request => request.url === '/domain/sld/save').success({code: 0});
            console.log(JSON.stringify(closed));
            """);

        assertEquals("[1]", output);
    }

    @Test
    void staleTldDetailCannotOpenOrOverwriteTheLatestEdit() throws Exception {
        String output = runListScript(TLD_LIST, """
            const firstButton = makeButton('edit-a');
            const secondButton = makeButton('edit-b');
            callbacks.table['tool(LAY-domain-tlds)']({
              event: 'edit', data: {id: 'tld-a'}, tr: {find: () => firstButton}
            });
            callbacks.table['tool(LAY-domain-tlds)']({
              event: 'edit', data: {id: 'tld-b'}, tr: {find: () => secondButton}
            });
            const details = requests.filter(request => request.url === '/domain/tld/detail');
            details[1].success({code: 0, data: {id: 'tld-b', displayName: '.b', orgName: 'B'}});
            details[0].success({code: 0, data: {id: 'tld-a', displayName: '.a', orgName: 'A'}});
            console.log(JSON.stringify({popupCount: popups.length, id: formValues.at(-1).value.id}));
            """);

        assertEquals("{\"popupCount\":1,\"id\":\"tld-b\"}", output);
    }

    @Test
    void staleSldDetailCannotOpenOrOverwriteTheLatestEdit() throws Exception {
        String output = runListScript(SLD_LIST, """
            const firstButton = makeButton('edit-a');
            const secondButton = makeButton('edit-b');
            callbacks.table['tool(LAY-domain-slds)']({
              event: 'edit', data: {id: 'sld-a'}, tr: {find: () => firstButton}
            });
            callbacks.table['tool(LAY-domain-slds)']({
              event: 'edit', data: {id: 'sld-b'}, tr: {find: () => secondButton}
            });
            const details = requests.filter(request => request.url === '/domain/sld/detail');
            details[1].success({code: 0, data: {id: 'sld-b', name: 'gov.b', status: 1}});
            details[0].success({code: 0, data: {id: 'sld-a', name: 'gov.a', status: 1}});
            console.log(JSON.stringify({popupCount: popups.length, id: formValues.at(-1).value.id}));
            """);

        assertEquals("{\"popupCount\":1,\"id\":\"sld-b\"}", output);
    }

    private static Set<String> routes(String source) {
        Pattern pattern = Pattern.compile("url\\s*:\\s*['\"]([^'\"]+)['\"]");
        Matcher matcher = pattern.matcher(source);
        java.util.HashSet<String> routes = new java.util.HashSet<>();
        while (matcher.find()) {
            routes.add(matcher.group(1));
        }
        return routes;
    }

    private static String runListScript(String resource, String scenario) throws Exception {
        String source = executableScript(read(resource));
        String harness = """
            const callbacks = {form: {}, table: {}};
            const requests = [];
            const popups = [];
            const closed = [];
            const formValues = [];

            function makeButton(name) {
              const values = {};
              return {
                name,
                data(key, value) {
                  if (arguments.length === 1) return values[key];
                  values[key] = value;
                  return this;
                },
                prop() { return this; },
                toggleClass() { return this; }
              };
            }
            function jquery(value) {
              return value && typeof value.data === 'function' ? value : makeButton(String(value));
            }
            jquery.trim = value => String(value).trim();
            const form = {
              render() {},
              on(name, callback) { callbacks.form[name] = callback; },
              val(filter, value) { formValues.push({filter, value}); }
            };
            const table = {
              render() {}, reload() {}, reloadData() {},
              on(name, callback) { callbacks.table[name] = callback; }
            };
            function view() {
              return {render() { return {done(callback) { callback(); }}; }};
            }
            const admin = {
              req(options) { requests.push(options); },
              popup(options) {
                const index = popups.length + 1;
                const submit = makeButton('popup-' + index);
                const layero = {find() { return submit; }};
                popups.push({options, index, submit});
                options.success.call(options, layero, index);
              }
            };
            globalThis.layer = {close(index) { closed.push(index); }, msg() {}, confirm() {}};
            globalThis.layui = {
              $: jquery, admin, table, form, view,
              util: {escape: value => String(value)},
              use(dependencies, callback) { callback(); }
            };
            """ + source + scenario;
        return runNode(harness);
    }

    private static String executableScript(String source) {
        int start = source.lastIndexOf("<script>");
        int end = source.indexOf("</script>", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("Missing executable domain list script");
        }
        return source.substring(start + "<script>".length(), end);
    }

    private static String runNode(String probe) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(probe.getBytes(StandardCharsets.UTF_8));
        String evaluation = "eval(Buffer.from('" + encoded + "', 'base64').toString('utf8'))";
        Process process = new ProcessBuilder("node", "--input-type=module", "--eval", evaluation)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static String read(String resource) throws IOException {
        ClassLoader loader = AdminDomainTemplateTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
