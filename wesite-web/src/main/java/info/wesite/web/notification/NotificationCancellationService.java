package info.wesite.web.notification;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import info.wesite.core.mapper.NotificationDeliveryBatchMapper;
import info.wesite.core.mapper.UserNotificationMapper;

/** Coordinates policy changes with every durable state that can still send mail. */
@Service
public class NotificationCancellationService {

    private final NotificationDeliveryBatchMapper batchMapper;
    private final UserNotificationMapper notificationMapper;

    public NotificationCancellationService(
        NotificationDeliveryBatchMapper batchMapper,
        UserNotificationMapper notificationMapper) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper");
        this.notificationMapper = Objects.requireNonNull(notificationMapper, "notificationMapper");
    }

    @Transactional
    public void cancelAllForUser(String userId, Instant changedAt) {
        Date updatedAt = Date.from(changedAt);
        notificationMapper.cancelUnclaimedForUser(userId, updatedAt);
        batchMapper.cancelFailedForUser(userId, updatedAt);
        batchMapper.requestCancellationForClaimedUser(userId, updatedAt);
    }

    @Transactional
    public void cancelForWatch(String userId, String watchId, Instant changedAt) {
        Date updatedAt = Date.from(changedAt);
        notificationMapper.cancelUnclaimedForWatch(userId, watchId, updatedAt);
        batchMapper.cancelFailedForWatch(userId, watchId, updatedAt);
        notificationMapper.cancelMembersOfCancelledBatchesForUser(userId, updatedAt);
        batchMapper.requestCancellationForClaimedWatch(userId, watchId, updatedAt);
    }

    @Transactional
    public void cancelForEventTypes(String userId, Set<String> eventTypes, Instant changedAt) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return;
        }
        Date updatedAt = Date.from(changedAt);
        notificationMapper.cancelUnclaimedForEventTypes(userId, eventTypes, updatedAt);
        batchMapper.cancelFailedForEventTypes(userId, eventTypes, updatedAt);
        notificationMapper.cancelMembersOfCancelledBatchesForUser(userId, updatedAt);
        batchMapper.requestCancellationForClaimedEventTypes(userId, eventTypes, updatedAt);
    }
}
