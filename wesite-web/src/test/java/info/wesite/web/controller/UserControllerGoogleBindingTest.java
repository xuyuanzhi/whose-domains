package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.EmailLoginLink;
import info.wesite.core.entity.User;
import info.wesite.core.service.EmailLoginLinkService;
import info.wesite.core.utils.MagicLinkTokenUtils;
import info.wesite.web.auth.AuthCookieService;
import info.wesite.web.auth.EmailLoginCompletionService;
import info.wesite.web.auth.ReturnTargetService;
import info.wesite.web.auth.google.GoogleLoginException;
import info.wesite.web.auth.google.PendingGoogleBinding;

@SuppressWarnings({ "unchecked", "rawtypes" })
class UserControllerGoogleBindingTest {

    private static final String TOKEN = "email-login-token";
    private static final String EMAIL = "person@outside.example";

    private EmailLoginLinkService emailLoginLinkService;
    private AuthCookieService authCookieService;
    private EmailLoginCompletionService emailLoginCompletionService;
    private UserController controller;
    private MockMvc mockMvc;
    private ResponseCookie authCookie;

    @BeforeEach
    void setUp() {
        emailLoginLinkService = mock(EmailLoginLinkService.class);
        authCookieService = mock(AuthCookieService.class);
        emailLoginCompletionService = mock(EmailLoginCompletionService.class);

        controller = new UserController();
        ReflectionTestUtils.setField(controller, "emailLoginLinkService", emailLoginLinkService);
        ReflectionTestUtils.setField(controller, "authCookieService", authCookieService);
        ReflectionTestUtils.setField(controller, "emailLoginCompletionService", emailLoginCompletionService);
        ReflectionTestUtils.setField(controller, "returnTargets", new ReturnTargetService());
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
        when(emailLoginCompletionService.complete(EMAIL, pending)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=success"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        ArgumentCaptor<UpdateWrapper<EmailLoginLink>> updateCaptor = updateCaptor();
        InOrder order = inOrder(emailLoginLinkService, emailLoginCompletionService, authCookieService);
        order.verify(emailLoginLinkService).update(isNull(), updateCaptor.capture());
        order.verify(emailLoginCompletionService).complete(EMAIL, pending);
        order.verify(authCookieService).create(user);
        assertAtomicConsumption(updateCaptor.getValue(), link.getId());
        assertTrue(session.isInvalid());
    }

    @Test
    void delegatesNewUserCreationAndBindingBeforeIssuingTheCookie() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        PendingGoogleBinding pending = livePending(null);
        MockHttpSession session = sessionWith(pending);
        User created = activeUser("created-user");
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, pending)).thenReturn(created);
        when(authCookieService.create(created)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=success"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        InOrder order = inOrder(emailLoginCompletionService, authCookieService);
        order.verify(emailLoginCompletionService).complete(EMAIL, pending);
        order.verify(authCookieService).create(created);
        assertTrue(session.isInvalid());
    }

    @Test
    void issuesTheCookieAndInvalidatesTheSessionOnlyAfterTransactionCommit() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = livePending("email-user");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, pending)).thenReturn(user);
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
    void transactionalMvcCommitsBeforeCompletingTheLoginResponse() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = new PendingGoogleBinding("email-user", "google-subject", EMAIL,
                Instant.now().plus(15, ChronoUnit.MINUTES), "/user/api-keys");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, pending)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        transactionalMockMvc(transactions)
                .perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/api-keys"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        assertTrue(session.isInvalid());
    }

    @Test
    void transactionalMvcRollsBackBindingFailureWithoutCompletingTheLoginResponse() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = livePending("email-user");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, pending))
                .thenThrow(new GoogleLoginException(GoogleLoginException.Code.ACCOUNT_CONFLICT));
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        transactionalMockMvc(transactions)
                .perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=google_conflict"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        assertFalse(session.isInvalid());
        assertSame(pending, session.getAttribute(PendingGoogleBinding.SESSION_KEY));
        verify(authCookieService, never()).create(any());
    }

    @Test
    void transactionalMvcDoesNotRunRegisteredCompletionAfterRollbackOnly() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = livePending("email-user");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, pending)).thenAnswer(invocation -> {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return user;
        });
        when(authCookieService.create(user)).thenReturn(authCookie);
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        transactionalMockMvc(transactions)
                .perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=success"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        assertFalse(session.isInvalid());
        assertSame(pending, session.getAttribute(PendingGoogleBinding.SESSION_KEY));
    }

    @Test
    void crossBrowserConfirmationLogsInWithoutBindingAndKeepsTheFixedRetryRedirect() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, null)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=google_bind_required"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        verify(emailLoginCompletionService).complete(EMAIL, null);
    }

    @Test
    void crossBrowserGoogleConfirmationRejectsAnInactiveUserWithoutIssuingACookie() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required");
        User user = activeUser("email-user");
        user.setStatus(BaseEntity.STATUS_INACTIVE);
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, null))
                .thenThrow(new GoogleLoginException(GoogleLoginException.Code.INACTIVE_USER));
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        transactionalMockMvc(transactions).perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=google_inactive"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
        verify(authCookieService, never()).create(any());
        verify(emailLoginCompletionService).complete(EMAIL, null);
    }

    @Test
    void ordinaryEmailLoginIgnoresAndPreservesAResidualPendingBinding() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=success");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = livePending("email-user");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, null)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=success"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        verify(emailLoginCompletionService).complete(EMAIL, null);
        assertFalse(session.isInvalid());
        assertSame(pending, session.getAttribute(PendingGoogleBinding.SESSION_KEY));
    }

    @Test
    void ordinaryEmailLoginReturnsToItsPersistedSafeTarget() throws Exception {
        EmailLoginLink link = validLink("/user/api-keys");
        User user = activeUser("email-user");
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, null)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/api-keys"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));
    }

    @Test
    void expiredEmailLoginReturnsToLoginWithItsPersistedSafeTarget() throws Exception {
        EmailLoginLink link = validLink("/user/api-keys");
        link.setExpiresAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
        when(emailLoginLinkService.getOne(any(QueryWrapper.class))).thenReturn(link);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?login=invalid&returnTo=%2Fuser%2Fapi-keys"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(emailLoginCompletionService, never()).complete(any(), any());
    }

    @Test
    void consumeRaceReturnsToLoginWithItsPersistedSafeTarget() throws Exception {
        EmailLoginLink link = validLink("/user/api-keys");
        when(emailLoginLinkService.getOne(any(QueryWrapper.class))).thenReturn(link);
        when(emailLoginLinkService.update(isNull(), any(UpdateWrapper.class))).thenReturn(false);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?login=invalid&returnTo=%2Fuser%2Fapi-keys"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(emailLoginCompletionService, never()).complete(any(), any());
    }

    @Test
    void nonexistentEmailLoginKeepsTheFixedInvalidLinkFallback() throws Exception {
        mockMvc.perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=invalid"));
    }

    @Test
    void blankEmailLoginTokenKeepsTheFixedInvalidLinkFallback() throws Exception {
        mockMvc.perform(get("/user/verify-email").param("token", " "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=invalid"));
    }

    @Test
    void nearMatchBindingRedirectDoesNotUseAResidualPendingBinding() throws Exception {
        EmailLoginLink link = validLink("/user/watchlist?login=google_bind_required&unexpected=true");
        User user = activeUser("email-user");
        PendingGoogleBinding pending = livePending("email-user");
        MockHttpSession session = sessionWith(pending);
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, null)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=google_bind_required&unexpected=true"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, authCookie.toString()));

        verify(emailLoginCompletionService).complete(EMAIL, null);
        assertFalse(session.isInvalid());
        assertSame(pending, session.getAttribute(PendingGoogleBinding.SESSION_KEY));
    }

    @Test
    void unrecognizedStoredRedirectFallsBackToTheFixedSuccessPath() throws Exception {
        EmailLoginLink link = validLink("https://attacker.example/steal");
        User user = activeUser("email-user");
        stubValidLink(link);
        when(emailLoginCompletionService.complete(EMAIL, null)).thenReturn(user);
        when(authCookieService.create(user)).thenReturn(authCookie);

        mockMvc.perform(get("/user/verify-email").param("token", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/watchlist?login=success"));

        verify(emailLoginCompletionService).complete(EMAIL, null);
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
        when(emailLoginCompletionService.complete(EMAIL, pending)).thenThrow(new GoogleLoginException(code));
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

    private MockMvc transactionalMockMvc(RecordingTransactionManager transactions) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(controller);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        return MockMvcBuilders.standaloneSetup(proxyFactory.getProxy()).build();
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

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
