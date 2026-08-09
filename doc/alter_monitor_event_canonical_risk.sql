-- Persist risk/source as facts of the event that was observed.
-- Existing events intentionally remain NULL and are exposed as UNKNOWN.
ALTER TABLE `WEB_MONITOR_EVENT`
  ADD COLUMN `RISK` varchar(16) NULL
    COMMENT 'LOW, MEDIUM, HIGH, CRITICAL; NULL only for legacy rows'
    AFTER `EVENT_TYPE`,
  ADD COLUMN `SOURCE` varchar(32) NULL
    COMMENT 'Collector source captured when the event is published'
    AFTER `RISK`;
