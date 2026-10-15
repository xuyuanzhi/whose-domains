package info.wesite.core.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Domain intelligence result shared by the website API surface and the MCP tools.
 *
 * <p>Every result carries its provenance ({@link #source}), a timestamp ({@link #checkedAt}) and
 * any collected {@link #errors} so a client can tell why a status is what it is.</p>
 */
@Data
public class DomainIntelResult {

    /** Normalized query domain (registrable main domain), or the raw input when invalid. */
    private String domain;

    private RegistrationStatus status = RegistrationStatus.UNKNOWN;

    /** Provenance of the decisive evidence: database | rdap | whois | dns | none. */
    private String source = "none";

    /** ISO-8601 UTC timestamp of when the lookup was performed. */
    private String checkedAt;

    /** True when the decisive evidence came from the local database/cache. */
    private boolean cached;

    private String registrar;
    private String createdAt;
    private String updatedAt;
    private String expiresAt;

    private List<String> registryStatuses = new ArrayList<>();
    private List<String> nameServers = new ArrayList<>();
    private String dnssec;

    private String whoisServer;
    private String rdapServer;

    /** Non-fatal problems encountered while resolving the status (never secrets). */
    private List<String> errors = new ArrayList<>();

    public DomainIntelResult addError(String message) {
        if (message != null && !message.isBlank()) {
            errors.add(message);
        }
        return this;
    }
}
