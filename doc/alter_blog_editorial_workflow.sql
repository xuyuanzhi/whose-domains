-- Add a dedicated timestamp for material blog edits without coupling it to
-- generic row updates such as view-count increments.
SET @content_updated_at_exists := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'WEB_BLOG_POST'
    AND COLUMN_NAME = 'CONTENT_UPDATED_AT'
);

SET @add_content_updated_at := IF(
  @content_updated_at_exists = 0,
  'ALTER TABLE `WEB_BLOG_POST` ADD COLUMN `CONTENT_UPDATED_AT` datetime NULL COMMENT ''Last material editorial content update'' AFTER `PUBLISH_DATE`',
  'SELECT 1'
);

PREPARE add_content_updated_at_statement FROM @add_content_updated_at;
EXECUTE add_content_updated_at_statement;
DEALLOCATE PREPARE add_content_updated_at_statement;

UPDATE `WEB_BLOG_POST`
SET `CONTENT_UPDATED_AT` = `PUBLISH_DATE`
WHERE `STATUS` = 1
  AND `DELETED` = 0
  AND `PUBLISH_DATE` IS NOT NULL
  AND `CONTENT_UPDATED_AT` IS NULL;
