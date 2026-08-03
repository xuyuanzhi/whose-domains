-- Apply once to an existing Whose.Domains database before deploying P1.
ALTER TABLE `SYS_USER`
  ADD COLUMN `EMAIL` varchar(255) NULL AFTER `PHONE_NO`,
  ADD UNIQUE KEY `IDX_USER_EMAIL` (`EMAIL`);

CREATE TABLE `WEB_EMAIL_LOGIN_LINK` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `EMAIL` varchar(255) NOT NULL,
  `TOKEN_HASH` varchar(64) NOT NULL,
  `USER_ID` varchar(32),
  `EXPIRES_AT` datetime NOT NULL,
  `CONSUMED_AT` datetime,
  `REDIRECT_PATH` varchar(500),
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_EMAIL_LOGIN_TOKEN` (`TOKEN_HASH`),
  KEY `IDX_EMAIL_LOGIN_EXPIRY` (`EXPIRES_AT`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
