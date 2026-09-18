-- Apply before deploying the resolved_at fix to an existing issue-center database.
-- Safe to repeat; new installations already have this column.
SET @issue_resolution_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='WEB_SYSTEM_ISSUE' AND column_name='resolved_at')=0,
 'ALTER TABLE WEB_SYSTEM_ISSUE ADD COLUMN resolved_at bigint NOT NULL DEFAULT 0',
 'SELECT 1');
PREPARE issue_resolution_upgrade FROM @issue_resolution_ddl;
EXECUTE issue_resolution_upgrade;
DEALLOCATE PREPARE issue_resolution_upgrade;
UPDATE WEB_SYSTEM_ISSUE i
LEFT JOIN (SELECT issue_id, MAX(created_at) AS resolved_at FROM WEB_SYSTEM_ISSUE_NOTE
 WHERE new_status='resolved' AND old_status<>'resolved' GROUP BY issue_id) n ON n.issue_id=i.id
SET i.resolved_at=COALESCE(n.resolved_at,i.managed_at)
WHERE i.status='resolved' AND i.resolved_at=0;
