-- MySQL 8.0.36+. Apply only after the compatible Web/Admin version is deployed.
-- Pause ALL DNS writers and drain in-flight refreshes. Recheck exact duplicates first.
-- Select the intended database explicitly. Run each statement separately; STOP on error.
-- Only the verified obsolete prefix index is dropped, AFTER new protection exists.
-- No rows are deleted and no IGNORE/REPLACE is used. STOP on a STOP result below.
-- DDL commits independently. If the first ALTER succeeds and the second fails,
-- retain the column, resolve duplicates, and rerun ONLY the second ALTER.
SET SESSION lock_wait_timeout = 15;

ALTER TABLE WEB_DOMAIN_DNS
    ADD COLUMN DNS_LIVE_FINGERPRINT BINARY(32)
    GENERATED ALWAYS AS (
        CASE WHEN DELETED = 0 THEN
            UNHEX(SHA2(CONCAT(
                HEX(DOMAIN_ID), ':', HEX(NAME), ':', HEX(TYPE), ':', HEX(VALUE)
            ), 256))
        ELSE NULL END
    ) VIRTUAL,
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE WEB_DOMAIN_DNS
    ADD UNIQUE INDEX UK_DNS_LIVE_FINGERPRINT (DNS_LIVE_FINGERPRINT),
    ALGORITHM=INPLACE, LOCK=NONE;

-- Retire legacy prefix index only after new protection is ready.
-- No stored routine privilege is needed. Keep this section in ONE connection.
SELECT COUNT(*) = 1 AND COALESCE(SUM(
    NON_UNIQUE = 0 AND COLUMN_NAME = 'DNS_LIVE_FINGERPRINT'
    AND SEQ_IN_INDEX = 1 AND SUB_PART IS NULL AND EXPRESSION IS NULL
    AND INDEX_TYPE = 'BTREE' AND IS_VISIBLE = 'YES'
), 0) = 1 INTO @dns_new_index_ready
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = 'web_domain_dns'
  AND INDEX_NAME = 'UK_DNS_LIVE_FINGERPRINT';

SELECT COUNT(*), COALESCE(SUM(
    NON_UNIQUE = 0 AND INDEX_TYPE = 'BTREE' AND EXPRESSION IS NULL
    AND COLLATION = 'A' AND (
        (SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'DOMAIN_ID' AND SUB_PART IS NULL) OR
        (SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'NAME' AND SUB_PART IS NULL) OR
        (SEQ_IN_INDEX = 3 AND COLUMN_NAME = 'VALUE' AND SUB_PART = 255) OR
        (SEQ_IN_INDEX = 4 AND COLUMN_NAME = 'TYPE' AND SUB_PART IS NULL)
    )
), 0) INTO @dns_legacy_parts, @dns_legacy_matching_parts
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = 'web_domain_dns'
  AND INDEX_NAME = 'IDX_NAME_VALUE_TYPE';

SET @dns_legacy_index_action = CASE
    WHEN @dns_new_index_ready <> 1 THEN
        'SELECT ''STOP: verified new unique index is missing; legacy index retained'' AS migration_status'
    WHEN @dns_legacy_parts = 0 THEN
        'SELECT ''OK: no legacy prefix index exists'' AS migration_status'
    WHEN @dns_legacy_parts <> 4 OR @dns_legacy_matching_parts <> 4 THEN
        'SELECT ''STOP: unexpected legacy index definition; inspect SHOW CREATE TABLE before proceeding'' AS migration_status'
    ELSE
        'ALTER TABLE WEB_DOMAIN_DNS DROP INDEX IDX_NAME_VALUE_TYPE, ALGORITHM=INPLACE, LOCK=NONE'
END;
PREPARE dns_legacy_index_stmt FROM @dns_legacy_index_action;
EXECUTE dns_legacy_index_stmt;
DEALLOCATE PREPARE dns_legacy_index_stmt;

SHOW INDEX FROM WEB_DOMAIN_DNS WHERE Key_name = 'UK_DNS_LIVE_FINGERPRINT';
SHOW INDEX FROM WEB_DOMAIN_DNS WHERE Key_name = 'IDX_NAME_VALUE_TYPE';
-- Require new Non_unique=0, Visible=YES AND no old index rows.
-- A STOP result means migration is incomplete; do not resume writers.
-- Other differently named unique indexes are never removed: inspect them separately.
-- STATUS deliberately is not part of identity: inactive records may be reactivated.
-- NULL identity components yield NULL and are not constrained, matching prior cleanup scope.
-- SHA-256 collisions are extraordinarily unlikely; the application checks full bytes on conflict.