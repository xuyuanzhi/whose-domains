package info.wesite.web.notification;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import info.wesite.core.mapper.NotificationDeliveryBatchMapper;
import info.wesite.core.mapper.UserNotificationMapper;
import info.wesite.core.mapper.DomainWatchMapper;
import info.wesite.core.entity.NotificationDeliveryBatch;

/** Coordinates policy changes with every durable state that can still send mail. */
@Service
public class NotificationCancellationService {

    private final NotificationDeliveryBatchMapper batchMapper;
    private final UserNotificationMapper notificationMapper;
    private final NotificationPolicyLock policyLock;
    private final DomainWatchMapper watchMapper;

    @Autowired
    public NotificationCancellationService(
        NotificationDeliveryBatchMapper batchMapper,
        UserNotificationMapper notificationMapper,
        NotificationPolicyLock policyLock,
        DomainWatchMapper watchMapper) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper");
        this.notificationMapper = Objects.requireNonNull(notificationMapper, "notificationMapper");
        this.policyLock = Objects.requireNonNull(policyLock, "policyLock");
        this.watchMapper = Objects.requireNonNull(watchMapper, "watchMapper");
    }

    NotificationCancellationService(
        NotificationDeliveryBatchMapper batchMapper,
        UserNotificationMapper notificationMapper) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper");
        this.notificationMapper = Objects.requireNonNull(notificationMapper, "notificationMapper");
        this.policyLock = null;
        this.watchMapper = null;
    }

    @Transactional
    public void cancelAllForUser(String userId, Instant changedAt) {
        Date updatedAt = Date.from(changedAt);
        lockUser(userId);
        cancelLockedBatches(batchMapper.selectForUserForUpdate(userId), updatedAt);
        notificationMapper.cancelMembersOfCancelledBatchesForUser(userId, updatedAt);
        notificationMapper.cancelUnclaimedForUser(userId, updatedAt);
    }

    @Transactional
    public void cancelForWatch(String userId, String watchId, Instant changedAt) {
        Date updatedAt = Date.from(changedAt);
        lockUser(userId);
        if (watchMapper != null) {
            watchMapper.selectByIdForUpdate(watchId);
        }
        cancelLockedBatches(batchMapper.selectForWatchForUpdate(userId, watchId), updatedAt);
        notificationMapper.cancelMembersOfCancelledBatchesForUser(userId, updatedAt);
        notificationMapper.cancelUnclaimedForWatch(userId, watchId, updatedAt);
    }

    @Transactional
    public void cancelForEventTypes(String userId, Set<String> eventTypes, Instant changedAt) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return;
        }
        Date updatedAt = Date.from(changedAt);
        lockUser(userId);
        cancelLockedBatches(batchMapper.selectForEventTypesForUpdate(userId, eventTypes), updatedAt);
        notificationMapper.cancelMembersOfCancelledBatchesForUser(userId, updatedAt);
        notificationMapper.cancelUnclaimedForEventTypes(userId, eventTypes, updatedAt);
    }

    private void lockUser(String userId) {
        if (policyLock != null) {
            policyLock.lockUser(userId);
        }
    }

    private void cancelLockedBatches(List<NotificationDeliveryBatch> batches, Date updatedAt) {
        if (batches == null) {
            return;
        }
        for (NotificationDeliveryBatch batch : batches) {
            if (NotificationDeliveryBatch.STATE_FAILED.equals(batch.getState())) {
                batchMapper.cancelFailedById(batch.getId(), updatedAt);
            } else if (NotificationDeliveryBatch.STATE_CLAIMED.equals(batch.getState())) {
                batchMapper.requestCancellationById(batch.getId(), updatedAt);
            }
        }
    }
}
