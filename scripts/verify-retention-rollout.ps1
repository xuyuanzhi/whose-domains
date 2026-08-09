[CmdletBinding()]
param(
    [ValidateSet('Static', 'PathA', 'PathB', 'LegacyUpgrade', 'All')]
    [string]$Fixture = 'All',

    [string]$DockerImage = 'mysql:8.4.0',

    [ValidateRange(15, 300)]
    [int]$StartupTimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Assert-RolloutContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "Retention rollout contract failed: $Message"
    }
}

function Read-RepositoryText([string]$RelativePath) {
    $path = Join-Path $RepositoryRoot $RelativePath
    Assert-RolloutContract (Test-Path -LiteralPath $path -PathType Leaf) "missing $RelativePath"
    return Get-Content -Raw -Encoding UTF8 -LiteralPath $path
}

function Assert-Contains(
        [string]$Content,
        [string]$Expected,
        [string]$Description) {
    Assert-RolloutContract $Content.Contains($Expected) $Description
}

function Test-StaticRolloutContract {
    $create = Read-RepositoryText 'doc/create.sql'
    $legacy = Read-RepositoryText 'doc/alter_domain_watch_snapshot.sql'
    $baseline = Read-RepositoryText 'doc/alter_retention_notification_center.sql'
    $snapshotIncrement = Read-RepositoryText 'doc/alter_monitor_snapshot_observation_sources.sql'
    $eventIncrement = Read-RepositoryText 'doc/alter_monitor_event_canonical_risk.sql'
    $completeIncrement = Read-RepositoryText 'doc/alter_retention_notification_center_from_e73ff4d.sql'
    $legacyFixture = Read-RepositoryText 'doc/fixtures/e73ff4d_notification_baseline.sql'
    $legacyWatchEmailFixture = Read-RepositoryText 'doc/fixtures/e73ff4d_notification_baseline_with_watch_email.sql'
    $retentionReport = Read-RepositoryText 'scripts/retention-report.sql'
    $readme = Read-RepositoryText 'README.md'
    $application = Read-RepositoryText 'wesite-web/src/main/resources/application.properties'
    $productionExample = Read-RepositoryText 'wesite-web/src/main/resources/application-prod.properties.example'
    $immediateJob = Read-RepositoryText 'wesite-web/src/main/java/info/wesite/web/task/ImmediateNotificationDeliveryJob.java'
    $digestJob = Read-RepositoryText 'wesite-web/src/main/java/info/wesite/web/task/DigestNotificationDeliveryJob.java'
    $deliveryTask = Read-RepositoryText 'wesite-web/src/main/java/info/wesite/web/task/NotificationDeliveryTask.java'
    $smtpSender = Read-RepositoryText 'wesite-core/src/main/java/info/wesite/core/mail/SmtpMailSender.java'

    foreach ($table in @(
            'WEB_DOMAIN_WATCH',
            'WEB_DOMAIN_WATCH_NOTIFY_LOG',
            'WEB_USER_QUERY_HISTORY',
            'WEB_API_USAGE_DAILY')) {
        Assert-Contains $create "CREATE TABLE ``$table``" "create.sql missing $table"
    }

    Assert-RolloutContract (
        [regex]::Matches($legacy, '(?im)^CREATE TABLE').Count -eq 2
    ) 'legacy migration must create exactly the watch/snapshot pair'
    Assert-Contains $legacy 'CREATE TABLE `WEB_DOMAIN_WATCH`' 'legacy migration missing WEB_DOMAIN_WATCH'
    Assert-Contains $legacy 'CREATE TABLE `WEB_DOMAIN_SNAPSHOT`' 'legacy migration missing WEB_DOMAIN_SNAPSHOT'

    foreach ($table in @(
            'WEB_MONITOR_SNAPSHOT',
            'WEB_MONITOR_EVENT',
            'WEB_USER_NOTIFICATION',
            'WEB_NOTIFICATION_DELIVERY_BATCH',
            'WEB_NOTIFICATION_PREFERENCE',
            'WEB_RETENTION_FACT_COLLECTION',
            'WEB_RETENTION_FACT_HEALTH')) {
        Assert-Contains $baseline "CREATE TABLE ``$table``" "retention baseline missing $table"
    }
    foreach ($column in @(
            'SCHEMA_VERSION', 'OBSERVED_SOURCES', 'RISK', 'SOURCE',
            'RECIPIENT_EMAIL', 'EMAIL_ATTEMPT_COUNT', 'EMAIL_CLAIM_TOKEN',
            'DELIVERY_BATCH_ID', 'CANCELLATION_REQUESTED', 'SCAN_CLAIM_TOKEN',
            'SCAN_CLAIM_UNTIL')) {
        Assert-Contains $baseline "``$column``" "retention baseline missing $column"
    }
    Assert-Contains $baseline 'CREATE TABLE `WEB_AUTHENTICATED_ACTIVITY_DAILY`' 'baseline missing daily authenticated activity fact'
    Assert-Contains $baseline 'CREATE TABLE `WEB_RETENTION_FACT_COLLECTION`' 'baseline missing durable fact collection boundary'
    Assert-Contains $baseline 'MINIMUM_RETENTION_DAYS' 'baseline missing fact retention contract'
    Assert-Contains $baseline 'CHECK (`MINIMUM_RETENTION_DAYS` >= 120)' 'baseline must reject retention below 120 days'
    Assert-Contains $baseline 'retain for at least 120 days' 'activity fact schema must document 120-day retention'
    Assert-Contains $baseline '(`USER_ID`, `EMAIL_MODE`, `RECIPIENT_EMAIL`, `WINDOW_KEY`)' 'baseline batch identity must include the frozen recipient'

    Assert-Contains $completeIncrement 'git e73ff4d' 'complete increment must identify its exact old baseline'
    foreach ($table in @(
            'WEB_NOTIFICATION_DELIVERY_BATCH',
            'WEB_AUTHENTICATED_ACTIVITY_DAILY',
            'WEB_RETENTION_FACT_COLLECTION',
            'WEB_RETENTION_FACT_HEALTH')) {
        Assert-Contains $completeIncrement "CREATE TABLE ``$table``" "complete increment missing $table"
    }
    foreach ($column in @(
            'RECIPIENT_EMAIL', 'EMAIL_ATTEMPT_COUNT', 'EMAIL_CLAIM_TOKEN',
            'CANCELLATION_REQUESTED', 'SCAN_CLAIM_TOKEN', 'SCAN_CLAIM_UNTIL')) {
        Assert-Contains $completeIncrement "``$column``" "complete increment missing $column"
    }
    Assert-Contains $completeIncrement 'W.`NOTIFY_TYPE` = 0' 'legacy queued routes must honor watch-level opt-out'
    Assert-Contains $completeIncrement 'W.`NOTIFY_EMAIL` IS NULL' 'legacy upgrade must not infer account recipients'
    Assert-Contains $legacyFixture "'n-seven'" 'legacy fixture must include upgrade data, not only empty DDL'
    Assert-RolloutContract (
        -not $legacyFixture.Contains('`NOTIFY_EMAIL` varchar')
    ) 'exact e73ff4d fixture must not invent a watch email column'
    Assert-Contains $legacyWatchEmailFixture 'SOURCE /sql/doc/fixtures/e73ff4d_notification_baseline.sql' 'watch-email compatibility fixture must extend the exact e73ff4d fixture'
    Assert-Contains $completeIncrement '@watch_notify_email_exists' 'complete increment must handle the exact e73ff4d schema without NOTIFY_EMAIL'
    Assert-Contains $completeIncrement 'ADD COLUMN `NOTIFY_EMAIL`' 'complete increment must add the missing e73ff4d watch email field'
    Assert-Contains $completeIncrement 'MODIFY COLUMN `NOTIFY_EMAIL`' 'complete increment must preserve operational legacy watch recipients'
    Assert-Contains $completeIncrement '(`USER_ID`, `EMAIL_MODE`, `RECIPIENT_EMAIL`, `WINDOW_KEY`)' 'complete increment batch identity must include the frozen recipient'

    Assert-Contains $retentionReport 'FROM WEB_AUTHENTICATED_ACTIVITY_DAILY' 'retention report must use daily authenticated activity facts'
    Assert-Contains $retentionReport 'FROM WEB_RETENTION_FACT_COLLECTION' 'retention report must use the durable collection boundary'
    Assert-RolloutContract (-not $retentionReport.Contains('WEB_USER_QUERY_HISTORY')) 'retention report must not rely on capped query history'
    Assert-Contains $retentionReport 'SET @minimum_cohort_size = 5' 'retention report must define k-anonymity threshold'
    Assert-Contains $retentionReport 'SET @minimum_report_days = 120' 'retention report must require the full 120-day observation window'
    Assert-Contains $retentionReport 'INSUFFICIENT_HISTORY' 'retention report must expose an explicit immature-data state'
    Assert-Contains $retentionReport "@report_status = 'READY'" 'retention report must gate cohorts on maturity'
    Assert-Contains $retentionReport 'DATE(U.CREATE_TIME) >= @fact_collection_start' 'legacy accounts must not become fake first-seen cohorts'
    Assert-Contains $retentionReport 'cohort_users >= @minimum_cohort_size' 'daily cohorts below k must be suppressed'

    Assert-RolloutContract (
        [regex]::Matches($snapshotIncrement, '(?im)^ALTER TABLE `WEB_MONITOR_SNAPSHOT`').Count -eq 1 -and
        [regex]::Matches($snapshotIncrement, '(?im)^\s*ADD COLUMN').Count -eq 2
    ) 'snapshot increment must add exactly two columns to WEB_MONITOR_SNAPSHOT'
    Assert-RolloutContract (
        [regex]::Matches($eventIncrement, '(?im)^ALTER TABLE `WEB_MONITOR_EVENT`').Count -eq 1 -and
        [regex]::Matches($eventIncrement, '(?im)^\s*ADD COLUMN').Count -eq 2
    ) 'event increment must add exactly two columns to WEB_MONITOR_EVENT'

    $pathBStart = $readme.IndexOf('**Path B')
    Assert-RolloutContract ($pathBStart -ge 0) 'README Path B section is missing'
    $pathB = $readme.Substring($pathBStart)
    $legacyPosition = $pathB.IndexOf('alter_domain_watch_snapshot.sql')
    $baselinePosition = $pathB.IndexOf('alter_retention_notification_center.sql')
    Assert-RolloutContract (
        $legacyPosition -ge 0 -and
        $legacyPosition -lt $baselinePosition
    ) 'README Path B migration order is not legacy -> baseline'
    $legacyUpgradeStart = $readme.IndexOf('**Legacy e73ff4d upgrade')
    Assert-RolloutContract ($legacyUpgradeStart -ge 0) 'README Legacy e73ff4d upgrade section is missing'
    $legacyUpgrade = $readme.Substring($legacyUpgradeStart)
    Assert-Contains $legacyUpgrade 'alter_retention_notification_center_from_e73ff4d.sql' 'README must prescribe the complete e73ff4d increment'
    Assert-Contains $readme 'If exactly one is present, stop' 'README missing partial legacy stop guard'
    Assert-Contains $readme 'If only some retention tables exist, stop' 'README missing partial baseline stop guard'
    Assert-Contains $readme '-Fixture LegacyUpgrade' 'README must document the real legacy-upgrade verifier'
    Assert-Contains $readme 'at least 120 days' 'README must document the minimum fact retention window'
    Assert-Contains $readme 'INSUFFICIENT_HISTORY' 'README must document the explicit immature report state'

    $immediateLine = 'wesite.notification-delivery.immediate-enabled=${WESITE_NOTIFICATION_DELIVERY_IMMEDIATE_ENABLED:false}'
    $digestLine = 'wesite.notification-delivery.digest-enabled=${WESITE_NOTIFICATION_DELIVERY_DIGEST_ENABLED:false}'
    foreach ($properties in @($application, $productionExample)) {
        Assert-Contains $properties $immediateLine 'immediate delivery must have an environment-backed false default'
        Assert-Contains $properties $digestLine 'digest delivery must have an environment-backed false default'
    }

    Assert-Contains $immediateJob 'prefix = "wesite"' 'immediate job must use the shared wesite property prefix'
    Assert-Contains $immediateJob 'notification-delivery.immediate-enabled' 'immediate job property mismatch'
    Assert-Contains $immediateJob '"mail.enabled"' 'immediate job must require enabled mail at registration'
    Assert-Contains $immediateJob 'matchIfMissing = false' 'immediate job must be absent when its property is missing'
    Assert-Contains $digestJob 'prefix = "wesite"' 'digest job must use the shared wesite property prefix'
    Assert-Contains $digestJob 'notification-delivery.digest-enabled' 'digest job property mismatch'
    Assert-Contains $digestJob '"mail.enabled"' 'digest job must require enabled mail at registration'
    Assert-Contains $digestJob 'matchIfMissing = false' 'digest job must be absent when its property is missing'
    Assert-RolloutContract (-not $deliveryTask.Contains('@Scheduled')) 'delivery service must not own a schedule'
    Assert-Contains $smtpSender 'MailSendResult.fail("mail sending is disabled")' 'disabled SMTP must not report success'

    $rolloutStart = $readme.IndexOf('#### Phased enablement')
    Assert-RolloutContract ($rolloutStart -ge 0) 'README phased enablement section is missing'
    $rollout = $readme.Substring($rolloutStart)
    $bothFalse = $rollout.IndexOf('both `wesite.notification-delivery.immediate-enabled=false`')
    $immediateTrue = $rollout.IndexOf('set only `wesite.notification-delivery.immediate-enabled=true`')
    $digestTrue = $rollout.IndexOf('set `wesite.notification-delivery.digest-enabled=true`')
    Assert-RolloutContract (
        $bothFalse -ge 0 -and
        $bothFalse -lt $immediateTrue -and
        $immediateTrue -lt $digestTrue
    ) 'README rollout must enable in-app -> immediate -> digest in order'
    Assert-RolloutContract (
        -not $readme.Contains('successful no-send result')
    ) 'README must not describe disabled mail as a successful send'
    Assert-Contains $readme '`wesite.mail.enabled=false` is a hard registration gate' 'README must document the disabled-mail registration gate'

    $legacyMatches = @(
        Get-ChildItem -Path @(
            (Join-Path $RepositoryRoot 'wesite-core/src/main'),
            (Join-Path $RepositoryRoot 'wesite-web/src/main')) -Recurse -Filter '*.java' |
            Select-String -Pattern 'sendExpiryNotifications|DomainWatchNotifyTask'
    )
    Assert-RolloutContract ($legacyMatches.Count -eq 0) 'legacy expiry delivery source entry point remains'

    Write-Host 'RETENTION_ROLLOUT_STATIC|PASS'
}

