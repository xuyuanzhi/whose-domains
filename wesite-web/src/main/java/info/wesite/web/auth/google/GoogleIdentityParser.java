package info.wesite.web.auth.google;

import java.util.Locale;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public class GoogleIdentityParser {

    public GoogleIdentity parse(OidcUser user) {
        String subject = trimmed(user.getSubject());
        String email = normalized(user.getEmail());
        if (subject == null || email == null) {
            throw new GoogleLoginException(GoogleLoginException.Code.INVALID_IDENTITY);
        }
        if (!user.getEmailVerified()) {
            throw new GoogleLoginException(GoogleLoginException.Code.UNVERIFIED_EMAIL);
        }

        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            throw new GoogleLoginException(GoogleLoginException.Code.INVALID_IDENTITY);
        }
        String localPart = email.substring(0, at);
        String emailDomain = email.substring(at + 1);
        String hostedDomain = normalized(user.getClaimAsString("hd"));
        String fullName = trimmed(user.getFullName());

        return new GoogleIdentity(subject, email, fullName == null ? localPart : fullName,
                "gmail.com".equals(emailDomain) || emailDomain.equals(hostedDomain));
    }

    private String normalized(String value) {
        String trimmed = trimmed(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
