-- =============================================================================
-- Historical schema migrations (archive)
--
-- This file is NOT needed for fresh installs — use create.sql instead, which
-- already contains the consolidated final schema.
--
-- These statements are kept only for upgrading an OLD production database
-- whose schema predates the current create.sql. They are listed in the order
-- they were originally applied.
--
-- Notable historical events:
--   * 2025-10-22: tables `site_domain` / `site_domain_dns` were renamed to
--                 `WEB_DOMAIN` / `WEB_DOMAIN_DNS`.
--   * Many `SITE_DOMAIN` ALTERs below predate the rename — apply them only on
--     databases that still carry the old names.
-- =============================================================================

-- ----------------------------------------------------------------------------
-- Pre-2025 cleanup
-- ----------------------------------------------------------------------------
ALTER TABLE SITE_DOMAIN ADD RETRY_DAYS SMALLINT(5) DEFAULT 30;

ALTER TABLE SITE_DOMAIN add column PARENT_WHOIS_SERVER varchar(200);
ALTER TABLE SITE_DOMAIN add column PARENT_WHOIS_TEXT longtext;
ALTER TABLE SITE_DOMAIN add column PARENT_RDAP_SERVER varchar(200);
ALTER TABLE SITE_DOMAIN add column PARENT_RDAP_TEXT longtext;
ALTER TABLE SITE_DOMAIN modify REGISTRANT_EMAIL varchar(200);
ALTER TABLE SITE_DOMAIN modify TECH_EMAIL varchar(200);
ALTER TABLE SITE_DOMAIN modify REGISTRANT_STATE varchar(100);
ALTER TABLE SITE_DOMAIN modify REGISTRY_DOMAIN_ID varchar(100);
ALTER TABLE SITE_DOMAIN modify NAME_SERVERS varchar(500);

-- 20251019
ALTER TABLE SITE_DOMAIN add column REFRESH_DNS_TIME datetime;
ALTER TABLE SITE_DOMAIN change TITLE HOME_PAGE_TITLE text;
ALTER TABLE SITE_DOMAIN change META_DESC HOME_PAGE_META_DESC text;
ALTER TABLE SITE_DOMAIN add column HOME_PAGE_URL varchar(500);
ALTER TABLE SITE_DOMAIN add column HOME_PAGE_HTML longtext;
ALTER TABLE SITE_DOMAIN modify REGISTRANT_NAME varchar(200);

-- 20251020
ALTER TABLE SITE_DOMAIN add column REFRESH_WEB_TIME datetime;
ALTER TABLE SITE_DOMAIN_DNS modify `VALUE` text;

-- 20251021
ALTER TABLE SITE_DOMAIN modify TECH_NAME varchar(200);
ALTER TABLE SITE_DOMAIN modify TECH_PHONE varchar(200);
ALTER TABLE site_domain_ext modify COUNTRY_NAME varchar(100);

-- 20251022 — RENAME site_domain / site_domain_dns to WEB_DOMAIN / WEB_DOMAIN_DNS
alter table site_domain rename to WEB_DOMAIN;
alter table site_domain_dns rename to WEB_DOMAIN_DNS;
ALTER TABLE WEB_DOMAIN_SITE ADD UNIQUE INDEX `IDX_SUB_DOMAIN_NAME` (`NAME` ASC) VISIBLE;
ALTER TABLE WEB_DOMAIN modify registrant_email varchar(500);

-- 20251023
ALTER TABLE WEB_DOMAIN_DNS ADD UNIQUE INDEX `IDX_NAME_VALUE_TYPE` (DOMAIN_ID asc, `NAME` ASC, `VALUE` ASC, `TYPE` ASC) VISIBLE;

ALTER TABLE `web_domain_site`
CHANGE COLUMN `DOMAIN_ID` `DOMAIN_ID` VARCHAR(32) NOT NULL ,
CHANGE COLUMN `MAIN_NAME` `MAIN_NAME` VARCHAR(100) NOT NULL ;

-- 20251108
ALTER TABLE WEB_DOMAIN add request_refresh_time datetime;
ALTER TABLE WEB_DOMAIN add request_refresh_ip varchar(50);

-- 20251110
ALTER TABLE WEB_DOMAIN_TLD add ORG_ADDR2 varchar(200);
ALTER TABLE WEB_DOMAIN_TLD add ADMIN_ADDR2 varchar(200);
ALTER TABLE WEB_DOMAIN_TLD add TECH_ADDR2 varchar(200);
ALTER TABLE WEB_DOMAIN_TLD modify ORG_COUNTRY varchar(100);
ALTER TABLE WEB_DOMAIN_TLD modify ADMIN_COUNTRY varchar(100);
ALTER TABLE WEB_DOMAIN_TLD modify TECH_COUNTRY varchar(100);
ALTER TABLE WEB_DOMAIN_TLD modify ORG_ADDR varchar(150);
ALTER TABLE WEB_DOMAIN_TLD modify ORG_NAME varchar(200);
ALTER TABLE WEB_DOMAIN_TLD modify ADMIN_ADDR varchar(200);
ALTER TABLE WEB_DOMAIN_TLD add LAST_UPDATED_DATE varchar(10);
ALTER TABLE WEB_DOMAIN_TLD add REGISTRATION_DATE varchar(10);