function Invoke-DockerRaw([string[]]$Arguments) {
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($lines)
    }
}

function Invoke-DockerCommand(
        [string[]]$Arguments,
        [switch]$ReturnOutput) {
    $result = Invoke-DockerRaw $Arguments
    if ($result.ExitCode -ne 0) {
        $command = $Arguments -join ' '
        $detail = $result.Lines -join [Environment]::NewLine
        throw "docker $command failed with exit $($result.ExitCode)`n$detail"
    }
    if ($ReturnOutput) {
        return $result.Lines -join [Environment]::NewLine
    }
}

function Wait-MySqlFixture(
        [string]$ContainerName,
        [string]$Password) {
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $initialized = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $logResult = Invoke-DockerRaw @('logs', $ContainerName)
        $logs = $logResult.Lines -join [Environment]::NewLine
        if ($logs.Contains('MySQL init process done')) {
            $initialized = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    Assert-RolloutContract $initialized "$ContainerName did not finish MySQL initialization"

    $authenticated = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $authResult = Invoke-DockerRaw @(
            'exec', $ContainerName, 'mysql', '--user=root', "--password=$Password", '--execute=SELECT 1')
        if ($authResult.ExitCode -eq 0) {
            $authenticated = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    Assert-RolloutContract $authenticated "$ContainerName did not accept an authenticated query"
}

function Invoke-FixtureSqlFile(
        [string]$ContainerName,
        [string]$Password,
        [string]$RelativePath) {
    Invoke-DockerCommand -Arguments @(
        'exec',
        $ContainerName,
        'sh',
        '-c',
        "mysql --user=root --password=$Password wesitedb < /sql/$RelativePath")
}

function Invoke-FixtureSqlFileOutput(
        [string]$ContainerName,
        [string]$Password,
        [string]$RelativePath) {
    return Invoke-DockerCommand -ReturnOutput -Arguments @(
        'exec',
        $ContainerName,
        'sh',
        '-c',
        "mysql --user=root --password=$Password --batch wesitedb < /sql/$RelativePath")
}

function Invoke-FixtureQuery(
        [string]$ContainerName,
        [string]$Password,
        [string]$Sql) {
    return Invoke-DockerCommand -ReturnOutput -Arguments @(
        'exec',
        $ContainerName,
        'mysql',
        '--user=root',
        "--password=$Password",
        '--batch',
        '--skip-column-names',
        'wesitedb',
        "--execute=$Sql")
}

function Get-FixtureCount(
        [string]$ContainerName,
        [string]$Password,
        [string]$Sql) {
    $output = Invoke-FixtureQuery $ContainerName $Password $Sql
    $numbers = @($output -split '\r?\n' | Where-Object { $_ -match '^\d+$' })
    Assert-RolloutContract ($numbers.Count -eq 1) "query did not return one numeric count: $output"
    return [int]$numbers[0]
}

function Assert-RetentionSchema(
        [string]$ContainerName,
        [string]$Password,
        [string]$Label) {
    $tables = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME IN ('WEB_MONITOR_SNAPSHOT','WEB_MONITOR_EVENT','WEB_USER_NOTIFICATION','WEB_NOTIFICATION_DELIVERY_BATCH','WEB_NOTIFICATION_PREFERENCE','WEB_RETENTION_FACT_COLLECTION','WEB_RETENTION_FACT_HEALTH')
'@
    $canonicalColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND ((TABLE_NAME = 'WEB_MONITOR_SNAPSHOT' AND COLUMN_NAME IN ('SCHEMA_VERSION','OBSERVED_SOURCES'))
    OR (TABLE_NAME = 'WEB_MONITOR_EVENT' AND COLUMN_NAME IN ('RISK','SOURCE')))
'@
    $pipelineColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND ((TABLE_NAME = 'WEB_MONITOR_SNAPSHOT' AND COLUMN_NAME IN ('SCHEMA_VERSION','OBSERVED_SOURCES'))
    OR (TABLE_NAME = 'WEB_MONITOR_EVENT' AND COLUMN_NAME IN ('RISK','SOURCE'))
    OR (TABLE_NAME = 'WEB_USER_NOTIFICATION' AND COLUMN_NAME IN ('RECIPIENT_EMAIL','EMAIL_MODE','EMAIL_STATE','EMAIL_ATTEMPT_COUNT','EMAIL_CLAIM_TOKEN','EMAIL_CLAIM_UNTIL','DELIVERY_BATCH_ID'))
    OR (TABLE_NAME = 'WEB_NOTIFICATION_DELIVERY_BATCH' AND COLUMN_NAME IN ('RECIPIENT_EMAIL','STATE','CLAIM_TOKEN','CLAIM_UNTIL','CANCELLATION_REQUESTED'))
    OR (TABLE_NAME = 'WEB_NOTIFICATION_PREFERENCE' AND COLUMN_NAME IN ('EMAIL_MODE','DOMAIN_EXPIRY_ENABLED','SSL_EXPIRY_ENABLED','DOMAIN_STATUS_ENABLED','DNS_CHANGE_ENABLED','WEBSITE_AVAILABILITY_ENABLED')))
'@
    $watchColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_DOMAIN_WATCH'
  AND COLUMN_NAME IN ('NOTIFY_EMAIL','SCAN_CLAIM_TOKEN','SCAN_CLAIM_UNTIL')
'@
    $activityColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_AUTHENTICATED_ACTIVITY_DAILY'
  AND COLUMN_NAME IN ('USER_ID','ACTIVITY_DATE')
'@
    $collectionColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_RETENTION_FACT_COLLECTION'
  AND COLUMN_NAME IN ('FACT_NAME','COLLECTION_STARTED_ON','MINIMUM_RETENTION_DAYS')
'@
    $collectionContract = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM WEB_RETENTION_FACT_COLLECTION
WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY'
  AND COLLECTION_STARTED_ON IS NULL
  AND MINIMUM_RETENTION_DAYS >= 120
'@
    $auditColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_DOMAIN_WATCH_NOTIFY_LOG'
  AND COLUMN_NAME IN ('NOTIFICATION_ID','BATCH_ID','EVENT_ID','DELIVERY_MODE')
'@
    $batchIdentityColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM (
  SELECT INDEX_NAME
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'wesitedb'
    AND TABLE_NAME = 'WEB_NOTIFICATION_DELIVERY_BATCH'
    AND INDEX_NAME = 'UK_NOTIFICATION_BATCH_USER_MODE_WINDOW'
    AND NON_UNIQUE = 0
  GROUP BY INDEX_NAME
  HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') =
    'USER_ID,EMAIL_MODE,RECIPIENT_EMAIL,WINDOW_KEY'
) exact_batch_identity
'@
    $batchRecipientRequired = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_NOTIFICATION_DELIVERY_BATCH'
  AND COLUMN_NAME = 'RECIPIENT_EMAIL'
  AND IS_NULLABLE = 'NO'
'@

    Assert-RolloutContract ($tables -eq 7) "$Label expected seven retention tables, found $tables"
    Assert-RolloutContract ($canonicalColumns -eq 4) "$Label expected four canonical columns, found $canonicalColumns"
    Assert-RolloutContract ($pipelineColumns -eq 22) "$Label expected 22 key pipeline columns, found $pipelineColumns"
    Assert-RolloutContract ($watchColumns -eq 3) "$Label expected three watch compatibility/lease columns, found $watchColumns"
    Assert-RolloutContract ($activityColumns -eq 2) "$Label expected the daily activity fact columns, found $activityColumns"
    Assert-RolloutContract ($collectionColumns -eq 3) "$Label expected three fact collection columns, found $collectionColumns"
    Assert-RolloutContract ($collectionContract -eq 1) "$Label missing the durable 120-day fact collection row"
    Assert-RolloutContract ($auditColumns -eq 4) "$Label expected four delivery audit columns, found $auditColumns"
    Assert-RolloutContract ($batchIdentityColumns -eq 1) "$Label batch identity is not the exact frozen-recipient unique key"
    Assert-RolloutContract ($batchRecipientRequired -eq 1) "$Label batch recipient must be required"
    Write-Host "RETENTION_ROLLOUT_FIXTURE|PASS|PATH=$Label|TABLES=$tables|PIPELINE_COLUMNS=$pipelineColumns|WATCH_COLUMNS=$watchColumns|ACTIVITY_COLUMNS=$activityColumns|COLLECTION_COLUMNS=$collectionColumns|AUDIT_COLUMNS=$auditColumns"
}

function Assert-RetentionReportData(
        [string]$ContainerName,
        [string]$Password,
        [string]$Label) {
    [void](Invoke-FixtureQuery $ContainerName $Password @'
CREATE TABLE IF NOT EXISTS SYS_USER (
  ID varchar(32) NOT NULL PRIMARY KEY,
  CREATE_TIME datetime NULL
) ENGINE=InnoDB;
'@)
    $immature = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $immature.Contains('INSUFFICIENT_HISTORY') "$Label report did not expose immature history"
    Assert-RolloutContract (
        -not [regex]::IsMatch($immature, '(?m)^(monitored|non_monitored)\t')
    ) "$Label immature report emitted a cohort"

    $retentionFixtureSql = @'
UPDATE WEB_RETENTION_FACT_COLLECTION
SET COLLECTION_STARTED_ON = DATE_SUB(CURDATE(), INTERVAL 130 DAY),
    MINIMUM_RETENTION_DAYS = 120
WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY';
DELETE FROM WEB_AUTHENTICATED_ACTIVITY_DAILY WHERE USER_ID LIKE 'ret-%';
DELETE FROM WEB_RETENTION_FACT_HEALTH WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY';
DELETE FROM WEB_DOMAIN_WATCH WHERE ID LIKE 'ret-%';
DELETE FROM SYS_USER WHERE ID LIKE 'ret-%';
INSERT INTO SYS_USER (ID, CREATE_TIME) VALUES
  ('ret-new-1', DATE_SUB(CURDATE(), INTERVAL 61 DAY)),
  ('ret-new-2', DATE_SUB(CURDATE(), INTERVAL 61 DAY)),
  ('ret-new-3', DATE_SUB(CURDATE(), INTERVAL 61 DAY)),
  ('ret-new-4', DATE_SUB(CURDATE(), INTERVAL 61 DAY)),
  ('ret-old-1', DATE_SUB(CURDATE(), INTERVAL 150 DAY)),
  ('ret-old-2', DATE_SUB(CURDATE(), INTERVAL 150 DAY)),
  ('ret-old-3', DATE_SUB(CURDATE(), INTERVAL 150 DAY)),
  ('ret-old-4', DATE_SUB(CURDATE(), INTERVAL 150 DAY)),
  ('ret-old-5', DATE_SUB(CURDATE(), INTERVAL 150 DAY));
INSERT INTO WEB_DOMAIN_WATCH
  (ID, STATUS, DELETED, USER_ID, DOMAIN_NAME, NOTIFY_TYPE, CREATE_TIME)
VALUES
  ('ret-mature-1', 1, 0, 'ret-mon-1', 'mature-1.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-mature-2', 1, 0, 'ret-mon-2', 'mature-2.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-mature-3', 1, 0, 'ret-mon-3', 'mature-3.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-mature-4', 1, 0, 'ret-mon-4', 'mature-4.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-mature-5', 1, 0, 'ret-mon-5', 'mature-5.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-immature-1', 1, 0, 'ret-imm-1', 'immature-1.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-immature-2', 1, 0, 'ret-imm-2', 'immature-2.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-immature-3', 1, 0, 'ret-imm-3', 'immature-3.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-immature-4', 1, 0, 'ret-imm-4', 'immature-4.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-immature-5', 1, 0, 'ret-imm-5', 'immature-5.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY));
INSERT INTO WEB_AUTHENTICATED_ACTIVITY_DAILY (USER_ID, ACTIVITY_DATE) VALUES
  ('ret-return-m1-5', 'ret-mon-1', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-return-m1-20', 'ret-mon-1', DATE_SUB(CURDATE(), INTERVAL 40 DAY)),
  ('ret-return-m2-5', 'ret-mon-2', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-return-m2-25', 'ret-mon-2', DATE_SUB(CURDATE(), INTERVAL 35 DAY)),
  ('ret-return-m3-5', 'ret-mon-3', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-return-m3-29', 'ret-mon-3', DATE_SUB(CURDATE(), INTERVAL 31 DAY)),
  ('ret-return-m4-20', 'ret-mon-4', DATE_SUB(CURDATE(), INTERVAL 40 DAY)),
  ('ret-first-new1', 'ret-new-1', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-return-new1-5', 'ret-new-1', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-return-new1-20', 'ret-new-1', DATE_SUB(CURDATE(), INTERVAL 40 DAY)),
  ('ret-first-new2', 'ret-new-2', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-return-new2-5', 'ret-new-2', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-return-new2-20', 'ret-new-2', DATE_SUB(CURDATE(), INTERVAL 40 DAY)),
  ('ret-first-new3', 'ret-new-3', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-return-new3-5', 'ret-new-3', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-return-new3-20', 'ret-new-3', DATE_SUB(CURDATE(), INTERVAL 40 DAY)),
  ('ret-first-new4', 'ret-new-4', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-return-new4-5', 'ret-new-4', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-return-new4-20', 'ret-new-4', DATE_SUB(CURDATE(), INTERVAL 40 DAY)),
  ('ret-first-old1', 'ret-old-1', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-old2', 'ret-old-2', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-old3', 'ret-old-3', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-old4', 'ret-old-4', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-old5', 'ret-old-5', DATE_SUB(CURDATE(), INTERVAL 60 DAY));
'@
    $factIds = @('ret-return-m1-5','ret-return-m1-20','ret-return-m2-5','ret-return-m2-25','ret-return-m3-5','ret-return-m3-29','ret-return-m4-20','ret-first-new1','ret-return-new1-5','ret-return-new1-20','ret-first-new2','ret-return-new2-5','ret-return-new2-20','ret-first-new3','ret-return-new3-5','ret-return-new3-20','ret-first-new4','ret-return-new4-5','ret-return-new4-20','ret-first-old1','ret-first-old2','ret-first-old3','ret-first-old4','ret-first-old5')
    foreach ($factId in $factIds) { $retentionFixtureSql = $retentionFixtureSql.Replace("('$factId', ", '(') }
    [void](Invoke-FixtureQuery $ContainerName $Password $retentionFixtureSql)

    [void](Invoke-FixtureQuery $ContainerName $Password @'
INSERT INTO WEB_RETENTION_FACT_HEALTH
  (FACT_NAME, FACT_DATE, SUCCESSFUL_WRITE_COUNT, FAILURE_COUNT, EXPECTED_FACT_ROWS, LAST_SUCCESS_AT)
WITH RECURSIVE days AS (
  SELECT DATE_SUB(CURDATE(), INTERVAL 120 DAY) AS fact_date
  UNION ALL SELECT DATE_ADD(fact_date, INTERVAL 1 DAY) FROM days
  WHERE fact_date < DATE_SUB(CURDATE(), INTERVAL 1 DAY)
)
SELECT 'AUTHENTICATED_ACTIVITY_DAILY', fact_date, 1, 0,
  (SELECT COUNT(*) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY A WHERE A.ACTIVITY_DATE = fact_date), NOW()
FROM days;
'@)

    [void](Invoke-FixtureQuery $ContainerName $Password "DELETE FROM WEB_RETENTION_FACT_HEALTH WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=DATE_SUB(CURDATE(), INTERVAL 100 DAY);")
    $gap = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $gap.Contains('INSUFFICIENT_HISTORY') "$Label report accepted an interrupted health interval"
    [void](Invoke-FixtureQuery $ContainerName $Password @'
INSERT INTO WEB_RETENTION_FACT_HEALTH
  (FACT_NAME, FACT_DATE, SUCCESSFUL_WRITE_COUNT, FAILURE_COUNT, EXPECTED_FACT_ROWS, LAST_SUCCESS_AT)
SELECT 'AUTHENTICATED_ACTIVITY_DAILY', DATE_SUB(CURDATE(), INTERVAL 100 DAY), 1, 0,
  COUNT(*), NOW() FROM WEB_AUTHENTICATED_ACTIVITY_DAILY
WHERE ACTIVITY_DATE=DATE_SUB(CURDATE(), INTERVAL 100 DAY);
DELETE FROM WEB_AUTHENTICATED_ACTIVITY_DAILY
WHERE USER_ID='ret-mon-1' AND ACTIVITY_DATE=DATE_SUB(CURDATE(), INTERVAL 55 DAY);
'@)
    $cleaned = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $cleaned.Contains('INSUFFICIENT_HISTORY') "$Label report accepted prematurely cleaned facts"
    [void](Invoke-FixtureQuery $ContainerName $Password "INSERT INTO WEB_AUTHENTICATED_ACTIVITY_DAILY (USER_ID,ACTIVITY_DATE) VALUES ('ret-mon-1',DATE_SUB(CURDATE(), INTERVAL 55 DAY));")

    $mature = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $mature.Contains('READY') "$Label mature report did not become ready"
    Assert-RolloutContract (
        [regex]::Matches($mature, '(?m)^monitored\t\d{4}-\d{2}-\d{2}\t5\t3\t60\.00\t4\t80\.00\r?$').Count -eq 1
    ) "$Label mature five-user cohort or return counts were incorrect: $mature"
    Assert-RolloutContract (
        [regex]::Matches($mature, '(?m)^non_monitored\t').Count -eq 0
    ) "$Label emitted a k=4 control cohort or treated pre-collection accounts as first seen: $mature"
    Write-Host "RETENTION_REPORT_DATA|PASS|PATH=$Label|IMMATURE=EMPTY|GAP=INSUFFICIENT|CLEANUP=INSUFFICIENT|MATURE=5|SUPPRESSED=4"
}

function Assert-LegacyUpgradeData(
        [string]$ContainerName,
        [string]$Password,
        [bool]$HasLegacyWatchEmail) {
    $allowed = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_USER_NOTIFICATION
WHERE ID = 'n-seven' AND EMAIL_STATE = 'QUEUED'
  AND EMAIL_MODE = 'IMMEDIATE_EMAIL' AND RECIPIENT_EMAIL = 'seven@example.com'
'@
    $cancelled = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_USER_NOTIFICATION
WHERE ID IN ('n-none','n-seven','n-thirty','n-no-email')
  AND EMAIL_STATE = 'IN_APP_ONLY' AND EMAIL_MODE = 'IN_APP_ONLY'
  AND RECIPIENT_EMAIL IS NULL
'@
    $expectedAllowed = if ($HasLegacyWatchEmail) { 1 } else { 0 }
    $expectedCancelled = if ($HasLegacyWatchEmail) { 3 } else { 4 }
    Assert-RolloutContract ($allowed -eq $expectedAllowed) "Legacy upgrade expected $expectedAllowed compatible frozen recipients, found $allowed"
    Assert-RolloutContract ($cancelled -eq $expectedCancelled) "Legacy upgrade expected $expectedCancelled incompatible routes cancelled, found $cancelled"
    Write-Host "RETENTION_LEGACY_DATA|PASS|WATCH_EMAIL=$HasLegacyWatchEmail|ALLOWED=$allowed|CANCELLED=$cancelled"
}

function Assert-PathBWatchCompatibilityData([string]$ContainerName, [string]$Password) {
    $preserved = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_DOMAIN_WATCH
WHERE ID = 'pathb-valid' AND NOTIFY_TYPE = 1 AND NOTIFY_EMAIL = 'pathb@example.com'
'@
    $rejected = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_DOMAIN_WATCH
WHERE ID = 'pathb-invalid' AND NOTIFY_TYPE = 0 AND NOTIFY_EMAIL IS NULL
'@
    Assert-RolloutContract ($preserved -eq 1) 'PathB did not preserve and normalize its valid legacy watch recipient'
    Assert-RolloutContract ($rejected -eq 1) 'PathB did not fail closed for malformed legacy watch settings'
    Write-Host "RETENTION_PATHB_WATCH_DATA|PASS|PRESERVED=$preserved|REJECTED=$rejected"
}

function Invoke-MySqlFixture([ValidateSet('PathA', 'PathB', 'LegacyUpgrade', 'LegacyWatchEmail')][string]$Path) {
    Assert-RolloutContract ($null -ne (Get-Command docker -ErrorAction SilentlyContinue)) 'docker is required for MySQL fixtures'
    Invoke-DockerCommand -Arguments @('version', '--format', '{{.Server.Version}}')

    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $containerName = "whose-retention-$($Path.ToLowerInvariant())-$suffix"
    $password = 'retentionfixture'
    $started = $false
    $bodyError = $null
    $cleanupError = $null

    try {
        Invoke-DockerCommand -Arguments @(
            'run',
            '--rm',
            '-d',
            '--name', $containerName,
            '-e', "MYSQL_ROOT_PASSWORD=$password",
            '-e', 'MYSQL_DATABASE=wesitedb',
            '-v', "${RepositoryRoot}:/sql:ro",
            $DockerImage,
            '--character-set-server=utf8mb4',
            '--collation-server=utf8mb4_bin')
        $started = $true
        Wait-MySqlFixture $containerName $password

        if ($Path -eq 'LegacyUpgrade' -or $Path -eq 'LegacyWatchEmail') {
            $legacyFixturePath = if ($Path -eq 'LegacyWatchEmail') {
                'doc/fixtures/e73ff4d_notification_baseline_with_watch_email.sql'
            } else {
                'doc/fixtures/e73ff4d_notification_baseline.sql'
            }
            Invoke-FixtureSqlFile $containerName $password $legacyFixturePath
            Invoke-FixtureSqlFile $containerName $password 'doc/alter_retention_notification_center_from_e73ff4d.sql'
            Assert-RetentionSchema $containerName $password $Path
            Assert-LegacyUpgradeData $containerName $password ($Path -eq 'LegacyWatchEmail')
            Assert-RetentionReportData $containerName $password $Path
        } else {
            Invoke-FixtureSqlFile $containerName $password 'doc/create.sql'
            if ($Path -eq 'PathB') {
                [void](Invoke-FixtureQuery $containerName $password 'DROP TABLE WEB_DOMAIN_WATCH, WEB_DOMAIN_SNAPSHOT')
                Invoke-FixtureSqlFile $containerName $password 'doc/alter_domain_watch_snapshot.sql'
                [void](Invoke-FixtureQuery $containerName $password @'
ALTER TABLE WEB_DOMAIN_WATCH ADD COLUMN NOTIFY_EMAIL varchar(256) NULL AFTER REMARK;
INSERT INTO WEB_DOMAIN_WATCH
  (ID, STATUS, DELETED, USER_ID, DOMAIN_NAME, NOTIFY_TYPE, NOTIFY_EMAIL, CREATE_TIME)
VALUES
  ('pathb-valid', 1, 0, 'pathb-user-1', 'valid.example', 1, ' pathb@example.com ', NOW()),
  ('pathb-invalid', 1, 0, 'pathb-user-2', 'invalid.example', 9, 'bad recipient', NOW());
'@)
                $legacyTables = Get-FixtureCount $containerName $password @'
SELECT COUNT(*)
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME IN ('WEB_DOMAIN_WATCH','WEB_DOMAIN_SNAPSHOT')
'@
                Assert-RolloutContract ($legacyTables -eq 2) "PathB legacy migration recreated $legacyTables of two tables"
            }
            Invoke-FixtureSqlFile $containerName $password 'doc/alter_retention_notification_center.sql'
            Assert-RetentionSchema $containerName $password $Path
            if ($Path -eq 'PathB') {
                Assert-PathBWatchCompatibilityData $containerName $password
            }
            Assert-RetentionReportData $containerName $password $Path
        }
    } catch {
        $bodyError = $_
    } finally {
        if ($started) {
            $stopResult = Invoke-DockerRaw @('stop', $containerName)
            if ($stopResult.ExitCode -ne 0) {
                $cleanupError = "docker stop failed for $containerName with exit $($stopResult.ExitCode)"
            }
        }
        $psResult = Invoke-DockerRaw @(
            'ps', '-a', '--filter', "name=^/$containerName$", '--format', '{{.Names}}')
        $leftovers = @($psResult.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
        if ($psResult.ExitCode -ne 0) {
            $cleanupError = "docker ps cleanup verification failed with exit $($psResult.ExitCode)"
        }
        if ($leftovers.Count -ne 0) {
            $cleanupError = "temporary container remains: $containerName"
        }
        $cleanupExit = if ($null -eq $cleanupError) { 0 } else { 1 }
        Write-Host "RETENTION_ROLLOUT_CLEANUP|PATH=$Path|EXIT=$cleanupExit|LEFTOVERS=$($leftovers.Count)"
    }

    if ($null -ne $bodyError) {
        throw $bodyError
    }
    if ($null -ne $cleanupError) {
        throw $cleanupError
    }
}

Test-StaticRolloutContract

if ($Fixture -eq 'PathA' -or $Fixture -eq 'All') {
    Invoke-MySqlFixture 'PathA'
}
if ($Fixture -eq 'PathB' -or $Fixture -eq 'All') {
    Invoke-MySqlFixture 'PathB'
}
if ($Fixture -eq 'LegacyUpgrade' -or $Fixture -eq 'All') {
    Invoke-MySqlFixture 'LegacyUpgrade'
    Invoke-MySqlFixture 'LegacyWatchEmail'
}

Write-Host "RETENTION_ROLLOUT_VERIFY|PASS|FIXTURE=$Fixture"
