package info.wesite.admin.maintenance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import info.wesite.core.blog.BlogSanitizationMaintenanceService;
import info.wesite.core.blog.BlogSanitizationReport;

class BlogSanitizationRunnerTest {

    @Test
    void runnerExistsOnlyInExplicitMaintenanceProfileAndMode() {
        contextRunner()
            .withPropertyValues(
                "spring.profiles.active=prod",
                "wesite.blog.sanitization.mode=dry-run")
            .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                .doesNotHaveBean(BlogSanitizationRunner.class));

        contextRunner()
            .withPropertyValues(
                "spring.profiles.active=blog-sanitize",
                "wesite.blog.sanitization.mode=dry-run")
            .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                .hasSingleBean(BlogSanitizationRunner.class));
    }

    @Test
    void dryRunAndApplyDelegateCorrectlyAndCloseContextOnSuccess() throws Exception {
        BlogSanitizationMaintenanceService service = mock(BlogSanitizationMaintenanceService.class);
        ConfigurableApplicationContext dryContext = mock(ConfigurableApplicationContext.class);
        ConfigurableApplicationContext applyContext = mock(ConfigurableApplicationContext.class);
        ApplicationArguments arguments = mock(ApplicationArguments.class);
        when(service.run(false, 25)).thenReturn(new BlogSanitizationReport(3, List.of()));
        when(service.run(true, 50)).thenReturn(new BlogSanitizationReport(4, List.of()));

        new BlogSanitizationRunner(service, dryContext, "dry-run", 25).run(arguments);
        new BlogSanitizationRunner(service, applyContext, "apply", 50).run(arguments);

        verify(service).run(false, 25);
        verify(service).run(true, 50);
        verify(dryContext).close();
        verify(applyContext).close();
    }

    @Test
    void unsupportedModeFailsAndDoesNotReportSuccess() {
        BlogSanitizationMaintenanceService service = mock(BlogSanitizationMaintenanceService.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

        assertThrows(IllegalArgumentException.class,
            () -> new BlogSanitizationRunner(service, context, "preview", 100)
                .run(mock(ApplicationArguments.class)));

        verify(service, never()).run(org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyInt());
        verify(context, never()).close();
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withBean(BlogSanitizationMaintenanceService.class,
                () -> mock(BlogSanitizationMaintenanceService.class))
            .withUserConfiguration(BlogSanitizationRunner.class);
    }
}
