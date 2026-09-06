package info.wesite.admin.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AdminLoginTemplateTest {

    @Test
    void mainEntryLoadsVersionedLoginStylesBeforeSpaRendering() throws Exception {
        Document entry = Jsoup.parse(read("views/index.html"));
        Element loginStyles = entry.select("link[rel=stylesheet]").stream()
            .filter(link -> link.attr("th:href")
                .contains("static/layuiadmin/adminui/dist/css/login.css"))
            .findFirst()
            .orElse(null);

        assertNotNull(loginStyles,
            "the entry page must load the login layout stylesheet before rendering the login view");
        assertTrue(loginStyles.attr("th:href").contains("v={v}"),
            "the login stylesheet must use the shared cache-busting version");

        String loginCss = read("static/layuiadmin/adminui/dist/css/login.css");
        assertTrue(loginCss.contains(".layadmin-user-login-main"));
        assertTrue(loginCss.contains("@media screen and (max-width:768px)"));
    }

    @Test
    void loginFormSupportsAccessiblePasswordEntryAndSafeSubmission() throws Exception {
        Document login = Jsoup.parse(read("static/layuiadmin/views/user/login.html"));
        Element form = login.selectFirst("form[lay-filter=admin-login-submit]");
        Element password = login.selectFirst("input[type=password][name=password]");

        assertTrue(form != null, "登录表单必须具有 Layui 提交过滤器");
        assertTrue(password != null, "登录表单必须使用密码输入框");
        assertTrue(login.selectFirst("label[for=admin-login-password]") != null,
            "密码输入框必须有可访问标签");
        Element passwordLabel = login.selectFirst("label[for=admin-login-password]");
        assertFalse(passwordLabel.hasClass("layui-hide"), "password label must remain in the accessibility tree");
        assertFalse(passwordLabel.attr("style").replaceAll("\\s", "").contains("display:none"),
            "password label must not use display:none");
        assertTrue(login.selectFirst("button[type=button][aria-controls=admin-login-password]") != null,
            "必须提供显示密码控件");

        String source = read("static/layuiadmin/views/user/login.html");
        assertTrue(source.contains("url: '/login'"));
        assertTrue(source.contains("contentType: 'application/json'"));
        assertTrue(source.contains("JSON.stringify(field)"));
        assertTrue(source.contains(".prop('disabled', busy)"));
        assertTrue(source.contains("/^\\/(?!\\/)/.test(value || '') ? value : '/'") );
        assertTrue(source.contains("layui.data(setter.tableName, { key: setter.request.tokenName, value: response.data.token })"));
    }

    @Test
    void sessionCleanupIsSharedByLogoutAndUnauthorizedResponsesInBothBundles() throws Exception {
        assertSessionCleanupContract(read("static/layuiadmin/adminui/src/modules/view.js"));
        assertSessionCleanupContract(read("static/layuiadmin/adminui/dist/modules/view.js"));
    }

    @Test
    void encodedRedirectsPreserveInternalPathsAndSafelyRejectUnsafeValues() throws Exception {
        String source = read("static/layuiadmin/views/user/login.html");
        assertTrue(source.contains("(layui.router().search || {}).redirect"),
            "login redirect must be read from the Layui router search map");
        assertFalse(source.contains("(layui.router().params || {}).redirect"),
            "Layui puts redirect= in router.search, not router.params");

        assumeNodeAvailable();
        int start = source.indexOf("function decodeRedirect");
        int end = source.indexOf("function setBusy", start);
        assertTrue(start >= 0 && end > start, "login must decode route parameters before validation");

        String redirectFunctions = source.substring(start, end);
        String probe = redirectFunctions + """
            let route;
            globalThis.layui = {router: () => route};
            function resolve(candidate) {
              route = candidate;
              return redirectFromRouter();
            }
            console.log(JSON.stringify([
              resolve({search: {redirect: '%2Fdomain%2Flist'}}),
              resolve({search: {redirect: '%2F%2Fevil.example'}}),
              resolve({search: {redirect: '%E0%A4%A'}}),
              resolve({params: {redirect: '%2Fperson%2Flist'}})
            ]));
            """;
        Process process = new ProcessBuilder("node", "--input-type=module", "--eval", probe)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        assertEquals(0, process.waitFor());
        assertEquals("[\"/domain/list\",\"/\",\"/\",\"/\"]", output);
    }

    @Test
    void loginFeedbackIsNotDuplicatedByTheSharedRequestWrapper() throws Exception {
        assumeNodeAvailable();
        ClassLoader loader = AdminLoginTemplateTest.class.getClassLoader();
        for (String bundle : new String[] {"src", "dist"}) {
            Process process = new ProcessBuilder("node",
                java.nio.file.Path.of(loader.getResource("admin-login-feedback.cjs").toURI()).toString(),
                java.nio.file.Path.of(loader.getResource("static/layuiadmin/config.js").toURI()).toString(),
                java.nio.file.Path.of(loader.getResource("static/layuiadmin/adminui/" + bundle + "/modules/view.js").toURI()).toString(),
                java.nio.file.Path.of(loader.getResource("static/layuiadmin/views/user/login.html").toURI()).toString())
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), bundle + ": " + output);
        }
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
            "Node.js is unavailable; static login template contracts still execute");
    }

    @Test
    void adminConfigurationKeepsLoginOutsideTheAuthenticatedShell() throws Exception {
        String config = read("static/layuiadmin/config.js");

        assertTrue(config.contains("name: 'Whose.Domains Admin'"));
        assertTrue(config.contains("tableName: 'whoseDomainsAdmin'"));
        assertTrue(config.contains("debug: false"));
        assertTrue(config.contains("indPage: ['/user/login']"));
        assertEquals(1, countProperty(config, "name"));
        assertEquals(1, countProperty(config, "tableName"));
        assertEquals(1, countProperty(config, "debug"));
    }

    private static void assertSessionCleanupContract(String source) {
        assertTrue(source.contains("clearSession"));
        assertTrue(source.matches("(?s).*key\\s*:\\s*(?:setter|s)\\.request\\.tokenName.*"));
        assertTrue(source.matches("(?s).*key\\s*:\\s*['\"]admin['\"].*"));
        assertTrue(source.matches("(?s).*key\\s*:\\s*['\"]tabs['\"].*"));
        assertTrue(source.matches("(?s).*(?:view|u)\\.exit\\s*=\\s*function\\s*\\(\\)\\s*\\{\\s*(?:view|u)\\.clearSession\\(\\).*"));
        assertTrue(source.matches("(?s).*location\\.hash\\s*=\\s*['\"]/user/login['\"].*"));
    }

    private static long countProperty(String source, String property) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(property) + "\\s*:")
            .matcher(source)
            .results()
            .count();
    }

    private static String read(String resource) throws IOException {
        ClassLoader loader = AdminLoginTemplateTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
