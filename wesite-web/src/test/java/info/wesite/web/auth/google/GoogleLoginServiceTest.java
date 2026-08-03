package info.wesite.web.auth.google;

import static info.wesite.web.auth.google.GoogleLoginException.Code.ACCOUNT_CONFLICT;
import static info.wesite.web.auth.google.GoogleLoginException.Code.EXPIRED_FLOW;
import static info.wesite.web.auth.google.GoogleLoginException.Code.INACTIVE_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.web.auth.EmailLoginRequestResult;
import info.wesite.web.auth.EmailLoginService;

@SuppressWarnings({ "unchecked", "rawtypes" })
class GoogleLoginServiceTest {

    private UserService userService;
    private EmailLoginService emailLoginService;
    private GoogleLoginService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        emailLoginService = mock(EmailLoginService.class);
        service = new GoogleLoginService(userService, emailLoginService);
    }

    @Test
    void signsInTheActiveUserAlreadyOwnedByTheGoogleSubjectBeforeLookingUpEmail() {
        User subjectUser = activeUser("subject-user", "old@example.com", "google-subject");
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(subjectUser);

        GoogleLoginResult result = service.authenticate(gmailIdentity(), null);

        assertEquals(GoogleLoginResult.Status.SIGNED_IN, result.status());
        assertSame(subjectUser, result.user());
        assertNull(result.pendingBinding());
        verify(userService, times(1)).getOne(any(QueryWrapper.class));
        verify(userService, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void rejectsAnInactiveUserOwnedByTheGoogleSubject() {
        User subjectUser = activeUser("subject-user", "person@gmail.com", "google-subject");
        subjectUser.setStatus(BaseEntity.STATUS_INACTIVE);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(subjectUser);

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.authenticate(gmailIdentity(), null));

        assertEquals(INACTIVE_USER, error.code());
        verify(userService, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void bindsTheCurrentJwtUserWhenItsNormalizedEmailMatches() {
        User currentUser = activeUser("current-user", " Person@Gmail.com ", null);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(userService.update(isNull(), any(Wrapper.class))).thenReturn(true);

        GoogleLoginResult result = service.authenticate(gmailIdentity(), currentUser);

        assertSame(currentUser, result.user());
        assertEquals("google-subject", currentUser.getGoogleSub());
        verify(userService, times(1)).getOne(any(QueryWrapper.class));
        verify(userService).update(isNull(), any(Wrapper.class));
    }

    @Test
    void bindsAnExistingGmailUserByNormalizedEmail() {
        User emailUser = activeUser("email-user", "person@gmail.com", null);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null, emailUser);
        when(userService.update(isNull(), any(Wrapper.class))).thenReturn(true);

        GoogleLoginResult result = service.authenticate(gmailIdentity(), null);

        assertSame(emailUser, result.user());
        assertEquals("google-subject", emailUser.getGoogleSub());
        ArgumentCaptor<QueryWrapper<User>> queries = queryCaptor();
        verify(userService, times(2)).getOne(queries.capture());
        QueryWrapper<User> emailQuery = queries.getAllValues().get(1);
        emailQuery.getSqlSegment();
        assertTrue(emailQuery.getParamNameValuePairs().containsValue("person@gmail.com"));
    }

    @Test
    void createsAndSignsInANewGmailUser() {
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(userService.save(any(User.class))).thenReturn(true);
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);

        GoogleLoginResult result = service.authenticate(gmailIdentity(), null);

        verify(userService).save(savedUser.capture());
        User user = savedUser.getValue();
        assertSame(user, result.user());
        assertEquals("person@gmail.com", user.getEmail());
        assertEquals("Person", user.getName());
        assertEquals("google-subject", user.getGoogleSub());
        assertEquals(BaseEntity.STATUS_ACTIVE, user.getStatus());
        assertEquals(User.TYPE_PERSON, user.getUserType());
        assertFalse(user.getId().isBlank());
        assertFalse(user.getSecureKey().isBlank());
    }

    @Test
    void requestsConfirmationForAnExistingThirdPartyEmailUser() {
        User emailUser = activeUser("email-user", "person@outside.example", null);
        GoogleIdentity identity = thirdPartyIdentity();
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null, emailUser);
        when(emailLoginService.request(any(), any())).thenReturn(EmailLoginRequestResult.success("sent"));
        Instant before = Instant.now();

        GoogleLoginResult result = service.authenticate(identity, null);

        assertTrue(result.requiresEmailConfirmation());
        assertEquals("email-user", result.pendingBinding().userId());
        assertEquals("google-subject", result.pendingBinding().subject());
        assertEquals("person@outside.example", result.pendingBinding().email());
        assertTrue(result.pendingBinding().expiresAt().isAfter(before.plus(14, ChronoUnit.MINUTES)));
        assertTrue(result.pendingBinding().expiresAt().isBefore(before.plus(16, ChronoUnit.MINUTES)));
        verify(userService, never()).save(any(User.class));
        verify(userService, never()).update(any(), any(Wrapper.class));
        verify(emailLoginService).request("person@outside.example",
                "/user/watchlist?login=google_bind_required");
    }

    @Test
    void requestsConfirmationWithoutPersistingANewThirdPartyUser() {
        GoogleIdentity thirdPartyIdentity = thirdPartyIdentity();
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(emailLoginService.request(any(), any())).thenReturn(EmailLoginRequestResult.success("sent"));

        GoogleLoginResult result = service.authenticate(thirdPartyIdentity, null);

        assertTrue(result.requiresEmailConfirmation());
        verify(userService, never()).save(any(User.class));
        assertNull(result.pendingBinding().userId());
        verify(emailLoginService).request(
                "person@outside.example",
                "/user/watchlist?login=google_bind_required");
    }

    @Test
    void rejectsAnEmailMatchedUserAlreadyBoundToAnotherSubject() {
        User emailUser = activeUser("email-user", "person@gmail.com", "different-subject");
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null, emailUser);

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.authenticate(gmailIdentity(), null));

        assertEquals(ACCOUNT_CONFLICT, error.code());
        verify(userService, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void reloadsAnIdempotentlyBoundUserWhenTheConditionalUpdateLosesARace() {
        User emailUser = activeUser("email-user", "person@gmail.com", null);
        User racedUser = activeUser("email-user", "person@gmail.com", "google-subject");
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null, emailUser);
        when(userService.update(isNull(), any(Wrapper.class))).thenReturn(false);
        when(userService.getById("email-user")).thenReturn(racedUser);
        ArgumentCaptor<Wrapper<User>> wrapper = wrapperCaptor();

        GoogleLoginResult result = service.authenticate(gmailIdentity(), null);

        assertSame(racedUser, result.user());
        verify(userService).update(isNull(), wrapper.capture());
        UpdateWrapper<User> update = (UpdateWrapper<User>) wrapper.getValue();
        assertTrue(update.getSqlSet().contains("GOOGLE_SUB"));
        assertTrue(update.getSqlSet().contains("UPDATE_TIME"));
        assertTrue(update.getSqlSegment().contains("ID ="));
        assertTrue(update.getSqlSegment().contains("STATUS ="));
        assertTrue(update.getSqlSegment().contains("GOOGLE_SUB IS NULL"));
        assertTrue(update.getParamNameValuePairs().containsValue("email-user"));
        assertTrue(update.getParamNameValuePairs().containsValue(BaseEntity.STATUS_ACTIVE));
    }

    @Test
    void rejectsTheDifferentSubjectThatWinsAConditionalUpdateRace() {
        User emailUser = activeUser("email-user", "person@gmail.com", null);
        User racedUser = activeUser("email-user", "person@gmail.com", "different-subject");
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null, emailUser);
        when(userService.update(isNull(), any(Wrapper.class))).thenReturn(false);
        when(userService.getById("email-user")).thenReturn(racedUser);

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.authenticate(gmailIdentity(), null));

        assertEquals(ACCOUNT_CONFLICT, error.code());
    }

    @Test
    void retriesAConcurrentDuplicateEmailCreateByReloadingSubjectThenEmail() {
        User concurrentUser = activeUser("concurrent-user", "person@gmail.com", null);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null, null, null, concurrentUser);
        when(userService.save(any(User.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(userService.update(isNull(), any(Wrapper.class))).thenReturn(true);

        GoogleLoginResult result = service.authenticate(gmailIdentity(), null);

        assertSame(concurrentUser, result.user());
        verify(userService, times(4)).getOne(any(QueryWrapper.class));
        verify(userService).update(isNull(), any(Wrapper.class));
    }

    @Test
    void doesNotCatchAnUnrelatedDatabaseFailureDuringCreate() {
        DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("offline");
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(userService.save(any(User.class))).thenThrow(databaseFailure);

        DataAccessResourceFailureException error = assertThrows(DataAccessResourceFailureException.class,
                () -> service.authenticate(gmailIdentity(), null));

        assertSame(databaseFailure, error);
        verify(userService, times(2)).getOne(any(QueryWrapper.class));
    }

    @Test
    void rejectsAnExpiredPendingBinding() {
        User emailUser = activeUser("email-user", "person@outside.example", null);
        PendingGoogleBinding pending = new PendingGoogleBinding("email-user", "google-subject",
                "person@outside.example", Instant.now().minusSeconds(1));

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.completeConfirmedBinding(emailUser, pending));

        assertEquals(EXPIRED_FLOW, error.code());
        verify(userService, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void rejectsAConfirmedBindingWhoseNormalizedEmailDoesNotMatch() {
        User emailUser = activeUser("email-user", "other@outside.example", null);
        PendingGoogleBinding pending = livePending("email-user");

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.completeConfirmedBinding(emailUser, pending));

        assertEquals(ACCOUNT_CONFLICT, error.code());
    }

    @Test
    void rejectsAConfirmedBindingWithAMissingPendingEmailAsAMismatch() {
        User emailUser = activeUser("email-user", "person@outside.example", null);
        PendingGoogleBinding pending = new PendingGoogleBinding("email-user", "google-subject", null,
                Instant.now().plus(15, ChronoUnit.MINUTES));

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.completeConfirmedBinding(emailUser, pending));

        assertEquals(ACCOUNT_CONFLICT, error.code());
    }

    @Test
    void rejectsAConfirmedBindingForADifferentExistingUserId() {
        User emailUser = activeUser("different-user", "person@outside.example", null);
        PendingGoogleBinding pending = livePending("email-user");

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.completeConfirmedBinding(emailUser, pending));

        assertEquals(ACCOUNT_CONFLICT, error.code());
    }

    @Test
    void rejectsAConfirmedBindingForAnInactiveUser() {
        User emailUser = activeUser("email-user", "person@outside.example", null);
        emailUser.setStatus(BaseEntity.STATUS_INACTIVE);

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.completeConfirmedBinding(emailUser, livePending("email-user")));

        assertEquals(INACTIVE_USER, error.code());
    }

    @Test
    void rejectsAConfirmedBindingWhenTheUserHasAnotherSubject() {
        User emailUser = activeUser("email-user", "person@outside.example", "different-subject");

        GoogleLoginException error = assertThrows(GoogleLoginException.class,
                () -> service.completeConfirmedBinding(emailUser, livePending("email-user")));

        assertEquals(ACCOUNT_CONFLICT, error.code());
    }

    @Test
    void bindsANewlyCreatedThirdPartyUserAfterEmailConfirmation() {
        User emailUser = activeUser("new-user", " Person@Outside.Example ", null);
        when(userService.update(isNull(), any(Wrapper.class))).thenReturn(true);

        User result = service.completeConfirmedBinding(emailUser, livePending(null));

        assertSame(emailUser, result);
        assertEquals("google-subject", result.getGoogleSub());
        verify(userService).update(isNull(), any(Wrapper.class));
    }

    private GoogleIdentity gmailIdentity() {
        return new GoogleIdentity("google-subject", " Person@Gmail.com ", "Person", true);
    }

    private GoogleIdentity thirdPartyIdentity() {
        return new GoogleIdentity("google-subject", " Person@Outside.Example ", "Person", false);
    }

    private PendingGoogleBinding livePending(String userId) {
        return new PendingGoogleBinding(userId, "google-subject", "person@outside.example",
                Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    private User activeUser(String id, String email, String googleSub) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setGoogleSub(googleSub);
        user.setStatus(BaseEntity.STATUS_ACTIVE);
        return user;
    }

    private ArgumentCaptor<QueryWrapper<User>> queryCaptor() {
        return ArgumentCaptor.forClass((Class) QueryWrapper.class);
    }

    private ArgumentCaptor<Wrapper<User>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) Wrapper.class);
    }
}
