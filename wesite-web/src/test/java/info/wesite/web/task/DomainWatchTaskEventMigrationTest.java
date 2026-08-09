package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.mail.MailSender;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.service.MonitorSnapshotService;
import info.wesite.web.monitor.DnsMonitorCollector;
import info.wesite.web.monitor.DomainMonitorCollector;
import info.wesite.web.monitor.MonitorChangeDetector;
import info.wesite.web.monitor.MonitorCollectorResult;
import info.wesite.web.monitor.MonitorEventDraft;
import info.wesite.web.monitor.MonitorEventPublisher;
import info.wesite.web.monitor.MonitorEventType;
import info.wesite.web.monitor.MonitorDeadline;
import info.wesite.web.monitor.MonitorState;
import info.wesite.web.monitor.SslMonitorCollector;
import info.wesite.web.monitor.WebsiteMonitorCollector;

class DomainWatchTaskEventMigrationTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void expiryScanRoutesCurrentExpiryStateThroughTheEventPublisher() {
        DomainWatchService watchService = mock(DomainWatchService.class);
        DomainService domainService = mock(DomainService.class);
        MonitorSnapshotService snapshotService = mock(MonitorSnapshotService.class);
        MonitorEventPublisher publisher = mock(MonitorEventPublisher.class);
        DomainWatch watch = watch();
        Page<DomainWatch> page = new Page<>(1, 100);
        page.setRecords(List.of(watch));
        when(watchService.page(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(watchService.updateById(any(DomainWatch.class))).thenReturn(true);
        when(domainService.getById("domain-1")).thenReturn(domain());

        task(watchService, domainService, snapshotService, publisher).checkDomainExpiry();

        ArgumentCaptor<MonitorState> state = ArgumentCaptor.forClass(MonitorState.class);
        verify(publisher).publish(eq(watch), state.capture(), eq(true), any(Set.class));
        assertEquals("example.com", state.getValue().domain());
        assertEquals(LocalDate.of(2026, 9, 8), state.getValue().domainExpiry());
        InOrder persistenceOrder = inOrder(publisher, watchService);
        persistenceOrder.verify(publisher).publish(
            eq(watch), any(MonitorState.class), eq(true), any(Set.class));
        persistenceOrder.verify(watchService).updateById(watch);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void publisherFailureDoesNotAdvanceTheSuccessfulScanCursor() {
        DomainWatchService watchService = mock(DomainWatchService.class);
        DomainService domainService = mock(DomainService.class);
        MonitorSnapshotService snapshotService = mock(MonitorSnapshotService.class);
        MonitorEventPublisher publisher = mock(MonitorEventPublisher.class);
        DomainWatch watch = watch();
        Page<DomainWatch> page = new Page<>(1, 100);
        page.setRecords(List.of(watch));
        when(watchService.page(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(domainService.getById("domain-1")).thenReturn(domain());
        when(publisher.publish(
            eq(watch), any(MonitorState.class), eq(true), any(Set.class)))
            .thenThrow(new IllegalStateException("publisher unavailable"));

        task(watchService, domainService, snapshotService, publisher).checkDomainExpiry();

        assertNull(watch.getLastCheckTime());
        verify(watchService, never()).updateById(any(DomainWatch.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void recentLegacyCheckDoesNotSuppressFirstSuccessfulExpiryPublication() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC);
        DomainWatchService watchService = mock(DomainWatchService.class);
        DomainService domainService = mock(DomainService.class);
        MonitorSnapshotService snapshotService = mock(MonitorSnapshotService.class);
        MonitorEventPublisher publisher = mock(MonitorEventPublisher.class);
        DomainWatch watch = watch();
        watch.setLastCheckTime(new Date());
        Page<DomainWatch> page = new Page<>(1, 100);
        page.setRecords(List.of(watch));
        when(watchService.page(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(watchService.updateById(any(DomainWatch.class))).thenReturn(true);
        when(snapshotService.count(any(Wrapper.class))).thenReturn(0L);
        when(domainService.getById("domain-1")).thenReturn(domain("2026-08-10"));

        task(watchService, domainService, snapshotService, publisher).checkDomainExpiry();

        ArgumentCaptor<MonitorState> state = ArgumentCaptor.forClass(MonitorState.class);
        verify(publisher).publish(eq(watch), state.capture(), eq(true), any(Set.class));
        List<MonitorEventDraft> events = new MonitorChangeDetector(clock).detect(
            null, state.getValue(), null, clock.instant());
        assertTrue(events.stream().anyMatch(event ->
            event.type() == MonitorEventType.DOMAIN_EXPIRING
                && "domainExpiry:1".equals(event.field())));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void recentCheckStillSuppressesRefreshWhenASuccessfulSnapshotExists() {
        DomainWatchService watchService = mock(DomainWatchService.class);
        DomainService domainService = mock(DomainService.class);
        MonitorSnapshotService snapshotService = mock(MonitorSnapshotService.class);
        MonitorEventPublisher publisher = mock(MonitorEventPublisher.class);
        DomainWatch watch = watch();
        watch.setLastCheckTime(new Date());
        Page<DomainWatch> page = new Page<>(1, 100);
        page.setRecords(List.of(watch));
        when(watchService.page(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(snapshotService.count(any(Wrapper.class))).thenReturn(1L);

        task(watchService, domainService, snapshotService, publisher).checkDomainExpiry();

        verifyNoInteractions(domainService, publisher);
        verify(watchService, never()).updateById(any(DomainWatch.class));
    }

    @Test
    void legacyDirectExpiryMailEntryPointAndMailDependencyAreRemoved() {
        assertFalse(List.of(DomainWatchTask.class.getDeclaredMethods()).stream()
            .anyMatch(method -> method.getName().equals("sendExpiryNotifications")));
        assertFalse(List.of(DomainWatchTask.class.getDeclaredFields()).stream()
            .anyMatch(field -> MailSender.class.isAssignableFrom(field.getType())));
        assertTrue(List.of(DomainWatchTask.class.getDeclaredFields()).stream()
            .anyMatch(field -> MonitorEventPublisher.class.isAssignableFrom(field.getType())));
    }

    private static DomainWatch watch() {
        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        watch.setUserId("user-1");
        watch.setDomainId("domain-1");
        watch.setDomainName("example.com");
        watch.setStatus(DomainWatch.STATUS_ACTIVE);
        return watch;
    }

    private static DomainWatchTask task(
        DomainWatchService watchService,
        DomainService domainService,
        MonitorSnapshotService snapshotService,
        MonitorEventPublisher publisher) {
        DomainMonitorCollector domainCollector = mock(DomainMonitorCollector.class);
        DnsMonitorCollector dnsCollector = mock(DnsMonitorCollector.class);
        SslMonitorCollector sslCollector = mock(SslMonitorCollector.class);
        WebsiteMonitorCollector websiteCollector = mock(WebsiteMonitorCollector.class);
        when(domainCollector.collect(
            any(Domain.class), any(MonitorDeadline.class))).thenAnswer(invocation -> {
            Domain value = invocation.getArgument(0);
            LocalDate expiry = LocalDate.parse(value.getRegistExpiryDateText().substring(0, 10));
            return MonitorCollectorResult.success(
                MonitorCollectorResult.Source.DOMAIN,
                new MonitorState(value.getName(), Set.of(value.getDomainStatus()), expiry,
                    null, Map.of(), false, 0));
        });
        when(dnsCollector.collect(
            anyString(), any(MonitorDeadline.class))).thenAnswer(invocation ->
            MonitorCollectorResult.success(
                MonitorCollectorResult.Source.DNS,
                new MonitorState(invocation.getArgument(0), Set.of(), null, null,
                    Map.of(), false, 0)));
        when(sslCollector.collect(
            anyString(), any(MonitorDeadline.class))).thenAnswer(invocation ->
            MonitorCollectorResult.success(
                MonitorCollectorResult.Source.SSL,
                new MonitorState(invocation.getArgument(0), Set.of(), null, null,
                    Map.of(), false, 0)));
        when(websiteCollector.collect(
            anyString(), anyInt(), any(MonitorDeadline.class))).thenAnswer(invocation ->
            MonitorCollectorResult.success(
                MonitorCollectorResult.Source.WEBSITE,
                new MonitorState(invocation.getArgument(0), Set.of(), null, null,
                    Map.of(), true, 0)));
        return new DomainWatchTask(
            watchService,
            domainService,
            snapshotService,
            publisher,
            domainCollector,
            dnsCollector,
            sslCollector,
            websiteCollector);
    }

    private static Domain domain() {
        return domain("2026-09-08");
    }

    private static Domain domain(String expiryDate) {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setName("example.com");
        domain.setRegistExpiryDateText(expiryDate);
        domain.setDomainStatus("ok");
        domain.setRegistrar("Example Registrar");
        domain.setUpdateTime(Date.from(
            LocalDate.of(2026, 8, 9).atStartOfDay().toInstant(ZoneOffset.UTC)));
        return domain;
    }
}
