package info.wesite.core.service;

import info.wesite.core.entity.ApiPlan;
import info.wesite.core.model.QuotaDecision;

/**
 * Account-level API quota enforcement. Backed by Redis and shared by every metered surface so the
 * rules (and their rollback/idempotency behaviour) live in exactly one place.
 */
public interface ApiQuotaService {

    /**
     * Consumes {@code units} of quota for {@code subject} under {@code plan}.
     *
     * <p>Usage is incremented atomically and rolled back when a limit would be exceeded, so the
     * stored counter never passes the limit even under concurrency. A non-blank
     * {@code idempotencyKey} makes retries of the same request free (no double charge).</p>
     *
     * @throws RuntimeException when metering storage is unavailable; callers should surface a 503
     */
    QuotaDecision consume(String subject, ApiPlan plan, String idempotencyKey, int units);

    /** Reads current usage for {@code subject} without consuming quota. */
    QuotaDecision peek(String subject, ApiPlan plan);
}
