-- Google login schema migration for MySQL 5.7+/MariaDB 10.2+.
-- Application login flows trim and lowercase email before lookup and persistence.
-- EMAIL stays binary so normalized case variants collide while accent variants remain distinct.

DROP PROCEDURE IF EXISTS `migrate_google_login`;
DELIMITER //

CREATE PROCEDURE `migrate_google_login`()
BEGIN
  DECLARE duplicate_count BIGINT DEFAULT 0;

  -- This is the exact key shape written by the migration and compared by the
  -- final utf8mb4_bin unique index. NULL remains nullable; blank strings do not.
  SELECT COUNT(*) INTO duplicate_count
  FROM (
    SELECT CONVERT(LOWER(TRIM(`EMAIL`)) USING utf8mb4) COLLATE utf8mb4_bin AS normalized_email
    FROM `SYS_USER`
    WHERE `EMAIL` IS NOT NULL
    GROUP BY CONVERT(LOWER(TRIM(`EMAIL`)) USING utf8mb4) COLLATE utf8mb4_bin
    HAVING COUNT(*) > 1
  ) AS duplicate_emails;

  IF duplicate_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Google login migration blocked: duplicate normalized email values require manual resolution';
  END IF;

  UPDATE `SYS_USER`
  SET `EMAIL` = CONVERT(LOWER(TRIM(`EMAIL`)) USING utf8mb4)
  WHERE `EMAIL` IS NOT NULL;

  ALTER TABLE `SYS_USER`
    DROP INDEX `IDX_USER_EMAIL`,
    MODIFY COLUMN `EMAIL` varchar(255) COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY `IDX_USER_EMAIL` (`EMAIL`),
    ADD COLUMN `GOOGLE_SUB` varchar(255) COLLATE utf8mb4_bin NULL AFTER `EMAIL`,
    ADD UNIQUE KEY `IDX_USER_GOOGLE_SUB` (`GOOGLE_SUB`);
END//

DELIMITER ;

CALL `migrate_google_login`();
DROP PROCEDURE `migrate_google_login`;
