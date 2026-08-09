-- Privacy-preserving 7/30-day retention report for MySQL 8.
-- This script returns one readiness row followed by cohort aggregates only. It
-- never SELECTs a user ID. A complete 90-day window of closed 30-day cohorts
-- requires at least 120 days of continuously retained activity facts.
--
-- IMPORTANT: +08:00 is the configured reporting zone used by the writer too.

SET time_zone = '+08:00';
SET @reporting_time_zone = '+08:00';
SET time_zone = @reporting_time_zone;
SET @minimum_cohort_size = 5;
SET @minimum_report_days = 120;
SET @fact_name = 'AUTHENTICATED_ACTIVITY_DAILY';
SET @fact_collection_start = (
  SELECT COLLECTION_STARTED_ON
  FROM WEB_RETENTION_FACT_COLLECTION
  WHERE FACT_NAME = @fact_name
  LIMIT 1
);
SET @configured_retention_days = (
  SELECT MINIMUM_RETENTION_DAYS
  FROM WEB_RETENTION_FACT_COLLECTION
  WHERE FACT_NAME = @fact_name
  LIMIT 1
);
SET @required_fact_days = GREATEST(COALESCE(@configured_retention_days, 0), @minimum_report_days);
SET @health_start = DATE_SUB(CURDATE(), INTERVAL @required_fact_days DAY);
SET @healthy_days = (
  SELECT COUNT(*) FROM WEB_RETENTION_FACT_HEALTH
  WHERE FACT_NAME = @fact_name AND FACT_DATE >= @health_start AND FACT_DATE < CURDATE()
    AND VERIFICATION_STATUS = 'VERIFIED' AND FAILURE_COUNT = 0 AND SUCCESSFUL_WRITE_COUNT > 0
);
SET @incomplete_days = (
  SELECT COUNT(*) FROM WEB_RETENTION_FACT_HEALTH H
  WHERE H.FACT_NAME = @fact_name AND H.FACT_DATE >= @health_start AND H.FACT_DATE < CURDATE()
    AND (H.FAILURE_COUNT > 0 OR
      (SELECT COUNT(*) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY A WHERE A.ACTIVITY_DATE = H.FACT_DATE)
        < H.EXPECTED_FACT_ROWS)
);
SET @observed_fact_days = CASE
  WHEN @fact_collection_start IS NULL THEN 0
  ELSE GREATEST(DATEDIFF(CURDATE(), @fact_collection_start), 0)
END;
SET @report_status = CASE
  WHEN @configured_retention_days IS NULL THEN 'MISSING_COLLECTION_METADATA'
  WHEN @fact_collection_start IS NULL OR @observed_fact_days < @required_fact_days
    OR @healthy_days <> @required_fact_days OR @incomplete_days <> 0
    THEN 'INSUFFICIENT_HISTORY'
  ELSE 'READY'
END;
SET @cohort_end_exclusive = DATE_SUB(CURDATE(), INTERVAL 30 DAY);
SET @cohort_start = DATE_SUB(@cohort_end_exclusive, INTERVAL 90 DAY);

-- Always emit one explicit readiness row. When status is not READY, both cohort
-- result sets below are intentionally empty rather than presenting partial data.
SELECT
  @report_status AS report_status,
  @fact_collection_start AS collection_started_on,
  @observed_fact_days AS observed_fact_days,
  @required_fact_days AS required_fact_days,
  @cohort_start AS requested_cohort_start,
  @cohort_end_exclusive AS requested_cohort_end_exclusive;

DROP TEMPORARY TABLE IF EXISTS retention_activity_days;
CREATE TEMPORARY TABLE retention_activity_days AS
SELECT USER_ID, ACTIVITY_DATE AS activity_date
FROM WEB_AUTHENTICATED_ACTIVITY_DAILY
WHERE USER_ID IS NOT NULL
  AND ACTIVITY_DATE IS NOT NULL
  AND @report_status = 'READY'
  AND ACTIVITY_DATE >= @fact_collection_start
  AND ACTIVITY_DATE <= CURDATE();
ALTER TABLE retention_activity_days ADD PRIMARY KEY (USER_ID, activity_date);

DROP TEMPORARY TABLE IF EXISTS retention_first_watch;
CREATE TEMPORARY TABLE retention_first_watch AS
SELECT USER_ID, DATE(MIN(CREATE_TIME)) AS first_watch_date
FROM WEB_DOMAIN_WATCH
WHERE USER_ID IS NOT NULL AND CREATE_TIME IS NOT NULL
GROUP BY USER_ID;
ALTER TABLE retention_first_watch ADD PRIMARY KEY (USER_ID);

DROP TEMPORARY TABLE IF EXISTS retention_first_activity;
CREATE TEMPORARY TABLE retention_first_activity AS
SELECT A.USER_ID, MIN(A.activity_date) AS first_activity_date
FROM retention_activity_days A
JOIN SYS_USER U ON U.ID = A.USER_ID
  -- Accounts created before collection began have an unknown true first-seen
  -- date. Excluding them prevents rollout day from becoming a fake cohort date.
  AND U.CREATE_TIME IS NOT NULL
  AND DATE(U.CREATE_TIME) >= @fact_collection_start
