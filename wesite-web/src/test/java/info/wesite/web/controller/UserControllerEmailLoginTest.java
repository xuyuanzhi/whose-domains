package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import info.wesite.core.entity.User;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.auth.EmailLoginRequestResult;
import info.wesite.web.auth.EmailLoginService;

class UserControllerEmailLoginTest {

    @Test
    void delegatesEmailLoginRequestsToTheSharedService() {
        EmailLoginService emailLoginService = mock(EmailLoginService.class);
        when(emailLoginService.request("person@example.com", "/user/watchlist?login=success"))
                .thenReturn(EmailLoginRequestResult.failure(
                        "Please wait 2 minutes before requesting another sign-in link."));

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "emailLoginService", emailLoginService);
        User request = new User();
        request.setEmail("person@example.com");

        ResponseJson<Void> response = controller.requestEmailLogin(request);

        verify(emailLoginService).request("person@example.com", "/user/watchlist?login=success");
        assertEquals(ResponseJson.CODE_FAILURE, response.getCode());
        assertEquals("Please wait 2 minutes before requesting another sign-in link.", response.getMsg());
    }

}
