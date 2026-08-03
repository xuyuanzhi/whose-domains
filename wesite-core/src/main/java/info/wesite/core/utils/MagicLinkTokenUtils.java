package info.wesite.core.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Utilities for opaque, one-time email-login tokens. */
public final class MagicLinkTokenUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private MagicLinkTokenUtils() {
    }

    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        if (token == null) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    public static boolean matches(String token, String tokenHash) {
        if (token == null || tokenHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.UTF_8), tokenHash.getBytes(StandardCharsets.UTF_8));
    }
}
