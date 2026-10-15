package info.wesite.core.service;

import info.wesite.core.utils.RdapUtils;

/**
 * Outbound registration-data probes used by {@link DomainIntelService}.
 *
 * <p>Centralising the network calls behind this thin seam keeps the status logic unit-testable
 * offline and gives one place to review outbound behaviour. The production implementation
 * delegates to the existing WHOIS/RDAP/DNS helpers so no lookup logic is duplicated.</p>
 */
public interface DomainProbe {

    /** Performs an RDAP lookup, distinguishing "found", "not found" and "failed". */
    RdapUtils.RdapResponse rdap(String domain, String server);

    /** Performs a WHOIS lookup; {@code error} is null on a completed query with any response text. */
    WhoisResult whois(String domain, String server);

    /** True when the domain resolves to at least one DNS record. Absence must never mean "available". */
    boolean dnsEnabled(String domain);

    final class WhoisResult {
        private final String text;
        private final String error;

        public WhoisResult(String text, String error) {
            this.text = text;
            this.error = error;
        }

        public static WhoisResult ok(String text) {
            return new WhoisResult(text, null);
        }

        public static WhoisResult failed(String error) {
            return new WhoisResult(null, error);
        }

        public String getText() {
            return text;
        }

        public String getError() {
            return error;
        }

        public boolean isOk() {
            return error == null;
        }
    }
}
