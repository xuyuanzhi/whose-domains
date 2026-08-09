package info.wesite.web.monitor;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Owns the fixed capacity used by all monitoring address lookups. */
@Configuration(proxyBeanMethods = false)
class MonitorResolverConfiguration {

    static final String EXECUTOR_BEAN = "monitorResolverExecutor";
    private static final int POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 8;

    @Bean(name = EXECUTOR_BEAN)
    ThreadPoolTaskExecutor monitorResolverExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("monitor-resolver-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
