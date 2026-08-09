package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("WEB_MONITOR_EVENT")
@Data
@EqualsAndHashCode(callSuper = true)
public class MonitorEvent extends BaseEntity {

    private String watchId;

    private String snapshotId;

    private String fingerprint;

    private String eventType;

    private String risk;

    private String source;

    private String oldValue;

    private String newValue;

    private Date occurredAt;
}
