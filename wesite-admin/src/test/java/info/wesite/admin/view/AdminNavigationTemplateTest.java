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
        int cleanup = layout.indexOf("view.clearSession()", logoutRequest);
        assertTrue(logoutRequest >= 0, "退出必须请求 /logout");
        assertTrue(cleanup > logoutRequest, "本地会话只能在 /logout 请求之后清理");
        assertTrue(layout.contains("complete: function()"), "无论退出请求成功或失败都必须清理本地会话");
        assertTrue(layout.contains("logoutInProgress"), "重复点击退出时不得重复发起请求");
        assertTrue(layout.contains("location.hash = '/user/login'"), "退出后必须回到登录页");
    }

    private static List<String> menuDestinations(String menu) {
        Matcher matcher = MENU_JUMP.matcher(menu);
        List<String> destinations = new ArrayList<>();
        while (matcher.find()) {
            destinations.add(matcher.group(1));
        }
        return destinations;
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
