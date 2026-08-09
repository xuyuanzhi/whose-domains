-- Retention notification-center release migration.
-- On a current clean install, run doc/create.sql first and then this file; do not run
-- doc/alter_domain_watch_snapshot.sql because create.sql already creates WEB_DOMAIN_WATCH.
-- On an older install missing BOTH WEB_DOMAIN_WATCH and WEB_DOMAIN_SNAPSHOT, run
-- doc/alter_domain_watch_snapshot.sql first, then this file. See README for preflight checks.
-- This current baseline already includes SCHEMA_VERSION/OBSERVED_SOURCES and RISK/SOURCE.
-- Do not also run the later incremental column migrations on a fresh application of this file.
CREATE TABLE `WEB_MONITOR_SNAPSHOT` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `WATCH_ID` varchar(32) NOT NULL,
  `CHECKED_AT` datetime NOT NULL,
  `STATE_JSON` mediumtext NOT NULL,
  `SCHEMA_VERSION` smallint NULL COMMENT '2=source-aware MonitorState; NULL=legacy DOMAIN-only provenance',
  `OBSERVED_SOURCES` varchar(128) NULL COMMENT 'Sorted collector source names represented by STATE_JSON',
  PRIMARY KEY (`ID`),
  KEY `IDX_MONITOR_SNAPSHOT_WATCH_CHECKED` (`WATCH_ID`, `CHECKED_AT`, `ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_MONITOR_EVENT` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `WATCH_ID` varchar(32) NOT NULL,
  `SNAPSHOT_ID` varchar(32),
  `FINGERPRINT` varchar(128) NOT NULL,
  `EVENT_TYPE` varchar(64) NOT NULL,
  `RISK` varchar(16) NULL COMMENT 'LOW, MEDIUM, HIGH, CRITICAL; NULL only for legacy rows',
  `SOURCE` varchar(32) NULL COMMENT 'Collector source captured when the event is published',
  `OLD_VALUE` text,
  `NEW_VALUE` text,
  `OCCURRED_AT` datetime NOT NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_MONITOR_EVENT_WATCH_FINGERPRINT` (`WATCH_ID`, `FINGERPRINT`),
  KEY `IDX_MONITOR_EVENT_WATCH_OCCURRED` (`WATCH_ID`, `OCCURRED_AT`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_USER_NOTIFICATION` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL,
  `EVENT_ID` varchar(32) NOT NULL,
  `TITLE` varchar(255) NOT NULL,
  `CONTENT` text,
  `TARGET_PATH` varchar(500) NOT NULL,
  `RECIPIENT_EMAIL` varchar(254) NULL COMMENT 'Frozen validated watch recipient; never inferred from SYS_USER',
  `READ_AT` datetime,
  `EMAIL_MODE` varchar(32) NULL,
  `EMAIL_STATE` varchar(32) NOT NULL DEFAULT 'QUEUED',
  `EMAIL_ATTEMPT_COUNT` int NOT NULL DEFAULT '0',
  `EMAIL_CLAIM_TOKEN` varchar(64) NULL,
  `EMAIL_CLAIM_UNTIL` datetime NULL,
  `DELIVERY_BATCH_ID` varchar(32) NULL,
  `EMAILED_AT` datetime,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_USER_NOTIFICATION_USER_EVENT` (`USER_ID`, `EVENT_ID`),
  KEY `IDX_USER_NOTIFICATION_USER_READ_CREATED` (`USER_ID`, `READ_AT`, `CREATE_TIME`),
  KEY `IDX_USER_NOTIFICATION_EMAIL_CREATED` (`EMAIL_MODE`, `EMAIL_STATE`, `CREATE_TIME`),
  KEY `IDX_USER_NOTIFICATION_BATCH` (`DELIVERY_BATCH_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

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
  `ID` varchar(32) NOT NULL,
  `USER_ID` varchar(32) NOT NULL,
  `ACTIVITY_DATE` date NOT NULL,
  `CREATE_TIME` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_AUTH_ACTIVITY_USER_DATE` (`USER_ID`, `ACTIVITY_DATE`),
  KEY `IDX_AUTH_ACTIVITY_DATE` (`ACTIVITY_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
  COMMENT='Minimal authenticated activity fact; retain for at least 120 days';

CREATE TABLE `WEB_RETENTION_FACT_COLLECTION` (
  `ID` varchar(32) NOT NULL,
  `FACT_NAME` varchar(64) NOT NULL,
  `COLLECTION_STARTED_ON` date NOT NULL,
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
  ('authenticated-activity', 'AUTHENTICATED_ACTIVITY_DAILY', CURDATE(), 120);

-- Clean installs lack NOTIFY_EMAIL, while a few older operational databases
-- added the legacy field independently. Preserve both preflight-approved shapes.
SET @watch_notify_email_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'WEB_DOMAIN_WATCH'
    AND COLUMN_NAME = 'NOTIFY_EMAIL'
);
SET @watch_notify_email_ddl = IF(
  @watch_notify_email_exists = 0,
  'ALTER TABLE `WEB_DOMAIN_WATCH` ADD COLUMN `NOTIFY_EMAIL` varchar(254) NULL COMMENT ''Validated watch-level recipient; NULL means no email'' AFTER `LAST_CHECK_TIME`',
  'ALTER TABLE `WEB_DOMAIN_WATCH` MODIFY COLUMN `NOTIFY_EMAIL` varchar(254) NULL COMMENT ''Validated watch-level recipient; NULL means no email'''
);
PREPARE watch_notify_email_statement FROM @watch_notify_email_ddl;
EXECUTE watch_notify_email_statement;
DEALLOCATE PREPARE watch_notify_email_statement;

ALTER TABLE `WEB_DOMAIN_WATCH`
  ADD COLUMN `SCAN_CLAIM_TOKEN` varchar(64) NULL AFTER `REMARK`,
  ADD COLUMN `SCAN_CLAIM_UNTIL` datetime NULL AFTER `SCAN_CLAIM_TOKEN`,
  ADD KEY `IDX_DOMAIN_WATCH_SCAN_CLAIM` (`STATUS`, `DELETED`, `SCAN_CLAIM_UNTIL`, `ID`);

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

CREATE TABLE `WEB_NOTIFICATION_PREFERENCE` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL,
  `EMAIL_MODE` varchar(32) NOT NULL DEFAULT 'DAILY',
  `DOMAIN_EXPIRY_ENABLED` smallint(1) NOT NULL DEFAULT '1',
  `SSL_EXPIRY_ENABLED` smallint(1) NOT NULL DEFAULT '1',
  `DOMAIN_STATUS_ENABLED` smallint(1) NOT NULL DEFAULT '1',
  `DNS_CHANGE_ENABLED` smallint(1) NOT NULL DEFAULT '1',
  `WEBSITE_AVAILABILITY_ENABLED` smallint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_NOTIFICATION_PREFERENCE_USER` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE `WEB_DOMAIN_WATCH_NOTIFY_LOG`
  MODIFY COLUMN `WATCH_ID` varchar(32) NULL,
  MODIFY COLUMN `SEND_STATUS` smallint(1) COMMENT '0=pending, 1=success, 2=fail',
  MODIFY COLUMN `ERROR_MSG` text NULL,
  ADD COLUMN `NOTIFICATION_ID` varchar(32) NULL AFTER `UPDATE_TIME`,
  ADD COLUMN `BATCH_ID` varchar(32) NULL AFTER `NOTIFICATION_ID`,
  ADD COLUMN `EVENT_ID` varchar(32) NULL AFTER `BATCH_ID`,
  ADD COLUMN `DELIVERY_MODE` varchar(32) NULL AFTER `EVENT_ID`,
  ADD COLUMN `SUBJECT` varchar(255) NULL AFTER `DELIVERY_MODE`,
  ADD KEY `IDX_NOTIFY_LOG_NOTIFICATION_MODE_RETRY` (`NOTIFICATION_ID`, `DELIVERY_MODE`, `RETRY_COUNT`),
  ADD UNIQUE KEY `UK_NOTIFY_LOG_BATCH_RETRY` (`BATCH_ID`, `RETRY_COUNT`),
  ADD KEY `IDX_NOTIFY_LOG_EVENT` (`EVENT_ID`);

-- Legacy failed rows have no durable batch identity and cannot be retried safely.
-- Retain them for audit, but archive and exhaust them before the new workers start.
UPDATE `WEB_DOMAIN_WATCH_NOTIFY_LOG`
SET `STATUS` = 2, `RETRY_COUNT` = 3
WHERE `SEND_STATUS` = 2 AND `BATCH_ID` IS NULL;

-- Preserve queued legacy routes that never started an attempt under the new mode/state split.
UPDATE `WEB_USER_NOTIFICATION`
SET `EMAIL_MODE` = `EMAIL_STATE`, `EMAIL_STATE` = 'QUEUED', `EMAIL_ATTEMPT_COUNT` = 0
WHERE `EMAIL_MODE` IS NULL
  AND `EMAIL_STATE` IN ('IMMEDIATE_EMAIL', 'DAILY_DIGEST', 'WEEKLY_DIGEST');

UPDATE `WEB_USER_NOTIFICATION`
SET `EMAIL_MODE` = 'IN_APP_ONLY', `EMAIL_STATE` = 'IN_APP_ONLY',
    `EMAIL_CLAIM_TOKEN` = NULL, `EMAIL_CLAIM_UNTIL` = NULL, `DELIVERY_BATCH_ID` = NULL
WHERE `EMAIL_STATE` IN ('pending', 'SENDING', 'FAILED');
