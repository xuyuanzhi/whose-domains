package info.wesite.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import info.wesite.admin.view.AdminSessionView;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.view.LoginParam;
import info.wesite.core.view.ResponseJson;

class MainControllerTest {

    private UserService users;
    private MainController controller;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        controller = new MainController(users);
    }

    @AfterEach
    void clearCurrentUser() {
        UserHolder.remove();
    }

    @Test
    void loginDoesNotRevealWhetherAdministratorIsMissingOrDisabled() {
        when(users.getOne(any())).thenReturn(null, disabledAdmin());

        assertEquals("用户名或密码错误", controller.login(login("admin", "wrong")).getMsg());
        assertEquals("用户名或密码错误", controller.login(login("admin", "wrong")).getMsg());
    }

    @Test
    void loginRejectsDeletedAndNonAdministratorAccountsWithTheSameMessage() {
        User deleted = activeAdmin("admin-1");
        deleted.setDeleted(1);
        User person = activeAdmin("user-1");
        person.setUserType(User.TYPE_PERSON);
        when(users.getOne(any())).thenReturn(deleted, person);

        assertEquals("用户名或密码错误", controller.login(login("admin", "wrong")).getMsg());
        assertEquals("用户名或密码错误", controller.login(login("admin", "wrong")).getMsg());
    }

    @Test
    void loginHandlesMissingLegacyPasswordWithoutThrowing() {
        User user = activeAdmin("admin-1");
        user.setPassword(null);
        when(users.getOne(any())).thenReturn(user);

        assertEquals("用户名或密码错误", controller.login(login("admin", "wrong")).getMsg());
    }

    @Test
    void loginRejectsAnIncompleteAdministratorRecordWithoutThrowing() {
        User user = activeAdmin("admin-1");
        user.setStatus(null);
        when(users.getOne(any())).thenReturn(user);

        assertEquals("用户名或密码错误", controller.login(login("admin", "wrong")).getMsg());
    }

    @Test
    void userInfoReturnsOnlySafeSessionFields() {
        UserHolder.set(activeAdmin("admin-1"));

        ResponseJson<AdminSessionView> response = controller.userInfo();
        AdminSessionView session = assertInstanceOf(AdminSessionView.class, response.getData());
        assertEquals("admin-1", session.id());
        assertEquals("Administrator", session.name());
        assertEquals("13800000000", session.phoneNo());
        assertEquals(3, session.getClass().getRecordComponents().length);
    }

    private static LoginParam login(String username, String password) {
        LoginParam param = new LoginParam();
        param.setUsername(username);
        param.setPassword(password);
        return param;
    }

    private static User disabledAdmin() {
        User user = activeAdmin("admin-1");
        user.setStatus(User.STATUS_INACTIVE);
        return user;
    }

    private static User activeAdmin(String id) {
        User user = new User();
        user.setId(id);
        user.setName("Administrator");
        user.setPhoneNo("13800000000");
        user.setUserType(User.TYPE_ADMIN);
        user.setStatus(User.STATUS_ACTIVE);
        user.setDeleted(0);
        user.setSecureKey("secure-key");
        return user;
    }
}
