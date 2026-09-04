package info.wesite.admin.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

class AdminNavigationTemplateTest {

    private static final Pattern MENU_JUMP = Pattern.compile("\\\"jump\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Test
    void menuExposesOnlyTheSixSupportedAdminDestinations() throws Exception {
        String menu = read("static/layuiadmin/json/menu.js");

        assertEquals(List.of("/", "person/list", "domain/tld", "domain/sld", "blog/list", "contact/list"),
            menuDestinations(menu), "菜单只能链接到当前后台已支持的页面");
        for (String demoEntry : List.of("senior", "template", "app", "component", "www.baidu.com")) {
            assertFalse(menu.contains(demoEntry), "菜单不得保留演示入口：" + demoEntry);
        }
    }

    @Test
    void routeParentsMatchTheirFirstPathSegmentSoDirectNavigationExpandsThem() throws Exception {
        JSONArray menu = JSON.parseObject(read("static/layuiadmin/json/menu.js")).getJSONArray("data");

        assertEquals("domain", menuItem(menu, "域名管理").getString("name"));
        assertEquals("blog", menuItem(menu, "内容管理").getString("name"));
        assertEquals("contact", menuItem(menu, "客户服务").getString("name"));
    }

    @Test
    void layoutUsesTheSafeSessionViewAndLogsOutBeforeLocalCleanup() throws Exception {
        String layout = read("static/layuiadmin/views/layout.html");

        assertTrue(layout.contains("lay-url=\"./userInfo\""), "管理员信息必须来自安全的 /userInfo 视图");
        assertTrue(layout.contains("d.data.name"), "管理员展示只能消费安全视图中的名称");
        assertFalse(layout.contains("layadmin-event=\"note\""));
        assertFalse(layout.contains("layadmin-event=\"theme\""));
        assertFalse(layout.contains("layadmin-event=\"about\""));
        assertFalse(layout.contains("layadmin-event=\"more\""));
        assertFalse(layout.contains("template/search"));
        assertFalse(layout.contains("system/about"));

        int logoutRequest = layout.indexOf("url: '/logout'");
        assertTrue(logoutRequest >= 0, "退出必须请求 /logout");
        assertTrue(layout.contains("complete: function()"), "无论退出请求成功或失败都必须清理本地会话");
        assertTrue(layout.contains("logoutInProgress"), "重复点击退出时不得重复发起请求");
        assertEquals("[\"clear\",\"/user/login\"]", invokeLogoutComplete(layout),
            "退出请求完成后必须清理本地会话并回到登录页");
    }

    @Test
    void runtimeConfigurationUsesTheBrandThemeAndAnIsolatedStorageKey() throws Exception {
        String config = read("static/layuiadmin/config.js");
        String output = runNode("""
            let setter;
            globalThis.layui = {
              cache: { base: '/static/' },
              define: function(dependencies, factory) {
                factory(function(name, value) {
                  if (name === 'setter') setter = value;
                });
              }
            };
            """ + config + """
            const color = setter.theme.color[setter.theme.initColorIndex];
            console.log(JSON.stringify({ tableName: setter.tableName, color }));
            """);
        JSONObject runtime = JSON.parseObject(output);
        JSONObject color = runtime.getJSONObject("color");

        assertEquals("whoseDomainsAdmin", runtime.getString("tableName"));
        assertFalse("layuiAdmin".equals(runtime.getString("tableName")), "新后台不能读取旧 LayuiAdmin 本地存储");
        assertEquals("#17365D", color.getString("main"));
        assertEquals("#168F8B", color.getString("selected"));
        assertEquals("#17365D", color.getString("logo"));
    }

    @Test
    void sourceAndDistributionCssKeepTheSameMinimalBrandSupplement() throws Exception {
        String source = brandingSupplement(read("static/layuiadmin/adminui/src/css/admin.css"));
        String distribution = brandingSupplement(read("static/layuiadmin/adminui/dist/css/admin.css"));

        assertTrue(source.contains(":root{--whose-domains-focus: #F0B429;}"));
        assertTrue(distribution.contains(":root{--whose-domains-focus:#F0B429}"));
        assertFalse(source.contains("background-color"), "动态主题负责品牌背景色，补充 CSS 不得覆盖它");
        assertFalse(distribution.contains("background-color"), "发布版补充 CSS 不能覆盖动态主题");
        assertTrue(source.contains(".layui-layout-admin a:focus-visible"));
        assertTrue(distribution.contains(".layui-layout-admin a:focus-visible"));
        assertTrue(source.contains(".layui-layout-admin .layui-layout-left .layui-nav-item{margin: 0 8px;}"));
        assertTrue(distribution.contains(".layui-layout-admin .layui-layout-left .layui-nav-item{margin:0 8px}"));
    }

    private static List<String> menuDestinations(String menu) {
        Matcher matcher = MENU_JUMP.matcher(menu);
        List<String> destinations = new ArrayList<>();
        while (matcher.find()) {
            destinations.add(matcher.group(1));
        }
        return destinations;
    }

    private static JSONObject menuItem(JSONArray menu, String title) {
        for (int index = 0; index < menu.size(); index++) {
            JSONObject item = menu.getJSONObject(index);
            if (title.equals(item.getString("title"))) {
                return item;
            }
        }
        throw new AssertionError("Missing menu item: " + title);
    }

    private static String invokeLogoutComplete(String layout) throws Exception {
        int callback = layout.indexOf("complete: function()");
        int function = layout.indexOf("function", callback);
        String complete = functionSource(layout, function);
        String probe = """
            const calls = [];
            const layui = { view: { clearSession: () => calls.push('clear') } };
            const location = {};
            const complete =
            """ + complete + """
            ;
            complete();
            calls.push(location.hash);
            console.log(JSON.stringify(calls));
            """;
        return runNode(probe);
    }

    private static String functionSource(String source, int functionStart) {
        int bodyStart = source.indexOf('{', functionStart);
        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(functionStart, index + 1);
            }
        }
        throw new AssertionError("Unclosed logout completion callback");
    }

    private static String brandingSupplement(String css) {
        int supplement = css.indexOf(":root{--whose-domains-focus");
        if (supplement < 0) {
            throw new AssertionError("Missing Whose.Domains CSS supplement");
        }
        return css.substring(supplement);
    }

    private static String runNode(String probe) throws Exception {
        Process process = new ProcessBuilder("node", "--input-type=module", "--eval", probe)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static String read(String resource) throws IOException {
        ClassLoader loader = AdminNavigationTemplateTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
