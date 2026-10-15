-- API plans and account-level quota configuration.
--
-- Applied out of band (no migration framework). Safe to run once on a database where
-- WEB_API_PLAN does not yet exist. Re-running the CREATE statement will fail by design.
--
-- Prices/limits are seeded as *defaults* and are meant to be edited by an operator;
-- they are not referenced by hard-coded business logic. price_cents = minor units.

CREATE TABLE `WEB_API_PLAN` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1', `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50), `CREATE_TIME` datetime, `UPDATE_BY` varchar(50), `UPDATE_TIME` datetime,
  `CODE` varchar(32) NOT NULL,
  `NAME` varchar(64) NOT NULL,
  `DESCRIPTION` varchar(255),
  `PRICE_CENTS` int NOT NULL DEFAULT 0,
  `CURRENCY` char(3) NOT NULL DEFAULT 'USD',
  `BILLING_PERIOD` varchar(16) NOT NULL DEFAULT 'NONE',
  `DAILY_REQUEST_LIMIT` int NOT NULL DEFAULT 0,
  `MONTHLY_REQUEST_LIMIT` int NOT NULL DEFAULT 0,
  `MAX_DOMAINS_PER_BATCH` int NOT NULL DEFAULT 0,
  `REQUESTS_PER_MINUTE` int NOT NULL DEFAULT 0,
  `MAX_ACTIVE_KEYS` int NOT NULL DEFAULT 0,
  `SORT_ORDER` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`ID`), UNIQUE KEY `UK_API_PLAN_CODE` (`CODE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO `WEB_API_PLAN`
  (`ID`, `STATUS`, `DELETED`, `CREATE_TIME`, `CODE`, `NAME`, `DESCRIPTION`,
   `PRICE_CENTS`, `CURRENCY`, `BILLING_PERIOD`, `DAILY_REQUEST_LIMIT`, `MONTHLY_REQUEST_LIMIT`,
   `MAX_DOMAINS_PER_BATCH`, `REQUESTS_PER_MINUTE`, `MAX_ACTIVE_KEYS`, `SORT_ORDER`)
VALUES
  ('plan_free_00000000000000000001', 1, 0, NOW(), 'free', 'Free',
   'Free tier for evaluation and light usage.', 0, 'USD', 'NONE', 100, 3000, 10, 120, 3, 0),
  ('plan_dev_000000000000000000002', 1, 0, NOW(), 'developer', 'Developer',
   'For independent developers and small projects.', 900, 'USD', 'MONTHLY', 2000, 60000, 50, 600, 10, 10),
  ('plan_biz_000000000000000000003', 1, 0, NOW(), 'business', 'Business',
   'High-volume and commercial applications.', 4900, 'USD', 'MONTHLY', 20000, 600000, 100, 3000, 25, 20);

-- Every account has a plan; NULL is treated as the free plan.
ALTER TABLE `SYS_USER` ADD COLUMN `PLAN_ID` varchar(32) NULL, ADD KEY `IDX_USER_PLAN` (`PLAN_ID`);
