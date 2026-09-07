-- Run with editorial writers stopped, after backing up WEB_BLOG_POST.
-- Nullable preserves unknown provenance; never infer AI from an organization byline alone.
SET @blog_ai_generated_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'WEB_BLOG_POST'
    AND COLUMN_NAME = 'AI_GENERATED'
);
SET @blog_ai_generated_ddl := IF(@blog_ai_generated_exists = 0,
  'ALTER TABLE `WEB_BLOG_POST` ADD COLUMN `AI_GENERATED` tinyint NULL COMMENT ''Known AI-assisted origin, independent of byline'' AFTER `AUTHOR`',
  'SELECT 1');
PREPARE blog_ai_generated_statement FROM @blog_ai_generated_ddl;
EXECUTE blog_ai_generated_statement;
DEALLOCATE PREPARE blog_ai_generated_statement;

UPDATE `WEB_BLOG_POST` SET `AI_GENERATED` = 1
WHERE (LOWER(TRIM(`CREATE_BY`)) = 'ai'
       OR LOWER(TRIM(`AUTHOR`)) IN ('james chen', 'mark zhang'))
  AND (`AI_GENERATED` IS NULL OR `AI_GENERATED` <> 1);
