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
import info.wesite.core.entity.User;
import info.wesite.core.mapper.DomainWatchSummaryMapper;
import info.wesite.core.mapper.model.DomainWatchLatestCheckRow;
import info.wesite.core.mapper.model.DomainWatchLatestEventRow;
import info.wesite.core.mapper.model.DomainWatchUnreadCountRow;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.view.ResponseJson;

@SuppressWarnings({ "rawtypes", "unchecked" })
class DomainWatchSummaryTest {

    private DomainWatchService watches;
    private DomainWatchSummaryMapper summaries;
    private DomainWatchController controller;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "DomainWatchSummaryTest"),
                DomainWatch.class);
        watches = mock(DomainWatchService.class);
        summaries = mock(DomainWatchSummaryMapper.class);
        controller = new DomainWatchController();
        ReflectionTestUtils.setField(controller, "domainWatchService", watches);
        ReflectionTestUtils.setField(controller, "domainService", mock(DomainService.class));
        ReflectionTestUtils.setField(controller, "domainWatchSummaryMapper", summaries);

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
        when(summaries.selectLatestEvents(any())).thenReturn(List.of());
        when(summaries.selectLatestSuccessfulChecks(any())).thenReturn(List.of());
        when(summaries.selectUnreadCounts(any(), any())).thenReturn(List.of());

        ResponseJson<DomainWatchSummary> response = controller.listWatches();

        DomainWatchSummary summary = summaries(response).get(0);
        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        assertEquals(watch, summary.getWatch());
        assertEquals("UNKNOWN", summary.getLatestEventRisk());
        assertEquals(0L, summary.getUnreadCount());
        assertNull(summary.getLastSuccessfulCheck());
        assertEquals("No monitoring events yet", summary.getLatestEventSummary());
    }

    @Test
    void listUsesPerWatchAggregateRowsAndDoesNotLoadHistoryOrExpandEventIds() {
        DomainWatch firstWatch = watch("watch-1", "first.example");
        DomainWatch secondWatch = watch("watch-2", "second.example");
        when(watches.list(any(Wrapper.class))).thenReturn(List.of(firstWatch, secondWatch));

        when(summaries.selectLatestEvents(any())).thenReturn(List.of(
                latestEvent("watch-1", "LOW", "WEBSITE_DOWN", "offline"),
                latestEvent("watch-2", "HIGH", "SSL_EXPIRING", "soon")));
        when(summaries.selectLatestSuccessfulChecks(any())).thenReturn(List.of(
                latestCheck("watch-1", 4_000L)));
        when(summaries.selectUnreadCounts(any(), any())).thenReturn(List.of(
                unreadCount("watch-1", 1L)));

        ResponseJson<DomainWatchSummary> response = controller.listWatches();

        DomainWatchSummary first = summaries(response).get(0);
        DomainWatchSummary secondSummary = summaries(response).get(1);
        assertEquals("LOW", first.getLatestEventRisk(), "The persisted event risk must not be reinterpreted from event text.");
        assertEquals("WEBSITE_DOWN: offline", first.getLatestEventSummary());
        assertEquals(1L, first.getUnreadCount());
        assertEquals(new Date(4_000L), first.getLastSuccessfulCheck());
        assertEquals(0L, secondSummary.getUnreadCount(), "Another user's unread notification must not leak into this watch.");

        verify(summaries, times(1)).selectLatestEvents(List.of("watch-1", "watch-2"));
        verify(summaries, times(1)).selectLatestSuccessfulChecks(List.of("watch-1", "watch-2"));
        verify(summaries, times(1)).selectUnreadCounts("user-1", List.of("watch-1", "watch-2"));
    }

    private static DomainWatch watch(String id, String domain) {
        DomainWatch watch = new DomainWatch();
        watch.setId(id);
        watch.setDomainName(domain);
        watch.setStatus(BaseEntity.STATUS_ACTIVE);
        return watch;
    }

    private static DomainWatchLatestEventRow latestEvent(String watchId, String risk, String type, String value) {
        return new DomainWatchLatestEventRow(watchId, risk, type, value);
    }

    private static DomainWatchLatestCheckRow latestCheck(String watchId, long checkedAt) {
        return new DomainWatchLatestCheckRow(watchId, new Date(checkedAt));
    }

    private static DomainWatchUnreadCountRow unreadCount(String watchId, long count) {
        return new DomainWatchUnreadCountRow(watchId, count);
    }

    private static List<DomainWatchSummary> summaries(ResponseJson<DomainWatchSummary> response) {
        return (List<DomainWatchSummary>) response.getData();
    }
}
