package info.wesite.admin.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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

    private static Set<String> fieldNames(Document document) {
        return document.select("input[name], select[name]").stream()
            .map(element -> element.attr("name"))
            .collect(Collectors.toSet());
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
