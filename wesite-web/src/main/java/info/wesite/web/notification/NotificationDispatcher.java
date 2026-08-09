package info.wesite.web.notification;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.DomainWatch;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.mapper.DomainWatchMapper;
import info.wesite.core.service.NotificationPreferenceService;
import info.wesite.core.service.UserNotificationService;

/**
 * Applies notification preferences by persisting a delivery route. It never
 * sends mail; scheduled delivery is deliberately isolated from this policy.
 */
@Service
public class NotificationDispatcher {

    private final NotificationPreferenceService preferenceService;
    private final UserNotificationService notificationService;
    private final DomainWatchMapper watchMapper;
    private final NotificationPreferenceResolver resolver;
    private final NotificationPolicyLock policyLock;

    @Autowired
    public NotificationDispatcher(
        NotificationPreferenceService preferenceService,
        UserNotificationService notificationService,
        DomainWatchMapper watchMapper,
        NotificationPolicyLock policyLock) {
        this(preferenceService, notificationService, watchMapper,
            new NotificationPreferenceResolver(), policyLock);
    }

    NotificationDispatcher(
        NotificationPreferenceService preferenceService,
        UserNotificationService notificationService) {
        this(preferenceService, notificationService, null, new NotificationPreferenceResolver(), null);
    }

    NotificationDispatcher(
        NotificationPreferenceService preferenceService,
        UserNotificationService notificationService,
        NotificationPreferenceResolver resolver) {
        this(preferenceService, notificationService, null, resolver, null);
    }

    NotificationDispatcher(
        NotificationPreferenceService preferenceService,
        UserNotificationService notificationService,
        DomainWatchMapper watchMapper,
        NotificationPreferenceResolver resolver) {
        this(preferenceService, notificationService, watchMapper, resolver, null);
    }

    NotificationDispatcher(
        NotificationPreferenceService preferenceService,
        UserNotificationService notificationService,
        DomainWatchMapper watchMapper,
        NotificationPreferenceResolver resolver,
        NotificationPolicyLock policyLock) {
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.watchMapper = watchMapper;
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.policyLock = policyLock;
    }

    @Transactional
    public NotificationDispatchDecision dispatch(
        MonitorEvent event,
        UserNotification notification,
        DomainWatch watch,
        Integer domainExpiryThresholdDays) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(notification, "notification");
        Objects.requireNonNull(watch, "watch");

        String userId = notification.getUserId();
        NotificationPreference preference = currentPreference(userId);
        DomainWatch currentWatch = currentWatch(watch);
        String recipient = currentWatch == null
            ? null
            : NotificationEmailAddress.normalize(currentWatch.getNotifyEmail()).orElse(null);
        boolean watchAllowsEmail = currentWatch != null
            && watchAllowsEmail(currentWatch, event, domainExpiryThresholdDays);

        NotificationDispatchDecision decision = watchAllowsEmail
            ? resolver.resolve(event, preference, recipient != null)
            : NotificationDispatchDecision.IN_APP_ONLY;
        notification.setEmailMode(decision.name());
        notification.setEmailState(decision == NotificationDispatchDecision.IN_APP_ONLY
            ? UserNotification.EMAIL_STATE_IN_APP_ONLY
            : UserNotification.EMAIL_STATE_QUEUED);
        notification.setEmailAttemptCount(0);
        notification.setEmailClaimToken(null);
        notification.setEmailClaimUntil(null);
        notification.setDeliveryBatchId(null);
        notification.setRecipientEmail(decision == NotificationDispatchDecision.IN_APP_ONLY ? null : recipient);
        notification.setEmailedAt(null);
        if (!notificationService.updateById(notification)) {
            throw new IllegalStateException("Failed to persist notification delivery state");
        }
        return decision;
    }

    private NotificationPreference currentPreference(String userId) {
        if (StringUtils.isBlank(userId)) {
            return null;
        }
        if (policyLock != null) {
            return policyLock.lockCurrent(userId);
        }
        return preferenceService.getOne(Wrappers.<NotificationPreference>lambdaQuery()
            .eq(NotificationPreference::getUserId, userId));
    }

    private DomainWatch currentWatch(DomainWatch supplied) {
        if (watchMapper == null) {
            return supplied;
        }
        DomainWatch current = watchMapper.selectByIdForUpdate(supplied.getId());
        if (current == null
                || !Integer.valueOf(DomainWatch.STATUS_ACTIVE).equals(current.getStatus())
                || !Integer.valueOf(0).equals(current.getDeleted())) {
            return null;
        }
        return current;
    }

    private static boolean watchAllowsEmail(
        DomainWatch watch,
        MonitorEvent event,
        Integer domainExpiryThresholdDays) {
        int notifyType = watch.getNotifyType() == null ? DomainWatch.NOTIFY_NONE : watch.getNotifyType();
        if (notifyType == DomainWatch.NOTIFY_NONE) {
            return false;
        }
        if (!"DOMAIN_EXPIRING".equals(event.getEventType())) {
            return true;
        }
        if (domainExpiryThresholdDays == null) {
            return false;
        }
        return (domainExpiryThresholdDays == 7
                && (notifyType == DomainWatch.NOTIFY_7_DAYS || notifyType == DomainWatch.NOTIFY_BOTH))
            || (domainExpiryThresholdDays == 30
                && (notifyType == DomainWatch.NOTIFY_30_DAYS || notifyType == DomainWatch.NOTIFY_BOTH));
    }
}
