package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

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
import info.wesite.web.monitor.MonitorEventPublisher;
import info.wesite.web.monitor.MonitorState;

class DomainWatchTaskEventMigrationTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void expiryScanRoutesCurrentExpiryStateThroughTheEventPublisher() {
        DomainWatchService watchService = mock(DomainWatchService.class);
        DomainService domainService = mock(DomainService.class);
        MonitorEventPublisher publisher = mock(MonitorEventPublisher.class);
        DomainWatch watch = watch();
        Page<DomainWatch> page = new Page<>(1, 100);
        page.setRecords(List.of(watch));
        when(watchService.page(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(watchService.updateById(any(DomainWatch.class))).thenReturn(true);
        when(domainService.getById("domain-1")).thenReturn(domain());

        new DomainWatchTask(watchService, domainService, publisher).checkDomainExpiry();

        ArgumentCaptor<MonitorState> state = ArgumentCaptor.forClass(MonitorState.class);
        verify(publisher).publish(eq(watch), state.capture(), eq(true));
        assertEquals("example.com", state.getValue().domain());
        assertEquals(LocalDate.of(2026, 9, 8), state.getValue().domainExpiry());
        InOrder persistenceOrder = inOrder(publisher, watchService);
        persistenceOrder.verify(publisher).publish(eq(watch), any(MonitorState.class), eq(true));
        persistenceOrder.verify(watchService).updateById(watch);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void publisherFailureDoesNotAdvanceTheSuccessfulScanCursor() {
        DomainWatchService watchService = mock(DomainWatchService.class);
        DomainService domainService = mock(DomainService.class);
        MonitorEventPublisher publisher = mock(MonitorEventPublisher.class);
        DomainWatch watch = watch();
        Page<DomainWatch> page = new Page<>(1, 100);
        page.setRecords(List.of(watch));
        when(watchService.page(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(domainService.getById("domain-1")).thenReturn(domain());
        when(publisher.publish(eq(watch), any(MonitorState.class), eq(true)))
            .thenThrow(new IllegalStateException("publisher unavailable"));

        new DomainWatchTask(watchService, domainService, publisher).checkDomainExpiry();

        assertNull(watch.getLastCheckTime());
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

    private static Domain domain() {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setName("example.com");
        domain.setRegistExpiryDateText("2026-09-08");
        domain.setDomainStatus("ok");
        domain.setRegistrar("Example Registrar");
        domain.setUpdateTime(Date.from(
            LocalDate.of(2026, 8, 9).atStartOfDay().toInstant(ZoneOffset.UTC)));
        return domain;
    }
}
