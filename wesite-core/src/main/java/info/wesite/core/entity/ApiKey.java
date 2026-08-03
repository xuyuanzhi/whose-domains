package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("WEB_API_KEY")
public class ApiKey extends BaseEntity {
    private String userId;
    private String name;
    private String keyPrefix;
    private String keyHash;
    private java.util.Date lastUsedAt;
    private java.util.Date revokedAt;
}