GROUP BY A.USER_ID;
ALTER TABLE retention_first_activity ADD PRIMARY KEY (USER_ID);

DROP TEMPORARY TABLE IF EXISTS retention_cohorts;
CREATE TEMPORARY TABLE retention_cohorts (
  cohort_type varchar(16) NOT NULL,
  USER_ID varchar(32) NOT NULL,
  cohort_date date NOT NULL,
  PRIMARY KEY (cohort_type, USER_ID),
  KEY (cohort_date)
);
INSERT INTO retention_cohorts (cohort_type, USER_ID, cohort_date)
SELECT 'monitored' AS cohort_type, USER_ID, first_watch_date AS cohort_date
FROM retention_first_watch
WHERE @report_status = 'READY'
  AND first_watch_date >= @fact_collection_start
  AND first_watch_date >= @cohort_start
  AND first_watch_date < @cohort_end_exclusive;
INSERT INTO retention_cohorts (cohort_type, USER_ID, cohort_date)
SELECT 'non_monitored' AS cohort_type, A.USER_ID, A.first_activity_date AS cohort_date
FROM retention_first_activity A
LEFT JOIN retention_first_watch W ON W.USER_ID = A.USER_ID
WHERE @report_status = 'READY'
  AND A.first_activity_date >= @fact_collection_start
  AND A.first_activity_date >= @cohort_start
  AND A.first_activity_date < @cohort_end_exclusive
  -- A control user must remain without a watch through the entire 30-day window.
  AND (W.first_watch_date IS NULL OR W.first_watch_date > DATE_ADD(A.first_activity_date, INTERVAL 30 DAY));

-- Collapse activity to exactly one row per cohort/user before counting the
-- privacy denominator. Multiple active days must never turn one person into
-- multiple cohort members or let a sub-k cohort escape suppression.
DROP TEMPORARY TABLE IF EXISTS retention_user_results;
CREATE TEMPORARY TABLE retention_user_results AS
SELECT
  C.cohort_type,
  C.cohort_date,
  C.USER_ID,
  MAX(CASE WHEN A.activity_date > C.cohort_date
             AND A.activity_date <= DATE_ADD(C.cohort_date, INTERVAL 7 DAY)
           THEN 1 ELSE 0 END) AS returned_7d,
  MAX(CASE WHEN A.activity_date > C.cohort_date
             AND A.activity_date <= DATE_ADD(C.cohort_date, INTERVAL 30 DAY)
           THEN 1 ELSE 0 END) AS returned_30d
FROM retention_cohorts C
LEFT JOIN retention_activity_days A
  ON A.USER_ID = C.USER_ID
 AND A.activity_date > C.cohort_date
 AND A.activity_date <= DATE_ADD(C.cohort_date, INTERVAL 30 DAY)
GROUP BY C.cohort_type, C.cohort_date, C.USER_ID;

DROP TEMPORARY TABLE IF EXISTS retention_results;
CREATE TEMPORARY TABLE retention_results AS
SELECT
  cohort_type,
  cohort_date,
  COUNT(*) AS cohort_users,
  SUM(returned_7d) AS returned_users_7d,
  SUM(returned_30d) AS returned_users_30d
FROM retention_user_results
GROUP BY cohort_type, cohort_date;

-- Daily closed cohorts, suitable for a trend chart. Cohorts smaller than k=5 are
-- suppressed rather than emitted; no user-level fields are selected.
SELECT
  cohort_type,
  cohort_date,
  cohort_users,
  returned_users_7d,
  ROUND(100.0 * returned_users_7d / NULLIF(cohort_users, 0), 2) AS return_rate_7d_pct,
  returned_users_30d,
  ROUND(100.0 * returned_users_30d / NULLIF(cohort_users, 0), 2) AS return_rate_30d_pct
FROM retention_results
WHERE @report_status = 'READY'
  AND cohort_users >= @minimum_cohort_size
ORDER BY cohort_date, cohort_type;

-- Aggregate comparison for the selected 90 closed cohort days. No user-level fields are selected.
SELECT
  cohort_type,
  SUM(cohort_users) AS cohort_users,
  SUM(returned_users_7d) AS returned_users_7d,
  ROUND(100.0 * SUM(returned_users_7d) / NULLIF(SUM(cohort_users), 0), 2) AS return_rate_7d_pct,
  SUM(returned_users_30d) AS returned_users_30d,
  ROUND(100.0 * SUM(returned_users_30d) / NULLIF(SUM(cohort_users), 0), 2) AS return_rate_30d_pct
FROM retention_results
WHERE @report_status = 'READY'
GROUP BY cohort_type
HAVING SUM(cohort_users) >= @minimum_cohort_size
ORDER BY cohort_type;

DROP TEMPORARY TABLE retention_results;
DROP TEMPORARY TABLE retention_user_results;
DROP TEMPORARY TABLE retention_cohorts;
DROP TEMPORARY TABLE retention_first_activity;
DROP TEMPORARY TABLE retention_first_watch;
DROP TEMPORARY TABLE retention_activity_days;
