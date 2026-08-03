package info.wesite.web.auth;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import info.wesite.core.entity.EmailLoginLink;
import info.wesite.core.mail.Mail;
import info.wesite.core.mail.MailSender;
import info.wesite.core.service.EmailLoginLinkService;
import info.wesite.core.utils.MagicLinkTokenUtils;
import info.wesite.core.utils.RandomUtils;

@Service
public class EmailLoginService {

    private static final Logger logger = LoggerFactory.getLogger(EmailLoginService.class);
    private static final int EMAIL_LOGIN_LINK_COOLDOWN_MINUTES = 2;
    private static final int EMAIL_LOGIN_LINK_DAILY_LIMIT = 10;
    private static final Set<String> ALLOWED_REDIRECT_PATHS = Set.of(
            "/user/watchlist?login=success", "/user/watchlist?login=google_bind_required");
    private static final Object[] EMAIL_LOGIN_LOCKS = new Object[64];

    static {
        for (int i = 0; i < EMAIL_LOGIN_LOCKS.length; i++) {
            EMAIL_LOGIN_LOCKS[i] = new Object();
        }
    }

    @Autowired
    private EmailLoginLinkService emailLoginLinkService;

    @Autowired(required = false)
    private MailSender mailSender;

    @Value("${wesite.public-base-url:https://whose.domains}")
    private String publicBaseUrl;

    public EmailLoginRequestResult request(String rawEmail, String redirectPath) {
        if (!ALLOWED_REDIRECT_PATHS.contains(redirectPath)) {
            throw new IllegalArgumentException("Unsupported email login redirect path");
        }

        String email = rawEmail == null ? null : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            return EmailLoginRequestResult.failure("Please enter a valid email address.");
        }
        if (mailSender == null) {
            logger.error("Email login requested but MailSender is not configured");
            return EmailLoginRequestResult.failure("Email sign-in is temporarily unavailable.");
        }

        synchronized (emailLoginLock(email)) {
            Date cooldownStart = DateUtils.addMinutes(new Date(), -EMAIL_LOGIN_LINK_COOLDOWN_MINUTES);
            long recentLinkCount = emailLoginLinkService.count(new QueryWrapper<EmailLoginLink>()
                    .eq("EMAIL", email).ge("CREATE_TIME", cooldownStart));
            if (recentLinkCount > 0) {
                return EmailLoginRequestResult.failure("Please wait 2 minutes before requesting another sign-in link.");
            }
            Date startOfDay = DateUtils.truncate(new Date(), Calendar.DATE);
            long dailyLinkCount = emailLoginLinkService.count(new QueryWrapper<EmailLoginLink>()
                    .eq("EMAIL", email).ge("CREATE_TIME", startOfDay));
            if (dailyLinkCount >= EMAIL_LOGIN_LINK_DAILY_LIMIT) {
                return EmailLoginRequestResult.failure("You have reached the daily limit of " + EMAIL_LOGIN_LINK_DAILY_LIMIT
                        + " sign-in links. Please try again tomorrow.");
            }

            String token = MagicLinkTokenUtils.generateToken();
            EmailLoginLink link = new EmailLoginLink();
            link.setId(RandomUtils.generateId());
            link.setEmail(email);
            link.setTokenHash(MagicLinkTokenUtils.hash(token));
            link.setExpiresAt(DateUtils.addMinutes(new Date(), 15));
            link.setRedirectPath(redirectPath);
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
                return EmailLoginRequestResult.failure("We could not send that email. Please try again.");
            }
            return EmailLoginRequestResult.success("Check your inbox for a secure sign-in link.");
        }
    }

    private static Object emailLoginLock(String email) {
        return EMAIL_LOGIN_LOCKS[(email.hashCode() & Integer.MAX_VALUE) % EMAIL_LOGIN_LOCKS.length];
    }
}
