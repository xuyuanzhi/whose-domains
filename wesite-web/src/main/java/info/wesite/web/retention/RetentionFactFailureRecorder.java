package info.wesite.web.retention;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import info.wesite.core.mapper.RetentionFactHealthMapper;

@Service
public class RetentionFactFailureRecorder {
    private final RetentionFactHealthMapper health;
    private final Clock reportingClock;

    public RetentionFactFailureRecorder(RetentionFactHealthMapper health,
            @Qualifier(RetentionReportingClockConfiguration.BEAN_NAME) Clock reportingClock) {
        this.health = health;
        this.reportingClock = reportingClock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure() {
        health.recordFailure(LocalDate.now(reportingClock));
    }
}
