package info.wesite.web.auth.google;

import java.io.Serializable;
import java.time.Instant;

public record PendingGoogleBinding(String userId, String subject, String email, Instant expiresAt)
        implements Serializable {

    public static final String SESSION_KEY = "GOOGLE_PENDING_BINDING";
}
