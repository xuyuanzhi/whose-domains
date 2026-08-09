package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("WEB_USER_NOTIFICATION")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserNotification extends BaseEntity {

    public static final String EMAIL_STATE_QUEUED = "QUEUED";
    public static final String EMAIL_STATE_CLAIMED = "CLAIMED";
    public static final String EMAIL_STATE_SENT = "SENT";
    public static final String EMAIL_STATE_FAILED = "FAILED";
    public static final String EMAIL_STATE_IN_APP_ONLY = "IN_APP_ONLY";

    private String userId;

    private String eventId;

    private String title;

    private String content;

    private String targetPath;

    private Date readAt;

    private String emailState;

    private String emailMode;

    private Integer emailAttemptCount;

    private String emailClaimToken;

    private Date emailClaimUntil;

    private String deliveryBatchId;

    /** Validated recipient frozen when the notification is routed. */
    private String recipientEmail;

    private Date emailedAt;
}
