package info.wesite.core.service;

import info.wesite.core.model.DomainIntelResult;

/**
 * Single source of truth for domain registration intelligence, shared by the public website
 * APIs and the MCP tools. Implementations must not infer availability from DNS/HTTP failures.
 */
public interface DomainIntelService {

    /**
     * Resolves the registration intelligence for one domain.
     *
     * @param domain user-supplied domain (any case, optionally Unicode); invalid input yields
     *               an {@code unknown} result with an error rather than an exception
     * @return a result carrying status, provenance, timestamp and any errors
     */
    DomainIntelResult check(String domain);
}
