-- Complete one-way upgrade from the notification schema shipped at git e73ff4d.
-- Preconditions (verify before running): WEB_MONITOR_SNAPSHOT, WEB_MONITOR_EVENT,
-- WEB_USER_NOTIFICATION and WEB_NOTIFICATION_PREFERENCE exist;
-- WEB_NOTIFICATION_DELIVERY_BATCH, WEB_AUTHENTICATED_ACTIVITY_DAILY,
-- WEB_RETENTION_FACT_COLLECTION and WEB_RETENTION_FACT_HEALTH do not exist;
-- the columns added below do not exist.
-- Stop monitoring/delivery workers and take a backup. This script is intentionally
-- not idempotent so that a partial or repeated production application fails visibly.

-- The checked-in e73ff4d schema did not contain NOTIFY_EMAIL, while some
-- operational installations had independently added it. Preserve either shape:
-- add an empty field for the exact baseline, or narrow the existing legacy field.
SET @watch_notify_email_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'WEB_DOMAIN_WATCH'
    AND COLUMN_NAME = 'NOTIFY_EMAIL'
);
SET @watch_notify_email_ddl = IF(
  @watch_notify_email_exists = 0,
  'ALTER TABLE `WEB_DOMAIN_WATCH` ADD COLUMN `NOTIFY_EMAIL` varchar(254) NULL AFTER `REMARK`',
  'ALTER TABLE `WEB_DOMAIN_WATCH` MODIFY COLUMN `NOTIFY_EMAIL` varchar(254) NULL'
);
PREPARE watch_notify_email_statement FROM @watch_notify_email_ddl;
EXECUTE watch_notify_email_statement;
DEALLOCATE PREPARE watch_notify_email_statement;

-- Add an explicit reporting-calendar fact without guessing the timezone of the
-- legacy timezone-less CREATE_TIME wall clock. Operators may prefix this script
-- with both @legacy_watch_source_time_zone and @retention_reporting_time_zone;
-- without two valid explicit zones, legacy rows remain NULL and are excluded.
SET @watch_created_on_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'WEB_DOMAIN_WATCH'
    AND COLUMN_NAME = 'WATCH_CREATED_ON'
);
SET @watch_created_on_ddl = IF(
  @watch_created_on_exists = 0,
  'ALTER TABLE `WEB_DOMAIN_WATCH` ADD COLUMN `WATCH_CREATED_ON` date NULL COMMENT ''Creation date in WESITE_RETENTION_REPORTING_ZONE; NULL excludes unverified legacy rows'' AFTER `USER_ID`',
  'ALTER TABLE `WEB_DOMAIN_WATCH` MODIFY COLUMN `WATCH_CREATED_ON` date NULL COMMENT ''Creation date in WESITE_RETENTION_REPORTING_ZONE; NULL excludes unverified legacy rows'''
);
PREPARE watch_created_on_statement FROM @watch_created_on_ddl;
EXECUTE watch_created_on_statement;
DEALLOCATE PREPARE watch_created_on_statement;

SET @watch_created_on_index_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'WEB_DOMAIN_WATCH'
    AND INDEX_NAME = 'IDX_DOMAIN_WATCH_USER_CREATED_ON'
);
SET @watch_created_on_index_ddl = IF(
  @watch_created_on_index_exists = 0,
  'ALTER TABLE `WEB_DOMAIN_WATCH` ADD KEY `IDX_DOMAIN_WATCH_USER_CREATED_ON` (`USER_ID`, `WATCH_CREATED_ON`)',
  'SELECT 1'
);
PREPARE watch_created_on_index_statement FROM @watch_created_on_index_ddl;
EXECUTE watch_created_on_index_statement;
DEALLOCATE PREPARE watch_created_on_index_statement;

SET @legacy_watch_source_time_zone = NULLIF(TRIM(@legacy_watch_source_time_zone), '');
SET @retention_reporting_time_zone = NULLIF(TRIM(@retention_reporting_time_zone), '');
SET @watch_created_on_zones_valid =
  @legacy_watch_source_time_zone IS NOT NULL
  AND @retention_reporting_time_zone IS NOT NULL
  AND CONVERT_TZ('2000-01-01 00:00:00',
        @legacy_watch_source_time_zone, @retention_reporting_time_zone) IS NOT NULL;
UPDATE `WEB_DOMAIN_WATCH`
SET `WATCH_CREATED_ON` = DATE(CONVERT_TZ(
  `CREATE_TIME`, @legacy_watch_source_time_zone, @retention_reporting_time_zone))
WHERE `WATCH_CREATED_ON` IS NULL
  AND `CREATE_TIME` IS NOT NULL
  AND @watch_created_on_zones_valid;
