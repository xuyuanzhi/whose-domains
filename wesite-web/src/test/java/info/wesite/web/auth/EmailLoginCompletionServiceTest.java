package info.wesite.web.auth;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.User;
import info.wesite.core.mapper.UserMapper;
import info.wesite.core.service.UserService;
import info.wesite.web.auth.google.GoogleLoginService;
import info.wesite.web.auth.google.GoogleLoginException;
import info.wesite.web.auth.google.PendingGoogleBinding;

@SuppressWarnings({ "rawtypes", "unchecked" })
class EmailLoginCompletionServiceTest {

    private UserService userService;
    private UserMapper userMapper;
    private GoogleLoginService googleLoginService;
    private EmailLoginCompletionService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        userMapper = mock(UserMapper.class);
        googleLoginService = mock(GoogleLoginService.class);
        service = new EmailLoginCompletionService(userService, userMapper, googleLoginService);
    }

    @Test
    void duplicateEmailCreateReloadsTheWinningActiveUserWithAWriteLock() {
        User winner = activeUser("winner");
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(userService.save(any(User.class))).thenThrow(new DuplicateKeyException("duplicate email"));
        when(userMapper.selectByEmailForUpdate("person@outside.example")).thenReturn(winner);

        User completed = service.complete("person@outside.example", null);

        assertSame(winner, completed);
        InOrder order = inOrder(userService, userMapper);
        order.verify(userService).getOne(any(QueryWrapper.class));
        order.verify(userService).save(any(User.class));
        order.verify(userMapper).selectByEmailForUpdate("person@outside.example");
    }

    @Test
    void createsAStandardActivePersonalUserForANewOrdinaryEmailLogin() {
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(userService.save(any(User.class))).thenReturn(true);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);

        User completed = service.complete("person@outside.example", null);

        verify(userService).save(saved.capture());
        assertSame(saved.getValue(), completed);
        assertEquals("person@outside.example", completed.getEmail());
        assertEquals("person", completed.getName());
        assertEquals("person@outside.example", completed.getCreateBy());
        assertEquals(BaseEntity.STATUS_ACTIVE, completed.getStatus());
        assertEquals(User.TYPE_PERSON, completed.getUserType());
        assertNotNull(completed.getCreateTime());
        assertFalse(completed.getId().isBlank());
        assertFalse(completed.getSecureKey().isBlank());
        verify(googleLoginService, never()).completeConfirmedBinding(any(), any());
    }

    @Test
    void rejectsAnExistingInactiveUser() {
        User inactive = activeUser("inactive");
        inactive.setStatus(BaseEntity.STATUS_INACTIVE);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(inactive);

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.complete("person@outside.example", null));

        assertEquals(GoogleLoginException.Code.INACTIVE_USER, error.code());
        verify(userService, never()).save(any());
    }

    @Test
    void rejectsAnInactiveUserReloadedAfterADuplicateEmailCreate() {
        User winner = activeUser("winner");
        winner.setStatus(BaseEntity.STATUS_INACTIVE);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(userService.save(any(User.class))).thenThrow(new DuplicateKeyException("duplicate email"));
        when(userMapper.selectByEmailForUpdate("person@outside.example")).thenReturn(winner);

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.complete("person@outside.example", null));

        assertEquals(GoogleLoginException.Code.INACTIVE_USER, error.code());
    }

    @Test
    void duplicateEmailCreateStillCompletesTheMatchingPendingGoogleBinding() {
        User winner = activeUser("winner");
        User bound = activeUser("winner");
        bound.setGoogleSub("google-subject");
        PendingGoogleBinding pending = new PendingGoogleBinding(null, "google-subject",
                "person@outside.example", Instant.parse("2030-01-01T00:00:00Z"));
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(userService.save(any(User.class))).thenThrow(new DuplicateKeyException("duplicate email"));
        when(userMapper.selectByEmailForUpdate("person@outside.example")).thenReturn(winner);
        when(googleLoginService.completeConfirmedBinding(winner, pending)).thenReturn(bound);

        User completed = service.complete("person@outside.example", pending);

        assertSame(bound, completed);
    }

    private User activeUser(String id) {
        User user = new User();
        user.setId(id);
        user.setEmail("person@outside.example");
        user.setStatus(BaseEntity.STATUS_ACTIVE);
        return user;
    }
}
