package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.web.monitor.DnsMonitorCollector;
import info.wesite.web.monitor.DomainMonitorCollector;
import info.wesite.web.monitor.MonitorCollectorResult;
import info.wesite.web.monitor.MonitorEventPublisher;
import info.wesite.web.monitor.MonitorState;
import info.wesite.web.monitor.SslMonitorCollector;
import info.wesite.web.monitor.WebsiteMonitorCollector;

class DomainWatchTaskTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void partialFailurePreservesLastSuccessfulSourceAndPublishesOnce() {
        Fixture fixture = fixture();
        MonitorState previous = new MonitorState(
            "example.com",
            Set.of("oldStatus"),
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 11, 1),
            Map.of("A", Set.of("203.0.113.1")),
            false,
            1);
        when(fixture.snapshotService.getOne(any(Wrapper.class))).thenReturn(snapshot(previous));
        when(fixture.domainCollector.collect(fixture.domain)).thenReturn(success(
            MonitorCollectorResult.Source.DOMAIN,
            new MonitorState("example.com", Set.of("ok"), LocalDate.of(2027, 1, 3),
                null, Map.of(), false, 0)));
        when(fixture.dnsCollector.collect("example.com")).thenReturn(MonitorCollectorResult.failure(
            MonitorCollectorResult.Source.DNS,
            MonitorCollectorResult.FailureKind.NOT_FOUND,
            "NXDOMAIN"));
        when(fixture.sslCollector.collect("example.com")).thenReturn(success(
            MonitorCollectorResult.Source.SSL,
            new MonitorState("example.com", Set.of(), null, LocalDate.of(2027, 2, 4),
                Map.of(), false, 0)));
        when(fixture.websiteCollector.collect("example.com", 1)).thenReturn(success(
            MonitorCollectorResult.Source.WEBSITE,
            new MonitorState("example.com", Set.of(), null, null, Map.of(), false, 2)));

        fixture.task.checkDomainExpiry();

        ArgumentCaptor<MonitorState> state = ArgumentCaptor.forClass(MonitorState.class);
        verify(fixture.publisher, times(1)).publish(eq(fixture.watch), state.capture(), eq(true));
        assertEquals(Set.of("ok"), state.getValue().domainStatuses());
        assertEquals(Map.of("A", Set.of("203.0.113.1")), state.getValue().dnsRecords());
        assertEquals(LocalDate.of(2027, 2, 4), state.getValue().sslExpiry());
        assertFalse(state.getValue().websiteAvailable());
        assertEquals(2, state.getValue().websiteFailureCount());
        verify(fixture.watchService).updateById(fixture.watch);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void firstPartialCheckCreatesOneFailedDiagnosticInsteadOfAnUnknownBaseline() {
        Fixture fixture = fixture();
        when(fixture.snapshotService.getOne(any(Wrapper.class))).thenReturn(null);
        when(fixture.domainCollector.collect(fixture.domain)).thenReturn(success(
            MonitorCollectorResult.Source.DOMAIN,
            new MonitorState("example.com", Set.of("ok"), LocalDate.of(2027, 1, 3),
                null, Map.of(), false, 0)));
        when(fixture.dnsCollector.collect("example.com")).thenReturn(success(
            MonitorCollectorResult.Source.DNS,
            new MonitorState("example.com", Set.of(), null, null,
                Map.of("A", Set.of("203.0.113.8")), false, 0)));
        when(fixture.sslCollector.collect("example.com")).thenReturn(success(
            MonitorCollectorResult.Source.SSL,
            new MonitorState("example.com", Set.of(), null, LocalDate.of(2027, 2, 4),
                Map.of(), false, 0)));
        when(fixture.websiteCollector.collect("example.com", 0)).thenReturn(MonitorCollectorResult.failure(
            MonitorCollectorResult.Source.WEBSITE,
            MonitorCollectorResult.FailureKind.TIMEOUT,
            "request timed out"));

        fixture.task.checkDomainExpiry();

        ArgumentCaptor<MonitorState> state = ArgumentCaptor.forClass(MonitorState.class);
        verify(fixture.publisher, times(1)).publish(eq(fixture.watch), state.capture(), eq(false));
        assertEquals(Set.of("ok"), state.getValue().domainStatuses());
        assertEquals(Map.of("A", Set.of("203.0.113.8")), state.getValue().dnsRecords());
        assertTrue(state.getValue().websiteAvailable());
    }

    private static MonitorCollectorResult success(
        MonitorCollectorResult.Source source,
        MonitorState state) {
        return MonitorCollectorResult.success(source, state);
    }

    private static MonitorSnapshot snapshot(MonitorState state) {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setStateJson(JSON.toJSONString(state));
        return snapshot;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Fixture fixture() {
        DomainWatchService watchService = mock(DomainWatchService.class);
        DomainService domainService = mock(DomainService.class);
        MonitorSnapshotService snapshotService = mock(MonitorSnapshotService.class);
        MonitorEventPublisher publisher = mock(MonitorEventPublisher.class);
        DomainMonitorCollector domainCollector = mock(DomainMonitorCollector.class);
        DnsMonitorCollector dnsCollector = mock(DnsMonitorCollector.class);
        SslMonitorCollector sslCollector = mock(SslMonitorCollector.class);
        WebsiteMonitorCollector websiteCollector = mock(WebsiteMonitorCollector.class);

        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setUserId("user-1");
        watch.setDomainId("domain-1");
        watch.setDomainName("example.com");
        watch.setStatus(DomainWatch.STATUS_ACTIVE);
        Page<DomainWatch> page = new Page<>(1, 100);
        page.setRecords(List.of(watch));
        when(watchService.page(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(watchService.updateById(any(DomainWatch.class))).thenReturn(true);

        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setName("example.com");
        domain.setRegistrar("Old Registrar");
        when(domainService.getById("domain-1")).thenReturn(domain);

        DomainWatchTask task = new DomainWatchTask(
            watchService,
            domainService,
            snapshotService,
            publisher,
            domainCollector,
            dnsCollector,
            sslCollector,
            websiteCollector);
        return new Fixture(
            task,
            watchService,
            snapshotService,
            publisher,
            domainCollector,
            dnsCollector,
            sslCollector,
            websiteCollector,
            watch,
            domain);
    }

    private record Fixture(
        DomainWatchTask task,
        DomainWatchService watchService,
        MonitorSnapshotService snapshotService,
        MonitorEventPublisher publisher,
        DomainMonitorCollector domainCollector,
        DnsMonitorCollector dnsCollector,
        SslMonitorCollector sslCollector,
        WebsiteMonitorCollector websiteCollector,
        DomainWatch watch,
        Domain domain) {
    }
}
