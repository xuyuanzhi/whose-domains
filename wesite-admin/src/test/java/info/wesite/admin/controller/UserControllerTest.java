package info.wesite.admin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.admin.view.SearchParam;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.view.ResponseJson;

class UserControllerTest {

    private UserService users;
    private UserController controller;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        users = mock(UserService.class);
        controller = controllerWith(users);
        UserHolder.set(person("admin-session", "Administrator", "13600000000", User.STATUS_ACTIVE));
    }

    @AfterEach
    void clearCurrentUser() {
        UserHolder.remove();
    }

    @Test
    void usesConstructorInjectionForItsUserService() {
        assertTrue(Arrays.stream(UserController.class.getDeclaredConstructors())
            .anyMatch(constructor -> Arrays.equals(constructor.getParameterTypes(), new Class<?>[] {UserService.class})));
    }

    @Test
    void createsPersonByCopyingOnlyEditableFieldsAndNormalizingPhone() {
        User request = request(null, "Alice", " 13800000000 ", User.STATUS_ACTIVE);
        request.setUserType(User.TYPE_ADMIN);
        request.setPassword("client-password");
        request.setSecureKey("client-key");
        when(users.count(any(Wrapper.class))).thenReturn(0L);
        when(users.saveOrUpdate(any(User.class))).thenReturn(true);

        ResponseJson<?> response = controller.save(request);

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        verify(users).saveOrUpdate(argThat(saved ->
            saved.getId() != null
                && "Alice".equals(saved.getName())
                && "13800000000".equals(saved.getPhoneNo())
                && User.STATUS_ACTIVE == saved.getStatus()
                && User.TYPE_PERSON.equals(saved.getUserType())
                && "admin-session".equals(saved.getCreateBy())
                && saved.getCreateTime() != null
                && saved.getPassword() == null
                && saved.getSecureKey() == null));
    }

    @Test
    void editsOnlyAllowedFieldsOnAnExistingPerson() {
        User existing = person("person-1", "Before", "13700000000", User.STATUS_ACTIVE);
        existing.setPassword("stored-password");
        existing.setSecureKey("stored-key");
        Date created = new Date(1_000L);
        existing.setCreateTime(created);
        when(users.getById("person-1")).thenReturn(existing);
        when(users.count(any(Wrapper.class))).thenReturn(0L);
        when(users.saveOrUpdate(any(User.class))).thenReturn(true);

        User request = request("person-1", "After", " 13900000000 ", User.STATUS_INACTIVE);
        request.setUserType(User.TYPE_ADMIN);
        request.setPassword("replacement-password");
        ResponseJson<?> response = controller.save(request);

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        verify(users).saveOrUpdate(argThat(saved ->
            "person-1".equals(saved.getId())
                && "After".equals(saved.getName())
                && "13900000000".equals(saved.getPhoneNo())
                && User.STATUS_INACTIVE == saved.getStatus()
                && User.TYPE_PERSON.equals(saved.getUserType())
                && "stored-password".equals(saved.getPassword())
                && "stored-key".equals(saved.getSecureKey())
                && created.equals(saved.getCreateTime())
                && "admin-session".equals(saved.getUpdateBy())
                && saved.getUpdateTime() != null));
    }

    @Test
    void searchesPersonsByNameOrPhoneAndReturnsOnlySafeViews() {
        User record = person("person-1", "Alice", "13800000000", User.STATUS_ACTIVE);
        record.setPassword("secret");
        record.setSecureKey("secure");
        Page<User> result = Page.of(2, 10);
        result.setRecords(List.of(record));
        result.setTotal(1);
        when(users.page(any(Page.class), any(Wrapper.class))).thenReturn(result);

        SearchParam param = new SearchParam();
        param.setPage(2);
        param.setLimit(10);
        param.setKeyword("  Alice  ");
        ResponseJson<?> response = controller.list(param);

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        assertEquals(1L, response.getTotal());
        List<?> data = assertInstanceOf(List.class, response.getData());
        Object view = data.get(0);
        assertEquals("AdminUserView", view.getClass().getSimpleName());
        assertEquals("person-1", recordValue(view, "id"));
        assertEquals("Alice", recordValue(view, "name"));
        assertEquals("13800000000", recordValue(view, "phoneNo"));
        Set<String> exposed = Arrays.stream(view.getClass().getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toSet());
        assertEquals(Set.of("id", "name", "phoneNo", "status", "statusText", "createTimeText", "updateTimeText"), exposed);

        Wrapper<?> query = capturedPageQuery();
        String sql = query.getSqlSegment().toUpperCase();
        assertTrue(sql.contains("USER_TYPE"));
        assertTrue(sql.contains("NAME"));
        assertTrue(sql.contains("PHONE_NO"));
        assertTrue(sql.contains(" OR "));
    }

    @Test
    void detailReturnsSafeViewForPerson() {
        User person = person("person-1", "Alice", "13800000000", User.STATUS_ACTIVE);
        person.setPassword("password");
        person.setSecureKey("secure-key");
        when(users.getById("person-1")).thenReturn(person);

        ResponseJson<?> response = controller.detail(request("person-1", null, null, null));

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        Object view = response.getData();
        assertEquals("AdminUserView", view.getClass().getSimpleName());
        assertEquals("person-1", recordValue(view, "id"));
        assertFalse(Arrays.stream(view.getClass().getRecordComponents())
            .map(RecordComponent::getName)
            .anyMatch(name -> Set.of("password", "secureKey", "token", "userType").contains(name)));
    }

    @Test
    void rejectsAdministratorDetailDeleteAndEdit() {
        User admin = person("admin-1", "Administrator", "13800000000", User.STATUS_ACTIVE);
        admin.setUserType(User.TYPE_ADMIN);
        when(users.getById("admin-1")).thenReturn(admin);

        assertEquals(ResponseJson.CODE_FAILURE,
            controller.detail(request("admin-1", null, null, null)).getCode());
        assertEquals(ResponseJson.CODE_FAILURE,
            controller.delete(request("admin-1", null, null, null)).getCode());
        assertEquals(ResponseJson.CODE_FAILURE,
            controller.save(request("admin-1", "Changed", "13900000000", User.STATUS_ACTIVE)).getCode());

        verify(users, never()).removeById(any(String.class));
        verify(users, never()).saveOrUpdate(any(User.class));
    }

    @Test
    void rejectsDuplicateNormalizedPhone() {
        when(users.count(any(Wrapper.class))).thenReturn(1L);

        ResponseJson<?> response = controller.save(
            request(null, "Alice", " 13800000000 ", User.STATUS_ACTIVE));

        assertEquals(ResponseJson.CODE_FAILURE, response.getCode());
        verify(users, never()).saveOrUpdate(any(User.class));
        Wrapper<?> duplicateQuery = capturedCountQuery();
        assertTrue(duplicateQuery.getSqlSegment().toUpperCase().contains("PHONE_NO"));
        AbstractWrapper<?, ?, ?> duplicate = assertInstanceOf(AbstractWrapper.class, duplicateQuery);
        assertNotNull(duplicate.getParamNameValuePairs());
        assertTrue(duplicate.getParamNameValuePairs().containsValue("13800000000"));
    }

    @Test
    void rejectsBlankPhoneAndUnsupportedStatus() {
        assertEquals(ResponseJson.CODE_FAILURE,
            controller.save(request(null, "Alice", "   ", User.STATUS_ACTIVE)).getCode());
        assertEquals(ResponseJson.CODE_FAILURE,
            controller.save(request(null, "Alice", "13800000000", User.STATUS_NEW)).getCode());

        verify(users, never()).saveOrUpdate(any(User.class));
    }

    private Wrapper<?> capturedPageQuery() {
        org.mockito.ArgumentCaptor<Wrapper> query = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(users).page(any(Page.class), query.capture());
        return query.getValue();
    }

    private Wrapper<?> capturedCountQuery() {
        org.mockito.ArgumentCaptor<Wrapper> query = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(users).count(query.capture());
        return query.getValue();
    }

    private static UserController controllerWith(UserService users) throws ReflectiveOperationException {
        for (Constructor<?> constructor : UserController.class.getDeclaredConstructors()) {
            if (Arrays.equals(constructor.getParameterTypes(), new Class<?>[] {UserService.class})) {
                return (UserController) constructor.newInstance(users);
            }
        }
        Constructor<UserController> constructor = UserController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        UserController controller = constructor.newInstance();
        ReflectionTestUtils.setField(controller, "userService", users);
        return controller;
    }

    private static Object recordValue(Object record, String component) {
        try {
            return record.getClass().getMethod(component).invoke(record);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Missing record component: " + component, exception);
        }
    }

    private static User request(String id, String name, String phone, Integer status) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setPhoneNo(phone);
        user.setStatus(status);
        return user;
    }

    private static User person(String id, String name, String phone, Integer status) {
        User user = request(id, name, phone, status);
        user.setUserType(User.TYPE_PERSON);
        return user;
    }
}
