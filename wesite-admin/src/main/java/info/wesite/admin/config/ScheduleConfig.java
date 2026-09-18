package info.wesite.admin.config;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class ScheduleConfig implements SchedulingConfigurer {
	@org.springframework.beans.factory.annotation.Autowired
	private info.wesite.core.diagnostics.DiagnosticRecorder diagnosticRecorder;

	@org.springframework.context.annotation.Bean
	public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler applicationTaskScheduler() {
		var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
		scheduler.setPoolSize(15);
		scheduler.setThreadNamePrefix("scheduled-");
		scheduler.setErrorHandler(error -> {
			diagnosticRecorder.task("scheduled", error);
			logger.error("Scheduled task failed", error);
		});
		return scheduler;
	}

	
	protected static Logger logger = LoggerFactory.getLogger(ScheduleConfig.class);

	@Override
	public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
		taskRegistrar.setTaskScheduler(applicationTaskScheduler());
	}

}
