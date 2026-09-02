package info.wesite.admin.controller;

import static info.wesite.admin.blog.BlogAdminModels.IdRequest;
import static info.wesite.admin.blog.BlogAdminModels.PreviewRequest;
import static info.wesite.admin.blog.BlogAdminModels.SaveRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.admin.blog.BlogAdminModels.DetailResponse;
import info.wesite.admin.blog.BlogAdminModels.PreviewResponse;
import info.wesite.core.blog.BlogEditCommand;
import info.wesite.core.blog.BlogEditorialException;
import info.wesite.core.blog.BlogEditorialService;
import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.entity.User;
import info.wesite.core.service.BlogPostService;
import info.wesite.core.view.ResponseJson;

class BlogAdminControllerTest {

    private BlogPostService posts;
    private BlogEditorialService editorial;
    private BlogAdminController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        posts = mock(BlogPostService.class);
        editorial = mock(BlogEditorialService.class);
        controller = new BlogAdminController(posts, editorial);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void clearCurrentUser() {
        UserHolder.remove();
    }

    @Test
    void controllerRequiresAnAuthenticatedSession() {
        AccessControl access = BlogAdminController.class.getAnnotation(AccessControl.class);
        assertEquals(AccessControl.Level.SESSION, access.level());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listBoundsPaginationAndDoesNotExposeBody() throws Exception {
        BlogPost post = blogPost("post-1");
        post.setContent("<p>private body</p>");
        Page<BlogPost> result = Page.of(1, 20);
        result.setRecords(java.util.List.of(post));
        result.setTotal(1);
        when(posts.page(any(Page.class), any(Wrapper.class))).thenReturn(result);

        mvc.perform(post("/blog/list")
                .contentType(APPLICATION_JSON)
                .content("{\"page\":1,\"limit\":20,\"status\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.data[0].id").value("post-1"))
            .andExpect(jsonPath("$.data[0].content").doesNotExist());
    }

    @Test
    void listRejectsInvalidBoundsAndFiltersBeforeQuerying() throws Exception {
        assertListFailure("{\"page\":0,\"limit\":20}");
        assertListFailure("{\"page\":1,\"limit\":101}");
        assertListFailure("{\"page\":1,\"limit\":20,\"status\":2}");
        assertListFailure("{\"page\":1,\"limit\":20,\"keyword\":\"" + "x".repeat(201) + "\"}");
        verify(posts, never()).page(any(Page.class), any(Wrapper.class));
    }

    @Test
    void detailReturnsEditableFieldsAndMissingPostIsReadableFailure() {
        BlogPost post = blogPost("post-1");
        post.setContent("<p>Body</p>");
        post.setMetaTitle("Meta title");
        post.setMetaDescription("Meta description");
        when(posts.getById("post-1")).thenReturn(post);

        ResponseJson<?> found = controller.detail(new IdRequest("post-1"));
        assertEquals(ResponseJson.CODE_SUCCESS, found.getCode());
        DetailResponse detail = assertInstanceOf(DetailResponse.class, found.getData());
        assertEquals("<p>Body</p>", detail.content());
        assertEquals("Meta title", detail.metaTitle());

        ResponseJson<?> missing = controller.detail(new IdRequest("missing"));
        assertEquals(ResponseJson.CODE_FAILURE, missing.getCode());
        assertEquals("Post not found", missing.getMsg());
    }

    @Test
    void saveMapsOnlyDtoFieldsAndUsesCurrentAdminAsActor() {
        UserHolder.set(admin("admin-1"));
        SaveRequest request = new SaveRequest(
            "post-1", "draft-slug", "Title", "Summary", "<p>Body</p>",
            null, "dns", "dns,security", "Meta title", "Meta description");

        ResponseJson<?> response = controller.save(request);

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        ArgumentCaptor<BlogEditCommand> command = ArgumentCaptor.forClass(BlogEditCommand.class);
        verify(editorial).save(command.capture(), eq("admin-1"));
        assertEquals("post-1", command.getValue().id());
        assertEquals("draft-slug", command.getValue().slug());
        assertEquals("<p>Body</p>", command.getValue().content());
        assertEquals("Meta description", command.getValue().metaDescription());
    }

    @Test
    void previewReturnsOnlySanitizedHtml() {
        when(editorial.sanitizePreview(any())).thenReturn("<p>safe</p>");

        ResponseJson<?> response = controller.preview(
            new PreviewRequest("<script>x()</script><p>safe</p>"));

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        PreviewResponse preview = assertInstanceOf(PreviewResponse.class, response.getData());
        assertEquals("<p>safe</p>", preview.html());
        assertEquals(1, preview.getClass().getRecordComponents().length);
    }

    @Test
    void publishAndUnpublishUseCurrentAdminAsActor() {
        UserHolder.set(admin("admin-2"));

        assertEquals(ResponseJson.CODE_SUCCESS, controller.publish(new IdRequest("post-1")).getCode());
        assertEquals(ResponseJson.CODE_SUCCESS, controller.unpublish(new IdRequest("post-2")).getCode());

        verify(editorial).publish("post-1", "admin-2");
        verify(editorial).unpublish("post-2", "admin-2");
    }

    @Test
    void editorialAndUnexpectedFailuresUseSafeEnvelopes() {
        UserHolder.set(admin("admin-3"));
        when(editorial.publish("bad", "admin-3"))
            .thenThrow(new BlogEditorialException("Summary is required"));
        when(editorial.publish("broken", "admin-3"))
            .thenThrow(new IllegalStateException("SQL password=secret"));

        ResponseJson<?> expected = controller.publish(new IdRequest("bad"));
        ResponseJson<?> unexpected = controller.publish(new IdRequest("broken"));

        assertEquals(ResponseJson.CODE_FAILURE, expected.getCode());
        assertEquals("Summary is required", expected.getMsg());
        assertEquals(ResponseJson.CODE_ERROR, unexpected.getCode());
        assertEquals("Unable to complete the blog operation", unexpected.getMsg());
        assertTrue(!unexpected.getMsg().contains("secret"));
    }

    private void assertListFailure(String body) throws Exception {
        mvc.perform(post("/blog/list").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(ResponseJson.CODE_FAILURE));
    }

    private static User admin(String id) {
        User admin = new User();
        admin.setId(id);
        admin.setUserType(User.TYPE_ADMIN);
        return admin;
    }

    private static BlogPost blogPost(String id) {
        BlogPost post = new BlogPost();
        post.setId(id);
        post.setSlug("post-slug");
        post.setTitle("Title");
        post.setSummary("Summary");
        post.setAuthor("Author");
        post.setCategory("dns");
        post.setTags("dns,security");
        post.setStatus(BlogPost.POST_STATUS_DRAFT);
        post.setPublishDate(new Date(1_000));
        post.setContentUpdatedAt(new Date(2_000));
        return post;
    }
}
