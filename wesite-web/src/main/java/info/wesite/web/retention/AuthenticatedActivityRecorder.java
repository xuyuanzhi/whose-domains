package info.wesite.web.retention;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import info.wesite.core.mapper.AuthenticatedActivityDailyMapper;
import info.wesite.core.mapper.RetentionFactHealthMapper;

@Service
public class AuthenticatedActivityRecorder {
    private final AuthenticatedActivityDailyMapper activities;
    private final RetentionFactHealthMapper health;
    private final ZoneId reportingZone;

    public AuthenticatedActivityRecorder(AuthenticatedActivityDailyMapper activities,
            RetentionFactHealthMapper health,
            @Value("${wesite.retention.reporting-zone:Asia/Shanghai}") String reportingZone) {
        this.activities = activities;
        this.health = health;
        this.reportingZone = ZoneId.of(reportingZone);
    }

    @Transactional
    public void record(String userId) {
        LocalDate date = LocalDate.now(reportingZone);
        int inserted = activities.recordDaily(userId, date);
        health.recordSuccess(date, inserted);
        health.activate(date);
    }
}
