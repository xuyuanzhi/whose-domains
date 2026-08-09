package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.service.UserNotificationService;

@SuppressWarnings({ "rawtypes", "unchecked" })
class NotificationControllerTest {

    private UserNotificationService notifications;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "NotificationControllerTest"),
                UserNotification.class);
        notifications = mock(UserNotificationService.class);
        NotificationController controller = new NotificationController();
        ReflectionTestUtils.setField(controller, "notificationService", notifications);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        User user = new User();
        user.setId("user-1");
        UserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void listPaginatesOnlyCurrentUsersExpiryNotificationsAndDoesNotExposeDeliveryFields() throws Exception {
        UserNotification notification = notification("notice-1", "/domain/example.com");
        notification.setEmailState(UserNotification.EMAIL_STATE_FAILED);
        notification.setEmailClaimToken("internal-claim-token");
        Page<UserNotification> page = new Page<>(2, 20, 1);
        page.setRecords(List.of(notification));
        when(notifications.page(any(Page.class), any(Wrapper.class))).thenReturn(page);

        mvc.perform(get("/api/notifications").param("page", "2").param("category", "expiry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("notice-1"))
                .andExpect(jsonPath("$.data.items[0].targetPath").value("/domain/example.com"))
                .andExpect(jsonPath("$.data.items[0].emailState").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].emailClaimToken").doesNotExist());

        ArgumentCaptor<Wrapper<UserNotification>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(notifications).page(any(Page.class), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"), sql);
        assertTrue(sql.contains("event_id"), sql);
    }

    @Test
    void listRejectsUnknownCategoriesInsteadOfPassingThemIntoSql() throws Exception {
        mvc.perform(get("/api/notifications").param("category", "expiry' OR '1'='1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        verify(notifications, never()).page(any(Page.class), any(Wrapper.class));
    }

    @Test
    void listSuppressesExternalAndProtocolRelativeTargets() throws Exception {
        Page<UserNotification> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(notification("external", "https://attacker.example"), notification("protocol-relative", "//attacker.example")));
        when(notifications.page(any(Page.class), any(Wrapper.class))).thenReturn(page);

        mvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].targetPath").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].targetPath").doesNotExist());
    }

    @Test
    void unreadCountUsesTheCurrentUserPredicate() throws Exception {
        when(notifications.count(any(Wrapper.class))).thenReturn(3L);

        mvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(3));

        ArgumentCaptor<Wrapper<UserNotification>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(notifications).count(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
        assertTrue(query.getValue().getSqlSegment().contains("read_at IS NULL"));
    }

    @Test
    void markReadUsesAnAtomicCurrentUserUpdateWithoutWritingDeliveryFields() throws Exception {
        when(notifications.update(isNull(), any(Wrapper.class))).thenReturn(true);

        mvc.perform(put("/api/notifications/notice-1/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<Wrapper<UserNotification>> update = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(notifications).update(isNull(), update.capture());
        LambdaUpdateWrapper<UserNotification> wrapper = (LambdaUpdateWrapper<UserNotification>) update.getValue();
        String sql = wrapper.getSqlSegment();
        String set = wrapper.getSqlSet();
        assertTrue(sql.contains("id") && sql.contains("user_id") && sql.contains("deleted")
                && sql.contains("read_at IS NULL"), sql);
        assertTrue(set.contains("read_at"), set);
        assertFalse(set.contains("email_state"), set);
        assertFalse(set.contains("email_claim"), set);
        assertFalse(set.contains("delivery_batch"), set);
        verify(notifications, never()).getOne(any(Wrapper.class));
        verify(notifications, never()).updateById(any(UserNotification.class));
    }

    @Test
    void markReadTreatsAnAlreadyReadCurrentUsersNotificationAsIdempotentSuccess() throws Exception {
        UserNotification alreadyRead = notification("notice-1", "/domain/example.com");
        alreadyRead.setReadAt(new Date());
        when(notifications.update(isNull(), any(Wrapper.class))).thenReturn(false);
        when(notifications.getOne(any(Wrapper.class))).thenReturn(alreadyRead);

        mvc.perform(put("/api/notifications/notice-1/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<Wrapper<UserNotification>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(notifications).getOne(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("id") && sql.contains("user_id") && sql.contains("deleted")
                && sql.contains("read_at IS NOT NULL"), sql);
    }

    @Test
    void markReadRejectsAnotherUsersNotificationWhenTheAtomicUpdateChangesNoRows() throws Exception {
        when(notifications.update(isNull(), any(Wrapper.class))).thenReturn(false);
        when(notifications.getOne(any(Wrapper.class))).thenReturn(null);

        mvc.perform(put("/api/notifications/other-users-notice/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        ArgumentCaptor<Wrapper<UserNotification>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(notifications).getOne(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("id") && sql.contains("user_id") && sql.contains("deleted"), sql);
        verify(notifications, never()).updateById(any(UserNotification.class));
    }

    @Test
    void markAllReadUpdatesOnlyUnreadNotificationsOwnedByTheCurrentUser() throws Exception {
        when(notifications.count(any(Wrapper.class))).thenReturn(1L);
        when(notifications.update(isNull(), any(Wrapper.class))).thenReturn(true);

        mvc.perform(put("/api/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<Wrapper<UserNotification>> update = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(notifications).update(isNull(), update.capture());
        String sql = update.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"), sql);
        assertTrue(sql.contains("read_at IS NULL"), sql);
        assertTrue(sql.contains("deleted"), sql);
    }

    @Test
    void markAllReadIsIdempotentWhenNoUnreadNotificationsExist() throws Exception {
        when(notifications.count(any(Wrapper.class))).thenReturn(0L);

        mvc.perform(put("/api/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(notifications, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void markAllReadFailsWhenUnreadNotificationsRemainAfterAnUpdateFailure() throws Exception {
        when(notifications.count(any(Wrapper.class))).thenReturn(1L);
        when(notifications.update(isNull(), any(Wrapper.class))).thenReturn(false);

        mvc.perform(put("/api/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        verify(notifications, times(2)).count(any(Wrapper.class));
    }

    @Test
    void markAllReadTreatsAConcurrentSuccessfulReadAsIdempotentSuccess() throws Exception {
        when(notifications.count(any(Wrapper.class))).thenReturn(1L, 0L);
        when(notifications.update(isNull(), any(Wrapper.class))).thenReturn(false);

        mvc.perform(put("/api/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void deleteUsesNotificationAndCurrentUserPredicates() throws Exception {
        when(notifications.remove(any(Wrapper.class))).thenReturn(true);

        mvc.perform(delete("/api/notifications/notice-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<Wrapper<UserNotification>> delete = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(notifications).remove(delete.capture());
        String sql = delete.getValue().getSqlSegment();
        assertTrue(sql.contains("id") && sql.contains("user_id"), sql);
    }

    private static UserNotification notification(String id, String targetPath) {
        UserNotification notification = new UserNotification();
        notification.setId(id);
        notification.setTitle("Domain change");
        notification.setContent("The nameserver changed.");
        notification.setTargetPath(targetPath);
        notification.setCreateTime(new Date(0));
        return notification;
    }
}
