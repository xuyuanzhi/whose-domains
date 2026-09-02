package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import info.wesite.core.blog.BlogDraftCommand;
import info.wesite.core.blog.BlogEditorialException;
import info.wesite.core.blog.BlogEditorialService;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.service.BlogPostService;
import info.wesite.web.ai.DeepSeekClient;

class AiBlogTaskTest {

    @Test
    void canonicalizesLinksThenCreatesDraftThroughEditorialService() throws Exception {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "AiBlogTaskTest"),
                BlogPost.class);
        DeepSeekClient client = mock(DeepSeekClient.class);
        BlogPostService posts = mock(BlogPostService.class);
        BlogEditorialService editorial = mock(BlogEditorialService.class);
        when(client.isConfigured()).thenReturn(true);
        when(posts.list(org.mockito.ArgumentMatchers.<Wrapper<BlogPost>>any()))
                .thenReturn(List.of());
        when(client.chat(contains("editorial planner"), anyString())).thenReturn("""
                TITLE: DNS Monitoring for Investors
                CATEGORY: dns
                TAGS: dns, monitoring, domains
                """);
        when(client.chat(contains("professional technical writer"), anyString())).thenReturn("""
                ===SUMMARY===
                A practical guide to DNS monitoring.
                ===CONTENT===
                <p>Use the <a href="/tools/dns_analyzer?d=example.com">DNS Analyzer</a>.</p>
                ===META_DESCRIPTION===
                Monitor domain DNS changes with practical checks and reliable tooling.
                """);

        AiBlogTask task = new AiBlogTask();
        ReflectionTestUtils.setField(task, "deepSeekClient", client);
        ReflectionTestUtils.setField(task, "blogPostService", posts);
        ReflectionTestUtils.setField(task, "editorial", editorial);

        task.generateBlogPost();

        ArgumentCaptor<BlogDraftCommand> command = ArgumentCaptor.forClass(BlogDraftCommand.class);
        verify(editorial).createAiDraft(command.capture());
        assertEquals("dns-monitoring-for-investors", command.getValue().slug());
        assertEquals("DNS Monitoring for Investors", command.getValue().title());
        assertEquals("dns", command.getValue().category());
        assertTrue(command.getValue().content()
                .contains("/tools/dns-analyzer?d=example.com"));
        assertFalse(command.getValue().content().contains("/tools/dns_analyzer"));
        verify(posts, never()).save(any());
        verify(posts, never()).count(any());

        ArgumentCaptor<String> systemPrompts = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).chat(systemPrompts.capture(), anyString());
        String articlePrompt = systemPrompts.getAllValues().stream()
                .filter(prompt -> prompt.contains("professional technical writer"))
                .findFirst()
                .orElseThrow();
        assertTrue(articlePrompt.contains("/tools/domain-analyzer"));
        assertTrue(articlePrompt.contains("/tools/dns-analyzer"));
        assertTrue(articlePrompt.contains("/tools/ssl-checker"));
        assertFalse(articlePrompt.contains("/tools/domain_analyzer"));
        assertFalse(articlePrompt.contains("/tools/dns_analyzer"));
        assertFalse(articlePrompt.contains("/tools/ssl_checker"));
    }

    @Test
    void editorialRejectionDoesNotEscapeTheSchedulerThread() throws Exception {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "AiBlogTaskFailureTest"),
                BlogPost.class);
        DeepSeekClient client = mock(DeepSeekClient.class);
        BlogPostService posts = mock(BlogPostService.class);
        BlogEditorialService editorial = mock(BlogEditorialService.class);
        when(client.isConfigured()).thenReturn(true);
        when(posts.list(org.mockito.ArgumentMatchers.<Wrapper<BlogPost>>any())).thenReturn(List.of());
        when(client.chat(contains("editorial planner"), anyString())).thenReturn("""
                TITLE: Duplicate Topic
                CATEGORY: dns
                TAGS: dns, domains
                """);
        when(client.chat(contains("professional technical writer"), anyString())).thenReturn("""
                ===SUMMARY===
                Summary
                ===CONTENT===
                <p>Body</p>
                ===META_DESCRIPTION===
                Description
                """);
        when(editorial.createAiDraft(any()))
            .thenThrow(new BlogEditorialException("Slug already exists"));

        AiBlogTask task = new AiBlogTask();
        ReflectionTestUtils.setField(task, "deepSeekClient", client);
        ReflectionTestUtils.setField(task, "blogPostService", posts);
        ReflectionTestUtils.setField(task, "editorial", editorial);

        assertDoesNotThrow(task::generateBlogPost);
        verify(editorial).createAiDraft(any(BlogDraftCommand.class));
        verify(posts, never()).save(any());
    }
}
