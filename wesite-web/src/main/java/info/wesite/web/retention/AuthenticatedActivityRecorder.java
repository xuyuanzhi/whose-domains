package info.wesite.web.retention;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import info.wesite.core.mapper.AuthenticatedActivityDailyMapper;
import info.wesite.core.mapper.RetentionFactHealthMapper;

@Service
public class AuthenticatedActivityRecorder {
    private final AuthenticatedActivityDailyMapper activities;
    private final RetentionFactHealthMapper health;
    private final Clock reportingClock;

    public AuthenticatedActivityRecorder(AuthenticatedActivityDailyMapper activities,
            RetentionFactHealthMapper health,
            @Qualifier(RetentionReportingClockConfiguration.BEAN_NAME) Clock reportingClock) {
        this.activities = activities;
        this.health = health;
        this.reportingClock = reportingClock;
    }

    @Transactional
    public void record(String userId) {
        LocalDate date = LocalDate.now(reportingClock);
        int inserted = activities.recordDaily(userId, date);
        health.recordSuccess(date, inserted);
    }
}
