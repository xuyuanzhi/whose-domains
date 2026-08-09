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

    /** Compatibility field containing cumulative established/observed-ever source names. */
    private String observedSources;

    /** Collector sources that succeeded in this snapshot's scan only. */
    private String currentObservedSources;

    /** Most recent successful DOMAIN collector time, independent of snapshot time. */
    private Date domainLastSuccessAt;

    /** Most recent successful DNS collector time, independent of snapshot time. */
    private Date dnsLastSuccessAt;

    /** Most recent successful SSL collector time, independent of snapshot time. */
    private Date sslLastSuccessAt;

    /** Most recent successful WEBSITE collector time, independent of snapshot time. */
    private Date websiteLastSuccessAt;
}
