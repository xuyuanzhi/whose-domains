package info.wesite.web.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Registers digest schedules only after an explicit rollout opt-in. */
@Profile({"prod", "mac"})
@Component
@ConditionalOnProperty(
    prefix = "wesite",
    name = {"notification-delivery.digest-enabled", "mail.enabled"},
    havingValue = "true",
    matchIfMissing = false)
public class DigestNotificationDeliveryJob {

    private final NotificationDeliveryTask deliveryTask;

    public DigestNotificationDeliveryJob(NotificationDeliveryTask deliveryTask) {
        this.deliveryTask = deliveryTask;
    }

    @Scheduled(cron = "0 */5 * * * ?")
    public void recover() {
        deliveryTask.recoverDigestDeliveries();
    }

    @Scheduled(cron = "0 0 8 * * ?")
    public void runDaily() {
        deliveryTask.deliverDailyDigest();
    }

    @Scheduled(cron = "0 0 8 * * MON")
    public void runWeekly() {
        deliveryTask.deliverWeeklyDigest();
    }
}
