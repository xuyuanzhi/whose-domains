package info.wesite.web.auth.google;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.web.auth.EmailLoginRequestResult;
import info.wesite.web.auth.EmailLoginService;
import info.wesite.web.auth.google.GoogleLoginException.Code;

@Service
public class GoogleLoginService {

    private static final String BINDING_REDIRECT = "/user/watchlist?login=google_bind_required";

    private final UserService userService;
    private final EmailLoginService emailLoginService;

    public GoogleLoginService(UserService userService, EmailLoginService emailLoginService) {
        this.userService = userService;
        this.emailLoginService = emailLoginService;
    }

    @Transactional
    public GoogleLoginResult authenticate(GoogleIdentity identity, User currentUser) {
        validateIdentity(identity);
        String subject = identity.subject();
        String email = normalizeEmail(identity.email());

        User subjectUser = findBySubject(subject);
        if (subjectUser != null) {
            requireActive(subjectUser);
            return GoogleLoginResult.signedIn(subjectUser);
        }

        if (currentUser != null && email.equals(normalizeEmail(currentUser.getEmail()))) {
            return GoogleLoginResult.signedIn(bindSubject(currentUser, subject));
        }

        User emailUser = findByEmail(email);
        if (emailUser != null) {
            requireActive(emailUser);
            if (identity.googleManagedEmail()) {
                return GoogleLoginResult.signedIn(bindSubject(emailUser, subject));
            }
            requireNoSubjectConflict(emailUser, subject);
            return requestEmailConfirmation(emailUser.getId(), subject, email);
        }

        if (!identity.googleManagedEmail()) {
            return requestEmailConfirmation(null, subject, email);
        }

        return GoogleLoginResult.signedIn(createAuthoritativeUser(identity, subject, email));
    }

    @Transactional
    public User completeConfirmedBinding(User emailUser, PendingGoogleBinding pending) {
        if (pending == null || pending.expiresAt() == null || !pending.expiresAt().isAfter(Instant.now())) {
            throw new GoogleLoginException(Code.EXPIRED_FLOW);
        }
        String pendingEmail = normalizeEmail(pending.email());
        if (emailUser == null || pendingEmail == null || !pendingEmail.equals(normalizeEmail(emailUser.getEmail()))) {
            throw new GoogleLoginException(Code.ACCOUNT_CONFLICT);
        }
        if (pending.userId() != null && !pending.userId().equals(emailUser.getId())) {
            throw new GoogleLoginException(Code.ACCOUNT_CONFLICT);
        }
        requireActive(emailUser);
        if (pending.subject() == null || pending.subject().isBlank()) {
            throw new GoogleLoginException(Code.INVALID_IDENTITY);
        }
        return bindSubject(emailUser, pending.subject());
    }

    private User createAuthoritativeUser(GoogleIdentity identity, String subject, String email) {
        User user = new User();
        user.setId(RandomUtils.generateId());
        user.setEmail(email);
        user.setName(displayName(identity, email));
        user.setGoogleSub(subject);
        user.setSecureKey(RandomUtils.generateId());
        user.setCreateBy(email);
        user.setCreateTime(new Date());
        user.setStatus(BaseEntity.STATUS_ACTIVE);
        user.setUserType(User.TYPE_PERSON);
        try {
            if (!userService.save(user)) {
                throw new IllegalStateException("Google user could not be created");
            }
            return user;
        } catch (DuplicateKeyException duplicate) {
            User subjectUser = findBySubject(subject);
            if (subjectUser != null) {
                requireActive(subjectUser);
                return subjectUser;
            }
            User emailUser = findByEmail(email);
            if (emailUser != null) {
                requireActive(emailUser);
                return bindSubject(emailUser, subject);
            }
            throw duplicate;
        }
    }

    private User bindSubject(User user, String subject) {
        requireActive(user);
        if (user.getGoogleSub() != null) {
            requireNoSubjectConflict(user, subject);
            return user;
        }

        Date updatedAt = new Date();
        boolean updated = userService.update(null, new UpdateWrapper<User>()
                .set("GOOGLE_SUB", subject)
                .set("UPDATE_TIME", updatedAt)
                .eq("ID", user.getId())
                .eq("STATUS", BaseEntity.STATUS_ACTIVE)
                .isNull("GOOGLE_SUB"));
        if (updated) {
            user.setGoogleSub(subject);
            user.setUpdateTime(updatedAt);
            return user;
        }

        User reloaded = userService.getById(user.getId());
        if (reloaded == null) {
            throw new GoogleLoginException(Code.ACCOUNT_CONFLICT);
        }
        requireActive(reloaded);
        requireNoSubjectConflict(reloaded, subject);
        if (reloaded.getGoogleSub() == null) {
            throw new GoogleLoginException(Code.ACCOUNT_CONFLICT);
        }
        return reloaded;
    }

    private GoogleLoginResult requestEmailConfirmation(String userId, String subject, String email) {
        EmailLoginRequestResult request = emailLoginService.request(email, BINDING_REDIRECT);
        if (request == null || !request.success()) {
            throw new GoogleLoginException(Code.EMAIL_CONFIRMATION_UNAVAILABLE);
        }
        PendingGoogleBinding pending = new PendingGoogleBinding(userId, subject, email,
                Instant.now().plus(15, ChronoUnit.MINUTES));
        return GoogleLoginResult.pending(pending);
    }

    private User findBySubject(String subject) {
        return userService.getOne(new QueryWrapper<User>().eq("GOOGLE_SUB", subject));
    }

    private User findByEmail(String email) {
        return userService.getOne(new QueryWrapper<User>().eq("EMAIL", email));
    }

    private void requireActive(User user) {
        if (!Integer.valueOf(BaseEntity.STATUS_ACTIVE).equals(user.getStatus())) {
            throw new GoogleLoginException(Code.INACTIVE_USER);
        }
    }

    private void requireNoSubjectConflict(User user, String subject) {
        if (user.getGoogleSub() != null && !user.getGoogleSub().equals(subject)) {
            throw new GoogleLoginException(Code.ACCOUNT_CONFLICT);
        }
    }

    private void validateIdentity(GoogleIdentity identity) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()
                || normalizeEmail(identity.email()) == null) {
            throw new GoogleLoginException(Code.INVALID_IDENTITY);
        }
    }

    private String displayName(GoogleIdentity identity, String email) {
        if (identity.displayName() != null && !identity.displayName().isBlank()) {
            return identity.displayName().trim();
        }
        return email.split("@", 2)[0];
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
