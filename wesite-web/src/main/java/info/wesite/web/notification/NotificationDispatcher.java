package info.wesite.web.notification;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.entity.User;
import info.wesite.core.entity.UserNotification;
import info.wesite.core.service.NotificationPreferenceService;
import info.wesite.core.service.UserNotificationService;
import info.wesite.core.service.UserService;

/**
 * Applies notification preferences by persisting a delivery route. It never
 * sends mail; scheduled delivery is deliberately isolated from this policy.
 */
@Service
public class NotificationDispatcher {

    private final NotificationPreferenceService preferenceService;
    private final UserService userService;
    private final UserNotificationService notificationService;
    private final NotificationPreferenceResolver resolver;

    @Autowired
    public NotificationDispatcher(
        NotificationPreferenceService preferenceService,
        UserService userService,
        UserNotificationService notificationService) {
        this(preferenceService, userService, notificationService, new NotificationPreferenceResolver());
    }

    NotificationDispatcher(
        NotificationPreferenceService preferenceService,
        UserService userService,
        UserNotificationService notificationService,
        NotificationPreferenceResolver resolver) {
        this.preferenceService = Objects.requireNonNull(preferenceService, "preferenceService");
        this.userService = Objects.requireNonNull(userService, "userService");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Transactional
    public NotificationDispatchDecision dispatch(MonitorEvent event, UserNotification notification) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(notification, "notification");

        String userId = notification.getUserId();
        NotificationPreference preference = StringUtils.isBlank(userId)
            ? null
            : preferenceService.getOne(Wrappers.<NotificationPreference>lambdaQuery()
                .eq(NotificationPreference::getUserId, userId));
        User user = StringUtils.isBlank(userId) ? null : userService.getById(userId);
        boolean hasEmail = user != null && StringUtils.isNotBlank(user.getEmail());

        NotificationDispatchDecision decision = resolver.resolve(event, preference, hasEmail);
        notification.setEmailState(decision.name());
        if (!notificationService.updateById(notification)) {
            throw new IllegalStateException("Failed to persist notification delivery state");
        }
        return decision;
    }
}
