package info.wesite.web.controller.api;

import java.util.Date;

import info.wesite.core.entity.DomainWatch;

/** The watch record together with the current user's monitoring activity. */
public final class DomainWatchSummary {

    private final DomainWatch watch;
    private final String latestEventRisk;
    private final long unreadCount;
    private final Date lastSuccessfulCheck;
    private final String latestEventSummary;

    public DomainWatchSummary(DomainWatch watch, String latestEventRisk, long unreadCount,
            Date lastSuccessfulCheck, String latestEventSummary) {
        this.watch = watch;
        this.latestEventRisk = latestEventRisk;
        this.unreadCount = unreadCount;
        this.lastSuccessfulCheck = lastSuccessfulCheck;
        this.latestEventSummary = latestEventSummary;
    }

    public DomainWatch getWatch() { return watch; }
    public String getLatestEventRisk() { return latestEventRisk; }
    public long getUnreadCount() { return unreadCount; }
    public Date getLastSuccessfulCheck() { return lastSuccessfulCheck; }
    public String getLatestEventSummary() { return latestEventSummary; }
}
