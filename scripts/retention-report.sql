-- Privacy-preserving 7/30-day retention report for MySQL 8.
-- This script returns cohort aggregates only. It never SELECTs a user ID.
-- Run with a database account that can create temporary tables, not with a client
-- that exports the temporary-table contents.
--
-- IMPORTANT: use the same fixed offset as the production JVM default time zone.
-- API usage currently uses LocalDate.now() without an explicit ZoneId, and query
-- history uses application/database local timestamps. Do not substitute UTC unless
-- the production JVM and database both use UTC. Update this value for the deployed
-- production offset (for example, +08:00) before execution.

SET time_zone = '+08:00';
SET @cohort_end_exclusive = DATE_SUB(CURDATE(), INTERVAL 30 DAY);
SET @cohort_start = DATE_SUB(@cohort_end_exclusive, INTERVAL 90 DAY);

DROP TEMPORARY TABLE IF EXISTS retention_activity_days;
CREATE TEMPORARY TABLE retention_activity_days AS
SELECT USER_ID, DATE(CREATE_TIME) AS activity_date
FROM WEB_USER_QUERY_HISTORY
WHERE USER_ID IS NOT NULL AND DELETED = 0 AND CREATE_TIME IS NOT NULL
UNION
SELECT USER_ID, DATE(READ_AT) AS activity_date
FROM WEB_USER_NOTIFICATION
WHERE USER_ID IS NOT NULL AND READ_AT IS NOT NULL
UNION
SELECT USER_ID, STR_TO_DATE(USAGE_DATE, '%Y-%m-%d') AS activity_date
FROM WEB_API_USAGE_DAILY
WHERE USER_ID IS NOT NULL AND DELETED = 0 AND REQUEST_COUNT > 0
  AND STR_TO_DATE(USAGE_DATE, '%Y-%m-%d') IS NOT NULL;
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
SELECT USER_ID, MIN(activity_date) AS first_activity_date
FROM retention_activity_days
GROUP BY USER_ID;
ALTER TABLE retention_first_activity ADD PRIMARY KEY (USER_ID);

DROP TEMPORARY TABLE IF EXISTS retention_cohorts;
CREATE TEMPORARY TABLE retention_cohorts AS
SELECT 'monitored' AS cohort_type, USER_ID, first_watch_date AS cohort_date
FROM retention_first_watch
WHERE first_watch_date >= @cohort_start AND first_watch_date < @cohort_end_exclusive
UNION ALL
SELECT 'non_monitored' AS cohort_type, A.USER_ID, A.first_activity_date AS cohort_date
FROM retention_first_activity A
LEFT JOIN retention_first_watch W ON W.USER_ID = A.USER_ID
WHERE A.first_activity_date >= @cohort_start AND A.first_activity_date < @cohort_end_exclusive
  -- A control user must remain without a watch through the entire 30-day window.
  AND (W.first_watch_date IS NULL OR W.first_watch_date > DATE_ADD(A.first_activity_date, INTERVAL 30 DAY));
ALTER TABLE retention_cohorts ADD PRIMARY KEY (cohort_type, USER_ID), ADD KEY (cohort_date);

DROP TEMPORARY TABLE IF EXISTS retention_results;
CREATE TEMPORARY TABLE retention_results AS
SELECT
  C.cohort_type,
  C.cohort_date,
  COUNT(*) AS cohort_users,
  COUNT(DISTINCT CASE WHEN A.activity_date > C.cohort_date
                        AND A.activity_date <= DATE_ADD(C.cohort_date, INTERVAL 7 DAY)
                      THEN C.USER_ID END) AS returned_users_7d,
  COUNT(DISTINCT CASE WHEN A.activity_date > C.cohort_date
                        AND A.activity_date <= DATE_ADD(C.cohort_date, INTERVAL 30 DAY)
                      THEN C.USER_ID END) AS returned_users_30d
FROM retention_cohorts C
LEFT JOIN retention_activity_days A
  ON A.USER_ID = C.USER_ID
 AND A.activity_date > C.cohort_date
 AND A.activity_date <= DATE_ADD(C.cohort_date, INTERVAL 30 DAY)
GROUP BY C.cohort_type, C.cohort_date;

-- Daily closed cohorts, suitable for a trend chart. No user-level fields are selected.
SELECT
  cohort_type,
  cohort_date,
  cohort_users,
  returned_users_7d,
  ROUND(100.0 * returned_users_7d / NULLIF(cohort_users, 0), 2) AS return_rate_7d_pct,
  returned_users_30d,
  ROUND(100.0 * returned_users_30d / NULLIF(cohort_users, 0), 2) AS return_rate_30d_pct
FROM retention_results
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
GROUP BY cohort_type
ORDER BY cohort_type;

DROP TEMPORARY TABLE retention_results;
DROP TEMPORARY TABLE retention_cohorts;
DROP TEMPORARY TABLE retention_first_activity;
DROP TEMPORARY TABLE retention_first_watch;
DROP TEMPORARY TABLE retention_activity_days;
