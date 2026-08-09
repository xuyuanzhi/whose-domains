-- Version snapshot provenance so legacy placeholder fields never become a
-- comparison baseline for newly enabled DNS, SSL, or WEBSITE collectors.
ALTER TABLE `WEB_MONITOR_SNAPSHOT`
  ADD COLUMN `SCHEMA_VERSION` smallint NULL
    COMMENT '2=cumulative established-source MonitorState; NULL=legacy DOMAIN-only provenance'
    AFTER `STATE_JSON`,
  ADD COLUMN `OBSERVED_SOURCES` varchar(128) NULL
    COMMENT 'Sorted collector sources with an established reliable baseline (observed-ever)'
    AFTER `SCHEMA_VERSION`;
