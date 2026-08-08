package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

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

        String xml = Files.readString(output.resolve("sitemap_all.xml"));
        assertTrue(xml.contains("https://whose.domains/tools/domain-analyzer"));
        assertTrue(xml.contains("https://whose.domains/tools/dns-analyzer"));
        assertTrue(xml.contains("https://whose.domains/tools/ssl-checker"));
        assertTrue(xml.contains("https://whose.domains/tools/competitor-analysis"));
        assertFalse(xml.contains("/tools/domain_analyzer"));
        assertFalse(xml.contains("/tools/dns_analyzer"));
        assertFalse(xml.contains("/tools/ssl_checker"));
        assertFalse(xml.contains("/tools/competitor_analysis"));
    }
}
