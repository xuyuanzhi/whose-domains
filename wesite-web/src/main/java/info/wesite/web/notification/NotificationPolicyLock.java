package info.wesite.web.notification;

import java.util.Objects;

import org.springframework.stereotype.Service;

import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.entity.User;
import info.wesite.core.mapper.NotificationPreferenceMapper;
import info.wesite.core.mapper.UserMapper;

/** Stable per-user mutex and current preference locking read. */
@Service
public class NotificationPolicyLock {

    private final UserMapper userMapper;
    private final NotificationPreferenceMapper preferenceMapper;

    public NotificationPolicyLock(UserMapper userMapper, NotificationPreferenceMapper preferenceMapper) {
        this.userMapper = Objects.requireNonNull(userMapper, "userMapper");
        this.preferenceMapper = Objects.requireNonNull(preferenceMapper, "preferenceMapper");
    }

    public User lockUser(String userId) {
        User user = userMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw new IllegalStateException("Notification policy user mutex is unavailable");
        }
        return user;
    }

    public NotificationPreference lockCurrent(String userId) {
        lockUser(userId);
        NotificationPreference preference = preferenceMapper.selectByUserIdForUpdate(userId);
        return preference == null ? NotificationPreference.defaultsFor(userId) : preference;
    }
}
