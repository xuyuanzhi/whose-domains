package info.wesite.core.mail;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MailSendResult {

    private boolean success;

    /** 失败原因，成功时为 null */
    private String errorMessage;

    public static MailSendResult ok() {
        return new MailSendResult(true, null);
    }

    public static MailSendResult fail(String msg) {
        return new MailSendResult(false, msg);
    }
}
