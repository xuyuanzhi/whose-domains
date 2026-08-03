package info.wesite.web.auth.google;

import java.io.Serializable;
import java.time.Instant;

public record PendingGoogleBinding(String userId, String subject, String email, Instant expiresAt)
        implements Serializable {
}
