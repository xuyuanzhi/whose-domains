package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.User;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.notification.NotificationCancellationService;
import info.wesite.web.notification.NotificationPolicyLock;
import org.mockito.ArgumentCaptor;

@SuppressWarnings({ "rawtypes", "unchecked" })
class DomainWatchControllerTest {

    private DomainWatchService watches;
    private DomainWatchController controller;
    private NotificationCancellationService cancellations;
    private NotificationPolicyLock policyLock;

    @BeforeEach
    void setUp() {
        watches = mock(DomainWatchService.class);
        controller = new DomainWatchController();
        ReflectionTestUtils.setField(controller, "domainWatchService", watches);
        ReflectionTestUtils.setField(controller, "domainService", mock(DomainService.class));
        cancellations = mock(NotificationCancellationService.class);
        policyLock = mock(NotificationPolicyLock.class);
        ReflectionTestUtils.setField(controller, "cancellationService", cancellations);
        ReflectionTestUtils.setField(controller, "policyLock", policyLock);
        ReflectionTestUtils.setField(controller, "retentionReportingClock", Clock.systemUTC());

        User user = new User();
        user.setId("user-1");
        UserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void repeatedPostForAnActiveWatchReturnsTheExistingWatchAsIdempotentSuccess() {
        DomainWatch existing = new DomainWatch();
        existing.setId("watch-1");
        existing.setUserId("user-1");
        existing.setDomainName("example.com");
        existing.setStatus(BaseEntity.STATUS_ACTIVE);
        when(watches.getOne(any(Wrapper.class))).thenReturn(existing);

        DomainWatch request = new DomainWatch();
        request.setDomainName(" Example.COM ");
        request.setNotifyType(DomainWatch.NOTIFY_BOTH);

        ResponseJson<DomainWatch> first = controller.watchDomain(request);
        ResponseJson<DomainWatch> repeated = controller.watchDomain(request);

        assertEquals(ResponseJson.CODE_SUCCESS, first.getCode());
        assertEquals(ResponseJson.CODE_SUCCESS, repeated.getCode());
        assertSame(existing, first.getData());
        assertSame(existing, repeated.getData());
        verify(watches, never()).count(any(Wrapper.class));
        verify(watches, never()).save(any(DomainWatch.class));
        verify(watches, never()).updateById(any(DomainWatch.class));
    }

    @Test
    void concurrentDuplicateInsertReturnsTheWinningActiveWatchAsIdempotentSuccess() {
        DomainWatch winner = new DomainWatch();
        winner.setId("watch-winner");
        winner.setUserId("user-1");
        winner.setDomainName("example.com");
        winner.setStatus(BaseEntity.STATUS_ACTIVE);
        when(watches.getOne(any(Wrapper.class))).thenReturn(null, winner);
        when(watches.count(any(Wrapper.class))).thenReturn(0L);
        when(watches.save(any(DomainWatch.class)))
                .thenThrow(new DuplicateKeyException("UK_USER_DOMAIN"));

        DomainWatch request = new DomainWatch();
        request.setDomainName("example.com");

        ResponseJson<DomainWatch> response = controller.watchDomain(request);

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        assertSame(winner, response.getData());
        verify(watches).save(any(DomainWatch.class));
    }

    @Test
    void newWatchPersistsTheInjectedReportingDateAcrossUtcAndNonPlusEightDayBoundaries() {
        assertNotNull(
            ReflectionUtils.findField(DomainWatchController.class, "retentionReportingClock"),
            "DomainWatchController must consume the shared retention reporting clock");
        when(watches.save(any(DomainWatch.class))).thenReturn(true);

        ReflectionTestUtils.setField(controller, "retentionReportingClock", Clock.fixed(
            Instant.parse("2026-08-08T16:30:00Z"), ZoneId.of("Asia/Shanghai")));
        DomainWatch shanghaiRequest = new DomainWatch();
        shanghaiRequest.setDomainName("shanghai-boundary.example");
        assertEquals(ResponseJson.CODE_SUCCESS, controller.watchDomain(shanghaiRequest).getCode());

        ReflectionTestUtils.setField(controller, "retentionReportingClock", Clock.fixed(
            Instant.parse("2026-08-09T02:30:00Z"), ZoneId.of("America/New_York")));
        DomainWatch newYorkRequest = new DomainWatch();
        newYorkRequest.setDomainName("new-york-boundary.example");
        assertEquals(ResponseJson.CODE_SUCCESS, controller.watchDomain(newYorkRequest).getCode());

        ArgumentCaptor<DomainWatch> saved = ArgumentCaptor.forClass(DomainWatch.class);
        verify(watches, times(2)).save(saved.capture());
        assertEquals(LocalDate.of(2026, 8, 9), saved.getAllValues().get(0).getWatchCreatedOn());
        assertEquals(LocalDate.of(2026, 8, 8), saved.getAllValues().get(1).getWatchCreatedOn());
    }

    @Test
    void updatePersistsTheValidatedTrimmedNotificationEmailAndCanClearIt() {
        DomainWatch existing = new DomainWatch();
        existing.setId("watch-1");
        existing.setUserId("user-1");
        existing.setDomainName("example.com");
        existing.setStatus(BaseEntity.STATUS_ACTIVE);
        existing.setNotifyEmail("old@example.com");
        when(watches.getOne(any(Wrapper.class))).thenReturn(existing);
        when(watches.updateById(existing)).thenReturn(true);

        DomainWatch update = new DomainWatch();
        update.setNotifyType(DomainWatch.NOTIFY_7_DAYS);
        update.setNotifyEmail("  alerts@example.com  ");
        ResponseJson<DomainWatch> saved = controller.updateWatch("watch-1", update);

        assertEquals(ResponseJson.CODE_SUCCESS, saved.getCode());
        assertEquals("alerts@example.com", existing.getNotifyEmail());
        assertEquals(DomainWatch.NOTIFY_7_DAYS, existing.getNotifyType());

        DomainWatch clear = new DomainWatch();
        clear.setNotifyType(DomainWatch.NOTIFY_NONE);
        clear.setNotifyEmail(null);
        controller.updateWatch("watch-1", clear);
        assertEquals(null, existing.getNotifyEmail());
        verify(cancellations, times(2)).cancelForWatch(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq("watch-1"), any());
        verify(policyLock, times(2)).lockUser("user-1");
    }

    @Test
    void createAndUpdateRejectHeaderInjectionOrMalformedRecipientAddresses() {
        DomainWatch create = new DomainWatch();
        create.setDomainName("example.com");
        create.setNotifyType(DomainWatch.NOTIFY_BOTH);
        create.setNotifyEmail("victim@example.com\r\nBcc: attacker@example.com");
        assertEquals(ResponseJson.CODE_FAILURE, controller.watchDomain(create).getCode());

        DomainWatch existing = new DomainWatch();
        existing.setId("watch-1");
        existing.setUserId("user-1");
        existing.setStatus(BaseEntity.STATUS_ACTIVE);
        when(watches.getOne(any(Wrapper.class))).thenReturn(existing);
        DomainWatch update = new DomainWatch();
        update.setNotifyEmail("not-an-email");
        assertEquals(ResponseJson.CODE_FAILURE, controller.updateWatch("watch-1", update).getCode());
        verify(watches, never()).updateById(existing);
    }

    @Test
    void unwatchCancelsEveryUnsentBatchAffectedByThatWatch() {
        DomainWatch existing = new DomainWatch();
        existing.setId("watch-1");
        existing.setUserId("user-1");
        existing.setStatus(BaseEntity.STATUS_ACTIVE);
        when(watches.getOne(any(Wrapper.class))).thenReturn(existing);
        when(watches.updateById(existing)).thenReturn(true);

        ResponseJson<String> response = controller.unwatchDomain("watch-1");

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        verify(cancellations).cancelForWatch(
                org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.eq("watch-1"), any());
    }

    @Test
    void cancellationFailureEscapesSoTheTransactionalWatchUpdateCanRollBack() throws Exception {
        DomainWatch existing = new DomainWatch();
        existing.setId("watch-1");
        existing.setUserId("user-1");
        existing.setStatus(BaseEntity.STATUS_ACTIVE);
        existing.setNotifyType(DomainWatch.NOTIFY_BOTH);
        existing.setNotifyEmail("old@example.com");
        when(watches.getOne(any(Wrapper.class))).thenReturn(existing);
        when(watches.updateById(existing)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("cancellation SQL failed"))
            .when(cancellations).cancelForWatch(
                org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.eq("watch-1"), any());

        DomainWatch update = new DomainWatch();
        update.setNotifyType(DomainWatch.NOTIFY_NONE);
        update.setNotifyEmail(null);

        assertThrows(IllegalStateException.class, () -> controller.updateWatch("watch-1", update));
        assertNotNull(DomainWatchController.class
            .getMethod("updateWatch", String.class, DomainWatch.class)
            .getAnnotation(Transactional.class));
        assertNotNull(DomainWatchController.class
            .getMethod("unwatchDomain", String.class)
            .getAnnotation(Transactional.class));
    }
}
