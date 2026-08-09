package info.wesite.web.retention;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RetentionReportingClockConfiguration {

    public static final String BEAN_NAME = "retentionReportingClock";

    @Bean(name = BEAN_NAME)
    Clock retentionReportingClock(
            @Value("${wesite.retention.reporting-zone:Asia/Shanghai}") String reportingZone) {
        return Clock.system(ZoneId.of(reportingZone));
    }
}
