package info.wesite.web.auth.google;

import info.wesite.core.entity.User;

public record GoogleLoginResult(Status status, User user, PendingGoogleBinding pendingBinding) {
    public enum Status {
        SIGNED_IN, EMAIL_CONFIRMATION_REQUIRED
    }

    public boolean requiresEmailConfirmation() {
        return status == Status.EMAIL_CONFIRMATION_REQUIRED;
    }

    public static GoogleLoginResult signedIn(User user) {
        return new GoogleLoginResult(Status.SIGNED_IN, user, null);
    }

    public static GoogleLoginResult pending(PendingGoogleBinding pendingBinding) {
        return new GoogleLoginResult(Status.EMAIL_CONFIRMATION_REQUIRED, null, pendingBinding);
    }
}
