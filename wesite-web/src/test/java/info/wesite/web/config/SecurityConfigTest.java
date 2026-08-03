package info.wesite.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import info.wesite.web.auth.google.GoogleAuthenticationFailureHandler;
import info.wesite.web.auth.google.GoogleAuthenticationSuccessHandler;

class SecurityConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfig.class, HandlerConfiguration.class);

    @Test
    void disabledModeKeepsAllRequestsOnThePassThroughChain() {
        contextRunner.withPropertyValues("wesite.google-login.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);

                    MockMvc mvc = mockMvc(context.getBean(FilterChainProxy.class));
                    mvc.perform(get("/"))
                            .andExpect(status().isOk())
                            .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
                    mvc.perform(post("/security-probe"))
                            .andExpect(status().isOk())
                            .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
                });
    }

    @Test
    void enabledModeBuildsTheFixedGoogleRegistrationAndKeepsNonOauthRequestsPassThrough() {
        enabledContext("https://whose.domains").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(2);

            ClientRegistration registration = context.getBean(ClientRegistrationRepository.class)
                    .findByRegistrationId("google");
            assertThat(registration).isNotNull();
            assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "email", "profile");
            assertThat(registration.getRedirectUri())
                    .isEqualTo("https://whose.domains/login/oauth2/code/{registrationId}");

            MockMvc mvc = mockMvc(context.getBean(FilterChainProxy.class));
            mvc.perform(post("/security-probe"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
            mvc.perform(get("/oauth2/authorization/google"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("https://accounts.google.com/**"));
        });
    }

    @Test
    void enabledModeRejectsBlankClientId() {
        enabledContext("https://whose.domains")
                .withPropertyValues("wesite.google-login.client-id= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Google client ID must not be blank");
                });
    }

    @Test
    void enabledModeRejectsBlankClientSecret() {
        enabledContext("https://whose.domains")
                .withPropertyValues("wesite.google-login.client-secret= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Google client secret must not be blank");
                });
    }

    @Test
    void productionRejectsPlainHttpPublicBaseUrl() {
        enabledContext("http://whose.domains")
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Google login public base URL must use HTTPS");
                });
    }

    @Test
    void localDevelopmentAcceptsPlainHttpLocalhost() {
        enabledContext("http://localhost:8080").run(context -> {
            assertThat(context).hasNotFailed();
            ClientRegistration registration = context.getBean(ClientRegistrationRepository.class)
                    .findByRegistrationId("google");
            assertThat(registration.getRedirectUri())
                    .isEqualTo("http://localhost:8080/login/oauth2/code/{registrationId}");
        });
    }

    private ApplicationContextRunner enabledContext(String publicBaseUrl) {
        return contextRunner.withPropertyValues(
                "wesite.google-login.enabled=true",
                "wesite.google-login.client-id=test-client-id",
                "wesite.google-login.client-secret=test-client-secret",
                "wesite.public-base-url=" + publicBaseUrl);
    }

    private MockMvc mockMvc(FilterChainProxy securityFilter) {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(securityFilter)
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfiguration {

        @Bean
        GoogleAuthenticationSuccessHandler googleAuthenticationSuccessHandler() {
            return mock(GoogleAuthenticationSuccessHandler.class);
        }

        @Bean
        GoogleAuthenticationFailureHandler googleAuthenticationFailureHandler() {
            return mock(GoogleAuthenticationFailureHandler.class);
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/")
        String index() {
            return "ok";
        }

        @PostMapping("/security-probe")
        String securityProbe() {
            return "ok";
        }
    }
}
