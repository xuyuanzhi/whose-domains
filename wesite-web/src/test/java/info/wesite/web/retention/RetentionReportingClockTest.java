package info.wesite.web.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import info.wesite.core.mapper.AuthenticatedActivityDailyMapper;
import info.wesite.core.mapper.RetentionFactHealthMapper;

class RetentionReportingClockTest {

    @Test
    void configurationUsesTheExactIanaZoneIncludingANonPlusEightZone() {
        RetentionReportingClockConfiguration configuration = new RetentionReportingClockConfiguration();

        assertEquals(ZoneId.of("Asia/Shanghai"),
            configuration.retentionReportingClock("Asia/Shanghai").getZone());
        assertEquals(ZoneId.of("America/New_York"),
            configuration.retentionReportingClock("America/New_York").getZone());
    }

    @Test
    void activityAndFailureFactsConsumeTheSameInjectedReportingClock() throws Exception {
        Constructor<?> activityConstructor = Arrays.stream(AuthenticatedActivityRecorder.class.getConstructors())
            .filter(candidate -> Arrays.equals(candidate.getParameterTypes(), new Class<?>[] {
                AuthenticatedActivityDailyMapper.class, RetentionFactHealthMapper.class, Clock.class
            }))
            .findFirst()
            .orElse(null);
        Constructor<?> failureConstructor = Arrays.stream(RetentionFactFailureRecorder.class.getConstructors())
            .filter(candidate -> Arrays.equals(candidate.getParameterTypes(), new Class<?>[] {
                RetentionFactHealthMapper.class, Clock.class
            }))
            .findFirst()
            .orElse(null);

        assertTrue(activityConstructor != null, "activity recorder must inject the shared Clock bean");
        assertTrue(failureConstructor != null, "failure recorder must inject the shared Clock bean");

        AuthenticatedActivityDailyMapper activities = mock(AuthenticatedActivityDailyMapper.class);
        RetentionFactHealthMapper health = mock(RetentionFactHealthMapper.class);
        Clock clock = Clock.fixed(
            Instant.parse("2026-08-09T02:30:00Z"), ZoneId.of("America/New_York"));
        AuthenticatedActivityRecorder activityRecorder = (AuthenticatedActivityRecorder)
            activityConstructor.newInstance(activities, health, clock);
        RetentionFactFailureRecorder failureRecorder = (RetentionFactFailureRecorder)
            failureConstructor.newInstance(health, clock);

        activityRecorder.record("user-1");
        failureRecorder.recordFailure();

        LocalDate expectedDate = LocalDate.of(2026, 8, 8);
        verify(activities).recordDaily("user-1", expectedDate);
        verify(health).recordSuccess(expectedDate, 0);
        verify(health).recordFailure(expectedDate);
    }
}
