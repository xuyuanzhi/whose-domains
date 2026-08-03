package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import info.wesite.core.config.AccessControl;

class ApiKeyPageAccessControlTest {

    @Test
    void apiKeyManagementPageRequiresAnAuthenticatedSession() throws NoSuchMethodException {
        Method method = MainController.class.getMethod("apiKeys", org.springframework.ui.Model.class);
        AccessControl accessControl = method.getAnnotation(AccessControl.class);

        assertNotNull(accessControl);
        assertEquals(AccessControl.Level.SESSION, accessControl.level());
    }

    @Test
    void apiKeyListUsesItsOwnRouteInsteadOfCollidingWithTheManagementPage() throws NoSuchMethodException {
        Method method = ApiKeyController.class.getMethod("list");
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertNotNull(mapping);
        assertEquals("/list", mapping.value()[0]);
    }
}
