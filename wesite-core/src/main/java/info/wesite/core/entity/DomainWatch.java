package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 域名监控实体 - 用户关注域名并接收过期提醒
 */
@TableName("WEB_DOMAIN_WATCH")
@Data
public class DomainWatch extends BaseEntity {

    public static final int NOTIFY_NONE = 0;
    public static final int NOTIFY_7_DAYS = 1;
    public static final int NOTIFY_30_DAYS = 2;
    public static final int NOTIFY_BOTH = 3;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 域名名称（如 example.com）
     */
    private String domainName;

    /**
     * 关联的域名ID（WEB_DOMAIN表的ID），可为空
     */
    private String domainId;

    /**
     * 域名注册商
     */
    private String registrar;

    /**
     * 域名过期日期文本
     */
    private String expiryDateText;

    /**
     * 域名过期日期
     */
    private Date expiryDate;

    /**
     * 通知类型：0-不通知, 1-7天前通知, 2-30天前通知, 3-两者都通知
     */
    private Integer notifyType;

    /**
     * 上次通知时间
     */
    private Date lastNotifyTime;

    /**
     * 上次检查时间
     */
    private Date lastCheckTime;

    /**
     * 鎺ユ敹閫氱煡鐨勯偖绠憋紙娣诲姞鐩戞帶鏃跺~鍐欙紝涓虹┖鏃朵笉鍙戦€侊級
     */
    private String notifyEmail;

    /**
     * 澶囨敞
     */
    private String remark;
}
