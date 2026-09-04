package info.wesite.admin.view;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class AdminLoginTemplateTest {

    @Test
    void loginFormSupportsAccessiblePasswordEntryAndSafeSubmission() throws Exception {
        Document login = Jsoup.parse(read("static/layuiadmin/views/user/login.html"));
        Element form = login.selectFirst("form[lay-filter=admin-login-submit]");
        Element password = login.selectFirst("input[type=password][name=password]");

        assertTrue(form != null, "登录表单必须具有 Layui 提交过滤器");
        assertTrue(password != null, "登录表单必须使用密码输入框");
        assertTrue(login.selectFirst("label[for=admin-login-password]") != null,
            "密码输入框必须有可访问标签");
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
    void adminConfigurationKeepsLoginOutsideTheAuthenticatedShell() throws Exception {
        String config = read("static/layuiadmin/config.js");

        assertTrue(config.contains("name: 'Whose.Domains Admin'"));
        assertTrue(config.contains("tableName: 'whoseDomainsAdmin'"));
        assertTrue(config.contains("debug: false"));
        assertTrue(config.contains("indPage: ['/user/login']"));
    }

    private static void assertSessionCleanupContract(String source) {
        assertTrue(source.contains("clearSession"));
        assertTrue(source.matches("(?s).*key\\s*:\\s*(?:setter|s)\\.request\\.tokenName.*"));
        assertTrue(source.matches("(?s).*key\\s*:\\s*['\"]admin['\"].*"));
        assertTrue(source.matches("(?s).*key\\s*:\\s*['\"]tabs['\"].*"));
        assertTrue(source.matches("(?s).*(?:view|u)\\.exit\\s*=\\s*function\\s*\\(\\)\\s*\\{\\s*(?:view|u)\\.clearSession\\(\\).*"));
        assertTrue(source.matches("(?s).*location\\.hash\\s*=\\s*['\"]/user/login['\"].*"));
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
