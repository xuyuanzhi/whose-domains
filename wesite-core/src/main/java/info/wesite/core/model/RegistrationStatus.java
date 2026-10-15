package info.wesite.core.model;

import java.util.Locale;

/**
 * Registration state for a domain, ordered from most to least authoritative.
 *
 * <p>Rules enforced by {@code DomainIntelService}:</p>
 * <ul>
 *   <li>{@link #REGISTERED} — a registry source (RDAP/WHOIS) or the local database confirms a registration.</li>
 *   <li>{@link #AVAILABLE} — an authoritative registrability/purchase source confirms it can be bought.
 *       This build has no such source, so it is never produced yet; do not fake it.</li>
 *   <li>{@link #UNREGISTERED_CANDIDATE} — RDAP and/or WHOIS report no record, but purchase is not confirmed.</li>
 *   <li>{@link #RESERVED} — registry status/text marks the name reserved or prohibited.</li>
 *   <li>{@link #UNKNOWN} — insufficient or failed lookups. Missing DNS records never imply availability.</li>
 * </ul>
 */
public enum RegistrationStatus {
    REGISTERED,
    AVAILABLE,
    UNREGISTERED_CANDIDATE,
    RESERVED,
    UNKNOWN;

    /** Lower-case token used in MCP/JSON responses (e.g. {@code unregistered_candidate}). */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
