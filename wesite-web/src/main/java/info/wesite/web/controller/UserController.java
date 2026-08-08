package info.wesite.web.controller;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.AccessControl.Level;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.EmailLoginLink;
import info.wesite.core.entity.User;
import info.wesite.core.service.EmailLoginLinkService;
import info.wesite.core.utils.MagicLinkTokenUtils;
import info.wesite.core.view.ResponseJson;
import info.wesite.web.auth.AuthCookieService;
import info.wesite.web.auth.EmailLoginCompletionService;
import info.wesite.web.auth.EmailLoginRequest;
import info.wesite.web.auth.EmailLoginRequestResult;
import info.wesite.web.auth.EmailLoginService;
import info.wesite.web.auth.ReturnTargetService;
import info.wesite.web.auth.google.GoogleLoginException;
import info.wesite.web.auth.google.PendingGoogleBinding;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/user")
public class UserController {

    private static final String GOOGLE_BIND_REQUIRED_REDIRECT = "/user/watchlist?login=google_bind_required";

    @Autowired
    private EmailLoginLinkService emailLoginLinkService;

    @Autowired
    private EmailLoginService emailLoginService;

    @Autowired
    private AuthCookieService authCookieService;

    @Autowired
    private EmailLoginCompletionService emailLoginCompletionService;

    @Autowired
    private ReturnTargetService returnTargets;

    @PostMapping("/email-login")
    @ResponseBody
    public ResponseJson<Void> requestEmailLogin(@RequestBody EmailLoginRequest param) {
        EmailLoginRequestResult result = emailLoginService.request(param.email(), returnTargets.resolve(param.returnTo()));
        return result.success() ? ResponseJson.success(result.message(), null) : ResponseJson.failure(result.message());
    }

    @GetMapping("/verify-email")
    @Transactional
    public String verifyEmail(String token, HttpServletRequest request, HttpServletResponse response) {
        if (StringUtils.isBlank(token)) {
            return "redirect:/user/watchlist?login=invalid";
        }
        EmailLoginLink link = emailLoginLinkService.getOne(new QueryWrapper<EmailLoginLink>()
                .eq("TOKEN_HASH", MagicLinkTokenUtils.hash(token)).isNull("CONSUMED_AT"));
        if (link == null) {
            return "redirect:/user/watchlist?login=invalid";
        }
        if (link.getExpiresAt().before(new Date())) {
            return invalidLinkRedirect(link);
        }

        Date consumedAt = new Date();
        boolean consumed = emailLoginLinkService.update(null,
                new UpdateWrapper<EmailLoginLink>().set("CONSUMED_AT", consumedAt).set("UPDATE_TIME", consumedAt)
                        .eq("ID", link.getId()).isNull("CONSUMED_AT").gt("EXPIRES_AT", consumedAt));
        if (!consumed) {
            return invalidLinkRedirect(link);
        }

        HttpSession session = request.getSession(false);
        PendingGoogleBinding pending = session == null || !GOOGLE_BIND_REQUIRED_REDIRECT.equals(link.getRedirectPath())
                ? null
                : pendingBinding(session.getAttribute(PendingGoogleBinding.SESSION_KEY));
        User user = emailLoginCompletionService.complete(link.getEmail(), pending);

        String cookie = authCookieService.create(user).toString();
        completeLoginAfterCommit(response, cookie, pending == null ? null : session);
        if (pending != null) {
            return "redirect:" + returnTargets.resolve(pending.returnTo());
        }
        return "redirect:" + safeRedirectPath(link.getRedirectPath());
    }

    @ExceptionHandler(GoogleLoginException.class)
    public String googleBindingFailure(GoogleLoginException exception, HttpServletRequest request) {
        String loginResult = switch (exception.code()) {
        case INVALID_IDENTITY -> "google_invalid";
        case UNVERIFIED_EMAIL -> "google_unverified";
        case ACCOUNT_CONFLICT -> "google_conflict";
        case INACTIVE_USER -> "google_inactive";
        case EMAIL_CONFIRMATION_UNAVAILABLE -> "google_email_unavailable";
        case EXPIRED_FLOW -> "google_expired";
        };
        HttpSession session = request.getSession(false);
        PendingGoogleBinding pending = session == null
                ? null
                : pendingBinding(session.getAttribute(PendingGoogleBinding.SESSION_KEY));
        if (pending != null && returnTargets.isDomainMonitorContinuation(pending.returnTo())) {
            return "redirect:" + returnTargets.monitorLoginResultUrl(pending.returnTo(), loginResult);
        }
        return "redirect:/user/watchlist?login=" + loginResult;
    }

    private PendingGoogleBinding pendingBinding(Object value) {
        return value instanceof PendingGoogleBinding pending ? pending : null;
    }

    private String safeRedirectPath(String redirectPath) {
        return GOOGLE_BIND_REQUIRED_REDIRECT.equals(redirectPath) ? GOOGLE_BIND_REQUIRED_REDIRECT
                : returnTargets.resolve(redirectPath);
    }

    private String invalidLinkRedirect(EmailLoginLink link) {
        return returnTargets.validated(link.getRedirectPath()).isPresent()
                ? "redirect:" + returnTargets.loginFailureUrl("invalid", link.getRedirectPath())
                : "redirect:/user/watchlist?login=invalid";
    }

    private void completeLoginAfterCommit(HttpServletResponse response, String cookie, HttpSession session) {
        Runnable complete = () -> {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie);
            if (session != null) {
                session.invalidate();
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    complete.run();
                }
            });
        } else {
            complete.run();
        }
    }

    @AccessControl(level = Level.SESSION)
    @GetMapping("/session")
    @ResponseBody
    public ResponseJson<Map<String, String>> session() {
        Map<String, String> json = new HashMap<>();
        json.put("name", UserHolder.get().getName());
        return ResponseJson.success(json);
    }

    @AccessControl(level = Level.SESSION)
    @PostMapping("/logout")
    @ResponseBody
    public ResponseJson<Map<String, Object>> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clear().toString());
        return ResponseJson.success();
    }
}
