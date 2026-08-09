package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.service.MonitorEventService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.view.ResponseJson;

@SuppressWarnings({ "rawtypes", "unchecked" })
class DomainWatchSummaryTest {

    private DomainWatchService watches;
    private MonitorEventService events;
    private MonitorSnapshotService snapshots;
    private UserNotificationService notifications;
    private DomainWatchController controller;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "DomainWatchSummaryTest"),
                DomainWatch.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "DomainWatchSummaryTest"),
                MonitorEvent.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "DomainWatchSummaryTest"),
                MonitorSnapshot.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "DomainWatchSummaryTest"),
                UserNotification.class);
        watches = mock(DomainWatchService.class);
        events = mock(MonitorEventService.class);
        snapshots = mock(MonitorSnapshotService.class);
        notifications = mock(UserNotificationService.class);
        controller = new DomainWatchController();
        ReflectionTestUtils.setField(controller, "domainWatchService", watches);
        ReflectionTestUtils.setField(controller, "domainService", mock(DomainService.class));
        ReflectionTestUtils.setField(controller, "monitorEventService", events);
        ReflectionTestUtils.setField(controller, "monitorSnapshotService", snapshots);
        ReflectionTestUtils.setField(controller, "notificationService", notifications);

        User user = new User();
        user.setId("user-1");
        UserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void listUsesQuietDefaultsWhenAWatchHasNoMonitoringHistory() {
        DomainWatch watch = watch("watch-1", "example.com");
        when(watches.list(any(Wrapper.class))).thenReturn(List.of(watch));
        when(events.list(any(Wrapper.class))).thenReturn(List.of());
        when(snapshots.list(any(Wrapper.class))).thenReturn(List.of());
        when(notifications.list(any(Wrapper.class))).thenReturn(List.of());

        ResponseJson<DomainWatchSummary> response = controller.listWatches();

        DomainWatchSummary summary = summaries(response).get(0);
        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        assertEquals(watch, summary.getWatch());
        assertEquals("UNKNOWN", summary.getLatestRisk());
        assertEquals(0L, summary.getUnreadCount());
        assertNull(summary.getLastSuccessfulCheck());
        assertEquals("No monitoring events yet", summary.getLatestEventSummary());
    }

    @Test
    void listSelectsTheNewestPersistedEventRiskAndGroupsOnlyCurrentUsersUnreadEvents() {
        DomainWatch firstWatch = watch("watch-1", "first.example");
        DomainWatch secondWatch = watch("watch-2", "second.example");
        when(watches.list(any(Wrapper.class))).thenReturn(List.of(firstWatch, secondWatch));

        MonitorEvent older = event("event-old", "watch-1", "CRITICAL", "DNS_CHANGED", "ns-old", 1_000L);
        MonitorEvent newest = event("event-new", "watch-1", "LOW", "WEBSITE_DOWN", "offline", 2_000L);
        MonitorEvent second = event("event-second", "watch-2", "HIGH", "SSL_EXPIRING", "soon", 1_500L);
        when(events.list(any(Wrapper.class))).thenReturn(List.of(older, newest, second));

        MonitorSnapshot previous = snapshot("watch-1", BaseEntity.STATUS_ACTIVE, 3_000L);
        MonitorSnapshot latest = snapshot("watch-1", BaseEntity.STATUS_ACTIVE, 4_000L);
        when(snapshots.list(any(Wrapper.class))).thenReturn(List.of(previous, latest));

        UserNotification unreadCurrent = notification("notice-current", "user-1", "event-new", null);
        UserNotification readCurrent = notification("notice-read", "user-1", "event-old", new Date(5_000L));
        UserNotification unreadOtherUser = notification("notice-other", "user-2", "event-second", null);
        when(notifications.list(any(Wrapper.class))).thenReturn(List.of(unreadCurrent, readCurrent, unreadOtherUser));

        ResponseJson<DomainWatchSummary> response = controller.listWatches();

        DomainWatchSummary first = summaries(response).get(0);
        DomainWatchSummary secondSummary = summaries(response).get(1);
        assertEquals("LOW", first.getLatestRisk(), "The persisted event risk must not be reinterpreted from event text.");
        assertEquals("WEBSITE_DOWN: offline", first.getLatestEventSummary());
        assertEquals(1L, first.getUnreadCount());
        assertEquals(new Date(4_000L), first.getLastSuccessfulCheck());
        assertEquals(0L, secondSummary.getUnreadCount(), "Another user's unread notification must not leak into this watch.");

        verify(events, times(1)).list(any(Wrapper.class));
        verify(snapshots, times(1)).list(any(Wrapper.class));
        verify(notifications, times(1)).list(any(Wrapper.class));

        org.mockito.ArgumentCaptor<Wrapper<MonitorEvent>> eventQuery = org.mockito.ArgumentCaptor.forClass((Class) Wrapper.class);
        org.mockito.ArgumentCaptor<Wrapper<MonitorSnapshot>> snapshotQuery = org.mockito.ArgumentCaptor.forClass((Class) Wrapper.class);
        org.mockito.ArgumentCaptor<Wrapper<UserNotification>> notificationQuery = org.mockito.ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(events).list(eventQuery.capture());
        verify(snapshots).list(snapshotQuery.capture());
        verify(notifications).list(notificationQuery.capture());
        assertTrue(eventQuery.getValue().getSqlSegment().contains("watch_id"));
        assertTrue(snapshotQuery.getValue().getSqlSegment().contains("watch_id")
                && snapshotQuery.getValue().getSqlSegment().contains("status"));
        assertTrue(notificationQuery.getValue().getSqlSegment().contains("user_id")
                && notificationQuery.getValue().getSqlSegment().contains("read_at IS NULL"));
    }

    private static DomainWatch watch(String id, String domain) {
        DomainWatch watch = new DomainWatch();
        watch.setId(id);
        watch.setDomainName(domain);
        watch.setStatus(BaseEntity.STATUS_ACTIVE);
        return watch;
    }

    private static MonitorEvent event(String id, String watchId, String risk, String type, String value, long occurredAt) {
        MonitorEvent event = new MonitorEvent();
        event.setId(id);
        event.setWatchId(watchId);
        event.setRisk(risk);
        event.setEventType(type);
        event.setNewValue(value);
        event.setOccurredAt(new Date(occurredAt));
        return event;
    }

    private static MonitorSnapshot snapshot(String watchId, int status, long checkedAt) {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setWatchId(watchId);
        snapshot.setStatus(status);
        snapshot.setCheckedAt(new Date(checkedAt));
        return snapshot;
    }

    private static UserNotification notification(String id, String userId, String eventId, Date readAt) {
        UserNotification notification = new UserNotification();
        notification.setId(id);
        notification.setUserId(userId);
        notification.setEventId(eventId);
        notification.setReadAt(readAt);
        return notification;
    }

    private static List<DomainWatchSummary> summaries(ResponseJson<DomainWatchSummary> response) {
        return (List<DomainWatchSummary>) response.getData();
    }
}
