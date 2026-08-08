package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import info.wesite.core.view.ResponseJson;
import info.wesite.web.auth.EmailLoginRequest;
import info.wesite.web.auth.EmailLoginRequestResult;
import info.wesite.web.auth.EmailLoginService;
import info.wesite.web.auth.ReturnTargetService;

class UserControllerEmailLoginTest {

    @Test
    void gatewayRequestUsesRequestedTarget() {
        EmailLoginService emailLoginService = mock(EmailLoginService.class);
        when(emailLoginService.request("person@example.com", "/user/api-keys"))
                .thenReturn(EmailLoginRequestResult.failure(
                        "Please wait 2 minutes before requesting another sign-in link."));

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "emailLoginService", emailLoginService);
        ReflectionTestUtils.setField(controller, "returnTargets", new ReturnTargetService());

        ResponseJson<Void> response = controller.requestEmailLogin(
                new EmailLoginRequest("person@example.com", "/user/api-keys"));

        verify(emailLoginService).request("person@example.com", "/user/api-keys");
        assertEquals(ResponseJson.CODE_FAILURE, response.getCode());
        assertEquals("Please wait 2 minutes before requesting another sign-in link.", response.getMsg());
    }

    @Test
    void modalRequestKeepsWatchlistDefault() {
        EmailLoginService emailLoginService = mock(EmailLoginService.class);
        when(emailLoginService.request("person@example.com", "/user/watchlist?login=success"))
                .thenReturn(EmailLoginRequestResult.success("Check your inbox for a secure sign-in link."));

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "emailLoginService", emailLoginService);
        ReflectionTestUtils.setField(controller, "returnTargets", new ReturnTargetService());

        ResponseJson<Void> response = controller.requestEmailLogin(new EmailLoginRequest("person@example.com", null));

        verify(emailLoginService).request("person@example.com", "/user/watchlist?login=success");
        assertEquals(ResponseJson.CODE_SUCCESS, response.getCode());
    }

}
