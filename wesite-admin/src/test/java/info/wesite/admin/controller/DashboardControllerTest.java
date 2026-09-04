package info.wesite.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.admin.view.DashboardSummaryView;
import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.entity.ContactInfo;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.entity.DomainTldExt;
import info.wesite.core.entity.User;
import info.wesite.core.service.BlogPostService;
import info.wesite.core.service.ContactInfoService;
import info.wesite.core.service.DomainTldExtService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.service.UserService;
import info.wesite.core.view.ResponseJson;

class DashboardControllerTest {

    private UserService users;
    private DomainTldService tlds;
    private DomainTldExtService slds;
    private BlogPostService posts;
    private ContactInfoService contacts;
    private DashboardController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        initTable(User.class);
        initTable(BlogPost.class);
        initTable(ContactInfo.class);
        users = mock(UserService.class);
        tlds = mock(DomainTldService.class);
        slds = mock(DomainTldExtService.class);
        posts = mock(BlogPostService.class);
        contacts = mock(ContactInfoService.class);
        controller = new DashboardController(users, tlds, slds, posts, contacts);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void summaryCountsOnlyBusinessRowsAndReturnsSafeBoundedRecentItems() throws Exception {
        when(users.count(any(Wrapper.class))).thenReturn(12L);
        when(tlds.count()).thenReturn(180L);
        when(slds.count()).thenReturn(35L);
        when(posts.count(any(Wrapper.class))).thenAnswer(invocation ->
            containsValue(invocation.getArgument(0), BlogPost.POST_STATUS_DRAFT) ? 7L : 23L);
        when(contacts.count(any(Wrapper.class))).thenReturn(4L);

        Page<BlogPost> recentPosts = Page.of(1, 5, false);
        recentPosts.setRecords(List.of(
            post("post-6", "最新文章", "latest-post", 6_000),
            post("post-5", "第五篇文章", "fifth-post", 5_000),
            post("post-4", "第四篇文章", "fourth-post", 4_000),
            post("post-3", "第三篇文章", "third-post", 3_000),
            post("post-2", "第二篇文章", "second-post", 2_000),
            post("post-1", "不应返回的文章", "old-post", 1_000)));
        when(posts.page(any(Page.class), any(Wrapper.class))).thenReturn(recentPosts);

        Page<ContactInfo> recentContacts = Page.of(1, 5, false);
        recentContacts.setRecords(List.of(
            contact("contact-6", "访客六", "最新留言", 6_000),
            contact("contact-5", "访客五", "第五条留言", 5_000),
            contact("contact-4", "访客四", "第四条留言", 4_000),
            contact("contact-3", "访客三", "第三条留言", 3_000),
            contact("contact-2", "访客二", "第二条留言", 2_000),
            contact("contact-1", "访客一", "不应返回的留言", 1_000)));
        when(contacts.page(any(Page.class), any(Wrapper.class))).thenReturn(recentContacts);

        mvc.perform(get("/dashboard/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ResponseJson.CODE_SUCCESS))
            .andExpect(jsonPath("$.data.userCount").value(12))
            .andExpect(jsonPath("$.data.tldCount").value(180))
            .andExpect(jsonPath("$.data.sldCount").value(35))
            .andExpect(jsonPath("$.data.draftPostCount").value(7))
            .andExpect(jsonPath("$.data.publishedPostCount").value(23))
            .andExpect(jsonPath("$.data.pendingContactCount").value(4))
            .andExpect(jsonPath("$.data.recentPosts.length()").value(5))
            .andExpect(jsonPath("$.data.recentPosts[0].id").value("post-6"))
            .andExpect(jsonPath("$.data.recentPosts[0].title").value("最新文章"))
            .andExpect(jsonPath("$.data.recentPosts[0].slug").value("latest-post"))
            .andExpect(jsonPath("$.data.recentPosts[0].content").doesNotExist())
            .andExpect(jsonPath("$.data.recentContacts.length()").value(5))
            .andExpect(jsonPath("$.data.recentContacts[0].id").value("contact-6"))
            .andExpect(jsonPath("$.data.recentContacts[0].subject").value("最新留言"))
            .andExpect(jsonPath("$.data.recentContacts[0].message").doesNotExist())
            .andExpect(jsonPath("$.data.recentContacts[0].requestIp").doesNotExist());

        ArgumentCaptor<Wrapper<User>> userQuery = wrapperCaptor();
        org.mockito.Mockito.verify(users).count(userQuery.capture());
        assertTrue(containsValue(userQuery.getValue(), User.TYPE_PERSON));

        List<Wrapper<BlogPost>> postCountQueries = captureCountQueries(posts);
        assertTrue(postCountQueries.stream().anyMatch(query ->
            containsValue(query, BlogPost.POST_STATUS_DRAFT)));
        assertTrue(postCountQueries.stream().anyMatch(query ->
            containsValue(query, BlogPost.POST_STATUS_PUBLISHED)));

        ArgumentCaptor<Wrapper<ContactInfo>> pendingQuery = wrapperCaptor();
        org.mockito.Mockito.verify(contacts).count(pendingQuery.capture());
        assertTrue(containsValue(pendingQuery.getValue(), BaseEntity.STATUS_ACTIVE));

        ArgumentCaptor<Page<BlogPost>> postPage = pageCaptor();
        ArgumentCaptor<Wrapper<BlogPost>> postOrder = wrapperCaptor();
        org.mockito.Mockito.verify(posts).page(postPage.capture(), postOrder.capture());
        assertEquals(5L, postPage.getValue().getSize());
        assertTrue(sql(postOrder.getValue()).contains("CONTENT_UPDATED_AT DESC"));

        ArgumentCaptor<Page<ContactInfo>> contactPage = pageCaptor();
        ArgumentCaptor<Wrapper<ContactInfo>> contactOrder = wrapperCaptor();
        org.mockito.Mockito.verify(contacts).page(contactPage.capture(), contactOrder.capture());
        assertEquals(5L, contactPage.getValue().getSize());
        assertTrue(sql(contactOrder.getValue()).contains("CREATE_TIME DESC"));
    }

    @Test
    void summaryUsesAnImmutableViewInsteadOfReturningEntities() {
        when(users.count(any(Wrapper.class))).thenReturn(0L);
        when(tlds.count()).thenReturn(0L);
        when(slds.count()).thenReturn(0L);
        when(posts.count(any(Wrapper.class))).thenReturn(0L);
        when(contacts.count(any(Wrapper.class))).thenReturn(0L);
        when(posts.page(any(Page.class), any(Wrapper.class))).thenReturn(Page.of(1, 5, false));
        when(contacts.page(any(Page.class), any(Wrapper.class))).thenReturn(Page.of(1, 5, false));

        ResponseJson<DashboardSummaryView> response = controller.summary();

        DashboardSummaryView summary = assertInstanceOf(DashboardSummaryView.class, response.getData());
        assertTrue(DashboardSummaryView.class.isRecord());
        assertTrue(summary.recentPosts().isEmpty());
        assertTrue(summary.recentContacts().isEmpty());
    }

    private static List<Wrapper<BlogPost>> captureCountQueries(BlogPostService service) {
        ArgumentCaptor<Wrapper<BlogPost>> captor = wrapperCaptor();
        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(2)).count(captor.capture());
        return captor.getAllValues();
    }

    private static String sql(Wrapper<?> wrapper) {
        return wrapper.getSqlSegment().toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean containsValue(Wrapper<?> wrapper, Object value) {
        if (!(wrapper instanceof AbstractWrapper<?, ?, ?> query)) {
            return false;
        }
        query.getSqlSegment();
        return query.getParamNameValuePairs().containsValue(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<Page<T>> pageCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Page.class);
    }

    private static void initTable(Class<?> entityType) {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), entityType.getSimpleName()), entityType);
    }

    private static BlogPost post(String id, String title, String slug, long updatedAt) {
        BlogPost post = new BlogPost();
        post.setId(id);
        post.setTitle(title);
        post.setSlug(slug);
        post.setStatus(BlogPost.POST_STATUS_PUBLISHED);
        post.setContent("<p>敏感正文</p>");
        post.setContentUpdatedAt(new Date(updatedAt));
        post.setCreateTime(new Date(updatedAt - 100));
        return post;
    }

    private static ContactInfo contact(String id, String name, String subject, long createdAt) {
        ContactInfo contact = new ContactInfo();
        contact.setId(id);
        contact.setName(name);
        contact.setEmail(name + "@example.com");
        contact.setSubject(subject);
        contact.setMessage("敏感留言全文");
        contact.setRequestIp("192.0.2.1");
        contact.setStatus(BaseEntity.STATUS_ACTIVE);
        contact.setCreateTime(new Date(createdAt));
        return contact;
    }
}
