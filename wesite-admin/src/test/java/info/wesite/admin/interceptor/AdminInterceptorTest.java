package info.wesite.admin.interceptor;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.TokenUtils;

class AdminInterceptorTest {

    private UserService users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .addInterceptors(new AdminInterceptor(users))
            .build();
    }

    @AfterEach
    void cleanThreadLocal() {
        UserHolder.remove();
    }

    @Test
    void unannotatedJsonHandlerRejectsMissingTokenWithExpectedEnvelope() throws Exception {
        mvc.perform(get("/protected"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(401));
        assertNull(UserHolder.get());
    }

    @Test
    void explicitlyPublicHandlerIgnoresStaleThreadIdentity() throws Exception {
        UserHolder.set(activeAdmin("stale"));

        mvc.perform(get("/public"))
            .andExpect(status().isOk())
            .andExpect(content().string("public"));

        assertNull(UserHolder.get());
    }

    @Test
    void validPersonTokenIsRejectedAndCurrentDatabaseAdminIsAccepted() throws Exception {
        User identity = identity("user-1");
        User person = activeAdmin("user-1");
        person.setUserType(User.TYPE_PERSON);
        User admin = activeAdmin("user-1");
        when(users.getById("user-1")).thenReturn(person, admin);

        try (MockedStatic<TokenUtils> tokens = mockStatic(TokenUtils.class)) {
            tokens.when(() -> TokenUtils.verifyToken("signed-token")).thenReturn(identity);

            mvc.perform(get("/protected").header(Constants.TOKEN_KEY, "signed-token"))
                .andExpect(jsonPath("$.code").value(401));
            assertNull(UserHolder.get());

            mvc.perform(get("/protected").header(Constants.TOKEN_KEY, "signed-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(User.TYPE_ADMIN));
            assertNull(UserHolder.get());
        }
    }

    @Test
    void layuiAccessTokenHeaderIsAcceptedForCompatibility() throws Exception {
        User identity = identity("user-2");
        when(users.getById("user-2")).thenReturn(activeAdmin("user-2"));

        try (MockedStatic<TokenUtils> tokens = mockStatic(TokenUtils.class)) {
            tokens.when(() -> TokenUtils.verifyToken("layui-token")).thenReturn(identity);

            mvc.perform(get("/protected").header("access_token", "layui-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(User.TYPE_ADMIN));
        }
    }

    @Test
    void inactiveAndDeletedAdministratorsAreRejectedAndCleared() throws Exception {
        User identity = identity("user-3");
        User inactive = activeAdmin("user-3");
        inactive.setStatus(User.STATUS_INACTIVE);
        User deleted = activeAdmin("user-3");
        deleted.setDeleted(1);
        when(users.getById("user-3")).thenReturn(inactive, deleted);

        try (MockedStatic<TokenUtils> tokens = mockStatic(TokenUtils.class)) {
            tokens.when(() -> TokenUtils.verifyToken("signed-token")).thenReturn(identity);

            mvc.perform(get("/protected").header(Constants.TOKEN_KEY, "signed-token"))
                .andExpect(jsonPath("$.code").value(401));
            assertNull(UserHolder.get());

            mvc.perform(get("/protected").header(Constants.TOKEN_KEY, "signed-token"))
                .andExpect(jsonPath("$.code").value(401));
            assertNull(UserHolder.get());
        }
    }

    private static User identity(String id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static User activeAdmin(String id) {
        User user = identity(id);
        user.setUserType(User.TYPE_ADMIN);
        user.setStatus(User.STATUS_ACTIVE);
        user.setDeleted(0);
        return user;
    }

    @RestController
    static class ProbeController {

        @GetMapping("/protected")
        String protectedEndpoint() {
            return UserHolder.get().getUserType();
        }

        @AccessControl(level = AccessControl.Level.NONE)
        @GetMapping("/public")
        String publicEndpoint() {
            return "public";
        }
    }
}
