package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户查询历史记录
 * 对应表：WEB_USER_QUERY_HISTORY
 */
@TableName("WEB_USER_QUERY_HISTORY")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryHistory extends BaseEntity {

    public static final String TYPE_WHOIS       = "WHOIS";
    public static final String TYPE_RDAP        = "RDAP";
    public static final String TYPE_DNS         = "DNS";
    public static final String TYPE_AVAILABILITY = "AVAILABILITY";
    public static final String TYPE_BULK        = "BULK";
    public static final String TYPE_SSL         = "SSL";
    public static final String TYPE_IP          = "IP";
    public static final String TYPE_EMAIL       = "EMAIL";
    public static final String TYPE_VALUATION   = "VALUATION";
    public static final String TYPE_PORT        = "PORT";
    public static final String TYPE_PING        = "PING";
    public static final String TYPE_SCORE       = "SCORE";

    /** 用户ID（未登录时可为空） */
    private String userId;

    /** 查询类型：WHOIS / DNS / AVAILABILITY / BULK 等 */
    private String queryType;

    /** 查询的值，如 example.com 或 user@example.com */
    private String queryValue;

    /** 结果摘要（简短文本，如 "Registered, expires 2027-01-01"） */
    private String resultSummary;
}
