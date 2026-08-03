package info.wesite.web.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import info.wesite.core.entity.EmailLoginLink;
import info.wesite.core.mail.MailSendResult;
import info.wesite.core.mail.MailSender;
import info.wesite.core.service.EmailLoginLinkService;

class EmailLoginServiceTest {

    @Test
    void rejectsExternalRedirectPaths() {
        EmailLoginService service = new EmailLoginService();

        assertThrows(IllegalArgumentException.class,
                () -> service.request("person@example.com", "https://evil.example/"));
    }

    @Test
    void preservesTheTwoMinuteCooldownMessage() {
        EmailLoginLinkService links = mock(EmailLoginLinkService.class);
        when(links.count(any())).thenReturn(1L);

        EmailLoginRequestResult result = service(links).request("person@example.com", "/user/watchlist?login=success");

        assertEquals("Please wait 2 minutes before requesting another sign-in link.", result.message());
    }

    @Test
    void preservesTheTenPerDayLimitMessage() {
        EmailLoginLinkService links = mock(EmailLoginLinkService.class);
        when(links.count(any())).thenReturn(0L, 10L);

        EmailLoginRequestResult result = service(links).request("person@example.com", "/user/watchlist?login=success");

        assertEquals("You have reached the daily limit of 10 sign-in links. Please try again tomorrow.", result.message());
    }

    @Test
    void storesTheValidatedRedirectPathWithTheHashedToken() {
        EmailLoginLinkService links = mock(EmailLoginLinkService.class);
        MailSender mailSender = mock(MailSender.class);
        when(links.count(any())).thenReturn(0L, 0L);
        when(mailSender.send(any())).thenReturn(MailSendResult.ok());
        ArgumentCaptor<EmailLoginLink> savedLink = ArgumentCaptor.forClass(EmailLoginLink.class);

        EmailLoginRequestResult result = service(links, mailSender).request(" Person@Example.com ",
                "/user/watchlist?login=google_bind_required");

        org.mockito.Mockito.verify(links).save(savedLink.capture());
        assertEquals("person@example.com", savedLink.getValue().getEmail());
        assertEquals("/user/watchlist?login=google_bind_required", savedLink.getValue().getRedirectPath());
        org.junit.jupiter.api.Assertions.assertNotNull(savedLink.getValue().getTokenHash());
        assertEquals("Check your inbox for a secure sign-in link.", result.message());
    }

    @Test
    void normalizesEmailIndependentlyOfTheJvmDefaultLocale() {
        EmailLoginLinkService links = mock(EmailLoginLinkService.class);
        MailSender mailSender = mock(MailSender.class);
        when(links.count(any())).thenReturn(0L, 0L);
        when(mailSender.send(any())).thenReturn(MailSendResult.ok());
        ArgumentCaptor<EmailLoginLink> savedLink = ArgumentCaptor.forClass(EmailLoginLink.class);
        Locale previous = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service(links, mailSender).request("I@EXAMPLE.COM", "/user/watchlist?login=success");
        } finally {
            Locale.setDefault(previous);
        }

        org.mockito.Mockito.verify(links).save(savedLink.capture());
        assertEquals("i@example.com", savedLink.getValue().getEmail());
    }

    private EmailLoginService service(EmailLoginLinkService links) {
        return service(links, mock(MailSender.class));
    }

    private EmailLoginService service(EmailLoginLinkService links, MailSender mailSender) {
        EmailLoginService service = new EmailLoginService();
        ReflectionTestUtils.setField(service, "emailLoginLinkService", links);
        ReflectionTestUtils.setField(service, "mailSender", mailSender);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "http://localhost:8080");
        return service;
    }
}
