package info.wesite.core.utils;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class ApiKeyUtils {
    private static final SecureRandom RANDOM = new SecureRandom();
    private ApiKeyUtils() { }
    public static String generate() {
        byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes);
        return "wd_" + HexFormat.of().formatHex(bytes);
    }
    public static String hash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String prefix(String key) { return key.substring(0, Math.min(key.length(), 11)); }
}
