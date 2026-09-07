package info.wesite.admin.maintenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import info.wesite.admin.config.AdminSchedulingConfiguration;
import info.wesite.core.blog.BlogReviewService;

class BlogAuditRunnerTest {
    @Test
    void auditProfileDisablesScheduledWriters() {
        new ApplicationContextRunner().withUserConfiguration(AdminSchedulingConfiguration.class)
            .withPropertyValues("spring.profiles.active=prod,blog-audit")
            .run(context -> assertFalse(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)));
    }

    @Test
    void runnerRequiresExplicitProfileAndEnableFlag() {
        var runner = new ApplicationContextRunner().withUserConfiguration(BlogAuditRunner.class)
            .withBean(BlogReviewService.class, () -> mock(BlogReviewService.class));
        runner.withPropertyValues("spring.profiles.active=prod", "wesite.blog.audit.enabled=true")
            .run(context -> assertFalse(context.containsBean("blogAuditRunner")));
        runner.withPropertyValues("spring.profiles.active=blog-audit")
            .run(context -> assertFalse(context.containsBean("blogAuditRunner")));
        runner.withPropertyValues("spring.profiles.active=blog-audit", "wesite.blog.audit.enabled=true")
            .run(context -> assertEquals(1, context.getBeansOfType(BlogAuditRunner.class).size()));
    }

    @Test
    void rejectsWebModeAndClosesOnlyAfterSuccessfulReadOnlyAudit() {
        var service = mock(BlogReviewService.class);
        var context = mock(ConfigurableApplicationContext.class);
        var environment = new MockEnvironment();
        when(context.getEnvironment()).thenReturn(environment);
        var runner = new BlogAuditRunner(service, context);
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()));
        verifyNoInteractions(service);
        environment.setProperty("spring.main.web-application-type", "none");
        when(service.audit()).thenReturn(new BlogReviewService.AuditReport(0, List.of()));
        runner.run(new DefaultApplicationArguments());
        verify(context).close();
    }
}
