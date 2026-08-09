package info.wesite.core.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
