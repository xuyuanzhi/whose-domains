package info.wesite.web.notification;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.NotificationDeliveryBatch;
import info.wesite.core.mapper.DomainWatchMapper;
import info.wesite.core.mapper.NotificationDeliveryBatchMapper;
import info.wesite.core.mapper.UserNotificationMapper;

class NotificationCancellationServiceTest {

    @Test
    void watchCancellationLocksUserWatchAndOrderedBatchesBeforeNotifications() {
        NotificationDeliveryBatchMapper batches = mock(NotificationDeliveryBatchMapper.class);
        UserNotificationMapper notifications = mock(UserNotificationMapper.class);
        NotificationPolicyLock policy = mock(NotificationPolicyLock.class);
        DomainWatchMapper watches = mock(DomainWatchMapper.class);
        DomainWatch watch = new DomainWatch();
        watch.setId("watch-1");
        NotificationDeliveryBatch failed = batch("batch-a", NotificationDeliveryBatch.STATE_FAILED);
        NotificationDeliveryBatch claimed = batch("batch-b", NotificationDeliveryBatch.STATE_CLAIMED);
        when(watches.selectByIdForUpdate("watch-1")).thenReturn(watch);
        when(batches.selectForWatchForUpdate("user-1", "watch-1"))
            .thenReturn(List.of(failed, claimed));
        NotificationCancellationService service = new NotificationCancellationService(
            batches, notifications, policy, watches);

        service.cancelForWatch("user-1", "watch-1", Instant.parse("2026-08-09T08:00:00Z"));

        InOrder order = inOrder(policy, watches, batches, notifications);
        order.verify(policy).lockUser("user-1");
        order.verify(watches).selectByIdForUpdate("watch-1");
        order.verify(batches).selectForWatchForUpdate("user-1", "watch-1");
        order.verify(batches).cancelFailedById(org.mockito.ArgumentMatchers.eq("batch-a"),
            org.mockito.ArgumentMatchers.any(Date.class));
        order.verify(batches).requestCancellationById(org.mockito.ArgumentMatchers.eq("batch-b"),
            org.mockito.ArgumentMatchers.any(Date.class));
        order.verify(notifications).cancelMembersOfCancelledBatchesForUser(
            org.mockito.ArgumentMatchers.eq("user-1"), org.mockito.ArgumentMatchers.any(Date.class));
        order.verify(notifications).cancelUnclaimedForWatch(
            org.mockito.ArgumentMatchers.eq("user-1"), org.mockito.ArgumentMatchers.eq("watch-1"),
            org.mockito.ArgumentMatchers.any(Date.class));
    }

    private static NotificationDeliveryBatch batch(String id, String state) {
        NotificationDeliveryBatch batch = new NotificationDeliveryBatch();
        batch.setId(id);
        batch.setState(state);
        return batch;
    }
}
