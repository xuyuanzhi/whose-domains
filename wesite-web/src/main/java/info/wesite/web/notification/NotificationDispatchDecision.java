package info.wesite.web.notification;

/**
 * The persisted mail-delivery route selected for a notification. This only
 * queues a route; a later delivery task performs any actual mail send.
 */
public enum NotificationDispatchDecision {
    IMMEDIATE_EMAIL,
    DAILY_DIGEST,
    WEEKLY_DIGEST,
    IN_APP_ONLY
}
