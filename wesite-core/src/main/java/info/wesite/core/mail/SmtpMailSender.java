package info.wesite.core.mail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * SMTP 邮件发送实现，基于 spring-boot-starter-mail 的 {@link JavaMailSender}。
 * <p>
 * 端口选择建议：587 (STARTTLS) > 465 (SSL/TLS)；25 端口被云服务商默认封禁，禁用。
 * <p>
 * 仅在配置 {@code spring.mail.host} 时生效；缺省时该 Bean 不注入，业务方注入 {@link MailSender}
 * 时会得到 NoSuchBeanDefinitionException —— 这是预期行为，方便本地开发不挂依赖。
 */
@Component
@Configuration
@EnableConfigurationProperties(MailProperties.class)
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    @Autowired
    private JavaMailSender javaMailSender;

    @Autowired
    private MailProperties mailProperties;

    /** 可选：用于渲染 Thymeleaf 邮件模板。无 thymeleaf 时也不影响普通发送 */
    @Autowired(required = false)
    private TemplateEngine templateEngine;

    @Override
    public MailSendResult send(Mail mail) {
        if (!mailProperties.isEnabled()) {
            log.info("[mail] disabled, skip sending to={}", mail.getTo());
            return MailSendResult.ok();
        }
        if (mail.getTo() == null || mail.getTo().isEmpty()) {
            return MailSendResult.fail("'to' is required");
        }
        if (mail.getSubject() == null || mail.getSubject().isBlank()) {
            return MailSendResult.fail("'subject' is required");
        }

        try {
            MimeMessage mime = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());

            String fromName = mailProperties.getFromName() != null ? mailProperties.getFromName() : "";
            helper.setFrom(new InternetAddress(mailProperties.getFrom(), fromName, StandardCharsets.UTF_8.name()));
            helper.setTo(mail.getTo().toArray(new String[0]));
            if (mail.getCc() != null && !mail.getCc().isEmpty()) {
                helper.setCc(mail.getCc().toArray(new String[0]));
            }
            if (mail.getBcc() != null && !mail.getBcc().isEmpty()) {
                helper.setBcc(mail.getBcc().toArray(new String[0]));
            }
            String replyTo = mail.getReplyTo() != null ? mail.getReplyTo() : mailProperties.getReplyTo();
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(mail.getSubject());

            // 优先：模板渲染；其次：直接 HTML；最后：纯文本
            String html = mail.getHtmlContent();
            if (mail.getTemplateName() != null && templateEngine != null) {
                Context ctx = new Context();
                if (mail.getTemplateVariables() != null) {
                    ctx.setVariables(mail.getTemplateVariables());
                }
                html = templateEngine.process(mail.getTemplateName(), ctx);
            }

            if (html != null) {
                if (mail.getPlainTextContent() != null) {
                    helper.setText(mail.getPlainTextContent(), html);
                } else {
                    helper.setText(html, true);
                }
            } else if (mail.getPlainTextContent() != null) {
                helper.setText(mail.getPlainTextContent(), false);
            } else {
                return MailSendResult.fail("Either htmlContent or plainTextContent or templateName is required");
            }

            javaMailSender.send(mime);
            return MailSendResult.ok();
        } catch (Exception e) {
            log.error("[mail] send failed to={} subject={}", mail.getTo(), mail.getSubject(), e);
            return MailSendResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public List<MailSendResult> sendBatch(List<Mail> mails) {
        if (mails == null || mails.isEmpty()) {
            return Collections.emptyList();
        }
        List<MailSendResult> results = new ArrayList<>(mails.size());
        long minIntervalMs = mailProperties.getSendRatePerSecond() > 0
                ? Math.max(1L, 1000L / mailProperties.getSendRatePerSecond()) : 0L;
        long lastSentAt = 0L;
        for (Mail mail : mails) {
            if (minIntervalMs > 0) {
                long wait = lastSentAt + minIntervalMs - System.currentTimeMillis();
                if (wait > 0) {
                    try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            results.add(send(mail));
            lastSentAt = System.currentTimeMillis();
        }
        return results;
    }
}
