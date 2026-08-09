package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("WEB_MONITOR_SNAPSHOT")
@Data
@EqualsAndHashCode(callSuper = true)
public class MonitorSnapshot extends BaseEntity {

    private String watchId;

    private Date checkedAt;

    private String stateJson;

    private Integer schemaVersion;

    private String observedSources;
}
