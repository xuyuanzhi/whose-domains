package info.wesite.web.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

class NotificationDeliveryJobRegistrationTest {

    @Test
    void deliveryJobsAreAbsentByDefaultAndCannotTouchTheQueue() {
        NotificationDeliveryTask deliveryTask = mock(NotificationDeliveryTask.class);

        runner(deliveryTask).run(context -> {
            assertThat(context).doesNotHaveBean(ImmediateNotificationDeliveryJob.class);
            assertThat(context).doesNotHaveBean(DigestNotificationDeliveryJob.class);
            verifyNoInteractions(deliveryTask);
        });
    }

    @Test
    void explicitlyDisabledDeliveryJobsAreAbsentAndCannotTouchTheQueue() {
        NotificationDeliveryTask deliveryTask = mock(NotificationDeliveryTask.class);

        runner(deliveryTask)
            .withPropertyValues(
                "wesite.notification-delivery.immediate-enabled=false",
                "wesite.notification-delivery.digest-enabled=false",
                "wesite.mail.enabled=true")
            .run(context -> {
                assertThat(context).doesNotHaveBean(ImmediateNotificationDeliveryJob.class);
                assertThat(context).doesNotHaveBean(DigestNotificationDeliveryJob.class);
                verifyNoInteractions(deliveryTask);
            });
    }

    @Test
    void mailDisabledPreventsBothJobsAndLeavesQueueBoundaryUntouched() {
        NotificationDeliveryTask deliveryTask = mock(NotificationDeliveryTask.class);

        runner(deliveryTask)
            .withPropertyValues(
                "wesite.notification-delivery.immediate-enabled=true",
                "wesite.notification-delivery.digest-enabled=true",
                "wesite.mail.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(ImmediateNotificationDeliveryJob.class);
                assertThat(context).doesNotHaveBean(DigestNotificationDeliveryJob.class);
                verifyNoInteractions(deliveryTask);
            });
    }

    @Test
    void immediateFlagRegistersOnlyImmediateJobAndUsesItsOwnSchedule() throws Exception {
        NotificationDeliveryTask deliveryTask = mock(NotificationDeliveryTask.class);

        runner(deliveryTask)
            .withPropertyValues(
                "wesite.notification-delivery.immediate-enabled=true",
                "wesite.mail.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(ImmediateNotificationDeliveryJob.class);
                assertThat(context).doesNotHaveBean(DigestNotificationDeliveryJob.class);

                context.getBean(ImmediateNotificationDeliveryJob.class).run();

                verify(deliveryTask).deliverImmediate();
            });
        assertEquals("0 */5 * * * ?", cron(ImmediateNotificationDeliveryJob.class, "run"));
    }

    @Test
    void digestFlagRegistersOnlyDigestJobAndUsesDailyAndWeeklySchedules() throws Exception {
        NotificationDeliveryTask deliveryTask = mock(NotificationDeliveryTask.class);

        runner(deliveryTask)
            .withPropertyValues(
                "wesite.notification-delivery.digest-enabled=true",
                "wesite.mail.enabled=true")
            .run(context -> {
                assertThat(context).doesNotHaveBean(ImmediateNotificationDeliveryJob.class);
                assertThat(context).hasSingleBean(DigestNotificationDeliveryJob.class);

                DigestNotificationDeliveryJob job = context.getBean(DigestNotificationDeliveryJob.class);
                job.runDaily();
                job.runWeekly();

                verify(deliveryTask).deliverDailyDigest();
                verify(deliveryTask).deliverWeeklyDigest();
            });
        assertEquals("0 0 8 * * ?", cron(DigestNotificationDeliveryJob.class, "runDaily"));
        assertEquals("0 0 8 * * MON", cron(DigestNotificationDeliveryJob.class, "runWeekly"));
    }

    private static ApplicationContextRunner runner(NotificationDeliveryTask deliveryTask) {
        return new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=prod")
            .withBean(NotificationDeliveryTask.class, () -> deliveryTask)
            .withUserConfiguration(
                ImmediateNotificationDeliveryJob.class,
                DigestNotificationDeliveryJob.class);
    }

    private static String cron(Class<?> type, String methodName) throws Exception {
        Method method = type.getMethod(methodName);
        return method.getAnnotation(Scheduled.class).cron();
    }
}
