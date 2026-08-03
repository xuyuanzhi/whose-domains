package info.wesite.web.controller;

import java.time.Duration;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
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
import info.wesite.core.mail.Mail;
import info.wesite.core.mail.MailSender;
import info.wesite.core.service.EmailLoginLinkService;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.Constants;
import info.wesite.core.utils.MagicLinkTokenUtils;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.utils.TokenUtils;
import info.wesite.core.view.ResponseJson;
import jakarta.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/user")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);
    private static final int EMAIL_LOGIN_LINK_COOLDOWN_MINUTES = 2;
    private static final int EMAIL_LOGIN_LINK_DAILY_LIMIT = 10;
    private static final Object[] EMAIL_LOGIN_LOCKS = new Object[64];

    static {
        for (int i = 0; i < EMAIL_LOGIN_LOCKS.length; i++) {
            EMAIL_LOGIN_LOCKS[i] = new Object();
        }
    }

    @Autowired
    private UserService userService;

    @Autowired
    private EmailLoginLinkService emailLoginLinkService;

    @Autowired(required = false)
    private MailSender mailSender;

    @Value("${wesite.public-base-url:https://whose.domains}")
    private String publicBaseUrl;

    @Value("${wesite.auth-cookie-secure:true}")
    private boolean authCookieSecure;

    @PostMapping("/email-login")
    @ResponseBody
    public ResponseJson<Void> requestEmailLogin(@RequestBody User param) {
        String email = param.getEmail() == null ? null : param.getEmail().trim().toLowerCase();
        if (email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            return ResponseJson.failure("Please enter a valid email address.");
        }
        if (mailSender == null) {
            logger.error("Email login requested but MailSender is not configured");
            return ResponseJson.failure("Email sign-in is temporarily unavailable.");
        }

        synchronized (emailLoginLock(email)) {
            Date cooldownStart = DateUtils.addMinutes(new Date(), -EMAIL_LOGIN_LINK_COOLDOWN_MINUTES);
            long recentLinkCount = emailLoginLinkService.count(new QueryWrapper<EmailLoginLink>()
                    .eq("EMAIL", email).ge("CREATE_TIME", cooldownStart));
            if (recentLinkCount > 0) {
                return ResponseJson.failure("Please wait 2 minutes before requesting another sign-in link.");
            }
            Date startOfDay = DateUtils.truncate(new Date(), Calendar.DATE);
            long dailyLinkCount = emailLoginLinkService.count(new QueryWrapper<EmailLoginLink>()
                    .eq("EMAIL", email).ge("CREATE_TIME", startOfDay));
            if (dailyLinkCount >= EMAIL_LOGIN_LINK_DAILY_LIMIT) {
                return ResponseJson.failure("You have reached the daily limit of " + EMAIL_LOGIN_LINK_DAILY_LIMIT
                        + " sign-in links. Please try again tomorrow.");
            }

            String token = MagicLinkTokenUtils.generateToken();
            EmailLoginLink link = new EmailLoginLink();
            link.setId(RandomUtils.generateId());
            link.setEmail(email);
            link.setTokenHash(MagicLinkTokenUtils.hash(token));
            link.setExpiresAt(DateUtils.addMinutes(new Date(), 15));
            link.setCreateBy(email);
            link.setCreateTime(new Date());
            emailLoginLinkService.save(link);

            String url = publicBaseUrl.replaceAll("/$", "") + "/user/verify-email?token=" + token;
            boolean sent = mailSender.send(Mail.of(email, "Your Whose.Domains sign-in link",
                    "<p>Click to sign in to Whose.Domains:</p><p><a href=\"" + url + "\">Sign in securely</a></p>"
                            + "<p>This link expires in 15 minutes and can only be used once.</p>"))
                    .isSuccess();
            if (!sent) {
                emailLoginLinkService.removeById(link.getId());
                return ResponseJson.failure("We could not send that email. Please try again.");
            }
            return ResponseJson.success("Check your inbox for a secure sign-in link.", null);
        }
    }

    private static Object emailLoginLock(String email) {
        return EMAIL_LOGIN_LOCKS[(email.hashCode() & Integer.MAX_VALUE) % EMAIL_LOGIN_LOCKS.length];
    }

    @GetMapping("/verify-email")
    @Transactional
    public String verifyEmail(String token, HttpServletResponse response) {
        if (StringUtils.isBlank(token)) {
            return "redirect:/user/watchlist?login=invalid";
        }
        EmailLoginLink link = emailLoginLinkService.getOne(new QueryWrapper<EmailLoginLink>()
                .eq("TOKEN_HASH", MagicLinkTokenUtils.hash(token)).isNull("CONSUMED_AT"));
        if (link == null || link.getExpiresAt().before(new Date())) {
            return "redirect:/user/watchlist?login=invalid";
        }

        Date consumedAt = new Date();
        boolean consumed = emailLoginLinkService.update(null,
                new UpdateWrapper<EmailLoginLink>().set("CONSUMED_AT", consumedAt).set("UPDATE_TIME", consumedAt)
                        .eq("ID", link.getId()).isNull("CONSUMED_AT").gt("EXPIRES_AT", consumedAt));
        if (!consumed) {
            return "redirect:/user/watchlist?login=invalid";
        }

        User user = userService.getOne(new QueryWrapper<User>().eq("EMAIL", link.getEmail()));
        if (user == null) {
            user = new User();
            user.setId(RandomUtils.generateId());
            user.setEmail(link.getEmail());
            user.setName(link.getEmail().split("@", 2)[0]);
            user.setSecureKey(RandomUtils.generateId());
            user.setCreateBy(link.getEmail());
            user.setCreateTime(new Date());
            user.setStatus(User.STATUS_ACTIVE);
            user.setUserType(User.TYPE_PERSON);
            userService.save(user);
        }
        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookie(TokenUtils.createToken(user, 60 * 24 * 30), Duration.ofDays(30)).toString());
        return "redirect:/user/watchlist?login=success";
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
        response.addHeader(HttpHeaders.SET_COOKIE, authCookie("", Duration.ZERO).toString());
        return ResponseJson.success();
    }

    private ResponseCookie authCookie(String token, Duration maxAge) {
        return ResponseCookie.from(Constants.TOKEN_KEY, token)
                .httpOnly(true).secure(authCookieSecure).sameSite("Lax").path("/").maxAge(maxAge).build();
    }
}
