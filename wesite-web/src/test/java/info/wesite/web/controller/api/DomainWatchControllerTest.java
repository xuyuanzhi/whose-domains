package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.User;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainWatchService;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.view.ResponseJson;

@SuppressWarnings({ "rawtypes", "unchecked" })
class DomainWatchControllerTest {

    private DomainWatchService watches;
    private DomainWatchController controller;
    private UserNotificationMapper notifications;

    @BeforeEach
    void setUp() {
        watches = mock(DomainWatchService.class);
        controller = new DomainWatchController();
        ReflectionTestUtils.setField(controller, "domainWatchService", watches);
        ReflectionTestUtils.setField(controller, "domainService", mock(DomainService.class));
        notifications = mock(UserNotificationMapper.class);
        ReflectionTestUtils.setField(controller, "notificationMapper", notifications);

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
        verify(notifications, times(2)).cancelUnclaimedForWatch(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq("watch-1"), any());
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
    void unwatchCancelsOnlyUnclaimedEmailForThatWatch() {
        DomainWatch existing = new DomainWatch();
        existing.setId("watch-1");
        existing.setUserId("user-1");
        existing.setStatus(BaseEntity.STATUS_ACTIVE);
        when(watches.getOne(any(Wrapper.class))).thenReturn(existing);
        when(watches.updateById(existing)).thenReturn(true);

        ResponseJson<String> response = controller.unwatchDomain("watch-1");

        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
        verify(notifications).cancelUnclaimedForWatch(
                org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.eq("watch-1"), any());
    }
}
