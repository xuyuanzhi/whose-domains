package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.mock.web.MockHttpServletRequest;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.web.auth.ReturnTargetService;

class LoginControllerTest {

    private final LoginController controller = new LoginController(new ReturnTargetService());

    @Test
    void loginPageExposesValidatedTarget() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("login", controller.login("/user/api-keys", model));
        assertEquals("/user/api-keys", model.get("returnTo"));
    }

    @Test
    void signedInVisitorSkipsGateway() {
        UserHolder.set(new User());
        try {
            assertEquals("redirect:/user/api-keys", controller.login("/user/api-keys", new ExtendedModelMap()));
        } finally {
            UserHolder.remove();
        }
    }

    @Test
    void googleStartStoresTarget() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertEquals("redirect:/oauth2/authorization/google", controller.google("/user/api-keys", request));
        assertEquals("/user/api-keys", request.getSession().getAttribute("LOGIN_RETURN_TO"));
    }
}
