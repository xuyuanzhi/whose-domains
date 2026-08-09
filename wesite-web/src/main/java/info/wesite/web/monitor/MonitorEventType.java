package info.wesite.web.monitor;

/**
 * Facts the monitoring pipeline can publish for a watched domain.
 */
public enum MonitorEventType {
    DOMAIN_EXPIRING,
    SSL_EXPIRING,
    DOMAIN_STATUS_CHANGED,
    DNS_CHANGED,
    WEBSITE_DOWN,
    WEBSITE_RECOVERED
}
