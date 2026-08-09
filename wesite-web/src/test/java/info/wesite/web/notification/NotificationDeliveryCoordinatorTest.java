package info.wesite.web.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import info.wesite.core.entity.DomainWatchNotifyLog;
import info.wesite.core.entity.NotificationDeliveryBatch;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mapper.DomainWatchNotifyLogMapper;
import info.wesite.core.mapper.NotificationDeliveryBatchMapper;
import info.wesite.core.mapper.UserNotificationMapper;

class NotificationDeliveryCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-09T08:00:00Z");
    private static final Instant LEASE_UNTIL = Instant.parse("2026-08-09T08:10:00Z");

    private NotificationDeliveryBatchMapper batchMapper;
    private UserNotificationMapper notificationMapper;
    private DomainWatchNotifyLogMapper logMapper;
    private NotificationDeliveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        batchMapper = mock(NotificationDeliveryBatchMapper.class);
        notificationMapper = mock(UserNotificationMapper.class);
        logMapper = mock(DomainWatchNotifyLogMapper.class);
        coordinator = new NotificationDeliveryCoordinator(batchMapper, notificationMapper, logMapper);
    }

    @Test
    void pendingAttemptLogMustPersistBeforeAClaimCanEscapeTheTransaction() {
        stubNewImmediate();
        when(logMapper.insert(any(DomainWatchNotifyLog.class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
            () -> coordinator.startImmediate("notification-1", NOW, LEASE_UNTIL));

        ArgumentCaptor<DomainWatchNotifyLog> log = ArgumentCaptor.forClass(DomainWatchNotifyLog.class);
        verify(logMapper).insert(log.capture());
        assertEquals(DomainWatchNotifyLog.SEND_STATUS_PENDING, log.getValue().getSendStatus());
        assertEquals(1, log.getValue().getRetryCount());
    }

    @Test
    void pendingAttemptLogExceptionAbortsTheClaim() {
        stubNewImmediate();
        when(logMapper.insert(any(DomainWatchNotifyLog.class)))
            .thenThrow(new IllegalStateException("audit database unavailable"));

        assertThrows(IllegalStateException.class,
            () -> coordinator.startImmediate("notification-1", NOW, LEASE_UNTIL));
    }

    @Test
    void expiredLeaseStartsTheNextPersistedAttemptAndFailsTheOldPendingLog() {
        NotificationDeliveryBatch batch = batch(1, NotificationDeliveryBatch.STATE_CLAIMED);
        batch.setClaimUntil(Date.from(NOW.minusSeconds(1)));
        when(batchMapper.selectRetryableForUpdate("IMMEDIATE_EMAIL", Date.from(NOW), 3)).thenReturn(batch);
        when(logMapper.finishPendingAttempt(
            eq("batch-1"), eq(1), eq(DomainWatchNotifyLog.SEND_STATUS_FAIL),
            any(Date.class), eq("delivery lease expired"),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(1);
        when(batchMapper.claimRetry(eq("batch-1"), eq(2), any(String.class), eq(Date.from(LEASE_UNTIL)),
            eq(Date.from(NOW)))).thenReturn(1);
        when(notificationMapper.claimBatchForAttempt(eq("batch-1"), eq(2), any(String.class),
            eq(Date.from(LEASE_UNTIL)), eq(Date.from(NOW)))).thenReturn(1);
        when(logMapper.insert(any(DomainWatchNotifyLog.class))).thenReturn(1);

        Optional<DeliveryBatchClaim> claim = coordinator.retryNext(
            "IMMEDIATE_EMAIL", NOW, LEASE_UNTIL);

        assertTrue(claim.isPresent());
        assertEquals(2, claim.orElseThrow().attempt());
        ArgumentCaptor<DomainWatchNotifyLog> nextLog = ArgumentCaptor.forClass(DomainWatchNotifyLog.class);
        verify(logMapper).insert(nextLog.capture());
        assertEquals(2, nextLog.getValue().getRetryCount());
        assertEquals(DomainWatchNotifyLog.SEND_STATUS_PENDING, nextLog.getValue().getSendStatus());
    }

    @Test
    void expiredThirdAttemptBecomesTerminalAndCannotStartAFourth() {
        NotificationDeliveryBatch exhausted = batch(3, NotificationDeliveryBatch.STATE_CLAIMED);
        exhausted.setClaimUntil(Date.from(NOW.minusSeconds(1)));
        when(batchMapper.selectExpiredExhaustedForUpdate("IMMEDIATE_EMAIL", Date.from(NOW), 3, 100))
            .thenReturn(List.of(exhausted));
        when(logMapper.finishPendingAttempt(
            eq("batch-1"), eq(3), eq(DomainWatchNotifyLog.SEND_STATUS_FAIL),
            any(Date.class), eq("delivery lease expired after final attempt"),
            eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(1);
        when(batchMapper.finalizeExpired("batch-1", Date.from(NOW))).thenReturn(1);
        when(notificationMapper.finalizeExpiredBatch("batch-1", Date.from(NOW))).thenReturn(1);

        coordinator.finalizeExpiredExhausted("IMMEDIATE_EMAIL", NOW);
        Optional<DeliveryBatchClaim> retry = coordinator.retryNext("IMMEDIATE_EMAIL", NOW, LEASE_UNTIL);

        assertTrue(retry.isEmpty());
        verify(batchMapper, never()).claimRetry(eq("batch-1"), eq(4), any(), any(), any());
    }

    @Test
    void completionPersistenceFailureLeavesTheClaimForLeaseRecovery() {
        DeliveryBatchClaim claim = new DeliveryBatchClaim(
            "batch-1", "user-1", "IMMEDIATE_EMAIL", "notification-1", 1, "claim-1");
        DeliveryAttemptDetails details = new DeliveryAttemptDetails(
            "notification-1", "event-1", "watch-1", "person@example.com", "example.com", 7,
            "subject", "lookup failed");
        when(logMapper.finishPendingAttempt(
            eq("batch-1"), eq(1), eq(DomainWatchNotifyLog.SEND_STATUS_FAIL),
            any(Date.class), eq("lookup failed"), eq("notification-1"), eq("event-1"),
            eq("watch-1"), eq("person@example.com"), eq("example.com"), eq(7), eq("subject")))
            .thenThrow(new IllegalStateException("audit update failed"));

        assertThrows(IllegalStateException.class, () -> coordinator.complete(
            claim, false, details, NOW, NOW.plusSeconds(300)));

        verify(batchMapper, never()).completeClaim(any(), any(), any(), any(), any(), any());
    }

    private void stubNewImmediate() {
        UserNotification notification = new UserNotification();
        notification.setId("notification-1");
        notification.setUserId("user-1");
        notification.setEventId("event-1");
        notification.setEmailMode("IMMEDIATE_EMAIL");
        when(notificationMapper.selectImmediateForUpdate("notification-1")).thenReturn(notification);
        when(batchMapper.insert(any(NotificationDeliveryBatch.class))).thenReturn(1);
        when(notificationMapper.assignImmediateToBatch(eq("notification-1"), any(String.class), eq(Date.from(NOW))))
            .thenReturn(1);
        when(notificationMapper.claimBatchForAttempt(any(String.class), eq(1), any(String.class),
            eq(Date.from(LEASE_UNTIL)), eq(Date.from(NOW)))).thenReturn(1);
    }

    private static NotificationDeliveryBatch batch(int attempts, String state) {
        NotificationDeliveryBatch batch = new NotificationDeliveryBatch();
        batch.setId("batch-1");
        batch.setUserId("user-1");
        batch.setEmailMode("IMMEDIATE_EMAIL");
        batch.setWindowKey("notification-1");
        batch.setState(state);
        batch.setAttemptCount(attempts);
        batch.setClaimToken("claim-" + attempts);
        return batch;
    }
}
