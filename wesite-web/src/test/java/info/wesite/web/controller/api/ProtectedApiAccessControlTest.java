package info.wesite.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import info.wesite.core.config.AccessControl;

class ProtectedApiAccessControlTest {

    @Test
    void highValueDataControllersRequireAnAuthenticatedSession() {
        assertSessionProtected(DomainReportController.class);
        assertSessionProtected(DomainHistoryController.class);
        assertSessionProtected(DomainScoreController.class);
        assertSessionProtected(RelatedDomainsController.class);
        assertSessionProtected(NotificationController.class);
        assertSessionProtected(NotificationPreferenceController.class);
    }

    private void assertSessionProtected(Class<?> controller) {
        AccessControl accessControl = controller.getAnnotation(AccessControl.class);
        assertNotNull(accessControl, controller.getSimpleName() + " must declare access control");
        assertEquals(AccessControl.Level.SESSION, accessControl.level());
    }
}
