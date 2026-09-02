package info.wesite.admin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import info.wesite.admin.controller.MainController;
import info.wesite.admin.interceptor.AdminInterceptor;
import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.service.UserService;

class AdminSecurityConfigurationTest {

    private AnnotationConfigWebApplicationContext context;

    @AfterEach
    void closeContext() {
        UserHolder.remove();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void configuredInterceptorDeniesUnannotatedControllerByDefault() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestWebConfiguration.class);
        context.refresh();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(get("/configured-protected"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void mainEntryAndLoginAreExplicitlyPublicButSessionEndpointsAreProtected() throws Exception {
        assertEquals(AccessControl.Level.NONE, accessLevel(MainController.class.getMethod("index",
            org.springframework.ui.Model.class)));
        assertEquals(AccessControl.Level.NONE, accessLevel(MainController.class.getMethod("login",
            info.wesite.core.view.LoginParam.class)));
        assertNull(MainController.class.getMethod("userInfo").getAnnotation(AccessControl.class));
        assertNull(MainController.class.getMethod("logout").getAnnotation(AccessControl.class));
    }

    @Test
    void productionDisablesApiDocsAndLayuiRecognizesNoAuthEnvelope() throws IOException {
        Properties defaults = PropertiesLoaderUtils.loadProperties(
            new ClassPathResource("application.properties"));
        Properties development = PropertiesLoaderUtils.loadProperties(
            new ClassPathResource("application-dev.properties"));
        String layuiConfig = new String(new ClassPathResource(
            "static/layuiadmin/config.js").getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);

        assertEquals("false", defaults.getProperty("springdoc.api-docs.enabled"));
        assertEquals("false", defaults.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("true", development.getProperty("springdoc.api-docs.enabled"));
        assertEquals("true", development.getProperty("springdoc.swagger-ui.enabled"));
        assertTrue(layuiConfig.contains("logout: 401"));
    }

    private static AccessControl.Level accessLevel(Method method) {
        AccessControl control = method.getAnnotation(AccessControl.class);
        assertNotNull(control);
        return control.level();
    }

    @Configuration
    @EnableWebMvc
    @Import({ InterceptorConfig.class, AdminInterceptor.class, ConfiguredProbeController.class })
    static class TestWebConfiguration {

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }
    }

    @RestController
    static class ConfiguredProbeController {

        @GetMapping("/configured-protected")
        String protectedEndpoint() {
            return "unsafe";
        }
    }
}
