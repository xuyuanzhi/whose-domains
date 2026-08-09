package info.wesite.web.monitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Derives the stable deduplication key for an event belonging to one watch.
 */
public final class MonitorFingerprint {

    private MonitorFingerprint() {
    }

    public static String of(String watchId, MonitorEventDraft draft) {
        return of(watchId, draft, "");
    }

    public static String of(String watchId, MonitorEventDraft draft, String episodeKey) {
        Objects.requireNonNull(watchId, "watchId");
        Objects.requireNonNull(draft, "draft");

        String canonical = canonical(
            watchId,
            Objects.requireNonNullElse(episodeKey, ""),
            draft.type().name(),
            draft.field(),
            draft.oldValue(),
            draft.newValue());
        return sha256(canonical);
    }

    private static String canonical(String... fields) {
        StringBuilder value = new StringBuilder();
        for (String field : fields) {
            String normalized = Objects.requireNonNullElse(field, "");
            value.append(normalized.length()).append(':').append(normalized);
        }
        return value.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
