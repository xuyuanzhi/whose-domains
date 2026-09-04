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
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AdminUserTemplateTest {

    @Test
    void listUsesRealUserRoutesAndTheSingleUserTable() throws Exception {
        String list = read("static/layuiadmin/views/person/list.html");
        Document document = Jsoup.parse(list);

        Element table = document.selectFirst("table#LAY-user-manage[lay-filter=LAY-user-manage]");
        assertNotNull(table);
        assertTrue(list.contains("url: '/user/list'"));
        assertTrue(list.contains("url: '/user/delete'"));
        assertTrue(list.contains("render('person/add')"));
        assertTrue(list.contains("render('person/edit')"));
        assertTrue(list.contains("url: '/user/detail'"));
        assertTrue(list.contains("table.reload('LAY-user-manage'"));
    }

    @Test
    void formsSubmitOnlyEditableUserFieldsToTheSaveRoute() throws Exception {
        String list = read("static/layuiadmin/views/person/list.html");
        Document add = Jsoup.parse(read("static/layuiadmin/views/person/add.html"));
        Document edit = Jsoup.parse(read("static/layuiadmin/views/person/edit.html"));

        assertTrue(list.contains("url: '/user/save'"));
        assertEquals(Set.of("name", "phoneNo", "status"), fieldNames(add));
        assertEquals(Set.of("id", "name", "phoneNo", "status"), fieldNames(edit));
        assertTrue(add.text().contains("用户名称"));
        assertTrue(add.text().contains("手机号码"));
        assertTrue(add.text().contains("状态"));
        assertTrue(edit.text().contains("用户名称"));
        assertTrue(edit.text().contains("手机号码"));
        assertTrue(edit.text().contains("状态"));
        assertFalse(fieldNames(add).contains("password"));
        assertFalse(fieldNames(edit).contains("userType"));
    }

    @Test
    void remoteTextIsEscapedAndFormValuesAreAssignedAsValues() throws Exception {
        String list = read("static/layuiadmin/views/person/list.html");

        assertTrue(list.contains("layui.util.escape"));
        assertTrue(list.contains("safeText(data.name)"));
        assertTrue(list.contains("safeText(data.phoneNo)"));
        assertTrue(list.contains("safeText(data.statusText)"));
        assertTrue(list.contains("form.val('person-edit-form'"));
        assertFalse(list.contains("{{ d.name"));
        assertFalse(list.contains("{{ d.phoneNo"));
    }

    @Test
    void submissionsAreLockedAndKnownPlaceholderEndpointsAreGone() throws Exception {
        String all = read("static/layuiadmin/views/person/list.html")
            + read("static/layuiadmin/views/person/add.html")
            + read("static/layuiadmin/views/person/edit.html");

        assertTrue(all.contains(".data('busy')"));
        assertTrue(all.contains(".prop('disabled', busy)"));
        assertTrue(all.contains("layer.confirm"));
        assertFalse(all.contains("/website/"));
        assertFalse(all.contains("monitor/"));
        assertFalse(all.contains("url: 'xxx'"));
        assertFalse(all.contains("res/json/upload"));
    }

    @Test
    void delayedSaveResponseClosesOnlyThePopupThatSubmittedIt() throws Exception {
        String output = runPersonListScript("""
            callbacks.table['toolbar(LAY-user-manage)']({event: 'add'});
            const firstSubmit = popups[0].submit;
            callbacks.form['submit(person-add-submit)']({
              field: {name: 'Alice', phoneNo: '13800000000', status: '1'},
              elem: firstSubmit
            });
            callbacks.table['toolbar(LAY-user-manage)']({event: 'add'});
            requests.find(request => request.url === '/user/save').success({code: 0});
            console.log(JSON.stringify(closed));
            """);

        assertEquals("[1]", output,
            "a delayed save must close its initiating popup, never a newer popup");
    }

    @Test
    void staleDetailResponseCannotOpenOrOverwriteTheLatestEdit() throws Exception {
        String output = runPersonListScript("""
            const firstButton = makeButton('edit-a');
            const secondButton = makeButton('edit-b');
            callbacks.table['tool(LAY-user-manage)']({
              event: 'edit', data: {id: 'person-a'}, tr: {find: () => firstButton}
            });
            callbacks.table['tool(LAY-user-manage)']({
              event: 'edit', data: {id: 'person-b'}, tr: {find: () => secondButton}
            });
            const details = requests.filter(request => request.url === '/user/detail');
            details[1].success({code: 0, data: {
              id: 'person-b', name: 'Bob', phoneNo: '13900000000', status: 1
            }});
            details[0].success({code: 0, data: {
              id: 'person-a', name: 'Alice', phoneNo: '13800000000', status: 1
            }});
            console.log(JSON.stringify({
              popupCount: popups.length,
              lastName: formValues.at(-1).value.name
            }));
            """);

        assertEquals("{\"popupCount\":1,\"lastName\":\"Bob\"}", output,
            "only the latest edit request may populate an edit popup");
    }

    private static Set<String> fieldNames(Document document) {
        return document.select("input[name], select[name]").stream()
            .map(element -> element.attr("name"))
            .collect(Collectors.toSet());
    }

    private static String runPersonListScript(String scenario) throws Exception {
        String source = executableScript(read("static/layuiadmin/views/person/list.html"));
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
              render() {},
              reload() {},
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
            globalThis.layer = {
              close(index) { closed.push(index); },
              msg() {},
              confirm() {}
            };
            globalThis.layui = {
              $: jquery,
              admin,
              table,
              form,
              view,
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
            throw new AssertionError("Missing executable person list script");
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
            "Node.js is unavailable; static user template contracts still execute");
    }

    private static String read(String resource) throws IOException {
        ClassLoader loader = AdminUserTemplateTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
