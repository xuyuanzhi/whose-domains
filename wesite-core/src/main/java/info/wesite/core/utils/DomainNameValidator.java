package info.wesite.core.utils;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strict domain-name validation and normalization shared by the website APIs and the
 * MCP tools. Accepts Unicode input, normalizes it to ASCII/punycode and lower case, and
 * rejects anything that is not a plain registrable-style LDH host name.
 *
 * <p>This is the single validation entry point for user-supplied domains; callers must not
 * pass raw input to outbound WHOIS/RDAP/DNS lookups without validating it here first.</p>
 */
public final class DomainNameValidator {

    private static final int MAX_LENGTH = 253;
    private static final String LDH_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern DOMAIN = Pattern.compile("^(?:" + LDH_LABEL + "\\.)+[a-z]{2,}$");

    private DomainNameValidator() {
    }

    /**
     * Normalizes a domain to lower-case ASCII (punycode). Returns {@code null} when the value
     * cannot be a domain at all (blank, too long, invalid IDN or characters).
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            value = IDN.toASCII(value, IDN.ALLOW_UNASSIGNED);
        } catch (RuntimeException e) {
            return null;
        }
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        return value;
    }

    /** True when {@code raw} normalizes to a syntactically valid domain name. */
    public static boolean isValid(String raw) {
        String normalized = normalize(raw);
        return normalized != null && DOMAIN.matcher(normalized).matches();
    }
}
