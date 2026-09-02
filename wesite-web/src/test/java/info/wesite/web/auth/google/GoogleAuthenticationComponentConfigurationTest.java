package info.wesite.web.auth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

import info.wesite.web.auth.AuthCookieService;
import info.wesite.web.auth.ReturnTargetService;

class GoogleAuthenticationComponentConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(
            GoogleAuthenticationSuccessHandler.class,
            OAuthSessionCleaner.class,
            HandlerDependencies.class)
        .withPropertyValues("spring.profiles.active=prod");

    @Test
    void disabledGoogleLoginDoesNotCreateAuthenticationComponents() {
        contextRunner
            .withPropertyValues("wesite.google-login.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(GoogleAuthenticationSuccessHandler.class);
                assertThat(context).doesNotHaveBean(OAuthSessionCleaner.class);
            });
    }

    @Test
    void enabledGoogleLoginCreatesAuthenticationComponents() {
        contextRunner
            .withPropertyValues("wesite.google-login.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(GoogleAuthenticationSuccessHandler.class);
                assertThat(context).hasSingleBean(OAuthSessionCleaner.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerDependencies {

        @Bean
        CurrentJwtUserResolver currentJwtUserResolver() {
            return mock(CurrentJwtUserResolver.class);
        }

        @Bean
        GoogleLoginService googleLoginService() {
            return mock(GoogleLoginService.class);
        }

        @Bean
        AuthCookieService authCookieService() {
            return mock(AuthCookieService.class);
        }

        @Bean
        ReturnTargetService returnTargetService() {
            return mock(ReturnTargetService.class);
        }

        @Bean
        OAuth2AuthorizedClientRepository authorizedClientRepository() {
            return mock(OAuth2AuthorizedClientRepository.class);
        }
    }
}
