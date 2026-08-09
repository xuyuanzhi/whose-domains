package info.wesite.web.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Registers the immediate-delivery schedule only after an explicit rollout opt-in. */
@Profile({"prod", "mac"})
@Component
@ConditionalOnProperty(
    prefix = "wesite",
    name = {"notification-delivery.immediate-enabled", "mail.enabled"},
    havingValue = "true",
    matchIfMissing = false)
public class ImmediateNotificationDeliveryJob {

    private final NotificationDeliveryTask deliveryTask;

    public ImmediateNotificationDeliveryJob(NotificationDeliveryTask deliveryTask) {
        this.deliveryTask = deliveryTask;
    }

    @Scheduled(cron = "0 */5 * * * ?")
    public void run() {
        deliveryTask.deliverImmediate();
    }
}
