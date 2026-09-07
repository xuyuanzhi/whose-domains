package info.wesite.admin.maintenance;

import com.alibaba.fastjson2.JSON;
import info.wesite.core.blog.BlogReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Read-only inventory; never deletes, merges, or unpublishes articles. */
@Component
@Profile("blog-audit")
@ConditionalOnProperty(name = "wesite.blog.audit.enabled", havingValue = "true")
public class BlogAuditRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlogAuditRunner.class);
    private final BlogReviewService review;
    private final ConfigurableApplicationContext context;

    public BlogAuditRunner(BlogReviewService review, ConfigurableApplicationContext context) {
        this.review = review;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("Blog audit requires spring.main.web-application-type=none");
        }
        LOGGER.info("Blog content audit completed: {}", JSON.toJSONString(review.audit()));
        context.close();
    }
}
