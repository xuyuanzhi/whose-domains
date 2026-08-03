-- P4: developer API keys and account-level daily usage.
CREATE TABLE `WEB_API_KEY` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1', `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL, `NAME` varchar(100) NOT NULL,
  `KEY_PREFIX` varchar(16) NOT NULL, `KEY_HASH` varchar(64) NOT NULL,
  `LAST_USED_AT` datetime, `REVOKED_AT` datetime,
  PRIMARY KEY (`ID`), UNIQUE KEY `UK_API_KEY_HASH` (`KEY_HASH`), KEY `IDX_API_KEY_USER` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_API_USAGE_DAILY` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1', `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL, `USAGE_DATE` char(10) NOT NULL, `REQUEST_COUNT` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`ID`), UNIQUE KEY `UK_API_USAGE_USER_DAY` (`USER_ID`, `USAGE_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
