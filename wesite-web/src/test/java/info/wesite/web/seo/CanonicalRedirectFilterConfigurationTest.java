package info.wesite.web.seo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

class CanonicalRedirectFilterConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FilterConfiguration.class);

    @Test
    void doesNotRegisterFilterOutsideProduction() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(CanonicalRedirectFilter.class));
    }

    @Test
    void registersFilterByDefaultInProduction() {
        contextRunner.withPropertyValues("spring.profiles.active=prod").run(context ->
                assertThat(context).hasSingleBean(CanonicalRedirectFilter.class));
    }

    @Test
    void allowsProductionRedirectsToBeExplicitlyDisabled() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=prod",
                "wesite.seo.canonical-redirect-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CanonicalRedirectFilter.class));
    }

    @Test
    void registersFilterWithConfiguredOrderWhenCanonicalRedirectIsEnabled() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=prod",
                "wesite.seo.canonical-redirect-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CanonicalRedirectFilter.class);

            ServletContextInitializerBeans initializers = new ServletContextInitializerBeans(context.getBeanFactory());
            FilterRegistrationBean<?> registration = StreamSupport.stream(initializers.spliterator(), false)
                    .filter(FilterRegistrationBean.class::isInstance)
                    .map(FilterRegistrationBean.class::cast)
                    .filter(candidate -> candidate.getFilter() instanceof CanonicalRedirectFilter)
                    .findFirst()
                    .orElseThrow();

            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = CanonicalRedirectFilter.class)
    static class FilterConfiguration {
    }
}
