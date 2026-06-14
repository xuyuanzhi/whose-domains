package info.wesite.core.mail;

import java.util.List;

/**
 * 邮件发送抽象。
 * 当前实现：{@link SmtpMailSender}（基于 spring-boot-starter-mail 的 SMTP 协议发送）。
 * 未来可扩展：RestApiMailSender（直连 SendGrid/Mailgun HTTP API），无需修改业务代码。
 */
public interface MailSender {

    /**
     * 发送一封邮件。
     *
     * @param mail 邮件内容（必须包含 to / subject / htmlContent）
     * @return 发送结果（成功失败 + 错误信息）
     */
    MailSendResult send(Mail mail);

    /**
     * 批量发送（同步顺序发送，调用方决定是否在线程池中并发）。
     *
     * @return 与入参顺序对齐的结果列表
     */
    List<MailSendResult> sendBatch(List<Mail> mails);
}
