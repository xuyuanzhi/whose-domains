package info.wesite.core.diagnostics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class DiagnosticSanitizer {
    private DiagnosticSanitizer() {}

    public static Throwable root(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (error != null && error.getCause() != null && seen.add(error.getCause())) error = error.getCause();
        return error;
    }

    public static String type(Throwable error) {
        return error == null ? "HTTP_5XX" : truncate(root(error).getClass().getName(), 160);
    }

    public static String frames(Throwable error) {
        if (error == null) return "";
        StringBuilder result = new StringBuilder();
        for (StackTraceElement frame : root(error).getStackTrace()) {
            if (frame.getClassName().startsWith("info.wesite.") && !frame.getClassName().contains(".diagnostics.")) {
                if (!result.isEmpty()) result.append('\n');
                result.append(frame.getClassName()).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
                if (result.length() > 3000) break;
            }
        }
        return truncate(result.toString(), 4000);
    }

    public static String fingerprint(DiagnosticEvent event) {
        String first = event.frames().lines().findFirst().orElse("");
        // Browser frames have no method name; their position identifies the failure.
        if (!"client_error".equals(event.source())) first = first.replaceAll(":\\d+(?::\\d+)?$", "");
        String key = event.source() + "|" + event.route() + "|" + event.method() + "|" + event.exceptionType() + "|" + first;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String truncate(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), limit));
    }
}
