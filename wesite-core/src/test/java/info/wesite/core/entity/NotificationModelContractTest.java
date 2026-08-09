package info.wesite.core.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NotificationModelContractTest {

    @Test
    void notificationDefaultsAreStable() {
        NotificationPreference preference = NotificationPreference.defaultsFor("u1");
        assertEquals(NotificationPreference.MODE_DAILY, preference.getEmailMode());
        assertTrue(preference.getDomainExpiryEnabled());
        assertTrue(preference.getSslExpiryEnabled());
        assertEquals(0, preference.getDeleted());
    }
}
