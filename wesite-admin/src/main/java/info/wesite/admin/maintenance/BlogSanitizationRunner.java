package info.wesite.admin.maintenance;

import java.util.Locale;

import com.alibaba.fastjson2.JSON;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import info.wesite.core.blog.BlogSanitizationMaintenanceService;
import info.wesite.core.blog.BlogSanitizationReport;

@Component
@Profile("blog-sanitize")
@ConditionalOnProperty(name = "wesite.blog.sanitization.mode")
public class BlogSanitizationRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlogSanitizationRunner.class);

    private final BlogSanitizationMaintenanceService service;
    private final ConfigurableApplicationContext context;
    private final String mode;
    private final int batchSize;

    public BlogSanitizationRunner(
            BlogSanitizationMaintenanceService service,
            ConfigurableApplicationContext context,
            @Value("${wesite.blog.sanitization.mode}") String mode,
            @Value("${wesite.blog.sanitization.batch-size:100}") int batchSize) {
        this.service = service;
        this.context = context;
        this.mode = mode;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean apply = resolveApplyMode(mode);
        BlogSanitizationReport report = service.run(apply, batchSize);
        LOGGER.info("Blog sanitization completed: {}", JSON.toJSONString(report));
        context.close();
    }

    private static boolean resolveApplyMode(String configuredMode) {
        String normalized = configuredMode == null
            ? ""
            : configuredMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "dry-run" -> false;
            case "apply" -> true;
            default -> throw new IllegalArgumentException(
                "Unsupported blog sanitization mode: " + configuredMode);
        };
    }
}
