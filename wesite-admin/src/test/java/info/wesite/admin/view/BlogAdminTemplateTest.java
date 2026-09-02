package info.wesite.admin.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class BlogAdminTemplateTest {

    @Test
    void menuAndViewsExposeEditorialWorkflowWithoutScriptEnabledPreview() throws Exception {
        String menu = read("static/layuiadmin/json/menu.js");
        String list = read("static/layuiadmin/views/blog/list.html");
        Document edit = Jsoup.parse(read("static/layuiadmin/views/blog/edit.html"));

        assertTrue(menu.contains("博客管理"));
        assertTrue(menu.contains("blog/list"));
        assertTrue(list.contains("/blog/publish"));
        assertTrue(list.contains("/blog/unpublish"));
        Element preview = edit.selectFirst("iframe#blog-preview");
        assertNotNull(preview);
        assertTrue(preview.hasAttr("sandbox"));
        assertFalse(preview.attr("sandbox").contains("allow-scripts"));
        assertFalse(preview.attr("sandbox").contains("allow-same-origin"));
    }

    @Test
    void listEscapesRemoteTextEncodesPublicSlugAndLocksActionsWhilePending() throws Exception {
        String list = read("static/layuiadmin/views/blog/list.html");

        assertTrue(list.contains("layui.util.escape"));
        assertTrue(list.contains("encodeURIComponent(data.slug)"));
        assertTrue(list.contains(".prop('disabled', busy)"));
        assertTrue(list.contains("setBusy(button, true)"));
        assertTrue(list.contains("setBusy(button, false)"));
        assertFalse(list.contains("{{ d.title"));
        assertFalse(list.contains("{{ d.slug"));
    }

    @Test
    void editorUsesValueAssignmentLocksPublishedSlugAndPreviewsServerSanitizedHtml() throws Exception {
        String edit = read("static/layuiadmin/views/blog/edit.html");

        assertTrue(edit.contains("url: '/blog/detail'"));
        assertTrue(edit.contains("$('#blog-content').val(detail.content || '')"));
        assertTrue(edit.contains("$('#blog-slug').prop('readonly', detail.status === 1)"));
        assertFalse(edit.contains("$('#blog-content').html("));
        assertTrue(edit.contains("url: '/blog/preview'"));
        assertTrue(edit.contains("document.getElementById('blog-preview').srcdoc = resp.data.html"));
    }

    @Test
    void publishSavesFirstAndButtonsRecoverAfterEveryRequest() throws Exception {
        String edit = read("static/layuiadmin/views/blog/edit.html");

        assertTrue(edit.contains("saveBlog(function()"));
        assertTrue(edit.contains("publishBlog();"));
        assertTrue(edit.contains(".prop('disabled', busy)"));
        assertTrue(edit.contains("setEditorBusy(true)"));
        assertTrue(edit.contains("setEditorBusy(false)"));
    }

    private static String read(String resource) throws IOException {
        ClassLoader loader = BlogAdminTemplateTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
