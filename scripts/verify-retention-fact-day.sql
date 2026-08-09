-- Run only after an independent source has confirmed that the given reporting
-- day is complete. Set these session variables deliberately; never automate
-- this statement from the application writer.
SET @reporting_time_zone = '+08:00';
SET time_zone = @reporting_time_zone;
SET @verified_fact_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY);

UPDATE WEB_RETENTION_FACT_HEALTH
SET VERIFICATION_STATUS = 'VERIFIED', VERIFIED_AT = NOW()
WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY'
  AND FACT_DATE = @verified_fact_date
  AND FAILURE_COUNT = 0
  AND EXPECTED_FACT_ROWS = (
    SELECT COUNT(*) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY
    WHERE ACTIVITY_DATE = @verified_fact_date
  );

UPDATE WEB_RETENTION_FACT_COLLECTION
SET COLLECTION_STARTED_ON = CASE
    WHEN COLLECTION_STARTED_ON IS NULL THEN @verified_fact_date
    ELSE LEAST(COLLECTION_STARTED_ON, @verified_fact_date)
  END,
  UPDATE_TIME = NOW()
WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY'
  AND EXISTS (
    SELECT 1 FROM WEB_RETENTION_FACT_HEALTH
    WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY'
      AND FACT_DATE = @verified_fact_date
      AND VERIFICATION_STATUS = 'VERIFIED'
  );

-- Exactly one row must be returned before treating this date as verified.
SELECT FACT_DATE, VERIFICATION_STATUS, VERIFIED_AT
FROM WEB_RETENTION_FACT_HEALTH
WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY'
  AND FACT_DATE = @verified_fact_date
  AND VERIFICATION_STATUS = 'VERIFIED';
