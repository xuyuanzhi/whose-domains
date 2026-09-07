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
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import info.wesite.core.entity.BlogPost;

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

    @Test
    void rendersHonestBylinesAndDisclosesOnlyIdentifiableAiContent() throws Exception {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        String article = Jsoup.parse(resource("/views/blog/detail.html")).selectFirst("article").outerHtml();
        for (String author : new String[] {"James Chen", "Mark Zhang", "Whose.Domains", "Ada Example", " ", null}) {
            BlogPost post = new BlogPost();
            post.setAuthor(author);
            post.setTitle("Test article");
            post.setContent("<p>Body</p>");
            Context context = new Context();
            context.setVariable("post", post);
            String rendered = Jsoup.parse(engine.process(article, context)).text();
            boolean legacy = "James Chen".equals(author) || "Mark Zhang".equals(author);
            assertTrue(rendered.contains("Ada Example".equals(author) ? author : "Whose.Domains"));
            assertTrue(rendered.contains("AI-assisted") == legacy);
            if (legacy) {
                assertFalse(rendered.contains(author));
            }
            post.setCreateBy("ai");
            assertTrue(Jsoup.parse(engine.process(article, context)).text().contains("AI-assisted"));
            post.setCreateBy(null);
            post.setAiGenerated(true);
            post.setAuthor("Verified Editor");
            String edited = Jsoup.parse(engine.process(article, context)).text();
            assertTrue(edited.contains("Verified Editor"));
            assertTrue(edited.contains("AI-assisted"));
        }
    }
}
