-- Disposable verifier fixture copied from the relevant schema at git e73ff4d.
-- In particular, that checked-in baseline did not yet contain NOTIFY_EMAIL.
-- Never run this fixture in production.
CREATE TABLE `WEB_DOMAIN_WATCH` (
  `ID` varchar(32) NOT NULL, `STATUS` smallint(1) DEFAULT 1, `DELETED` smallint(1) DEFAULT 0,
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL, `DOMAIN_NAME` varchar(255) NOT NULL, `DOMAIN_ID` varchar(32),
  `REGISTRAR` varchar(255), `EXPIRY_DATE_TEXT` varchar(50), `EXPIRY_DATE` datetime,
  `NOTIFY_TYPE` smallint(1) DEFAULT 3, `LAST_NOTIFY_TIME` datetime, `LAST_CHECK_TIME` datetime,
  `REMARK` varchar(500), PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_USER_DOMAIN` (`USER_ID`,`DOMAIN_NAME`), KEY `IDX_USER_ID` (`USER_ID`),
  KEY `IDX_EXPIRY_DATE` (`EXPIRY_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_MONITOR_SNAPSHOT` (
  `ID` varchar(32) NOT NULL, `STATUS` smallint(1) DEFAULT 1, `DELETED` smallint(1) DEFAULT 0,
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `WATCH_ID` varchar(32) NOT NULL, `CHECKED_AT` datetime NOT NULL, `STATE_JSON` mediumtext NOT NULL,
  PRIMARY KEY (`ID`), KEY `IDX_MONITOR_SNAPSHOT_WATCH_CHECKED` (`WATCH_ID`,`CHECKED_AT`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_MONITOR_EVENT` (
  `ID` varchar(32) NOT NULL, `STATUS` smallint(1) DEFAULT 1, `DELETED` smallint(1) DEFAULT 0,
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `WATCH_ID` varchar(32) NOT NULL, `SNAPSHOT_ID` varchar(32), `FINGERPRINT` varchar(128) NOT NULL,
  `EVENT_TYPE` varchar(64) NOT NULL, `OLD_VALUE` text, `NEW_VALUE` text, `OCCURRED_AT` datetime NOT NULL,
  PRIMARY KEY (`ID`), UNIQUE KEY `UK_MONITOR_EVENT_WATCH_FINGERPRINT` (`WATCH_ID`,`FINGERPRINT`),
  KEY `IDX_MONITOR_EVENT_WATCH_OCCURRED` (`WATCH_ID`,`OCCURRED_AT`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_USER_NOTIFICATION` (
  `ID` varchar(32) NOT NULL, `STATUS` smallint(1) DEFAULT 1, `DELETED` smallint(1) DEFAULT 0,
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL, `EVENT_ID` varchar(32) NOT NULL, `TITLE` varchar(255) NOT NULL,
  `CONTENT` text, `TARGET_PATH` varchar(500) NOT NULL, `READ_AT` datetime,
  `EMAIL_STATE` varchar(32) NOT NULL DEFAULT 'pending', `EMAILED_AT` datetime,
  PRIMARY KEY (`ID`), UNIQUE KEY `UK_USER_NOTIFICATION_USER_EVENT` (`USER_ID`,`EVENT_ID`),
  KEY `IDX_USER_NOTIFICATION_USER_READ_CREATED` (`USER_ID`,`READ_AT`,`CREATE_TIME`),
  KEY `IDX_USER_NOTIFICATION_EMAIL_CREATED` (`EMAIL_STATE`,`CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_NOTIFICATION_PREFERENCE` (
  `ID` varchar(32) NOT NULL, `STATUS` smallint(1) DEFAULT 1, `DELETED` smallint(1) DEFAULT 0,
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL, `EMAIL_MODE` varchar(32) NOT NULL DEFAULT 'DAILY',
  `DOMAIN_EXPIRY_ENABLED` smallint(1) NOT NULL DEFAULT 1, `SSL_EXPIRY_ENABLED` smallint(1) NOT NULL DEFAULT 1,
  `DOMAIN_STATUS_ENABLED` smallint(1) NOT NULL DEFAULT 1, `DNS_CHANGE_ENABLED` smallint(1) NOT NULL DEFAULT 1,
  `WEBSITE_AVAILABILITY_ENABLED` smallint(1) NOT NULL DEFAULT 1, PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_NOTIFICATION_PREFERENCE_USER` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_DOMAIN_WATCH_NOTIFY_LOG` (
  `ID` varchar(32) NOT NULL, `STATUS` smallint(1) DEFAULT 1, `DELETED` smallint(1) DEFAULT 0,
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `NOTIFICATION_ID` varchar(32), `EVENT_ID` varchar(32), `DELIVERY_MODE` varchar(32), `SUBJECT` varchar(255),
  `WATCH_ID` varchar(32) NOT NULL, `TO_EMAIL` varchar(255), `DOMAIN_NAME` varchar(255), `DAYS_LEFT` int,
  `SENT_AT` datetime, `SEND_STATUS` smallint(1), `ERROR_MSG` varchar(1000), `RETRY_COUNT` int DEFAULT 0,
  PRIMARY KEY (`ID`), KEY `IDX_WATCH_ID` (`WATCH_ID`), KEY `IDX_SENT_AT` (`SENT_AT`),
  KEY `IDX_NOTIFY_LOG_NOTIFICATION_MODE_RETRY` (`NOTIFICATION_ID`,`DELIVERY_MODE`,`RETRY_COUNT`),
  KEY `IDX_NOTIFY_LOG_EVENT` (`EVENT_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO `WEB_DOMAIN_WATCH`
  (`ID`,`STATUS`,`DELETED`,`USER_ID`,`DOMAIN_NAME`,`NOTIFY_TYPE`,`CREATE_TIME`) VALUES
  ('w-none',1,0,'u-none','none.example',0,'2026-08-01 00:00:00'),
  ('w-seven',1,0,'u-seven','seven.example',1,'2026-08-01 16:30:00'),
  ('w-no-email',1,0,'u-no-email','no-email.example',3,'2026-08-01 00:00:00');

INSERT INTO `WEB_MONITOR_EVENT`
  (`ID`,`STATUS`,`DELETED`,`WATCH_ID`,`FINGERPRINT`,`EVENT_TYPE`,`NEW_VALUE`,`OCCURRED_AT`) VALUES
  ('e-none',1,0,'w-none','fp-none','DNS_CHANGED','203.0.113.8','2026-08-09 00:00:00'),
  ('e-seven',1,0,'w-seven','fp-seven','DOMAIN_EXPIRING','2026-08-16','2026-08-09 00:00:00'),
  ('e-thirty',1,0,'w-seven','fp-thirty','DOMAIN_EXPIRING','2026-09-08','2026-08-09 00:00:00'),
  ('e-no-email',1,0,'w-no-email','fp-no-email','DNS_CHANGED','203.0.113.9','2026-08-09 00:00:00');

INSERT INTO `WEB_USER_NOTIFICATION`
  (`ID`,`STATUS`,`DELETED`,`USER_ID`,`EVENT_ID`,`TITLE`,`TARGET_PATH`,`EMAIL_STATE`,`CREATE_TIME`) VALUES
  ('n-none',1,0,'u-none','e-none','none','/domain/none.example','IMMEDIATE_EMAIL','2026-08-09 00:00:00'),
  ('n-seven',1,0,'u-seven','e-seven','seven','/domain/seven.example','IMMEDIATE_EMAIL','2026-08-09 00:00:00'),
  ('n-thirty',1,0,'u-seven','e-thirty','thirty','/domain/seven.example','DAILY_DIGEST','2026-08-09 00:00:00'),
  ('n-no-email',1,0,'u-no-email','e-no-email','no email','/domain/no-email.example','IMMEDIATE_EMAIL','2026-08-09 00:00:00');
