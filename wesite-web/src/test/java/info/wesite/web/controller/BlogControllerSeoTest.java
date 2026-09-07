package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import info.wesite.core.blog.BlogHtmlSanitizer;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.service.BlogPostService;

class BlogControllerSeoTest {

    private BlogPostService posts;
    private BlogController controller;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "BlogControllerSeoTest"),
            BlogPost.class);
        posts = mock(BlogPostService.class);
        controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogPostService", posts);
        ReflectionTestUtils.setField(controller, "htmlSanitizer", new BlogHtmlSanitizer());
        when(posts.list(org.mockito.ArgumentMatchers.<Wrapper<BlogPost>>any())).thenReturn(List.of());
    }

    @Test
    void detailSanitizesHistoricalBodyAndUsesEditorialDateInSchema() {
        BlogPost post = publishedPost();
        post.setTitle("Safe </script><script>alert(1)</script> Post");
        post.setContent("<p>safe</p><script>alert(1)</script>");
        post.setContentUpdatedAt(date("2026-08-20"));
        post.setUpdateTime(date("2026-09-02"));
        when(posts.getOne(org.mockito.ArgumentMatchers.<Wrapper<BlogPost>>any())).thenReturn(post);
        ExtendedModelMap model = new ExtendedModelMap();

        controller.detail(post.getSlug(), model, new MockHttpServletRequest("GET", "/blog/safe-post"));

        BlogPost rendered = (BlogPost) model.getAttribute("post");
        assertFalse(rendered.getContent().contains("script"));
        assertEquals("<p>safe</p>", rendered.getContent());
        String schema = (String) model.getAttribute("_blogSchema");
        assertTrue(schema.contains("2026-08-20"));
        assertFalse(schema.contains("2026-09-02"));
        assertFalse(schema.contains("</script>"));
        assertTrue(schema.contains("\\u003C/script\\u003E"));
    }

    @Test
    void schemaUsesOrganizationForBlankAuthorAndPersonForNamedAuthor() {
        BlogPost post = publishedPost();
        post.setAuthor("  ");
        when(posts.getOne(org.mockito.ArgumentMatchers.<Wrapper<BlogPost>>any())).thenReturn(post);

        JSONObject organizationSchema = renderSchema(post);
        assertEquals("Organization", organizationSchema.getJSONObject("author").getString("@type"));
        assertEquals("Whose.Domains", organizationSchema.getJSONObject("author").getString("name"));

        post.setAuthor("Ada Example");
        JSONObject personSchema = renderSchema(post);
        assertEquals("Person", personSchema.getJSONObject("author").getString("@type"));
        assertEquals("Ada Example", personSchema.getJSONObject("author").getString("name"));
    }

    @Test
    void schemaFallsBackToPublicationDateWhenEditorialDateIsMissing() {
        BlogPost post = publishedPost();
        post.setContentUpdatedAt(null);
        post.setUpdateTime(date("2026-09-02"));
        when(posts.getOne(org.mockito.ArgumentMatchers.<Wrapper<BlogPost>>any())).thenReturn(post);

        JSONObject schema = renderSchema(post);

        assertEquals("2026-08-01", schema.getString("datePublished"));
        assertEquals("2026-08-01", schema.getString("dateModified"));
    }

    private JSONObject renderSchema(BlogPost post) {
        ExtendedModelMap model = new ExtendedModelMap();
        controller.detail(post.getSlug(), model, new MockHttpServletRequest("GET", "/blog/" + post.getSlug()));
        return JSON.parseObject((String) model.getAttribute("_blogSchema"));
    }

    @Test
    void siteAndLegacyGeneratedAuthorsUseOrganizationSchema() {
        for (String author : new String[] {"Whose.Domains", " whose.domains ", "James Chen", "Mark Zhang"}) {
            BlogPost post = publishedPost();
            post.setAuthor(author);
            when(posts.getOne(org.mockito.ArgumentMatchers.<Wrapper<BlogPost>>any())).thenReturn(post);
            JSONObject schema = renderSchema(post).getJSONObject("author");
            assertEquals("Organization", schema.getString("@type"), author);
            assertEquals("Whose.Domains", schema.getString("name"), author);
            assertEquals("https://whose.domains", schema.getString("url"), author);
        }
    }

    private static BlogPost publishedPost() {
        BlogPost post = new BlogPost();
        post.setId("post-1");
        post.setSlug("safe-post");
        post.setTitle("Safe Post");
        post.setSummary("Summary");
        post.setContent("<p>Body</p>");
        post.setStatus(BlogPost.POST_STATUS_PUBLISHED);
        post.setPublishDate(date("2026-08-01"));
        post.setCategory("security");
        post.setTags("dns,security");
        return post;
    }

    private static Date date(String iso) {
        return Date.from(LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
