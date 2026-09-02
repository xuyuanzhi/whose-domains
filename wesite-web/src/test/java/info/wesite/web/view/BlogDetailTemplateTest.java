package info.wesite.web.view;

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

class BlogDetailTemplateTest {

    @Test
    void blogPostingSchemaIsEmittedOnlyFromControllerSerializedJson() throws Exception {
        String html = resource("/views/blog/detail.html");
        Document document = Jsoup.parse(html);
        Element schema = document.select("script[type=application/ld+json]").stream()
            .filter(element -> element.attr("th:utext").contains("_blogSchema"))
            .findFirst()
            .orElse(null);

        assertNotNull(schema);
        assertFalse(schema.hasAttr("th:inline"));
        assertFalse(html.contains("post.updateTime"));
        assertFalse(html.contains("'{\"@type\":\"Person\""));
        assertTrue(html.contains("th:utext=\"${_blogSchema}\""));
    }

    @Test
    void articleBodyRemainsUnescapedOnlyAtTheSanitizedContentBoundary() throws Exception {
        Document document = Jsoup.parse(resource("/views/blog/detail.html"));
        Element body = document.selectFirst(".blog-content");

        assertNotNull(body);
        assertTrue(body.attr("th:utext").equals("${post.content}"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = BlogDetailTemplateTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
