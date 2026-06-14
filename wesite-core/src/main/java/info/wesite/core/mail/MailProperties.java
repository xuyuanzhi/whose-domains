package info.wesite.core.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * wesite 自有的邮件全局配置（独立于 spring.mail.*，仅放业务参数）
 *
 * <pre>
 * wesite.mail:
 *   from: alerts@whose.domains
 *   from-name: Whose.Domains
 *   reply-to: support@whose.domains
 *   send-rate-per-second: 10
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "wesite.mail")
public class MailProperties {

    /** 默认发件人地址 */
    private String from;

    /** 默认发件人显示名 */
    private String fromName = "Whose.Domains";

    /** 默认 reply-to 地址，可为空 */
    private String replyTo;

    /** 批量发送限速（封/秒），默认 10，避免被 SMTP 服务商限流 */
    private int sendRatePerSecond = 10;

    /** 是否启用邮件发送（false 时 SmtpMailSender 直接返回成功，便于测试环境关闭）*/
    private boolean enabled = true;
}
