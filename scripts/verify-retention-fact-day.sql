-- Required caller-owned session variables:
-- @reporting_time_zone, @verified_fact_date, @external_expected_rows,
-- @reconciliation_source, @reconciliation_id.
-- The expected count MUST come from an independent system, never this database.
SET time_zone = @reporting_time_zone;

DELIMITER //
DROP PROCEDURE IF EXISTS verify_retention_fact_day//
CREATE PROCEDURE verify_retention_fact_day()
BEGIN
  DECLARE actual_rows BIGINT UNSIGNED;
  IF @reporting_time_zone IS NULL OR @verified_fact_date IS NULL
     OR @external_expected_rows IS NULL
     OR NULLIF(TRIM(@reconciliation_source), '') IS NULL
     OR NULLIF(TRIM(@reconciliation_id), '') IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'missing explicit retention reconciliation input';
  END IF;
  SELECT COUNT(DISTINCT USER_ID) INTO actual_rows
  FROM WEB_AUTHENTICATED_ACTIVITY_DAILY WHERE ACTIVITY_DATE = @verified_fact_date;
  IF actual_rows <> @external_expected_rows THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'external expected rows do not match distinct activity facts';
  END IF;
  UPDATE WEB_RETENTION_FACT_HEALTH
  SET VERIFICATION_STATUS='VERIFIED', VERIFIED_AT=NOW(),
      EXTERNAL_EXPECTED_ROWS=@external_expected_rows,
      RECONCILIATION_SOURCE=TRIM(@reconciliation_source),
      RECONCILIATION_ID=TRIM(@reconciliation_id)
  WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=@verified_fact_date
    AND FAILURE_COUNT=0 AND EXPECTED_FACT_ROWS=actual_rows;
  IF ROW_COUNT() <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'health row is missing, failed, or inconsistent';
  END IF;
  UPDATE WEB_RETENTION_FACT_COLLECTION
  SET COLLECTION_STARTED_ON=COALESCE(LEAST(COLLECTION_STARTED_ON,@verified_fact_date),@verified_fact_date),
      UPDATE_TIME=NOW()
  WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY';
END//
DELIMITER ;
CALL verify_retention_fact_day();
DROP PROCEDURE verify_retention_fact_day;

SELECT FACT_DATE,VERIFICATION_STATUS,EXTERNAL_EXPECTED_ROWS,
       RECONCILIATION_SOURCE,RECONCILIATION_ID,VERIFIED_AT
FROM WEB_RETENTION_FACT_HEALTH
WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=@verified_fact_date;