-- 20251115
ALTER TABLE WEB_DOMAIN_DNS ADD INDEX `IDX_DOMAIN_STATUS` (DOMAIN_ID asc, `STATUS` ASC, `DELETED` ASC) VISIBLE;
ALTER TABLE WEB_DOMAIN_SITE ADD INDEX `IDX_DOMAIN_STATUS` (DOMAIN_ID asc, `STATUS` ASC, `DELETED` ASC) VISIBLE;

-- 20251116
ALTER TABLE WEB_DOMAIN_TLD add DOT_NAME varchar(100);
ALTER TABLE WEB_DOMAIN_TLD_EXT add DOT_NAME varchar(100);

-- 20251117
ALTER TABLE WEB_DOMAIN add TLD_NAME varchar(50);
ALTER TABLE WEB_DOMAIN add SLD_NAME varchar(50);
ALTER TABLE WEB_DOMAIN_TLD_EXT add TLD_NAME varchar(50);

-- 20251119
ALTER TABLE WEB_DOMAIN_DNS add CITY_JSON text;
ALTER TABLE WEB_DOMAIN_DNS add ASN_JSON text;

-- ----------------------------------------------------------------------------
-- 20251215 (originally alter_domain_watch_snapshot.sql) — new tables
-- ----------------------------------------------------------------------------
CREATE TABLE `WEB_DOMAIN_WATCH` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `USER_ID` varchar(32) NOT NULL,
  `DOMAIN_NAME` varchar(255) NOT NULL,
  `DOMAIN_ID` varchar(32),
  `REGISTRAR` varchar(255),
  `EXPIRY_DATE_TEXT` varchar(50),
  `EXPIRY_DATE` datetime,
  `NOTIFY_TYPE` smallint(1) DEFAULT '3',
  `LAST_NOTIFY_TIME` datetime,
  `LAST_CHECK_TIME` datetime,
  `REMARK` varchar(500),
  PRIMARY KEY (`ID`),
  UNIQUE KEY `UK_USER_DOMAIN` (`USER_ID`, `DOMAIN_NAME`),
  KEY `IDX_USER_ID` (`USER_ID`),
  KEY `IDX_EXPIRY_DATE` (`EXPIRY_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `WEB_DOMAIN_SNAPSHOT` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `DOMAIN_NAME` varchar(255) NOT NULL,
  `DOMAIN_ID` varchar(32),
  `REGISTRAR` varchar(255),
  `REGISTRAR_URL` varchar(500),
  `REGIST_CREATE_DATE_TEXT` varchar(50),
  `REGIST_UPDATE_DATE_TEXT` varchar(50),
  `REGIST_EXPIRY_DATE_TEXT` varchar(50),
  `DOMAIN_STATUS` varchar(500),
  `NAME_SERVERS` varchar(500),
  `DNSSEC` varchar(50),
  `REGISTRANT_ORG` varchar(255),
  `REGISTRANT_NAME` varchar(255),
  `REGISTRANT_COUNTRY` varchar(100),
  `REGISTRANT_STATE` varchar(100),
  `REGISTRANT_CITY` varchar(100),
  `REGISTRANT_EMAIL` varchar(255),
  `TECH_NAME` varchar(255),
  `TECH_EMAIL` varchar(255),
  `WHOIS_TEXT` mediumtext,
  `RDAP_TEXT` mediumtext,
  `SNAPSHOT_TIME` datetime NOT NULL,
  `SOURCE_IP` varchar(50),
  PRIMARY KEY (`ID`),
  KEY `IDX_DOMAIN_NAME` (`DOMAIN_NAME`),
  KEY `IDX_SNAPSHOT_TIME` (`SNAPSHOT_TIME`),
  KEY `IDX_DOMAIN_SNAPSHOT` (`DOMAIN_NAME`, `SNAPSHOT_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- ----------------------------------------------------------------------------
-- 20260301 (originally alter_contact_info_table.sql)
-- ----------------------------------------------------------------------------
CREATE TABLE `WEB_CONTACT_INFO` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `NAME` varchar(100) NOT NULL COMMENT '联系人姓名',
  `EMAIL` varchar(100) NOT NULL COMMENT '联系人邮箱',
  `SUBJECT` varchar(200) NOT NULL COMMENT '主题',
  `MESSAGE` text NOT NULL COMMENT '留言内容',
  `REQUEST_IP` varchar(50) COMMENT '请求IP地址',
  PRIMARY KEY (`ID`),
  INDEX `IDX_EMAIL` (`EMAIL`),
  INDEX `IDX_CREATE_TIME` (`CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='联系表单信息表';

-- 20260414 — speed up expiring-domains range queries
ALTER TABLE WEB_DOMAIN ADD INDEX `IDX_EXPIRY_DATE_STATUS` (`STATUS` ASC, `DELETED` ASC, `REGIST_EXPIRY_DATE_TEXT` ASC) VISIBLE;

-- 20260426 — user query history
CREATE TABLE IF NOT EXISTS `WEB_USER_QUERY_HISTORY` (
  `ID`             VARCHAR(32)   NOT NULL COMMENT '主键',
  `USER_ID`        VARCHAR(32)   NULL     COMMENT '用户ID（未登录可为空）',
  `QUERY_TYPE`     VARCHAR(20)   NOT NULL COMMENT '查询类型：WHOIS/RDAP/DNS/AVAILABILITY/BULK/SSL/IP/EMAIL/VALUATION/PORT/PING/SCORE',
  `QUERY_VALUE`    VARCHAR(500)  NOT NULL COMMENT '查询值，如 example.com 或 user@example.com',
  `RESULT_SUMMARY` VARCHAR(500)  NULL     COMMENT '结果摘要（简短文本）',
  `STATUS`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '状态 1=正常',
  `DELETED`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删除',
  `CREATE_BY`      VARCHAR(64)  NULL,
  `CREATE_TIME`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `UPDATE_BY`      VARCHAR(64)  NULL,
  `UPDATE_TIME`    DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ID`),
  INDEX `IDX_USER_TYPE_TIME` (`USER_ID` ASC, `QUERY_TYPE` ASC, `CREATE_TIME` DESC),
  INDEX `IDX_USER_TIME`      (`USER_ID` ASC, `CREATE_TIME` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户查询历史记录';

-- 20260426 — blog
CREATE TABLE IF NOT EXISTS `WEB_BLOG_POST` (
  `ID`               VARCHAR(32)   NOT NULL COMMENT '主键',
  `SLUG`             VARCHAR(200)  NOT NULL COMMENT 'URL 友好标识',
  `TITLE`            VARCHAR(300)  NOT NULL COMMENT '文章标题',
  `SUMMARY`          VARCHAR(600)  NULL     COMMENT '摘要',
  `CONTENT`          LONGTEXT      NULL     COMMENT '正文 HTML',
  `COVER`            VARCHAR(500)  NULL     COMMENT '封面图片 URL',
  `AUTHOR`           VARCHAR(100)  NULL     COMMENT '作者',
  `TAGS`             VARCHAR(300)  NULL     COMMENT '标签（逗号分隔）',
  `CATEGORY`         VARCHAR(100)  NULL     COMMENT '分类 slug',
  `PUBLISH_DATE`     DATETIME      NULL     COMMENT '发布日期',
  `VIEW_COUNT`       INT           NOT NULL DEFAULT 0 COMMENT '浏览次数',
  `META_TITLE`       VARCHAR(300)  NULL     COMMENT 'SEO meta title',
  `META_DESCRIPTION` VARCHAR(600)  NULL     COMMENT 'SEO meta description',
  `STATUS`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '状态 0=草稿 1=已发布',
  `DELETED`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `CREATE_BY`        VARCHAR(64)  NULL,
  `CREATE_TIME`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `UPDATE_BY`        VARCHAR(64)  NULL,
  `UPDATE_TIME`      DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `IDX_SLUG` (`SLUG` ASC),
  INDEX `IDX_CATEGORY_DATE` (`CATEGORY` ASC, `PUBLISH_DATE` DESC),
  INDEX `IDX_STATUS_DATE`   (`STATUS` ASC, `DELETED` ASC, `PUBLISH_DATE` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Blog 文章';

-- 20260426 — TLD long-form content
CREATE TABLE IF NOT EXISTS `WEB_TLD_CONTENT` (
  `ID`                    VARCHAR(32)   NOT NULL COMMENT '主键',
  `TLD`                   VARCHAR(20)   NOT NULL COMMENT 'TLD 不含点，如 com / io / ai',
  `INTRO_HTML`            TEXT          NULL     COMMENT '简介 HTML',
  `HISTORY_HTML`          TEXT          NULL     COMMENT '历史背景 HTML',
  `USE_CASES_HTML`        TEXT          NULL     COMMENT '典型用途 HTML',
  `FAMOUS_SITES_JSON`     TEXT          NULL     COMMENT '知名网站 JSON 数组',
  `REGISTER_REQUIREMENTS_HTML` TEXT     NULL     COMMENT '注册要求 HTML',
  `STATUS`                TINYINT(1)   NOT NULL DEFAULT 1,
  `DELETED`               TINYINT(1)   NOT NULL DEFAULT 0,
  `CREATE_BY`             VARCHAR(64)  NULL,
  `CREATE_TIME`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `UPDATE_BY`             VARCHAR(64)  NULL,
  `UPDATE_TIME`           DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `IDX_TLD` (`TLD` ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TLD 内容详情';
