package info.wesite.core.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

class SmtpMailSenderTest {

    @Test
    void disabledSenderReportsUnavailableWithoutTouchingSmtp() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MailProperties properties = new MailProperties();
        properties.setEnabled(false);
        SmtpMailSender sender = new SmtpMailSender();
        ReflectionTestUtils.setField(sender, "javaMailSender", javaMailSender);
        ReflectionTestUtils.setField(sender, "mailProperties", properties);

        MailSendResult result = sender.send(Mail.builder()
            .to(List.of("internal@example.test"))
            .subject("Synthetic notification")
            .plainTextContent("No external message must be sent")
            .build());

        assertFalse(result.isSuccess());
        assertEquals("mail sending is disabled", result.getErrorMessage());
        verifyNoInteractions(javaMailSender);
    }
}
