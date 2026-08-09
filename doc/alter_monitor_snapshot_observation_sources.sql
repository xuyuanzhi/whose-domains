-- Version snapshot provenance so legacy placeholder fields never become a
-- comparison baseline for newly enabled DNS, SSL, or WEBSITE collectors.
-- Existing rows deliberately receive NULL current-source/last-success fields:
-- source freshness cannot be reconstructed safely from snapshot CHECKED_AT.
ALTER TABLE `WEB_MONITOR_SNAPSHOT`
  ADD COLUMN `SCHEMA_VERSION` smallint NULL
    COMMENT '3=per-source freshness; 2=cumulative provenance; NULL=legacy DOMAIN-only'
    AFTER `STATE_JSON`,
  ADD COLUMN `OBSERVED_SOURCES` varchar(128) NULL
    COMMENT 'Sorted collector sources with an established reliable baseline (observed-ever)'
    AFTER `SCHEMA_VERSION`,
  ADD COLUMN `CURRENT_OBSERVED_SOURCES` varchar(128) NULL
    COMMENT 'Collector sources that succeeded in this scan only'
    AFTER `OBSERVED_SOURCES`,
  ADD COLUMN `DOMAIN_LAST_SUCCESS_AT` datetime NULL
    COMMENT 'Last successful DOMAIN collector time; NULL=unknown'
    AFTER `CURRENT_OBSERVED_SOURCES`,
  ADD COLUMN `DNS_LAST_SUCCESS_AT` datetime NULL
    COMMENT 'Last successful DNS collector time; NULL=unknown'
    AFTER `DOMAIN_LAST_SUCCESS_AT`,
  ADD COLUMN `SSL_LAST_SUCCESS_AT` datetime NULL
    COMMENT 'Last successful SSL collector time; NULL=unknown'
    AFTER `DNS_LAST_SUCCESS_AT`,
  ADD COLUMN `WEBSITE_LAST_SUCCESS_AT` datetime NULL
    COMMENT 'Last successful WEBSITE collector time; NULL=unknown'
    AFTER `SSL_LAST_SUCCESS_AT`;
