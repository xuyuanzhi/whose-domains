-- Operational compatibility fixture: start from the exact e73ff4d schema, then
-- model installations that had independently added the legacy watch email field.
SOURCE /sql/doc/fixtures/e73ff4d_notification_baseline.sql;

ALTER TABLE `WEB_DOMAIN_WATCH`
  ADD COLUMN `NOTIFY_EMAIL` varchar(256) NULL AFTER `REMARK`;

UPDATE `WEB_DOMAIN_WATCH`
SET `NOTIFY_EMAIL` = CASE `ID`
  WHEN 'w-none' THEN 'none@example.com'
  WHEN 'w-seven' THEN ' seven@example.com '
  ELSE NULL
END;
