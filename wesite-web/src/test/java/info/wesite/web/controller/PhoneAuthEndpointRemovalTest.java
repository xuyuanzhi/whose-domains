package info.wesite.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PhoneAuthEndpointRemovalTest {

    @Test
    void userControllerDoesNotExposePhoneAuthenticationEndpoints() {
        String[] removedPaths = {"/login", "/sendVcode", "/create"};

        for (Method method : UserController.class.getDeclaredMethods()) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            if (mapping == null) {
                continue;
            }
            for (String removedPath : removedPaths) {
                assertFalse(Arrays.asList(mapping.value()).contains(removedPath),
                        () -> "Phone authentication endpoint must not be exposed: " + removedPath);
            }
        }
    }
}
