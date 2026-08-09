package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("WEB_NOTIFICATION_PREFERENCE")
@Data
@EqualsAndHashCode(callSuper = true)
public class NotificationPreference extends BaseEntity {

    public static final String MODE_IMMEDIATE = "immediate";
    public static final String MODE_DAILY = "daily";
    public static final String MODE_DISABLED = "disabled";

    private String userId;

    private String emailMode;

    private Boolean domainExpiryEnabled;

    private Boolean sslExpiryEnabled;

    private Boolean domainStatusEnabled;

    private Boolean dnsChangeEnabled;

    private Boolean websiteAvailabilityEnabled;

    public static NotificationPreference defaultsFor(String userId) {
        NotificationPreference value = new NotificationPreference();
        value.setUserId(userId);
        value.setEmailMode(MODE_DAILY);
        value.setDomainExpiryEnabled(true);
        value.setSslExpiryEnabled(true);
        value.setDomainStatusEnabled(true);
        value.setDnsChangeEnabled(true);
        value.setWebsiteAvailabilityEnabled(true);
        value.setDeleted(false);
        return value;
    }

    public void setDeleted(boolean deleted) {
        super.setDeleted(deleted ? 1 : 0);
    }
}
