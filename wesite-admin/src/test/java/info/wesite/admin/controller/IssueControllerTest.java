package info.wesite.admin.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import info.wesite.admin.interceptor.AdminInterceptor;
import info.wesite.core.config.UserHolder;
import info.wesite.core.diagnostics.IssueStore;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.TokenUtils;

class IssueControllerTest {
    @AfterEach void clear(){UserHolder.remove();}
    @Test void everyIssueEndpointRejectsAnonymousRequests() throws Exception {
        var store=mock(IssueStore.class);
        var mvc=MockMvcBuilders.standaloneSetup(new IssueController(store)).addInterceptors(new AdminInterceptor(mock(UserService.class))).build();
        for(String path:List.of("/list","/id","/id/events","/id/notes")) mvc.perform(get("/admin/issues"+path)).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/admin/issues/id/status").contentType("application/json").content("{}")).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(store);
    }
    @Test void nonAdminCannotReadButCurrentAdminCanAndAuditActorIsServerSide() throws Exception {
        var store=mock(IssueStore.class);var users=mock(UserService.class);var user=new User();user.setId("operator");user.setStatus(User.STATUS_ACTIVE);user.setDeleted(0);user.setUserType(User.TYPE_PERSON);
        when(users.getById("operator")).thenReturn(user);
        when(store.list(1,20,null,null,null,null,null,null)).thenReturn(Map.of("items",List.of(),"total",0));
        var mvc=MockMvcBuilders.standaloneSetup(new IssueController(store)).addInterceptors(new AdminInterceptor(users)).build();
        try(var tokens=mockStatic(TokenUtils.class)){
            tokens.when(()->TokenUtils.verifyToken("token")).thenReturn(user);
            mvc.perform(get("/admin/issues/list").header("access_token","token")).andExpect(jsonPath("$.code").value(401));
            user.setUserType(User.TYPE_ADMIN);
            mvc.perform(get("/admin/issues/list").header("access_token","token")).andExpect(jsonPath("$.code").value(0));
            mvc.perform(post("/admin/issues/id/status").header("access_token","token").contentType("application/json")
                .content("{\"status\":\"resolved\",\"note\":\"fixed\",\"resolvedRelease\":\"v1\",\"version\":3}"))
                .andExpect(jsonPath("$.code").value(0));
            verify(store).manage("id",3,"resolved","fixed","v1","operator");
        }
    }
    @Test void rejectsPaginationAndExposesOptimisticConflictWithoutInternalDetails() throws Exception {
        var store=mock(IssueStore.class);var mvc=MockMvcBuilders.standaloneSetup(new IssueController(store)).build();
        mvc.perform(get("/admin/issues/list?size=1000")).andExpect(status().isBadRequest());
        var user=new User();user.setId("admin");UserHolder.set(user);
        doThrow(new ConcurrentModificationException("internal detail")).when(store).manage(anyString(),anyLong(),anyString(),anyString(),anyString(),anyString());
        mvc.perform(post("/admin/issues/id/status").contentType("application/json").content("{\"status\":\"resolved\",\"note\":\"\",\"resolvedRelease\":\"\",\"version\":1}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.msg").value("问题已更新，请刷新后重新保存"));
    }
}
