package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.web.auth.ReturnTargetService;

class LoginControllerTest {

    private final LoginController controller = new LoginController(new ReturnTargetService());

    @Test
    void loginPageExposesValidatedTarget() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("login", controller.login("/user/api-keys", null, model));
        assertEquals("/user/api-keys", model.get("returnTo"));
    }

    @Test
    void missingReturnTargetFallsBackToWatchlist() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("login", controller.login(null, null, model));
        assertEquals(ReturnTargetService.DEFAULT_TARGET, model.get("returnTo"));
    }

    @Test
    void externalReturnTargetFallsBackToWatchlist() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("login", controller.login("https://evil.example", null, model));
        assertEquals(ReturnTargetService.DEFAULT_TARGET, model.get("returnTo"));
    }

    @Test
    void authenticationEndpointReturnTargetFallsBackToWatchlist() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("login", controller.login("/user/verify-email?token=attacker-token", null, model));
        assertEquals(ReturnTargetService.DEFAULT_TARGET, model.get("returnTo"));
    }

    @Test
    void overlongReturnTargetFallsBackToWatchlist() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("login", controller.login("/" + "a".repeat(500), null, model));
        assertEquals(ReturnTargetService.DEFAULT_TARGET, model.get("returnTo"));
    }

    @Test
    void signedInVisitorSkipsGateway() {
        UserHolder.set(new User());
        try {
            assertEquals("redirect:/user/api-keys",
                    controller.login("/user/api-keys", "google_error", new ExtendedModelMap()));
        } finally {
            UserHolder.remove();
        }
    }

    @Test
    void signedInVisitorCannotBeRedirectedIntoAnotherAuthenticationFlow() {
        UserHolder.set(new User());
        try {
            assertEquals("redirect:" + ReturnTargetService.DEFAULT_TARGET,
                    controller.login("/user/verify-email?token=attacker-token", null, new ExtendedModelMap()));
        } finally {
            UserHolder.remove();
        }
    }

    @Test
    void loginFailureForMonitorContinuationReturnsToDomainDialog() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.login(
                "/domain/example.com?monitor=pending", "google_error", model);

        assertEquals(
                "redirect:/domain/example.com?monitor=pending&login=google_error",
                view);
    }

    @Test
    void ordinaryLoginFailureStillRendersLoginGateway() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.login("/user/api-keys", "google_error", model);

        assertEquals("login", view);
    }

}
