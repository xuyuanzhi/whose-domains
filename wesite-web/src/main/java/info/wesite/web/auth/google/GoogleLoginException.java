package info.wesite.web.auth.google;

public class GoogleLoginException extends RuntimeException {

    public enum Code {
        INVALID_IDENTITY,
        UNVERIFIED_EMAIL,
        ACCOUNT_CONFLICT,
        INACTIVE_USER,
        EMAIL_CONFIRMATION_UNAVAILABLE,
        EXPIRED_FLOW
    }

    private final Code code;

    public GoogleLoginException(Code code) {
        super(messageFor(code));
        this.code = code;
    }

    public Code code() {
        return code;
    }

    private static String messageFor(Code code) {
        return switch (code) {
        case INVALID_IDENTITY -> "Google sign-in identity is invalid.";
        case UNVERIFIED_EMAIL -> "Google account email must be verified.";
        case ACCOUNT_CONFLICT -> "Google account is already linked to another user.";
        case INACTIVE_USER -> "This user account is inactive.";
        case EMAIL_CONFIRMATION_UNAVAILABLE -> "Email confirmation is unavailable.";
        case EXPIRED_FLOW -> "This sign-in flow has expired.";
        };
    }
}
