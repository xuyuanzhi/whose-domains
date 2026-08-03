package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("WEB_API_USAGE_DAILY")
public class ApiUsageDaily extends BaseEntity {
    private String userId;
    private String usageDate;
    private Integer requestCount;
}
