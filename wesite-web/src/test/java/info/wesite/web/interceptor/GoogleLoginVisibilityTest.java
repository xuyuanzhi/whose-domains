package info.wesite.web.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import info.wesite.web.config.GoogleLoginProperties;

class GoogleLoginVisibilityTest {

    @Test
    void disabledFeatureIsHiddenFromViews() throws Exception {
        assertVisibility(false);
    }

    @Test
    void enabledFeatureIsVisibleToViews() throws Exception {
        assertVisibility(true);
    }

    private void assertVisibility(boolean enabled) throws Exception {
        GoogleLoginProperties properties = new GoogleLoginProperties(enabled, "client-id", "client-secret");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GoogleLoginProperties.class, () -> properties);
            context.registerBean(WebInterceptor.class);
            context.refresh();

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/api/test");
            boolean allowed = context.getBean(WebInterceptor.class)
                    .preHandle(request, new MockHttpServletResponse(), new Object());

            assertTrue(allowed);
            assertEquals(enabled, request.getAttribute("_googleLoginEnabled"));
        }
    }
}
