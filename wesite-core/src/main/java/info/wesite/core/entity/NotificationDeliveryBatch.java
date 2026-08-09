package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Owns the durable state for one outbound notification email. */
@TableName("WEB_NOTIFICATION_DELIVERY_BATCH")
@Data
@EqualsAndHashCode(callSuper = true)
public class NotificationDeliveryBatch extends BaseEntity {

    public static final String STATE_CLAIMED = "CLAIMED";
    public static final String STATE_SENT = "SENT";
    public static final String STATE_FAILED = "FAILED";

    private String userId;

    private String emailMode;

    private String windowKey;

    private String state;

    private Integer attemptCount;

    private String claimToken;

    private Date claimUntil;

    private Date nextAttemptAt;

    private Date completedAt;
}