SELECT CASE
  WHEN @watch_created_on_zones_valid THEN 'EXPLICIT_LEGACY_WATCH_DATES_BACKFILLED'
  ELSE 'LEGACY_WATCH_DATES_LEFT_NULL_AND_EXCLUDED'
END AS `WATCH_CREATED_ON_MIGRATION_STATUS`;

ALTER TABLE `WEB_DOMAIN_WATCH`
  ADD COLUMN `SCAN_CLAIM_TOKEN` varchar(64) NULL AFTER `REMARK`,
  ADD COLUMN `SCAN_CLAIM_UNTIL` datetime NULL AFTER `SCAN_CLAIM_TOKEN`,
  ADD KEY `IDX_DOMAIN_WATCH_SCAN_CLAIM` (`STATUS`, `DELETED`, `SCAN_CLAIM_UNTIL`, `ID`);

-- Fail closed for malformed legacy recipients and invalid threshold values. Never
-- derive a recipient from SYS_USER.EMAIL during this upgrade.
UPDATE `WEB_DOMAIN_WATCH`
SET `NOTIFY_EMAIL` = NULL
WHERE `NOTIFY_EMAIL` IS NOT NULL
  AND (CHAR_LENGTH(TRIM(`NOTIFY_EMAIL`)) > 254
    OR TRIM(`NOTIFY_EMAIL`) NOT REGEXP '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}$');
UPDATE `WEB_DOMAIN_WATCH`
SET `NOTIFY_EMAIL` = NULLIF(TRIM(`NOTIFY_EMAIL`), '');
UPDATE `WEB_DOMAIN_WATCH`
SET `NOTIFY_TYPE` = 0
WHERE `NOTIFY_TYPE` IS NULL OR `NOTIFY_TYPE` NOT IN (0, 1, 2, 3);

ALTER TABLE `WEB_MONITOR_SNAPSHOT`
  DROP INDEX `IDX_MONITOR_SNAPSHOT_WATCH_CHECKED`,
  ADD COLUMN `SCHEMA_VERSION` smallint NULL
    COMMENT '2=cumulative established-source MonitorState; NULL=legacy DOMAIN-only provenance' AFTER `STATE_JSON`,
  ADD COLUMN `OBSERVED_SOURCES` varchar(128) NULL
    COMMENT 'Sorted collector sources with an established reliable baseline (observed-ever)' AFTER `SCHEMA_VERSION`,
  ADD KEY `IDX_MONITOR_SNAPSHOT_WATCH_CHECKED` (`WATCH_ID`, `CHECKED_AT`, `ID`);

ALTER TABLE `WEB_MONITOR_EVENT`
  ADD COLUMN `RISK` varchar(16) NULL
    COMMENT 'LOW, MEDIUM, HIGH, CRITICAL; NULL only for legacy rows' AFTER `EVENT_TYPE`,
  ADD COLUMN `SOURCE` varchar(32) NULL
    COMMENT 'Collector source captured when the event is published' AFTER `RISK`;

ALTER TABLE `WEB_USER_NOTIFICATION`
  DROP INDEX `IDX_USER_NOTIFICATION_EMAIL_CREATED`,
  ADD COLUMN `RECIPIENT_EMAIL` varchar(254) NULL
    COMMENT 'Frozen validated watch recipient; never inferred from SYS_USER' AFTER `TARGET_PATH`,
  ADD COLUMN `EMAIL_MODE` varchar(32) NULL AFTER `READ_AT`,
  MODIFY COLUMN `EMAIL_STATE` varchar(32) NOT NULL DEFAULT 'QUEUED',
  ADD COLUMN `EMAIL_ATTEMPT_COUNT` int NOT NULL DEFAULT '0' AFTER `EMAIL_STATE`,
  ADD COLUMN `EMAIL_CLAIM_TOKEN` varchar(64) NULL AFTER `EMAIL_ATTEMPT_COUNT`,
  ADD COLUMN `EMAIL_CLAIM_UNTIL` datetime NULL AFTER `EMAIL_CLAIM_TOKEN`,
  ADD COLUMN `DELIVERY_BATCH_ID` varchar(32) NULL AFTER `EMAIL_CLAIM_UNTIL`,
  ADD KEY `IDX_USER_NOTIFICATION_EMAIL_CREATED` (`EMAIL_MODE`, `EMAIL_STATE`, `CREATE_TIME`),
  ADD KEY `IDX_USER_NOTIFICATION_BATCH` (`DELIVERY_BATCH_ID`);

