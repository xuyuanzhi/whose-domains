package info.wesite.web.auth.google;

import java.io.Serializable;
import java.time.Instant;

public record PendingGoogleBinding(String userId, String subject, String email, Instant expiresAt, String returnTo)
        implements Serializable {

    public static final String SESSION_KEY = "GOOGLE_PENDING_BINDING";

    public PendingGoogleBinding(String userId, String subject, String email, Instant expiresAt) {
        this(userId, subject, email, expiresAt, null);
    }

    public PendingGoogleBinding withReturnTo(String target) {
        return new PendingGoogleBinding(userId, subject, email, expiresAt, target);
    }
}
