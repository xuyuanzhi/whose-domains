package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 域名监控通知日志（每发一封记一条），用于审计、限频、失败重试
 */
@TableName("WEB_DOMAIN_WATCH_NOTIFY_LOG")
@Data
@EqualsAndHashCode(callSuper = true)
public class DomainWatchNotifyLog extends BaseEntity {

    public static final int SEND_STATUS_SUCCESS = 1;
    public static final int SEND_STATUS_FAIL = 2;

    /** 关联的 watch id */
    private String watchId;

    /** 收件人邮箱（冗余存储，便于排查） */
    private String toEmail;

    /** 域名（冗余） */
    private String domainName;

    /** 距离过期天数（冗余） */
    private Integer daysLeft;

    /** 发送时间 */
    private Date sentAt;

    /** 发送状态：1=成功，2=失败 */
    private Integer sendStatus;

    /** 失败原因 */
    private String errorMsg;

    /** 重试次数 */
    private Integer retryCount;
}
