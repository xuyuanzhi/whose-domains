package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.EmailLoginLink;
import info.wesite.core.entity.User;
import info.wesite.core.service.EmailLoginLinkService;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.MagicLinkTokenUtils;
import info.wesite.web.auth.AuthCookieService;
import info.wesite.web.auth.google.GoogleLoginException;
import info.wesite.web.auth.google.GoogleLoginService;
import info.wesite.web.auth.google.PendingGoogleBinding;

@SuppressWarnings({ "unchecked", "rawtypes" })
class UserControllerGoogleBindingTest {

    private static final String TOKEN = "email-login-token";
    private static final String EMAIL = "person@outside.example";

    private EmailLoginLinkService emailLoginLinkService;
    private UserService userService;
    private GoogleLoginService googleLoginService;
    private AuthCookieService authCookieService;
    private MockMvc mockMvc;
    private ResponseCookie authCookie;

    @BeforeEach
    void setUp() {
        emailLoginLinkService = mock(EmailLoginLinkService.class);
        userService = mock(UserService.class);
        googleLoginService = mock(GoogleLoginService.class);
        authCookieService = mock(AuthCookieService.class);

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "emailLoginLinkService", emailLoginLinkService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "googleLoginService", googleLoginService);
        ReflectionTestUtils.setField(controller, "authCookieService", authCookieService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        authCookie = ResponseCookie.from("TOKEN", "jwt")
                .httpOnly(true).secure(true).sameSite("Lax").path("/").build();
    }

    @Test
    void completesAnExistingUsersPendingBindingAfterAtomicallyConsumingTheLink() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = livePending("email-user");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(user);
        when(googleLoginService.completeConfirmedBinding(user, pending)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=success"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        ArgumentCaptor<UpdateWrapper<EmailLoginLink>> updateCaptor = updateCaptor();
        InOrder order = inOrder(emailLoginLinkService, googleLoginService, authCookieService);
        order.verify(emailLoginLinkService).update(isNull(), updateCaptor.capture());
        order.verify(googleLoginService).completeConfirmedBinding(user, pending);
        order.verify(authCookieService).create(user);
        assertAtomicConsumption(updateCaptor.getValue(), link.getId());
        assertTrue(session.isInvalid());
    }

    @Test
    void createsAndBindsAStandardActivePersonalUserBeforeIssuingTheCookie() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        PendingGoogleBinding pending = livePending(null);
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(null);
        when(userService.save(any(User.class))).thenReturn(true);
        when(googleLoginService.completeConfirmedBinding(any(User.class), any(PendingGoogleBinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(authCookieService.create(any(User.class))).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=success"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        InOrder order = inOrder(userService, googleLoginService, authCookieService);
        order.verify(userService).save(userCaptor.capture());
        User created = userCaptor.getValue();
        order.verify(googleLoginService).completeConfirmedBinding(created, pending);
        order.verify(authCookieService).create(created);
        assertEquals(EMAIL, created.getEmail());
        assertEquals("person", created.getName());
        assertEquals(EMAIL, created.getCreateBy());
        assertEquals(BaseEntity.STATUS_ACTIVE, created.getStatus());
        assertEquals(User.TYPE_PERSON, created.getUserType());
        assertNotNull(created.getCreateTime());
        assertFalse(created.getId().isBlank());
        assertFalse(created.getSecureKey().isBlank());
        assertTrue(session.isInvalid());
    }

    @Test
    void issuesTheCookieAndInvalidatesTheSessionOnlyAfterTransactionCommit() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = livePending("email-user");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(user);
        when(googleLoginService.completeConfirmedBinding(user, pending)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        TransactionSynchronizationManager.initSynchronization();
        try {
            MvcResult result = mockMvc.perform(get("/user/verify-email").param("token", TOKEN).session(session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                    .andReturn();
            assertFalse(session.isInvalid());
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

            assertEquals(authCookie.toString(), result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
            assertTrue(session.isInvalid());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void crossBrowserConfirmationLogsInWithoutBindingAndKeepsTheFixedRetryRedirect() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        stubValidLink(link);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=google_bind_required"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        verify(googleLoginService, never()).completeConfirmedBinding(any(), any());
    }

    @Test
    void unrecognizedStoredRedirectFallsBackToTheFixedSuccessPath() throws Exception {
        EmailLoginLink link = validLink("https://attacker.example/steal");
        User user = activeUser("email-user");
        stubValidLink(link);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=success"));

        verify(googleLoginService, never()).completeConfirmedBinding(any(), any());
    }

    @Test
    void bindingConflictDoesNotIssueACookieAndUsesTheAllowlistedErrorRedirect() throws Exception {
        assertBindingFailure(GoogleLoginException.Code.ACCOUNT_CONFLICT,
                "/user/watchlist?login=google_conflict");
    }

    @Test
    void expiredBindingDoesNotIssueACookieAndUsesTheAllowlistedErrorRedirect() throws Exception {
        assertBindingFailure(GoogleLoginException.Code.EXPIRED_FLOW,
                "/user/watchlist?login=google_expired");
    }

    private void assertBindingFailure(GoogleLoginException.Code code, String redirect) throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = livePending("email-user");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(userService.getOne(any(QueryWrapper.class))).thenReturn(user);
        when(googleLoginService.completeConfirmedBinding(user, pending)).thenThrow(new GoogleLoginException(code));
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(redirect))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authCookieService, never()).create(any());
    }

    private EmailLoginLink validLink(String redirectPath) {
        EmailLoginLink link = new EmailLoginLink();
        link.setId("link-id");
        link.setEmail(EMAIL);
        link.setTokenHash(MagicLinkTokenUtils.hash(TOKEN));
        link.setExpiresAt(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)));
        link.setRedirectPath(redirectPath);
        return link;
    }

    private void stubValidLink(EmailLoginLink link) {
        when(emailLoginLinkService.getOne(any(QueryWrapper.class))).thenReturn(link);
        when(emailLoginLinkService.update(isNull(), any(UpdateWrapper.class))).thenReturn(true);
    }

    private User activeUser(String id) {
        User user = new User();
        user.setId(id);
        user.setEmail(EMAIL);
        user.setStatus(BaseEntity.STATUS_ACTIVE);
        return user;
    }

    private PendingGoogleBinding livePending(String userId) {
        return new PendingGoogleBinding(userId, "google-subject", EMAIL,
                Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    private MockHttpSession sessionWith(PendingGoogleBinding pending) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingGoogleBinding.SESSION_KEY, pending);
        return session;
    }

    private void assertAtomicConsumption(UpdateWrapper<EmailLoginLink> update, String linkId) {
        assertTrue(update.getSqlSet().contains("CONSUMED_AT"), update.getSqlSet());
        assertTrue(update.getSqlSet().contains("UPDATE_TIME"), update.getSqlSet());
        assertTrue(update.getSqlSegment().contains("ID ="), update.getSqlSegment());
        assertTrue(update.getSqlSegment().contains("CONSUMED_AT IS NULL"), update.getSqlSegment());
        assertTrue(update.getSqlSegment().contains("EXPIRES_AT >"), update.getSqlSegment());
        assertTrue(update.getParamNameValuePairs().containsValue(linkId),
                update.getParamNameValuePairs().toString());
    }

    private ArgumentCaptor<UpdateWrapper<EmailLoginLink>> updateCaptor() {
        return ArgumentCaptor.forClass((Class) UpdateWrapper.class);
    }
}
