package info.wesite.web.notification;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.DomainWatchNotifyLog;
import info.wesite.core.entity.NotificationDeliveryBatch;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mapper.DomainWatchNotifyLogMapper;
import info.wesite.core.mapper.NotificationDeliveryBatchMapper;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.utils.RandomUtils;

/** Transaction boundary for starting and completing durable email attempts. */
@Service
public class NotificationDeliveryCoordinator {

    public static final int MAX_ATTEMPTS = 3;
    private static final int EXHAUSTED_PAGE_SIZE = 100;

    private final NotificationDeliveryBatchMapper batchMapper;
    private final UserNotificationMapper notificationMapper;
    private final DomainWatchNotifyLogMapper logMapper;
    private final NotificationPolicyLock policyLock;

    public NotificationDeliveryCoordinator(
        NotificationDeliveryBatchMapper batchMapper,
        UserNotificationMapper notificationMapper,
        DomainWatchNotifyLogMapper logMapper) {
        this(batchMapper, notificationMapper, logMapper, null);
    }

    @Autowired
    public NotificationDeliveryCoordinator(
        NotificationDeliveryBatchMapper batchMapper,
        UserNotificationMapper notificationMapper,
        DomainWatchNotifyLogMapper logMapper,
        NotificationPolicyLock policyLock) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper");
        this.notificationMapper = Objects.requireNonNull(notificationMapper, "notificationMapper");
        this.logMapper = Objects.requireNonNull(logMapper, "logMapper");
        this.policyLock = policyLock;
    }

    @Transactional
    public Optional<DeliveryBatchClaim> startImmediate(
        String notificationId,
        Instant now,
        Instant leaseUntil) {
        Date startedAt = Date.from(now);
        if (policyLock != null) {
            String userId = notificationMapper.selectUserId(notificationId);
            if (userId == null) {
                return Optional.empty();
            }
            policyLock.lockUser(userId);
        }
        UserNotification notification = notificationMapper.selectImmediateForUpdate(notificationId);
        if (notification == null) {
            return Optional.empty();
        }

        NotificationDeliveryBatch batch = newBatch(
            notification.getUserId(), notification.getEmailMode(), notificationId,
            notification.getRecipientEmail(), now, leaseUntil);
        requireOne(batchMapper.insert(batch), "delivery batch insert");
        requireOne(notificationMapper.assignImmediateToBatch(notificationId, batch.getId(), startedAt),
            "immediate batch assignment");
        requirePositive(notificationMapper.claimBatchForAttempt(
            batch.getId(), 1, batch.getClaimToken(), batch.getClaimUntil(), startedAt),
            "immediate notification claim");
        insertPendingLog(batch, notification.getId(), notification.getEventId(), startedAt);
        return Optional.of(claim(batch));
    }

    @Transactional
    public Optional<DeliveryBatchClaim> startDigest(
        String userId,
        String mode,
        String windowKey,
        String recipientEmail,
        Instant cutoff,
        Instant now,
        Instant leaseUntil) {
        Date startedAt = Date.from(now);
        if (policyLock != null) {
            policyLock.lockUser(userId);
        }
        NotificationDeliveryBatch batch = newBatch(
            userId, mode, windowKey, recipientEmail, now, leaseUntil);
        try {
            requireOne(batchMapper.insert(batch), "digest batch insert");
        } catch (DuplicateKeyException concurrentWinner) {
            return Optional.empty();
        }

        int assigned = notificationMapper.assignDigestToBatch(
            userId, mode, recipientEmail, batch.getId(), Date.from(cutoff), startedAt);
        if (assigned == 0) {
            batchMapper.deleteById(batch.getId());
            return Optional.empty();
        }
        requirePositive(notificationMapper.claimBatchForAttempt(
            batch.getId(), 1, batch.getClaimToken(), batch.getClaimUntil(), startedAt),
            "digest notification claim");
        insertPendingLog(batch, null, null, startedAt);
        return Optional.of(claim(batch));
    }

    @Transactional
    public Optional<DeliveryBatchClaim> retryNext(String mode, Instant now, Instant leaseUntil) {
        Date startedAt = Date.from(now);
        NotificationDeliveryBatch batch = batchMapper.selectRetryableForUpdate(
            mode, startedAt, MAX_ATTEMPTS);
        if (batch == null) {
            return Optional.empty();
        }
        int previousAttempt = batch.getAttemptCount() == null ? 0 : batch.getAttemptCount();
        if (NotificationDeliveryBatch.STATE_CLAIMED.equals(batch.getState())) {
            requireOne(finishPendingLog(
                batch, previousAttempt, false, startedAt, "delivery lease expired", null),
                "expired pending attempt log");
        }

        int attempt = previousAttempt + 1;
        String claimToken = RandomUtils.generateId();
        requireOne(batchMapper.claimRetry(
            batch.getId(), attempt, claimToken, Date.from(leaseUntil), startedAt),
            "retry batch claim");
        int members = notificationMapper.claimBatchForAttempt(
            batch.getId(), attempt, claimToken, Date.from(leaseUntil), startedAt);
        if (members == 0) {
            requireOne(batchMapper.cancelWithoutMembers(batch.getId(), startedAt),
                "empty retry batch cancellation");
            return Optional.empty();
        }
        batch.setAttemptCount(attempt);
        batch.setClaimToken(claimToken);
        batch.setClaimUntil(Date.from(leaseUntil));
        batch.setState(NotificationDeliveryBatch.STATE_CLAIMED);
        insertPendingLog(batch, null, null, startedAt);
        return Optional.of(claim(batch));
    }

    @Transactional
    public void complete(
        DeliveryBatchClaim claim,
        boolean success,
        DeliveryAttemptDetails details,
        Instant completedAt,
        Instant nextAttemptAt) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(details, "details");
        Date completed = Date.from(completedAt);
        NotificationDeliveryBatch batch = batchMapper.selectByIdForUpdate(claim.batchId());
        if (batch == null || !NotificationDeliveryBatch.STATE_CLAIMED.equals(batch.getState())
            || !Objects.equals(claim.claimToken(), batch.getClaimToken())) {
            throw new IllegalStateException("Delivery claim is no longer owned by this worker");
        }
        int logState = success
            ? DomainWatchNotifyLog.SEND_STATUS_SUCCESS
            : DomainWatchNotifyLog.SEND_STATUS_FAIL;
        requireOne(logMapper.finishPendingAttempt(
            claim.batchId(), claim.attempt(), logState, completed,
            success ? null : details.errorMessage(), details.notificationId(), details.eventId(),
            details.watchId(), details.toEmail(), details.domainName(), details.daysLeft(), details.subject()),
            "attempt log completion");

        boolean cancelledAfterFailure = !success && Boolean.TRUE.equals(batch.getCancellationRequested());
        String state = success
            ? NotificationDeliveryBatch.STATE_SENT
            : cancelledAfterFailure ? NotificationDeliveryBatch.STATE_CANCELLED : NotificationDeliveryBatch.STATE_FAILED;
        Date next = success || cancelledAfterFailure || claim.attempt() >= MAX_ATTEMPTS || nextAttemptAt == null
            ? null
            : Date.from(nextAttemptAt);
        Date batchCompletedAt = success || cancelledAfterFailure || claim.attempt() >= MAX_ATTEMPTS
            ? completed
            : null;
        requireOne(batchMapper.completeClaim(
            claim.batchId(), claim.claimToken(), state, batchCompletedAt, next, completed),
            "batch completion");
        requirePositive(notificationMapper.completeBatchNotifications(
            claim.batchId(), claim.claimToken(),
            success ? UserNotification.EMAIL_STATE_SENT
                : cancelledAfterFailure ? UserNotification.EMAIL_STATE_IN_APP_ONLY
                    : UserNotification.EMAIL_STATE_FAILED,
            success ? completed : null,
            completed),
            "notification completion");
    }

    @Transactional
    public void finalizeExpiredExhausted(String mode, Instant now) {
        Date completedAt = Date.from(now);
        List<NotificationDeliveryBatch> batches = batchMapper.selectExpiredExhaustedForUpdate(
            mode, completedAt, MAX_ATTEMPTS, EXHAUSTED_PAGE_SIZE);
        if (batches == null) {
            return;
        }
        for (NotificationDeliveryBatch batch : batches) {
            requireOne(finishPendingLog(
                batch,
                batch.getAttemptCount(),
                false,
                completedAt,
                "delivery lease expired after final attempt",
                null),
                "final expired attempt log");
            requireOne(batchMapper.finalizeExpired(batch.getId(), completedAt), "expired batch finalization");
            requirePositive(notificationMapper.finalizeExpiredBatch(batch.getId(), completedAt),
                "expired notification finalization");
        }
    }

    @Transactional
    public void finalizeExpiredCancelled(String mode, Instant now) {
        Date completedAt = Date.from(now);
        List<NotificationDeliveryBatch> batches =
            batchMapper.selectExpiredCancellationRequestedForUpdate(
                mode, completedAt, EXHAUSTED_PAGE_SIZE);
        if (batches == null) {
            return;
        }
        for (NotificationDeliveryBatch batch : batches) {
            requireOne(finishPendingLog(
                batch,
                batch.getAttemptCount(),
                false,
                completedAt,
                "delivery cancelled after notification policy change",
                null),
                "cancelled pending attempt log");
            requireOne(batchMapper.finalizeCancelledClaim(batch.getId(), completedAt),
                "cancelled batch finalization");
            notificationMapper.cancelClaimedBatch(batch.getId(), completedAt);
        }
    }

    private int finishPendingLog(
        NotificationDeliveryBatch batch,
        int attempt,
        boolean success,
        Date completedAt,
        String error,
        DeliveryAttemptDetails details) {
        return logMapper.finishPendingAttempt(
            batch.getId(),
            attempt,
            success ? DomainWatchNotifyLog.SEND_STATUS_SUCCESS : DomainWatchNotifyLog.SEND_STATUS_FAIL,
            completedAt,
            error,
            details == null ? null : details.notificationId(),
            details == null ? null : details.eventId(),
            details == null ? null : details.watchId(),
            details == null ? null : details.toEmail(),
            details == null ? null : details.domainName(),
            details == null ? null : details.daysLeft(),
            details == null ? null : details.subject());
    }

    private void insertPendingLog(
        NotificationDeliveryBatch batch,
        String notificationId,
        String eventId,
        Date startedAt) {
        DomainWatchNotifyLog entry = new DomainWatchNotifyLog();
        entry.setId(RandomUtils.generateId());
        entry.setStatus(BaseEntity.STATUS_ACTIVE);
        entry.setDeleted(0);
        entry.setCreateTime(startedAt);
        entry.setBatchId(batch.getId());
        entry.setNotificationId(notificationId);
        entry.setEventId(eventId);
        entry.setDeliveryMode(batch.getEmailMode());
        entry.setSendStatus(DomainWatchNotifyLog.SEND_STATUS_PENDING);
        entry.setRetryCount(batch.getAttemptCount());
        requireOne(logMapper.insert(entry), "pending attempt log");
    }

    private static NotificationDeliveryBatch newBatch(
        String userId,
        String mode,
        String windowKey,
        String recipientEmail,
        Instant now,
        Instant leaseUntil) {
        Date createdAt = Date.from(now);
        String validatedRecipient = NotificationEmailAddress.normalize(recipientEmail)
            .orElseThrow(() -> new IllegalArgumentException("validated recipient email is required"));
        NotificationDeliveryBatch batch = new NotificationDeliveryBatch();
        batch.setId(RandomUtils.generateId());
        batch.setStatus(BaseEntity.STATUS_ACTIVE);
        batch.setDeleted(0);
        batch.setCreateTime(createdAt);
        batch.setUpdateTime(createdAt);
        batch.setUserId(userId);
        batch.setEmailMode(mode);
        batch.setWindowKey(windowKey);
        batch.setRecipientEmail(validatedRecipient);
        batch.setState(NotificationDeliveryBatch.STATE_CLAIMED);
        batch.setAttemptCount(1);
        batch.setClaimToken(RandomUtils.generateId());
        batch.setClaimUntil(Date.from(leaseUntil));
        batch.setCancellationRequested(false);
        return batch;
    }

    private static DeliveryBatchClaim claim(NotificationDeliveryBatch batch) {
        return new DeliveryBatchClaim(
            batch.getId(), batch.getUserId(), batch.getEmailMode(), batch.getWindowKey(),
            batch.getAttemptCount(), batch.getClaimToken(), batch.getRecipientEmail());
    }

    private static void requireOne(int affected, String operation) {
        if (affected != 1) {
            throw new IllegalStateException("Failed " + operation + "; affected rows=" + affected);
        }
    }

    private static void requirePositive(int affected, String operation) {
        if (affected < 1) {
            throw new IllegalStateException("Failed " + operation + "; affected rows=" + affected);
        }
    }
}
