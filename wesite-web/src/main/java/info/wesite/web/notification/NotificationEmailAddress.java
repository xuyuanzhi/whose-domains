package info.wesite.web.notification;

import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/** Shared syntax and header-safety validation for notification recipients. */
public final class NotificationEmailAddress {

    private static final int MAX_LENGTH = 254;
    private static final Pattern SYNTAX = Pattern.compile(
        "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    private NotificationEmailAddress() {
    }

    public static Optional<String> normalize(String raw) {
        String value = StringUtils.trimToNull(raw);
        if (value == null || value.length() > MAX_LENGTH || !SYNTAX.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static boolean isValid(String raw) {
        return normalize(raw).isPresent();
    }
}
