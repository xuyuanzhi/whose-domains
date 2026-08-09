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
import org.springframework.util.ReflectionUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.UserNotificationService;

@SuppressWarnings({ "rawtypes", "unchecked" })
class NotificationControllerTest {

    private UserNotificationService notifications;
    private MonitorEventService events;
    private DomainWatchService watches;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "NotificationControllerTest"),
                UserNotification.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "NotificationControllerTest"),
                DomainWatch.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "NotificationControllerTest"),
                MonitorEvent.class);
        notifications = mock(UserNotificationService.class);
        events = mock(MonitorEventService.class);
        watches = mock(DomainWatchService.class);
        NotificationController controller = new NotificationController();
        ReflectionTestUtils.setField(controller, "notificationService", notifications);
        if (ReflectionUtils.findField(NotificationController.class, "monitorEventService") != null) {
            ReflectionTestUtils.setField(controller, "monitorEventService", events);
            ReflectionTestUtils.setField(controller, "domainWatchService", watches);
        }
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
    void listScopesADomainFilterToTheCurrentUsersWatch() throws Exception {
        Page<UserNotification> page = new Page<>(1, 20, 0);
        page.setRecords(List.of());
        when(notifications.page(any(Page.class), any(Wrapper.class))).thenReturn(page);

        mvc.perform(get("/api/notifications").param("domain", "Example.COM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));

        ArgumentCaptor<Wrapper<UserNotification>> notificationQuery = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(notifications).page(any(Page.class), notificationQuery.capture());
        String sql = notificationQuery.getValue().getSqlSegment().toUpperCase(java.util.Locale.ROOT);
        assertTrue(sql.contains("EXISTS") && sql.contains("WEB_MONITOR_EVENT")
                && sql.contains("WEB_DOMAIN_WATCH") && sql.contains("DOMAIN_NAME")
                && sql.contains("USER_ID") && sql.contains("STATUS"), sql);
        verify(watches, never()).getOne(any(Wrapper.class));
        verify(events, never()).list(any(Wrapper.class));
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
    void listBatchUsesPersistedCanonicalRiskAndSourceWithoutReinterpretingEventFields() throws Exception {
        UserNotification website = notification("notice-web", "/domain/example.com");
        website.setEventId("event-web");
        website.setTitle("Recovered wording must not lower canonical risk");
        UserNotification ssl = notification("notice-ssl", "/domain/example.com");
        ssl.setEventId("event-ssl");
        UserNotification domain = notification("notice-domain", "/domain/example.com");
        domain.setEventId("event-domain");
        Page<UserNotification> page = new Page<>(1, 20, 3);
        page.setRecords(List.of(website, ssl, domain));
        when(notifications.page(any(Page.class), any(Wrapper.class))).thenReturn(page);

        Date occurredAt = new Date(1_786_233_600_000L); // 2026-08-09T00:00:00Z
        MonitorEvent websiteEvent = event("event-web", "watch-1", "WEBSITE_DOWN", "false", occurredAt);
        MonitorEvent sslEvent = event("event-ssl", "watch-1", "SSL_EXPIRING", "2026-09-08", occurredAt);
        MonitorEvent domainEvent = event("event-domain", "watch-1", "DOMAIN_EXPIRING", "2026-08-16", occurredAt);
        websiteEvent.setRisk("LOW");
        websiteEvent.setSource("HTTP");
        sslEvent.setRisk("MEDIUM");
        sslEvent.setSource("TLS");
        domainEvent.setRisk("CRITICAL");
        domainEvent.setSource("WHOIS/RDAP");
        when(events.listByIds(any())).thenReturn(List.of(websiteEvent, sslEvent, domainEvent));
        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setDomainName("example.com");
        when(watches.list(any(Wrapper.class))).thenReturn(List.of(watch));

        mvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eventType").value("WEBSITE_DOWN"))
                .andExpect(jsonPath("$.data.items[0].risk").value("LOW"))
                .andExpect(jsonPath("$.data.items[0].domain").value("example.com"))
                .andExpect(jsonPath("$.data.items[0].source").value("HTTP"))
                .andExpect(jsonPath("$.data.items[1].risk").value("MEDIUM"))
                .andExpect(jsonPath("$.data.items[1].source").value("TLS"))
                .andExpect(jsonPath("$.data.items[2].risk").value("CRITICAL"))
                .andExpect(jsonPath("$.data.items[2].source").value("WHOIS/RDAP"));

        verify(events, times(1)).listByIds(any());
        verify(watches, times(1)).list(any(Wrapper.class));
    }

    @Test
    void listLabelsLegacyNullRiskAndSourceAsUnknownInsteadOfInventingCanonicalFacts() throws Exception {
        UserNotification legacy = notification("notice-legacy", "/domain/example.com");
        legacy.setEventId("event-legacy");
        Page<UserNotification> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(legacy));
        when(notifications.page(any(Page.class), any(Wrapper.class))).thenReturn(page);
        MonitorEvent event = event(
            "event-legacy",
            "watch-1",
            "WEBSITE_DOWN",
            "false",
            new Date(1_786_233_600_000L));
        when(events.listByIds(any())).thenReturn(List.of(event));
        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setDomainName("example.com");
        when(watches.list(any(Wrapper.class))).thenReturn(List.of(watch));

        mvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].risk").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].source").value("UNKNOWN"));
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

    private static MonitorEvent event(String id, String watchId, String type, String newValue, Date occurredAt) {
        MonitorEvent event = new MonitorEvent();
        event.setId(id);
        event.setWatchId(watchId);
        event.setEventType(type);
        event.setNewValue(newValue);
        event.setOccurredAt(occurredAt);
        return event;
    }
}
