package info.wesite.core.diagnostics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "wesite.diagnostics")
public class DiagnosticsProperties {
    @jakarta.annotation.PostConstruct
    public void validate() {
        if (!java.util.Set.of("web", "admin").contains(app) || environment == null || !environment.matches("[a-zA-Z0-9_-]{1,32}")
            || eventRetentionDays < 1 || issueRetentionDays < eventRetentionDays) {
            throw new IllegalArgumentException("Invalid diagnostics configuration");
        }
    }
    private boolean enabled = false;
    private String app = "web";
    private String environment = "local";
    private int queueCapacity = 500;
    private int eventRetentionDays = 30;
    private int issueRetentionDays = 180;
}
