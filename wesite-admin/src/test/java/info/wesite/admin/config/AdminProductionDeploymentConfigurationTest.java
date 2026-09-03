package info.wesite.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

class AdminProductionDeploymentConfigurationTest {

    private static final String SCHEDULED_PROCESSOR_BEAN =
        "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    @Test
    void productionTemplateMapsTheSharedJwtSecret() throws IOException {
        Properties production = PropertiesLoaderUtils.loadProperties(
            new FileSystemResource("../deploy/config/wesite-admin.application-prod.properties.example"));

        assertEquals("${JWT_SECRET:please-change-this-default-secret-key-in-production}",
            production.getProperty("app.jwt.secret"));
        assertEquals("system", production.getProperty("app.jwt.issuer"));
    }

    @Test
    void normalApplicationRegistersSchedulingInfrastructure() {
        schedulingContext("prod").run(context -> assertThat(context)
            .hasBean(SCHEDULED_PROCESSOR_BEAN));
    }

    @Test
    void blogSanitizationDoesNotRegisterSchedulingInfrastructure() {
        schedulingContext("prod,blog-sanitize").run(context -> assertThat(context)
            .doesNotHaveBean(SCHEDULED_PROCESSOR_BEAN));
    }

    private static ApplicationContextRunner schedulingContext(String profiles) {
        return new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=" + profiles)
            .withUserConfiguration(AdminSchedulingConfiguration.class);
    }
}
