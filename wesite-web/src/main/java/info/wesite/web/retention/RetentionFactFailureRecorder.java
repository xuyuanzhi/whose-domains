package info.wesite.web.retention;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import info.wesite.core.mapper.RetentionFactHealthMapper;

@Service
public class RetentionFactFailureRecorder {
    private final RetentionFactHealthMapper health;
    private final ZoneId reportingZone;

    public RetentionFactFailureRecorder(RetentionFactHealthMapper health,
            @Value("${wesite.retention.reporting-zone:Asia/Shanghai}") String reportingZone) {
        this.health = health;
        this.reportingZone = ZoneId.of(reportingZone);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure() {
        health.recordFailure(LocalDate.now(reportingZone));
    }
}
