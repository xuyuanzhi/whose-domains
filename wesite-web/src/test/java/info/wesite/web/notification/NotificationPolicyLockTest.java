package info.wesite.web.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.entity.User;
import info.wesite.core.mapper.NotificationPreferenceMapper;
import info.wesite.core.mapper.UserMapper;

class NotificationPolicyLockTest {

    @Test
    void missingPreferenceStillLocksTheStableUserMutexBeforeReturningDefaults() {
        UserMapper users = mock(UserMapper.class);
        NotificationPreferenceMapper preferences = mock(NotificationPreferenceMapper.class);
        User user = new User();
        user.setId("user-1");
        when(users.selectByIdForUpdate("user-1")).thenReturn(user);
        when(preferences.selectByUserIdForUpdate("user-1")).thenReturn(null);

        NotificationPreference current = new NotificationPolicyLock(users, preferences)
            .lockCurrent("user-1");

        assertEquals(NotificationPreference.MODE_DAILY, current.getEmailMode());
        assertEquals("user-1", current.getUserId());
        InOrder order = inOrder(users, preferences);
        order.verify(users).selectByIdForUpdate("user-1");
        order.verify(preferences).selectByUserIdForUpdate("user-1");
    }

    @Test
    void existingPreferenceIsReadWithTheSameUserThenPreferenceLockOrder() {
        UserMapper users = mock(UserMapper.class);
        NotificationPreferenceMapper preferences = mock(NotificationPreferenceMapper.class);
        User user = new User();
        user.setId("user-1");
        NotificationPreference stored = NotificationPreference.defaultsFor("user-1");
        stored.setEmailMode(NotificationPreference.MODE_IN_APP_ONLY);
        when(users.selectByIdForUpdate("user-1")).thenReturn(user);
        when(preferences.selectByUserIdForUpdate("user-1")).thenReturn(stored);

        NotificationPreference current = new NotificationPolicyLock(users, preferences)
            .lockCurrent("user-1");

        assertSame(stored, current);
        InOrder order = inOrder(users, preferences);
        order.verify(users).selectByIdForUpdate("user-1");
        order.verify(preferences).selectByUserIdForUpdate("user-1");
    }
}
