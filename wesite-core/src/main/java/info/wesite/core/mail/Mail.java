package info.wesite.core.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 待发送邮件描述（不可变 DTO，用 Builder 构造）
 */
@Data
@Builder
public class Mail {

    /** 收件人，至少 1 个 */
    private List<String> to;

    /** 抄送，可为空 */
    private List<String> cc;

    /** 密送，可为空 */
    private List<String> bcc;

    /** 邮件标题 */
    private String subject;

    /** HTML 正文（已渲染好的 HTML 字符串）*/
    private String htmlContent;

    /** 纯文本备份（可选，用于不支持 HTML 的客户端）*/
    private String plainTextContent;

    /**
     * 模板名（可选）。如果设置，{@link SmtpMailSender} 会优先用 Thymeleaf 渲染该模板，
     * 路径相对于 templates/ 目录，例如 "email/domain-expire-notify"
     */
    private String templateName;

    /** 模板变量（仅在 templateName 非空时使用）*/
    private Map<String, Object> templateVariables;

    /** 邮件内的 reply-to，可选；为空时使用全局配置 */
    private String replyTo;

    /** 工厂方法：单收件人 + HTML 正文 */
    public static Mail of(String to, String subject, String html) {
        List<String> tos = new ArrayList<>();
        tos.add(to);
        return Mail.builder().to(tos).subject(subject).htmlContent(html).build();
    }
}
