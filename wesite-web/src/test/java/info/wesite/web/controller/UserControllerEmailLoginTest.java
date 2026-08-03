package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import info.wesite.core.entity.User;
import info.wesite.core.mail.MailSendResult;
import info.wesite.core.mail.MailSender;
import info.wesite.core.service.EmailLoginLinkService;
import info.wesite.core.view.ResponseJson;

class UserControllerEmailLoginTest {

    @Test
    void rejectsAnotherMagicLinkForTheSameEmailWithinTwoMinutes() {
        EmailLoginLinkService links = mock(EmailLoginLinkService.class);
        MailSender mailSender = mock(MailSender.class);
        when(links.count(any())).thenReturn(1L);
        when(mailSender.send(any())).thenReturn(MailSendResult.ok());

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "emailLoginLinkService", links);
        ReflectionTestUtils.setField(controller, "mailSender", mailSender);
        ReflectionTestUtils.setField(controller, "publicBaseUrl", "http://localhost:8080");
        User request = new User();
        request.setEmail("person@example.com");

        ResponseJson<Void> response = controller.requestEmailLogin(request);

        assertEquals(ResponseJson.CODE_FAILURE, response.getCode());
        assertEquals("Please wait 2 minutes before requesting another sign-in link.", response.getMsg());
    }

    @Test
    void rejectsTheEleventhMagicLinkForTheSameEmailOnTheSameDay() {
        EmailLoginLinkService links = mock(EmailLoginLinkService.class);
        MailSender mailSender = mock(MailSender.class);
        when(links.count(any())).thenReturn(0L, 10L);
        when(mailSender.send(any())).thenReturn(MailSendResult.ok());

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "emailLoginLinkService", links);
        ReflectionTestUtils.setField(controller, "mailSender", mailSender);
        ReflectionTestUtils.setField(controller, "publicBaseUrl", "http://localhost:8080");
        User request = new User();
        request.setEmail("person@example.com");

        ResponseJson<Void> response = controller.requestEmailLogin(request);

        assertEquals(ResponseJson.CODE_FAILURE, response.getCode());
        assertEquals("You have reached the daily limit of 10 sign-in links. Please try again tomorrow.", response.getMsg());
    }
}
