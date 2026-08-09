package info.wesite.core.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.Introspector;
import java.time.LocalDate;
import java.util.Arrays;
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

    @Test
    void monitorSnapshotsExposeObservationSchemaMetadata() {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setSchemaVersion(2);
        snapshot.setObservedSources("DNS,DOMAIN");

        assertEquals(2, snapshot.getSchemaVersion());
        assertEquals("DNS,DOMAIN", snapshot.getObservedSources());
    }

    @Test
    void monitorSnapshotsExposeCurrentSourcesAndPerSourceLastSuccessTimes() throws Exception {
        var properties = Arrays.asList(
            Introspector.getBeanInfo(MonitorSnapshot.class).getPropertyDescriptors());

        var currentSources = properties.stream()
            .filter(property -> "currentObservedSources".equals(property.getName()))
            .findFirst();
        assertTrue(currentSources.isPresent(), "missing currentObservedSources");
        assertEquals(String.class, currentSources.orElseThrow().getPropertyType());
        for (String name : Arrays.asList(
                "domainLastSuccessAt",
                "dnsLastSuccessAt",
                "sslLastSuccessAt",
                "websiteLastSuccessAt")) {
            var timestamp = properties.stream()
                .filter(property -> name.equals(property.getName()))
                .findFirst();
            assertTrue(timestamp.isPresent(), "missing " + name);
            assertEquals(Date.class, timestamp.orElseThrow().getPropertyType(), name);
        }
    }

    @Test
    void domainWatchExposesAnExplicitReportingCalendarCreationDate() throws Exception {
        var property = Arrays.stream(Introspector.getBeanInfo(DomainWatch.class).getPropertyDescriptors())
            .filter(candidate -> "watchCreatedOn".equals(candidate.getName()))
            .findFirst();

        assertTrue(property.isPresent(), "DomainWatch must expose WATCH_CREATED_ON as watchCreatedOn");
        assertEquals(LocalDate.class, property.orElseThrow().getPropertyType());
        DomainWatch watch = new DomainWatch();
        LocalDate createdOn = LocalDate.of(2026, 8, 9);
        property.orElseThrow().getWriteMethod().invoke(watch, createdOn);
        assertEquals(createdOn, property.orElseThrow().getReadMethod().invoke(watch));
    }
}
