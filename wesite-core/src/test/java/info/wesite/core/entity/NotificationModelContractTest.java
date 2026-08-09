package info.wesite.core.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

class NotificationModelContractTest {

    @Test
    void notificationDefaultsAreStable() {
        NotificationPreference preference = NotificationPreference.defaultsFor("u1");
        assertEquals("IMMEDIATE", NotificationPreference.MODE_IMMEDIATE);
        assertEquals("DAILY", NotificationPreference.MODE_DAILY);
        assertEquals("WEEKLY", NotificationPreference.MODE_WEEKLY);
        assertEquals("IN_APP_ONLY", NotificationPreference.MODE_IN_APP_ONLY);
        assertEquals("DAILY", preference.getEmailMode());
        assertTrue(preference.getDomainExpiryEnabled());
        assertTrue(preference.getSslExpiryEnabled());
        assertEquals(0, preference.getDeleted());
    }

    @Test
    void deliveryModelsExposeDurableModeAttemptAndLeaseState() {
        UserNotification notification = new UserNotification();
        notification.setEmailMode("DAILY_DIGEST");
        notification.setEmailState(UserNotification.EMAIL_STATE_CLAIMED);
        notification.setEmailAttemptCount(2);
        notification.setEmailClaimToken("claim-2");
        notification.setEmailClaimUntil(new Date(1234));
        notification.setDeliveryBatchId("batch-1");

        NotificationDeliveryBatch batch = new NotificationDeliveryBatch();
        batch.setUserId("user-1");
        batch.setEmailMode("DAILY_DIGEST");
        batch.setWindowKey("2026-08-09");
        batch.setState(NotificationDeliveryBatch.STATE_CLAIMED);
        batch.setAttemptCount(2);
        batch.setClaimToken("claim-2");
        batch.setClaimUntil(new Date(1234));

        assertEquals("DAILY_DIGEST", notification.getEmailMode());
        assertEquals(2, notification.getEmailAttemptCount());
        assertEquals("batch-1", notification.getDeliveryBatchId());
        assertEquals("user-1", batch.getUserId());
        assertEquals(2, batch.getAttemptCount());
        assertEquals(0, DomainWatchNotifyLog.SEND_STATUS_PENDING);
    }
}
