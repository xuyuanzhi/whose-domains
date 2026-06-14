-- =============================================================================
-- wesite — full schema (canonical)
--
-- Run this once on a fresh database to create all tables.
-- For historical incremental migrations applied in production environments,
-- see doc/alter_history.sql.
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- SYS_USER — application users (admin + end users)
-- -----------------------------------------------------------------------------
CREATE TABLE `SYS_USER` (
  `ID`          varchar(32)  NOT NULL,
  `STATUS`      smallint(1)  DEFAULT '1',
  `DELETED`     smallint(1)  DEFAULT '0',
  `CREATE_BY`   varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY`   varchar(50),
  `UPDATE_TIME` datetime,
  `NAME`        varchar(100),
  `PHONE_NO`    varchar(50),
  `PASSWORD`    varchar(200),
  `SECURE_KEY`  varchar(200),
  `VCODE`       varchar(50),
  `VCODE_TIME`  datetime,
  `USER_TYPE`   varchar(20),
  PRIMARY KEY (`ID`),
  UNIQUE KEY `IDX_USER_PHONE` (`PHONE_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_DOMAIN_TLD — top-level domain registry profile (.com, .net, .io …)
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN_TLD` (
  `ID`                 varchar(32)  NOT NULL,
  `STATUS`             smallint(1)  DEFAULT '1',
  `DELETED`            smallint(1)  DEFAULT '0',
  `CREATE_BY`          varchar(50),
  `CREATE_TIME`        datetime,
  `UPDATE_BY`          varchar(50),
  `UPDATE_TIME`        datetime,
  `NAME`               varchar(100),
  `DOT_NAME`           varchar(100),
  `DISPLAY_NAME`       varchar(100),
  `TYPE`               varchar(50),
  `ORG_NAME`           varchar(200),
  `ORG_ADDR`           varchar(150),
  `ORG_ADDR2`          varchar(200),
  `ORG_STATE`          varchar(50),
  `ORG_CITY`           varchar(50),
  `ORG_COUNTRY`        varchar(100),
  `ORG_ZIP`            varchar(10),
  `ADMIN_NAME`         varchar(100),
  `ADMIN_ORG`          varchar(100),
  `ADMIN_EMAIL`        varchar(100),
  `ADMIN_PHONE`        varchar(50),
  `ADMIN_FAX`          varchar(50),
  `ADMIN_ADDR`         varchar(200),
  `ADMIN_ADDR2`        varchar(200),
  `ADMIN_STATE`        varchar(50),
  `ADMIN_CITY`         varchar(50),
  `ADMIN_COUNTRY`      varchar(100),
  `ADMIN_ZIP`          varchar(10),
  `TECH_NAME`          varchar(100),
  `TECH_ORG`           varchar(100),
  `TECH_EMAIL`         varchar(100),
  `TECH_PHONE`         varchar(50),
  `TECH_FAX`           varchar(50),
  `TECH_ADDR`          varchar(200),
  `TECH_ADDR2`         varchar(200),
  `TECH_STATE`         varchar(50),
  `TECH_CITY`          varchar(50),
  `TECH_COUNTRY`       varchar(100),
  `TECH_ZIP`           varchar(10),
  `REGISTRATION_URL`   varchar(100),
  `WHOIS_SERVER`       varchar(100),
  `RDAP_SERVER`        varchar(100),
  `LAST_UPDATED_DATE`  varchar(10),
  `REGISTRATION_DATE`  varchar(10),
  `SOURCE_URL`         varchar(100),
  `SOURCE_HTML`        text,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `IDX_DOMAIN_TLD_NAME` (`NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_DOMAIN_TLD_EXT — secondary-level extensions (e.g. .co.uk, .com.cn)
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN_TLD_EXT` (
  `ID`           varchar(32)  NOT NULL,
  `STATUS`       smallint(1)  DEFAULT '1',
  `DELETED`      smallint(1)  DEFAULT '0',
  `CREATE_BY`    varchar(50),
  `CREATE_TIME`  datetime,
  `UPDATE_BY`    varchar(50),
  `UPDATE_TIME`  datetime,
  `NAME`         varchar(10),
  `DOT_NAME`     varchar(100),
  `TLD_NAME`     varchar(50),
  `COUNTRY_NAME` varchar(100),
  `NOTE`         varchar(100),
  PRIMARY KEY (`ID`),
  UNIQUE KEY `IDX_EXT_NAME` (`NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_DOMAIN — individual domain registrations (WHOIS / RDAP cache)
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN` (
  `ID`                       varchar(32)  NOT NULL,
  `STATUS`                   smallint(1)  DEFAULT '1',
  `DELETED`                  smallint(1)  DEFAULT '0',
  `CREATE_BY`                varchar(50),
  `CREATE_TIME`              datetime,
  `UPDATE_BY`                varchar(50),
  `UPDATE_TIME`              datetime,
  `NAME`                     varchar(50),
  `TLD_NAME`                 varchar(50),
  `SLD_NAME`                 varchar(50),
  `ICP_NO`                   varchar(30),
  `ICP_NAME`                 varchar(50),
  `REGISTRY_DOMAIN_ID`       varchar(100),
  `REGISTRAR`                varchar(200),
  `REGISTRAR_IANA_ID`        varchar(50),
  `REGISTRAR_URL`            varchar(200),
  `DOMAIN_STATUS`            varchar(500),
  `NAME_SERVERS`             varchar(500),
  `REGIST_CREATE_DATE_TEXT`  varchar(50),
  `REGIST_UPDATE_DATE_TEXT`  varchar(50),
  `REGIST_EXPIRY_DATE_TEXT`  varchar(50),
  `REGISTRANT_ORG`           varchar(200),
  `REGISTRANT_NAME`          varchar(200),
  `REGISTRANT_CITY`          varchar(100),
  `REGISTRANT_STATE`         varchar(100),
  `REGISTRANT_COUNTRY`       varchar(100),
  `REGISTRANT_PHONE`         varchar(50),
  `REGISTRANT_EMAIL`         varchar(500),
  `TECH_NAME`                varchar(200),
  `TECH_PHONE`               varchar(200),
  `TECH_EMAIL`               varchar(200),
  `DNSSEC`                   varchar(50),
  `RETRY_DAYS`               smallint(5)  DEFAULT '30',
  `PARENT_WHOIS_SERVER`      varchar(200),
  `PARENT_WHOIS_TEXT`        longtext,
  `WHOIS_SERVER`             varchar(200),
  `WHOIS_TEXT`               longtext,
  `PARENT_RDAP_SERVER`       varchar(200),
  `PARENT_RDAP_TEXT`         longtext,
  `RDAP_SERVER`              varchar(200),
  `RDAP_TEXT`                longtext,
  `REFRESH_DNS_TIME`         datetime,
  `REFRESH_WEB_TIME`         datetime,
  `SOURCE_IP`                varchar(200),
  `REQUEST_REFRESH_TIME`     datetime,
  `REQUEST_REFRESH_IP`       varchar(50),
  PRIMARY KEY (`ID`),
  KEY `IDX_DOMAIN_NAME` (`NAME`),
  KEY `IDX_EXPIRY_DATE_STATUS` (`STATUS`, `DELETED`, `REGIST_EXPIRY_DATE_TEXT`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_DOMAIN_DNS — DNS records resolved per domain (A / AAAA / CNAME / MX / TXT)
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN_DNS` (
  `ID`          varchar(32) NOT NULL,
  `STATUS`      smallint(1) DEFAULT '1',
  `DELETED`     smallint(1) DEFAULT '0',
  `CREATE_BY`   varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY`   varchar(50),
  `UPDATE_TIME` datetime,
  `DOMAIN_ID`   varchar(32),
  `NAME`        varchar(100),
  `VALUE`       text,
  `TYPE`        varchar(20),
  `TTL`         int(10),
  `CITY_JSON`   text,
  `ASN_JSON`    text,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `IDX_NAME_VALUE_TYPE` (`DOMAIN_ID`, `NAME`, `VALUE`(255), `TYPE`),
  KEY `IDX_DOMAIN_STATUS` (`DOMAIN_ID`, `STATUS`, `DELETED`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_DOMAIN_SITE — discovered subdomains / hosted sites under a domain
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN_SITE` (
  `ID`                  varchar(32) NOT NULL,
  `STATUS`              smallint(1) DEFAULT '1',
  `DELETED`             smallint(1) DEFAULT '0',
  `CREATE_BY`           varchar(50),
  `CREATE_TIME`         datetime,
  `UPDATE_BY`           varchar(50),
  `UPDATE_TIME`         datetime,
  `DOMAIN_ID`           varchar(32) NOT NULL,
  `MAIN_NAME`           varchar(100) NOT NULL,
  `NAME`                varchar(100),
  `HOME_PAGE_URL`       varchar(500),
  `HOME_PAGE_HTML`      longtext,
  `HOME_PAGE_TITLE`     text,
  `HOME_PAGE_META_DESC` text,
  `SERVER_NAME`         varchar(200),
  `SERVER_LOCATION`     varchar(200),
  `SOURCE_IP`           varchar(200),
  `REFRESH_DNS_TIME`    datetime,
  `REFRESH_WEB_TIME`    datetime,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `IDX_SUB_DOMAIN_NAME` (`NAME`),
  KEY `IDX_DOMAIN_STATUS` (`DOMAIN_ID`, `STATUS`, `DELETED`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_DOMAIN_SNAPSHOT — historical WHOIS/RDAP snapshots for change tracking
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN_SNAPSHOT` (
  `ID`                      varchar(32) NOT NULL,
  `STATUS`                  smallint(1) DEFAULT '1',
  `DELETED`                 smallint(1) DEFAULT '0',
  `CREATE_BY`               varchar(50),
  `CREATE_TIME`             datetime,
  `UPDATE_BY`               varchar(50),
  `UPDATE_TIME`             datetime,
  `DOMAIN_NAME`             varchar(255) NOT NULL,
  `DOMAIN_ID`               varchar(32),
  `REGISTRAR`               varchar(255),
  `REGISTRAR_URL`           varchar(500),
  `REGIST_CREATE_DATE_TEXT` varchar(50),
  `REGIST_UPDATE_DATE_TEXT` varchar(50),
  `REGIST_EXPIRY_DATE_TEXT` varchar(50),
  `DOMAIN_STATUS`           varchar(500),
  `NAME_SERVERS`            varchar(500),
  `DNSSEC`                  varchar(50),
  `REGISTRANT_ORG`          varchar(255),
  `REGISTRANT_NAME`         varchar(255),
  `REGISTRANT_COUNTRY`      varchar(100),
  `REGISTRANT_STATE`        varchar(100),
  `REGISTRANT_CITY`         varchar(100),
  `REGISTRANT_EMAIL`        varchar(255),
  `TECH_NAME`               varchar(255),
  `TECH_EMAIL`              varchar(255),
  `WHOIS_TEXT`              mediumtext,
  `RDAP_TEXT`               mediumtext,
  `SNAPSHOT_TIME`           datetime NOT NULL,
  `SOURCE_IP`               varchar(50),
  PRIMARY KEY (`ID`),
  KEY `IDX_DOMAIN_NAME` (`DOMAIN_NAME`),
  KEY `IDX_SNAPSHOT_TIME` (`SNAPSHOT_TIME`),
  KEY `IDX_DOMAIN_SNAPSHOT` (`DOMAIN_NAME`, `SNAPSHOT_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_DOMAIN_WATCH — user-subscribed domains for expiry notifications
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN_WATCH` (
  `ID`                varchar(32) NOT NULL,
  `STATUS`            smallint(1) DEFAULT '1',
  `DELETED`           smallint(1) DEFAULT '0',
  `CREATE_BY`         varchar(50),
  `CREATE_TIME`       datetime,
  `UPDATE_BY`         varchar(50),
  `UPDATE_TIME`       datetime,
  `USER_ID`           varchar(32) NOT NULL,
  `DOMAIN_NAME`       varchar(255) NOT NULL,
  `DOMAIN_ID`         varchar(32),
  `REGISTRAR`         varchar(255),
  `EXPIRY_DATE_TEXT`  varchar(50),
  `EXPIRY_DATE`       datetime,
  `NOTIFY_TYPE`       smallint(1) DEFAULT '3',
  `LAST_NOTIFY_TIME`  datetime,
  `LAST_CHECK_TIME`   datetime,
  `REMARK`            varchar(500),
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_USER_DOMAIN` (`USER_ID`, `DOMAIN_NAME`),
  KEY `IDX_USER_ID` (`USER_ID`),
  KEY `IDX_EXPIRY_DATE` (`EXPIRY_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_DOMAIN_WATCH_NOTIFY_LOG — audit/throttle log for sent watch notifications
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN_WATCH_NOTIFY_LOG` (
  `ID`          varchar(32) NOT NULL,
  `STATUS`      smallint(1) DEFAULT '1',
  `DELETED`     smallint(1) DEFAULT '0',
  `CREATE_BY`   varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY`   varchar(50),
  `UPDATE_TIME` datetime,
  `WATCH_ID`    varchar(32) NOT NULL,
  `TO_EMAIL`    varchar(255),
  `DOMAIN_NAME` varchar(255),
  `DAYS_LEFT`   int(5),
  `SENT_AT`     datetime,
  `SEND_STATUS` smallint(1)  COMMENT '1=success, 2=fail',
  `ERROR_MSG`   varchar(1000),
  `RETRY_COUNT` int(3) DEFAULT '0',
  PRIMARY KEY (`ID`),
  KEY `IDX_WATCH_ID` (`WATCH_ID`),
  KEY `IDX_SENT_AT` (`SENT_AT`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_REGISTRAR — registrar reference data (IANA id lookup)
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_REGISTRAR` (
  `ID`          varchar(32) NOT NULL,
  `STATUS`      smallint(1) DEFAULT '1',
  `DELETED`     smallint(1) DEFAULT '0',
  `CREATE_BY`   varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY`   varchar(50),
  `UPDATE_TIME` datetime,
  `NAME`        varchar(255),
  `IANA_ID`     varchar(50),
  PRIMARY KEY (`ID`),
  UNIQUE KEY `IDX_IANA_ID` (`IANA_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- -----------------------------------------------------------------------------
-- WEB_BLOG_POST — blog articles
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_BLOG_POST` (
  `ID`               varchar(32)   NOT NULL,
  `SLUG`             varchar(200)  NOT NULL COMMENT 'URL-friendly identifier',
  `TITLE`            varchar(300)  NOT NULL,
  `SUMMARY`          varchar(600),
  `CONTENT`          longtext      COMMENT 'HTML body',
  `COVER`            varchar(500)  COMMENT 'Cover image URL',
  `AUTHOR`           varchar(100),
  `TAGS`             varchar(300)  COMMENT 'Comma-separated tags',
  `CATEGORY`         varchar(100)  COMMENT 'Category slug',
  `PUBLISH_DATE`     datetime,
  `VIEW_COUNT`       int           NOT NULL DEFAULT '0',
  `META_TITLE`       varchar(300)  COMMENT 'SEO meta title',
  `META_DESCRIPTION` varchar(600)  COMMENT 'SEO meta description',
  `STATUS`           tinyint(1)    NOT NULL DEFAULT '1' COMMENT '0=draft, 1=published',
  `DELETED`          tinyint(1)    NOT NULL DEFAULT '0',
  `CREATE_BY`        varchar(64),
  `CREATE_TIME`      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `UPDATE_BY`        varchar(64),
  `UPDATE_TIME`      datetime      NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `IDX_SLUG` (`SLUG`),
  KEY `IDX_CATEGORY_DATE` (`CATEGORY`, `PUBLISH_DATE`),
  KEY `IDX_STATUS_DATE`   (`STATUS`, `DELETED`, `PUBLISH_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Blog articles';

-- -----------------------------------------------------------------------------
-- WEB_TLD_CONTENT — long-form editorial content per TLD (intro, history, etc.)
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_TLD_CONTENT` (
  `ID`                         varchar(32) NOT NULL,
  `TLD`                        varchar(20) NOT NULL COMMENT 'TLD without dot, e.g. com / io / ai',
  `INTRO_HTML`                 text,
  `HISTORY_HTML`               text,
  `USE_CASES_HTML`             text,
  `FAMOUS_SITES_JSON`          text COMMENT 'JSON array of well-known sites',
  `REGISTER_REQUIREMENTS_HTML` text,
  `STATUS`                     tinyint(1) NOT NULL DEFAULT '1',
  `DELETED`                    tinyint(1) NOT NULL DEFAULT '0',
  `CREATE_BY`                  varchar(64),
  `CREATE_TIME`                datetime   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `UPDATE_BY`                  varchar(64),
  `UPDATE_TIME`                datetime   NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `IDX_TLD` (`TLD`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TLD long-form content';

-- -----------------------------------------------------------------------------
-- WEB_CONTACT_INFO — contact form submissions
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_CONTACT_INFO` (
  `ID`          varchar(32)  NOT NULL,
  `STATUS`      smallint(1)  DEFAULT '1',
  `DELETED`     smallint(1)  DEFAULT '0',
  `CREATE_BY`   varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY`   varchar(50),
  `UPDATE_TIME` datetime,
  `NAME`        varchar(100) NOT NULL,
  `EMAIL`       varchar(150) NOT NULL,
  `SUBJECT`     varchar(200) NOT NULL,
  `MESSAGE`     text         NOT NULL,
  `REQUEST_IP`  varchar(45),
  PRIMARY KEY (`ID`),
  KEY `IDX_CREATE_TIME` (`CREATE_TIME`),
  KEY `IDX_EMAIL` (`EMAIL`),
  KEY `IDX_REQUEST_IP` (`REQUEST_IP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Contact form submissions';

-- -----------------------------------------------------------------------------
-- WEB_USER_QUERY_HISTORY — per-user query history across all tools
-- -----------------------------------------------------------------------------
CREATE TABLE `WEB_USER_QUERY_HISTORY` (
  `ID`             varchar(32)  NOT NULL,
  `USER_ID`        varchar(32)  COMMENT 'User ID (nullable for anonymous)',
  `QUERY_TYPE`     varchar(20)  NOT NULL COMMENT 'WHOIS/RDAP/DNS/AVAILABILITY/BULK/SSL/IP/EMAIL/VALUATION/PORT/PING/SCORE',
  `QUERY_VALUE`    varchar(500) NOT NULL,
  `RESULT_SUMMARY` varchar(500),
  `STATUS`         tinyint(1)   NOT NULL DEFAULT '1',
  `DELETED`        tinyint(1)   NOT NULL DEFAULT '0',
  `CREATE_BY`      varchar(64),
  `CREATE_TIME`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `UPDATE_BY`      varchar(64),
  `UPDATE_TIME`    datetime     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ID`),
  KEY `IDX_USER_TYPE_TIME` (`USER_ID`, `QUERY_TYPE`, `CREATE_TIME`),
  KEY `IDX_USER_TIME`      (`USER_ID`, `CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User query history';

SET FOREIGN_KEY_CHECKS = 1;
