package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import info.wesite.core.entity.BlogPost;
import info.wesite.core.service.BlogPostService;

class SitemapTaskTest {

    @TempDir
    Path output;

    @Test
    void generatedSitemapUsesOnlyCanonicalToolRoutes() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "SitemapTaskTest"),
                info.wesite.core.entity.BlogPost.class);
        BlogPostService posts = mock(BlogPostService.class);
        when(posts.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<info.wesite.core.entity.BlogPost>>any()))
                .thenReturn(List.of());

        SitemapTask task = new SitemapTask();
        ReflectionTestUtils.setField(task, "sitemapRoot", output.toString());
        ReflectionTestUtils.setField(task, "blogPostService", posts);

        task.createFile();

        Path sitemap = output.resolve("sitemap_all.xml");
        String xml = Files.readString(sitemap);
        var locations = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(sitemap.toFile())
                .getElementsByTagName("loc");
        assertEquals("https://whose.domains/", locations.item(0).getTextContent());
        assertTrue(xml.contains("https://whose.domains/tools/domain-analyzer"));
        assertTrue(xml.contains("https://whose.domains/tools/dns-analyzer"));
        assertTrue(xml.contains("https://whose.domains/tools/ssl-checker"));
        assertTrue(xml.contains("https://whose.domains/tools/competitor-analysis"));
        assertFalse(xml.contains("/tools/domain_analyzer"));
        assertFalse(xml.contains("/tools/dns_analyzer"));
        assertFalse(xml.contains("/tools/ssl_checker"));
        assertFalse(xml.contains("/tools/competitor_analysis"));
    }

    @Test
    void omitsLastModifiedWhenBlogPostHasNoContentTimestamp() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "SitemapTaskTest-no-lastmod"),
                BlogPost.class);
        BlogPost post = new BlogPost();
        post.setSlug("untimestamped-post");
        BlogPostService posts = mock(BlogPostService.class);
        when(posts.list(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<BlogPost>>any()))
                .thenReturn(List.of(post));

        SitemapTask task = new SitemapTask();
        ReflectionTestUtils.setField(task, "sitemapRoot", output.toString());
        ReflectionTestUtils.setField(task, "blogPostService", posts);

        task.createFile();

        NodeList urls = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(output.resolve("sitemap_all.xml").toFile())
                .getElementsByTagName("url");
        Node blogUrl = null;
        for (int i = 0; i < urls.getLength(); i++) {
            Node candidate = urls.item(i);
            if (candidate.getTextContent().contains("https://whose.domains/blog/untimestamped-post")) {
                blogUrl = candidate;
                break;
            }
        }

        assertTrue(blogUrl != null, "generated sitemap should contain the published blog URL");
        assertEquals(0, ((org.w3c.dom.Element) blogUrl).getElementsByTagName("lastmod").getLength());
    }
}