CREATE TABLE `WEB_NOTIFICATION_DELIVERY_BATCH` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL,
  `RECIPIENT_EMAIL` varchar(254) NOT NULL COMMENT 'Validated recipient frozen when the batch is created',
  `EMAIL_MODE` varchar(32) NOT NULL,
  `WINDOW_KEY` varchar(64) NOT NULL,
  `STATE` varchar(32) NOT NULL,
  `ATTEMPT_COUNT` int NOT NULL DEFAULT '0',
  `CLAIM_TOKEN` varchar(64) NULL,
  `CLAIM_UNTIL` datetime NULL,
  `NEXT_ATTEMPT_AT` datetime NULL,
  `COMPLETED_AT` datetime NULL,
  `CANCELLATION_REQUESTED` smallint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_NOTIFICATION_BATCH_USER_MODE_WINDOW` (`USER_ID`, `EMAIL_MODE`, `RECIPIENT_EMAIL`, `WINDOW_KEY`),
  KEY `IDX_NOTIFICATION_BATCH_DELIVERY` (`EMAIL_MODE`, `STATE`, `NEXT_ATTEMPT_AT`, `CLAIM_UNTIL`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_AUTHENTICATED_ACTIVITY_DAILY` (
  `USER_ID` varchar(32) NOT NULL,
  `ACTIVITY_DATE` date NOT NULL,
  PRIMARY KEY (`USER_ID`, `ACTIVITY_DATE`),
  KEY `IDX_AUTH_ACTIVITY_DATE` (`ACTIVITY_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
  COMMENT='Minimal authenticated activity fact; retain for at least 120 days';

CREATE TABLE `WEB_RETENTION_FACT_COLLECTION` (
  `ID` varchar(32) NOT NULL,
  `FACT_NAME` varchar(64) NOT NULL,
  `COLLECTION_STARTED_ON` date NULL,
  `MINIMUM_RETENTION_DAYS` smallint unsigned NOT NULL DEFAULT '120',
  `CREATE_TIME` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `UPDATE_TIME` datetime NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_RETENTION_FACT_COLLECTION_NAME` (`FACT_NAME`),
  CONSTRAINT `CK_RETENTION_FACT_MINIMUM_DAYS` CHECK (`MINIMUM_RETENTION_DAYS` >= 120)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
  COMMENT='Durable fact observation boundary and minimum retention contract';

INSERT INTO `WEB_RETENTION_FACT_COLLECTION`
  (`ID`, `FACT_NAME`, `COLLECTION_STARTED_ON`, `MINIMUM_RETENTION_DAYS`)
VALUES
  ('authenticated-activity', 'AUTHENTICATED_ACTIVITY_DAILY', NULL, 120);

CREATE TABLE `WEB_RETENTION_FACT_HEALTH` (
  `FACT_NAME` varchar(64) NOT NULL,
  `FACT_DATE` date NOT NULL,
  `SUCCESSFUL_WRITE_COUNT` bigint unsigned NOT NULL DEFAULT '0',
  `FAILURE_COUNT` bigint unsigned NOT NULL DEFAULT '0',
  `EXPECTED_FACT_ROWS` bigint unsigned NOT NULL DEFAULT '0',
  `VERIFICATION_STATUS` varchar(16) NOT NULL DEFAULT 'OPEN',
  `VERIFIED_AT` datetime NULL,
  `EXTERNAL_EXPECTED_ROWS` bigint unsigned NULL,
  `RECONCILIATION_SOURCE` varchar(128) NULL,
  `RECONCILIATION_ID` varchar(128) NULL,
  `LAST_SUCCESS_AT` datetime NULL,
  `LAST_FAILURE_AT` datetime NULL,
  PRIMARY KEY (`FACT_NAME`, `FACT_DATE`),
  UNIQUE KEY `UK_RETENTION_RECONCILIATION_ID` (`RECONCILIATION_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
  COMMENT='Non-user-level daily completeness watermark for retention facts';

ALTER TABLE `WEB_DOMAIN_WATCH_NOTIFY_LOG`
  MODIFY COLUMN `WATCH_ID` varchar(32) NULL,
  MODIFY COLUMN `SEND_STATUS` smallint(1) COMMENT '0=pending, 1=success, 2=fail',
  MODIFY COLUMN `ERROR_MSG` text NULL,
  ADD COLUMN `BATCH_ID` varchar(32) NULL AFTER `NOTIFICATION_ID`,
  ADD UNIQUE KEY `UK_NOTIFY_LOG_BATCH_RETRY` (`BATCH_ID`, `RETRY_COUNT`);

-- Split the e73ff4d overloaded delivery state into route and lifecycle fields.
UPDATE `WEB_USER_NOTIFICATION`
SET `EMAIL_MODE` = `EMAIL_STATE`, `EMAIL_STATE` = 'QUEUED', `EMAIL_ATTEMPT_COUNT` = 0
WHERE `EMAIL_STATE` IN ('IMMEDIATE_EMAIL', 'DAILY_DIGEST', 'WEEKLY_DIGEST');

UPDATE `WEB_USER_NOTIFICATION`
SET `EMAIL_MODE` = 'IN_APP_ONLY', `EMAIL_STATE` = 'IN_APP_ONLY',
    `RECIPIENT_EMAIL` = NULL, `EMAIL_CLAIM_TOKEN` = NULL,
    `EMAIL_CLAIM_UNTIL` = NULL, `DELIVERY_BATCH_ID` = NULL
WHERE `EMAIL_STATE` NOT IN ('QUEUED', 'SENT');

-- Re-evaluate every unsent legacy route against watch settings, category preferences,
-- exact expiry thresholds, and the validated watch recipient. This deliberately does
-- not fall back to SYS_USER.EMAIL. Any ambiguous legacy row remains available in-app.
UPDATE `WEB_USER_NOTIFICATION` N
LEFT JOIN `WEB_MONITOR_EVENT` E ON E.`ID` = N.`EVENT_ID`
LEFT JOIN `WEB_DOMAIN_WATCH` W ON W.`ID` = E.`WATCH_ID` AND W.`USER_ID` = N.`USER_ID`
LEFT JOIN `WEB_NOTIFICATION_PREFERENCE` P ON P.`USER_ID` = N.`USER_ID` AND P.`DELETED` = 0
SET N.`EMAIL_MODE` = 'IN_APP_ONLY', N.`EMAIL_STATE` = 'IN_APP_ONLY',
    N.`RECIPIENT_EMAIL` = NULL, N.`EMAIL_CLAIM_TOKEN` = NULL,
    N.`EMAIL_CLAIM_UNTIL` = NULL, N.`DELIVERY_BATCH_ID` = NULL
WHERE N.`EMAIL_STATE` = 'QUEUED'
  AND (E.`ID` IS NULL OR W.`ID` IS NULL OR W.`STATUS` <> 1 OR W.`DELETED` <> 0
    OR W.`NOTIFY_TYPE` = 0 OR W.`NOTIFY_EMAIL` IS NULL
    OR P.`EMAIL_MODE` = 'IN_APP_ONLY'
    OR (E.`EVENT_TYPE` = 'DOMAIN_EXPIRING' AND COALESCE((
         ((N.`CONTENT` LIKE 'domainExpiry:7:%'
             OR DATEDIFF(DATE(E.`NEW_VALUE`), DATE(E.`OCCURRED_AT`)) = 7)
           AND W.`NOTIFY_TYPE` IN (1, 3))
      OR ((N.`CONTENT` LIKE 'domainExpiry:30:%'
             OR DATEDIFF(DATE(E.`NEW_VALUE`), DATE(E.`OCCURRED_AT`)) = 30)
           AND W.`NOTIFY_TYPE` IN (2, 3))), 0) = 0)
    OR (P.`ID` IS NOT NULL AND (
         (E.`EVENT_TYPE` = 'DOMAIN_EXPIRING' AND P.`DOMAIN_EXPIRY_ENABLED` = 0)
      OR (E.`EVENT_TYPE` = 'SSL_EXPIRING' AND P.`SSL_EXPIRY_ENABLED` = 0)
      OR (E.`EVENT_TYPE` = 'DOMAIN_STATUS_CHANGED' AND P.`DOMAIN_STATUS_ENABLED` = 0)
      OR (E.`EVENT_TYPE` = 'DNS_CHANGED' AND P.`DNS_CHANGE_ENABLED` = 0)
      OR (E.`EVENT_TYPE` IN ('WEBSITE_DOWN', 'WEBSITE_RECOVERED') AND P.`WEBSITE_AVAILABILITY_ENABLED` = 0))));

UPDATE `WEB_USER_NOTIFICATION` N
JOIN `WEB_MONITOR_EVENT` E ON E.`ID` = N.`EVENT_ID`
JOIN `WEB_DOMAIN_WATCH` W ON W.`ID` = E.`WATCH_ID` AND W.`USER_ID` = N.`USER_ID`
SET N.`RECIPIENT_EMAIL` = W.`NOTIFY_EMAIL`
WHERE N.`EMAIL_STATE` = 'QUEUED';

-- Rows already accepted by SMTP remain historical SENT facts. They cannot acquire a
-- recipient retroactively and are never retried by the new workers.
UPDATE `WEB_USER_NOTIFICATION`
SET `EMAIL_MODE` = COALESCE(`EMAIL_MODE`, 'IN_APP_ONLY')
WHERE `EMAIL_STATE` = 'SENT';

UPDATE `WEB_DOMAIN_WATCH_NOTIFY_LOG`
SET `STATUS` = 2, `RETRY_COUNT` = 3
WHERE `SEND_STATUS` = 2 AND `BATCH_ID` IS NULL;
