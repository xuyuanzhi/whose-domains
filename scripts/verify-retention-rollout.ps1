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
$FixtureReportingTimeZone = '+08:00'
$MySqlSessionInit = "SET time_zone = '$FixtureReportingTimeZone'"
$MySqlSessionInitBase64 = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes("$MySqlSessionInit;`n"))

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
    $productionExample = Read-RepositoryText 'deploy/config/wesite-web.application-prod.properties.example'
    $adminProductionExample = Read-RepositoryText 'deploy/config/wesite-admin.application-prod.properties.example'
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
    Assert-Contains $create '`WATCH_CREATED_ON`' 'create.sql must persist the reporting-calendar watch creation date'
    Assert-Contains $legacy '`WATCH_CREATED_ON`' 'legacy watch baseline must persist the reporting-calendar watch creation date'

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
            'SCHEMA_VERSION', 'OBSERVED_SOURCES', 'CURRENT_OBSERVED_SOURCES',
            'DOMAIN_LAST_SUCCESS_AT', 'DNS_LAST_SUCCESS_AT',
            'SSL_LAST_SUCCESS_AT', 'WEBSITE_LAST_SUCCESS_AT', 'RISK', 'SOURCE',
            'RECIPIENT_EMAIL', 'EMAIL_ATTEMPT_COUNT', 'EMAIL_CLAIM_TOKEN',
            'DELIVERY_BATCH_ID', 'CANCELLATION_REQUESTED', 'SCAN_CLAIM_TOKEN',
            'SCAN_CLAIM_UNTIL', 'WATCH_CREATED_ON')) {
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
            'CANCELLATION_REQUESTED', 'SCAN_CLAIM_TOKEN', 'SCAN_CLAIM_UNTIL',
            'WATCH_CREATED_ON', 'CURRENT_OBSERVED_SOURCES',
            'DOMAIN_LAST_SUCCESS_AT', 'DNS_LAST_SUCCESS_AT',
            'SSL_LAST_SUCCESS_AT', 'WEBSITE_LAST_SUCCESS_AT')) {
        Assert-Contains $completeIncrement "``$column``" "complete increment missing $column"
    }
    foreach ($migration in @($baseline, $completeIncrement)) {
        Assert-Contains $migration '@legacy_watch_source_time_zone' 'watch-date backfill must require the explicit legacy source zone'
        Assert-Contains $migration '@retention_reporting_time_zone' 'watch-date backfill must require the explicit reporting target zone'
        Assert-Contains $migration 'CONVERT_TZ' 'watch-date backfill must convert between explicit zones'
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
    $completeIncrementHeader = (($completeIncrement -split '\r?\n') | Select-Object -First 8) -join "`n"
    foreach ($absentTable in @('WEB_RETENTION_FACT_COLLECTION', 'WEB_RETENTION_FACT_HEALTH')) {
        Assert-Contains $completeIncrementHeader $absentTable "complete increment precondition must name absent $absentTable"
    }

    Assert-Contains $retentionReport 'FROM WEB_AUTHENTICATED_ACTIVITY_DAILY' 'retention report must use daily authenticated activity facts'
    Assert-Contains $retentionReport 'FROM WEB_RETENTION_FACT_COLLECTION' 'retention report must use the durable collection boundary'
    Assert-RolloutContract (-not $retentionReport.Contains('WEB_USER_QUERY_HISTORY')) 'retention report must not rely on capped query history'
    Assert-Contains $retentionReport 'SET @minimum_cohort_size = 5' 'retention report must define k-anonymity threshold'
    Assert-Contains $retentionReport 'SET @minimum_report_days = 120' 'retention report must require the full 120-day observation window'
    Assert-Contains $retentionReport 'INSUFFICIENT_HISTORY' 'retention report must expose an explicit immature-data state'
    Assert-Contains $retentionReport "@report_status = 'READY'" 'retention report must gate cohorts on maturity'
    Assert-Contains $retentionReport 'MIN(WATCH_CREATED_ON)' 'monitored cohort must use the explicit watch calendar fact'
    Assert-Contains $retentionReport 'first_observed_activity_date' 'control must be named as a first-observed activity cohort'
    Assert-Contains $retentionReport 'observed_non_monitored' 'control cohort label must describe observation rather than account creation'
    Assert-Contains $retentionReport '@verified_window_start' 'first-observed cohorts must be bounded to the verified collection window'
    Assert-RolloutContract (-not $retentionReport.Contains('DATE(MIN(CREATE_TIME))')) 'retention report must not infer watch cohort dates from DATETIME'
    Assert-RolloutContract (-not $retentionReport.Contains('JOIN SYS_USER')) 'retention report must not infer control cohorts from account CREATE_TIME'
    Assert-Contains $retentionReport 'cohort_users >= @minimum_cohort_size' 'daily cohorts below k must be suppressed'
    Assert-Contains $retentionReport 'SET @reporting_time_zone = COALESCE(' 'retention report must consume a caller-initialized SQL session zone'
    Assert-Contains $retentionReport "NULLIF(@@session.time_zone, 'SYSTEM')" 'retention report must reject an implicit SYSTEM session zone'
    Assert-RolloutContract (
        -not $retentionReport.Contains("COALESCE(@reporting_time_zone, '+08:00')")
    ) 'retention report must not define a second reporting-zone default'

    Assert-RolloutContract (
        [regex]::Matches($snapshotIncrement, '(?im)^ALTER TABLE `WEB_MONITOR_SNAPSHOT`').Count -eq 1 -and
        [regex]::Matches($snapshotIncrement, '(?im)^\s*ADD COLUMN').Count -eq 7
    ) 'snapshot increment must add exactly seven provenance/freshness columns to WEB_MONITOR_SNAPSHOT'
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
    $legacyUpgradePreconditionEnd = $legacyUpgrade.IndexOf('Back up the tables')
    Assert-RolloutContract ($legacyUpgradePreconditionEnd -gt 0) 'README legacy precondition paragraph is malformed'
    $legacyUpgradePrecondition = $legacyUpgrade.Substring(0, $legacyUpgradePreconditionEnd)
    foreach ($absentTable in @('WEB_RETENTION_FACT_COLLECTION', 'WEB_RETENTION_FACT_HEALTH')) {
        Assert-Contains $legacyUpgradePrecondition $absentTable "README legacy precondition must name absent $absentTable"
    }
    Assert-Contains $legacyUpgrade 'alter_retention_notification_center_from_e73ff4d.sql' 'README must prescribe the complete e73ff4d increment'
    Assert-Contains $readme 'If exactly one is present, stop' 'README missing partial legacy stop guard'
    Assert-Contains $readme 'If only some retention tables exist, stop' 'README missing partial baseline stop guard'
    Assert-Contains $readme '-Fixture LegacyUpgrade' 'README must document the real legacy-upgrade verifier'
    Assert-Contains $readme 'at least 120 days' 'README must document the minimum fact retention window'
    Assert-Contains $readme 'INSUFFICIENT_HISTORY' 'README must document the explicit immature report state'
    Assert-Contains $readme '| `WESITE_RETENTION_REPORTING_ZONE` |' 'README must name the single formal reporting ZoneId configuration'
    Assert-Contains $readme '`WEB_DOMAIN_WATCH.WATCH_CREATED_ON`' 'README must identify the explicit monitored cohort fact'
    Assert-Contains $readme 'observed non-monitored cohort' 'README must name the control as an observed cohort'
    Assert-Contains $readme '@legacy_watch_source_time_zone' 'README must document explicit legacy source-zone backfill'
    Assert-Contains $readme 'connectionTimeZone=UTC' 'README must document the JDBC instant timezone contract'
    Assert-Contains $readme "SET @reporting_time_zone='+08:00';" 'README must show the caller-variable reporting command'
    Assert-Contains $readme '--init-command="SET time_zone=''+08:00''"' 'README must show the MySQL session-init reporting command'
    Assert-RolloutContract (-not $readme.Contains('JVM default')) 'README must not describe the JVM default zone as the reporting contract'
    Assert-RolloutContract (-not $readme.Contains('edit the first `SET time_zone')) 'README must not instruct operators to edit a nonexistent SET statement'

    $immediateLine = 'wesite.notification-delivery.immediate-enabled=${WESITE_NOTIFICATION_DELIVERY_IMMEDIATE_ENABLED:false}'
    $digestLine = 'wesite.notification-delivery.digest-enabled=${WESITE_NOTIFICATION_DELIVERY_DIGEST_ENABLED:false}'
    $reportingZoneLine = 'wesite.retention.reporting-zone=${WESITE_RETENTION_REPORTING_ZONE:Asia/Shanghai}'
    Assert-Contains $application $reportingZoneLine 'application must map the formal reporting ZoneId environment variable'
    Assert-Contains $productionExample $reportingZoneLine 'production example must expose the formal reporting ZoneId'
    foreach ($properties in @($productionExample, $adminProductionExample)) {
        Assert-Contains $properties 'connectionTimeZone=UTC' 'JDBC examples must define the instant conversion zone'
        Assert-Contains $properties 'forceConnectionTimeZoneToSession=true' 'JDBC examples must force the explicit session zone'
    }
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
            'exec', $ContainerName, 'mysql', '--user=root', "--password=$Password",
            "--init-command=$MySqlSessionInit", '--execute=SELECT 1')
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
        [string]$RelativePath,
        [string]$InitSql = '') {
    $sessionPrefix = "$MySqlSessionInit;`n$InitSql;`n"
    $sessionPrefixBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sessionPrefix))
    Invoke-DockerCommand -Arguments @(
        'exec',
        $ContainerName,
        'sh',
        '-c',
        "{ echo $sessionPrefixBase64 | base64 -d; cat /sql/$RelativePath; } | mysql --user=root --password=$Password wesitedb")
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
        "{ echo $MySqlSessionInitBase64 | base64 -d; cat /sql/$RelativePath; } | mysql --user=root --password=$Password --batch wesitedb")
}

function Invoke-ReconciliationScript(
        [string]$ContainerName,
        [string]$Password,
        [string]$InitSql) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($InitSql))
    return Invoke-DockerCommand -ReturnOutput -Arguments @(
        'exec', $ContainerName, 'sh', '-c',
        "{ echo $MySqlSessionInitBase64 | base64 -d; echo $encoded | base64 -d; echo ';'; cat /sql/scripts/verify-retention-fact-day.sql; } | mysql --user=root --password=$Password --batch wesitedb")
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
        "--init-command=$MySqlSessionInit",
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
  AND ((TABLE_NAME = 'WEB_MONITOR_SNAPSHOT' AND COLUMN_NAME IN (
        'SCHEMA_VERSION','OBSERVED_SOURCES','CURRENT_OBSERVED_SOURCES',
        'DOMAIN_LAST_SUCCESS_AT','DNS_LAST_SUCCESS_AT','SSL_LAST_SUCCESS_AT','WEBSITE_LAST_SUCCESS_AT'))
    OR (TABLE_NAME = 'WEB_MONITOR_EVENT' AND COLUMN_NAME IN ('RISK','SOURCE')))
'@
    $establishedSourceComment = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_MONITOR_SNAPSHOT'
  AND COLUMN_NAME = 'OBSERVED_SOURCES'
  AND COLUMN_COMMENT = 'Sorted collector sources with an established reliable baseline (observed-ever)'
'@
    $sourceFreshnessColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_MONITOR_SNAPSHOT'
  AND IS_NULLABLE = 'YES'
  AND ((COLUMN_NAME = 'CURRENT_OBSERVED_SOURCES'
        AND DATA_TYPE = 'varchar'
        AND COLUMN_COMMENT = 'Collector sources that succeeded in this scan only')
    OR (COLUMN_NAME IN ('DOMAIN_LAST_SUCCESS_AT','DNS_LAST_SUCCESS_AT','SSL_LAST_SUCCESS_AT','WEBSITE_LAST_SUCCESS_AT')
        AND DATA_TYPE = 'datetime'))
'@
    $pipelineColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND ((TABLE_NAME = 'WEB_MONITOR_SNAPSHOT' AND COLUMN_NAME IN (
        'SCHEMA_VERSION','OBSERVED_SOURCES','CURRENT_OBSERVED_SOURCES',
        'DOMAIN_LAST_SUCCESS_AT','DNS_LAST_SUCCESS_AT','SSL_LAST_SUCCESS_AT','WEBSITE_LAST_SUCCESS_AT'))
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
  AND COLUMN_NAME IN ('NOTIFY_EMAIL','SCAN_CLAIM_TOKEN','SCAN_CLAIM_UNTIL','WATCH_CREATED_ON')
'@
    $watchCreatedDateColumn = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_DOMAIN_WATCH'
  AND COLUMN_NAME = 'WATCH_CREATED_ON'
  AND DATA_TYPE = 'date'
  AND IS_NULLABLE = 'YES'
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
    Assert-RolloutContract ($canonicalColumns -eq 9) "$Label expected nine canonical provenance/freshness/event columns, found $canonicalColumns"
    Assert-RolloutContract ($establishedSourceComment -eq 1) "$Label OBSERVED_SOURCES must document cumulative established/observed-ever provenance"
    Assert-RolloutContract ($sourceFreshnessColumns -eq 5) "$Label expected nullable current-source and four per-source last-success columns, found $sourceFreshnessColumns"
    Assert-RolloutContract ($pipelineColumns -eq 27) "$Label expected 27 key pipeline columns, found $pipelineColumns"
    Assert-RolloutContract ($watchColumns -eq 4) "$Label expected four watch compatibility/lease/calendar columns, found $watchColumns"
    Assert-RolloutContract ($watchCreatedDateColumn -eq 1) "$Label WATCH_CREATED_ON must be a nullable DATE for conservative legacy exclusion"
    Assert-RolloutContract ($activityColumns -eq 2) "$Label expected the daily activity fact columns, found $activityColumns"
    Assert-RolloutContract ($collectionColumns -eq 3) "$Label expected three fact collection columns, found $collectionColumns"
    Assert-RolloutContract ($collectionContract -eq 1) "$Label missing the durable 120-day fact collection row"
    Assert-RolloutContract ($auditColumns -eq 4) "$Label expected four delivery audit columns, found $auditColumns"
    Assert-RolloutContract ($batchIdentityColumns -eq 1) "$Label batch identity is not the exact frozen-recipient unique key"
    Assert-RolloutContract ($batchRecipientRequired -eq 1) "$Label batch recipient must be required"
    Write-Host "RETENTION_ROLLOUT_FIXTURE|PASS|PATH=$Label|TABLES=$tables|PIPELINE_COLUMNS=$pipelineColumns|SOURCE_FRESHNESS_COLUMNS=$sourceFreshnessColumns|WATCH_COLUMNS=$watchColumns|ACTIVITY_COLUMNS=$activityColumns|COLLECTION_COLUMNS=$collectionColumns|AUDIT_COLUMNS=$auditColumns"
}

function Assert-RetentionReportData(
        [string]$ContainerName,
        [string]$Password,
        [string]$Label) {
    $sessionZone = Invoke-FixtureQuery $ContainerName $Password 'SELECT @@session.time_zone;'
    Assert-RolloutContract ([regex]::IsMatch($sessionZone, '(?m)^\+08:00\r?$')) "$Label fixture query did not initialize the reporting time zone before SQL"
    $immature = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $immature.Contains('INSUFFICIENT_HISTORY') "$Label report did not expose immature history"
    Assert-RolloutContract (
        -not [regex]::IsMatch($immature, '(?m)^(monitored|observed_non_monitored)\t')
    ) "$Label immature report emitted a cohort"

    $retentionFixtureSql = @'
UPDATE WEB_RETENTION_FACT_COLLECTION
SET COLLECTION_STARTED_ON = DATE_SUB(CURDATE(), INTERVAL 130 DAY),
    MINIMUM_RETENTION_DAYS = 120
WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY';
DELETE FROM WEB_AUTHENTICATED_ACTIVITY_DAILY WHERE USER_ID LIKE 'ret-%';
DELETE FROM WEB_RETENTION_FACT_HEALTH WHERE FACT_NAME = 'AUTHENTICATED_ACTIVITY_DAILY';
DELETE FROM WEB_DOMAIN_WATCH WHERE ID LIKE 'ret-%';
INSERT INTO WEB_DOMAIN_WATCH
  (ID, STATUS, DELETED, USER_ID, DOMAIN_NAME, NOTIFY_TYPE, CREATE_TIME, WATCH_CREATED_ON)
VALUES
  ('ret-mature-1', 1, 0, 'ret-mon-1', 'mature-1.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-mature-2', 1, 0, 'ret-mon-2', 'mature-2.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-mature-3', 1, 0, 'ret-mon-3', 'mature-3.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-mature-4', 1, 0, 'ret-mon-4', 'mature-4.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-mature-5', 1, 0, 'ret-mon-5', 'mature-5.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-immature-1', 1, 0, 'ret-imm-1', 'immature-1.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-immature-2', 1, 0, 'ret-imm-2', 'immature-2.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-immature-3', 1, 0, 'ret-imm-3', 'immature-3.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-immature-4', 1, 0, 'ret-imm-4', 'immature-4.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-immature-5', 1, 0, 'ret-imm-5', 'immature-5.example', 0, DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
  ('ret-legacy-null-1', 1, 0, 'ret-legacy-mon-1', 'legacy-null-1.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), NULL),
  ('ret-legacy-null-2', 1, 0, 'ret-legacy-mon-2', 'legacy-null-2.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), NULL),
  ('ret-legacy-null-3', 1, 0, 'ret-legacy-mon-3', 'legacy-null-3.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), NULL),
  ('ret-legacy-null-4', 1, 0, 'ret-legacy-mon-4', 'legacy-null-4.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), NULL),
  ('ret-legacy-null-5', 1, 0, 'ret-legacy-mon-5', 'legacy-null-5.example', 0, DATE_SUB(CURDATE(), INTERVAL 60 DAY), NULL);
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
  ('ret-first-old5', 'ret-old-5', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-legacy-mon1', 'ret-legacy-mon-1', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-legacy-mon2', 'ret-legacy-mon-2', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-legacy-mon3', 'ret-legacy-mon-3', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-legacy-mon4', 'ret-legacy-mon-4', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-first-legacy-mon5', 'ret-legacy-mon-5', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-prewindow1-old', 'ret-prewindow-1', DATE_SUB(CURDATE(), INTERVAL 121 DAY)),
  ('ret-prewindow1-observed', 'ret-prewindow-1', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-prewindow1-return', 'ret-prewindow-1', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-prewindow2-old', 'ret-prewindow-2', DATE_SUB(CURDATE(), INTERVAL 121 DAY)),
  ('ret-prewindow2-observed', 'ret-prewindow-2', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-prewindow2-return', 'ret-prewindow-2', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-prewindow3-old', 'ret-prewindow-3', DATE_SUB(CURDATE(), INTERVAL 121 DAY)),
  ('ret-prewindow3-observed', 'ret-prewindow-3', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-prewindow3-return', 'ret-prewindow-3', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-prewindow4-old', 'ret-prewindow-4', DATE_SUB(CURDATE(), INTERVAL 121 DAY)),
  ('ret-prewindow4-observed', 'ret-prewindow-4', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-prewindow4-return', 'ret-prewindow-4', DATE_SUB(CURDATE(), INTERVAL 55 DAY)),
  ('ret-prewindow5-old', 'ret-prewindow-5', DATE_SUB(CURDATE(), INTERVAL 121 DAY)),
  ('ret-prewindow5-observed', 'ret-prewindow-5', DATE_SUB(CURDATE(), INTERVAL 60 DAY)),
  ('ret-prewindow5-return', 'ret-prewindow-5', DATE_SUB(CURDATE(), INTERVAL 55 DAY));
'@
    $factIds = @('ret-return-m1-5','ret-return-m1-20','ret-return-m2-5','ret-return-m2-25','ret-return-m3-5','ret-return-m3-29','ret-return-m4-20','ret-first-new1','ret-return-new1-5','ret-return-new1-20','ret-first-new2','ret-return-new2-5','ret-return-new2-20','ret-first-new3','ret-return-new3-5','ret-return-new3-20','ret-first-new4','ret-return-new4-5','ret-return-new4-20','ret-first-old1','ret-first-old2','ret-first-old3','ret-first-old4','ret-first-old5','ret-first-legacy-mon1','ret-first-legacy-mon2','ret-first-legacy-mon3','ret-first-legacy-mon4','ret-first-legacy-mon5','ret-prewindow1-old','ret-prewindow1-observed','ret-prewindow1-return','ret-prewindow2-old','ret-prewindow2-observed','ret-prewindow2-return','ret-prewindow3-old','ret-prewindow3-observed','ret-prewindow3-return','ret-prewindow4-old','ret-prewindow4-observed','ret-prewindow4-return','ret-prewindow5-old','ret-prewindow5-observed','ret-prewindow5-return')
    foreach ($factId in $factIds) { $retentionFixtureSql = $retentionFixtureSql.Replace("('$factId', ", '(') }
    [void](Invoke-FixtureQuery $ContainerName $Password $retentionFixtureSql)

    [void](Invoke-FixtureQuery $ContainerName $Password @'
INSERT INTO WEB_RETENTION_FACT_HEALTH
  (FACT_NAME, FACT_DATE, SUCCESSFUL_WRITE_COUNT, FAILURE_COUNT,
   EXPECTED_FACT_ROWS, LAST_SUCCESS_AT, VERIFICATION_STATUS, VERIFIED_AT,
   EXTERNAL_EXPECTED_ROWS, RECONCILIATION_SOURCE, RECONCILIATION_ID)
WITH RECURSIVE days AS (
  SELECT DATE_SUB(CURDATE(), INTERVAL 120 DAY) AS fact_date
  UNION ALL SELECT DATE_ADD(fact_date, INTERVAL 1 DAY) FROM days
  WHERE fact_date < DATE_SUB(CURDATE(), INTERVAL 1 DAY)
)
SELECT 'AUTHENTICATED_ACTIVITY_DAILY', fact_date, 1, 0,
  (SELECT COUNT(DISTINCT USER_ID) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY A
   WHERE A.ACTIVITY_DATE = fact_date),
  NOW(), 'VERIFIED', NOW(),
  (SELECT COUNT(DISTINCT USER_ID) FROM WEB_AUTHENTICATED_ACTIVITY_DAILY A
   WHERE A.ACTIVITY_DATE = fact_date),
  'fixture-auth-gateway', CONCAT('fixture-', DATE_FORMAT(fact_date, '%Y%m%d'))
FROM days;
'@)

    [void](Invoke-FixtureQuery $ContainerName $Password @'
INSERT INTO WEB_RETENTION_FACT_HEALTH
  (FACT_NAME, FACT_DATE, SUCCESSFUL_WRITE_COUNT, FAILURE_COUNT,
   EXPECTED_FACT_ROWS, LAST_SUCCESS_AT, VERIFICATION_STATUS)
SELECT 'AUTHENTICATED_ACTIVITY_DAILY', CURDATE(), 1, 0,
  COUNT(DISTINCT USER_ID), NOW(), 'OPEN'
FROM WEB_AUTHENTICATED_ACTIVITY_DAILY WHERE ACTIVITY_DATE=CURDATE()
ON DUPLICATE KEY UPDATE VERIFICATION_STATUS='OPEN', VERIFIED_AT=NULL,
  EXTERNAL_EXPECTED_ROWS=NULL, RECONCILIATION_SOURCE=NULL, RECONCILIATION_ID=NULL;
'@)
    $todayInit = "SET @reporting_time_zone='$FixtureReportingTimeZone',@verified_fact_date=CURDATE(),@external_expected_rows=0,@reconciliation_source='auth-gateway',@reconciliation_id='today-$Label'"
    try { [void](Invoke-ReconciliationScript $ContainerName $Password $todayInit); throw 'current reporting date was accepted' }
    catch { Assert-RolloutContract (-not $_.Exception.Message.Contains('was accepted')) "$Label current reporting date was accepted" }
    $todayOpen = Get-FixtureCount $ContainerName $Password "SELECT COUNT(*) FROM WEB_RETENTION_FACT_HEALTH WHERE FACT_DATE=CURDATE() AND VERIFICATION_STATUS='OPEN' AND VERIFIED_AT IS NULL AND EXTERNAL_EXPECTED_ROWS IS NULL AND RECONCILIATION_SOURCE IS NULL AND RECONCILIATION_ID IS NULL;"
    Assert-RolloutContract ($todayOpen -eq 1) "$Label rejected current date did not remain OPEN without audit"
    [void](Invoke-FixtureQuery $ContainerName $Password "DELETE FROM WEB_RETENTION_FACT_HEALTH WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=CURDATE();")

    [void](Invoke-FixtureQuery $ContainerName $Password "UPDATE WEB_RETENTION_FACT_HEALTH SET VERIFICATION_STATUS='OPEN',VERIFIED_AT=NULL,EXTERNAL_EXPECTED_ROWS=NULL,RECONCILIATION_SOURCE=NULL,RECONCILIATION_ID=NULL WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=DATE_SUB(CURDATE(),INTERVAL 100 DAY);")
    try { [void](Invoke-ReconciliationScript $ContainerName $Password ''); throw 'missing reconciliation input was accepted' }
    catch { Assert-RolloutContract (-not $_.Exception.Message.Contains('was accepted')) "$Label missing reconciliation input was accepted" }
    $mismatchInit = "SET @reporting_time_zone='$FixtureReportingTimeZone',@verified_fact_date=DATE_SUB(CURDATE(),INTERVAL 100 DAY),@external_expected_rows=1,@reconciliation_source='auth-gateway',@reconciliation_id='mismatch-$Label'"
    try { [void](Invoke-ReconciliationScript $ContainerName $Password $mismatchInit); throw 'mismatched reconciliation was accepted' }
    catch { Assert-RolloutContract (-not $_.Exception.Message.Contains('was accepted')) "$Label mismatched reconciliation was accepted" }
    $matchInit = "SET @reporting_time_zone='$FixtureReportingTimeZone',@verified_fact_date=DATE_SUB(CURDATE(),INTERVAL 100 DAY),@external_expected_rows=0,@reconciliation_source='auth-gateway',@reconciliation_id='gateway-$Label-100'"
    [void](Invoke-ReconciliationScript $ContainerName $Password $matchInit)
    $reconciled = Get-FixtureCount $ContainerName $Password "SELECT COUNT(*) FROM WEB_RETENTION_FACT_HEALTH WHERE FACT_DATE=DATE_SUB(CURDATE(),INTERVAL 100 DAY) AND VERIFICATION_STATUS='VERIFIED' AND EXTERNAL_EXPECTED_ROWS=0 AND RECONCILIATION_SOURCE='auth-gateway' AND RECONCILIATION_ID='gateway-$Label-100';"
    Assert-RolloutContract ($reconciled -eq 1) "$Label matching reconciliation did not persist audit fields"

    $auditCorruptions = @(
        @{ Name = 'missing VERIFIED_AT'; Corrupt = 'VERIFIED_AT=NULL'; Restore = 'VERIFIED_AT=NOW()' },
        @{ Name = 'missing external count'; Corrupt = 'EXTERNAL_EXPECTED_ROWS=NULL'; Restore = 'EXTERNAL_EXPECTED_ROWS=EXPECTED_FACT_ROWS' },
        @{ Name = 'blank reconciliation source'; Corrupt = "RECONCILIATION_SOURCE='   '"; Restore = "RECONCILIATION_SOURCE='auth-gateway'" },
        @{ Name = 'missing reconciliation id'; Corrupt = 'RECONCILIATION_ID=NULL'; Restore = "RECONCILIATION_ID='gateway-$Label-100'" }
    )
    foreach ($corruption in $auditCorruptions) {
        [void](Invoke-FixtureQuery $ContainerName $Password "UPDATE WEB_RETENTION_FACT_HEALTH SET $($corruption.Corrupt) WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=DATE_SUB(CURDATE(),INTERVAL 100 DAY);")
        $missingAudit = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
        Assert-RolloutContract $missingAudit.Contains('INSUFFICIENT_HISTORY') "$Label report accepted $($corruption.Name)"
        [void](Invoke-FixtureQuery $ContainerName $Password "UPDATE WEB_RETENTION_FACT_HEALTH SET $($corruption.Restore) WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=DATE_SUB(CURDATE(),INTERVAL 100 DAY);")
    }

    [void](Invoke-FixtureQuery $ContainerName $Password "UPDATE WEB_RETENTION_FACT_HEALTH SET EXTERNAL_EXPECTED_ROWS=EXPECTED_FACT_ROWS+1 WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=DATE_SUB(CURDATE(),INTERVAL 100 DAY);")
    $externalMismatch = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $externalMismatch.Contains('INSUFFICIENT_HISTORY') "$Label report accepted EXPECTED_FACT_ROWS != EXTERNAL_EXPECTED_ROWS"
    [void](Invoke-FixtureQuery $ContainerName $Password "UPDATE WEB_RETENTION_FACT_HEALTH SET EXTERNAL_EXPECTED_ROWS=EXPECTED_FACT_ROWS WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=DATE_SUB(CURDATE(),INTERVAL 100 DAY);")

    [void](Invoke-FixtureQuery $ContainerName $Password "INSERT INTO WEB_AUTHENTICATED_ACTIVITY_DAILY (USER_ID,ACTIVITY_DATE) VALUES ('ret-rogue',DATE_SUB(CURDATE(),INTERVAL 54 DAY));")
    $extraFact = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $extraFact.Contains('INSUFFICIENT_HISTORY') "$Label report accepted actual distinct facts greater than both expected counts"
    [void](Invoke-FixtureQuery $ContainerName $Password "DELETE FROM WEB_AUTHENTICATED_ACTIVITY_DAILY WHERE USER_ID='ret-rogue' AND ACTIVITY_DATE=DATE_SUB(CURDATE(),INTERVAL 54 DAY);")

    [void](Invoke-FixtureQuery $ContainerName $Password "DELETE FROM WEB_RETENTION_FACT_HEALTH WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=DATE_SUB(CURDATE(), INTERVAL 100 DAY);")
    $auditedDays = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_RETENTION_FACT_HEALTH
WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY'
  AND FACT_DATE>=DATE_SUB(CURDATE(),INTERVAL 120 DAY) AND FACT_DATE<CURDATE()
  AND VERIFICATION_STATUS='VERIFIED' AND VERIFIED_AT IS NOT NULL
  AND EXTERNAL_EXPECTED_ROWS=EXPECTED_FACT_ROWS
  AND NULLIF(TRIM(RECONCILIATION_SOURCE),'') IS NOT NULL
  AND NULLIF(TRIM(RECONCILIATION_ID),'') IS NOT NULL;
'@
    Assert-RolloutContract ($auditedDays -eq 119) "$Label expected exactly 119 fully audited days after one-row removal, found $auditedDays"
    $gap = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $gap.Contains('INSUFFICIENT_HISTORY') "$Label report accepted an interrupted health interval"
    [void](Invoke-FixtureQuery $ContainerName $Password @'
INSERT INTO WEB_RETENTION_FACT_HEALTH
  (FACT_NAME, FACT_DATE, SUCCESSFUL_WRITE_COUNT, FAILURE_COUNT,
   EXPECTED_FACT_ROWS, LAST_SUCCESS_AT, VERIFICATION_STATUS, VERIFIED_AT,
   EXTERNAL_EXPECTED_ROWS, RECONCILIATION_SOURCE, RECONCILIATION_ID)
SELECT 'AUTHENTICATED_ACTIVITY_DAILY', DATE_SUB(CURDATE(), INTERVAL 100 DAY), 1, 0,
  COUNT(DISTINCT USER_ID), NOW(), 'VERIFIED', NOW(), COUNT(DISTINCT USER_ID),
  'fixture-auth-gateway', CONCAT('fixture-', DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 100 DAY), '%Y%m%d'))
FROM WEB_AUTHENTICATED_ACTIVITY_DAILY
WHERE ACTIVITY_DATE=DATE_SUB(CURDATE(), INTERVAL 100 DAY);
DELETE FROM WEB_AUTHENTICATED_ACTIVITY_DAILY
WHERE USER_ID='ret-mon-1' AND ACTIVITY_DATE=DATE_SUB(CURDATE(), INTERVAL 55 DAY);
'@)
    $cleaned = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $cleaned.Contains('INSUFFICIENT_HISTORY') "$Label report accepted prematurely cleaned facts"
    [void](Invoke-FixtureQuery $ContainerName $Password "INSERT INTO WEB_AUTHENTICATED_ACTIVITY_DAILY (USER_ID,ACTIVITY_DATE) VALUES ('ret-mon-1',DATE_SUB(CURDATE(), INTERVAL 55 DAY));")

    [void](Invoke-FixtureQuery $ContainerName $Password "UPDATE WEB_RETENTION_FACT_HEALTH SET VERIFICATION_STATUS='OPEN',VERIFIED_AT=NULL,EXTERNAL_EXPECTED_ROWS=NULL,RECONCILIATION_SOURCE=NULL,RECONCILIATION_ID=NULL WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY' AND FACT_DATE=DATE_SUB(CURDATE(),INTERVAL 56 DAY);")
    $beforeLateFactInit = "SET @reporting_time_zone='$FixtureReportingTimeZone',@verified_fact_date=DATE_SUB(CURDATE(),INTERVAL 56 DAY),@external_expected_rows=0,@reconciliation_source='auth-gateway',@reconciliation_id='late-before-$Label'"
    [void](Invoke-ReconciliationScript $ContainerName $Password $beforeLateFactInit)
    [void](Invoke-FixtureQuery $ContainerName $Password @'
START TRANSACTION;
INSERT IGNORE INTO WEB_AUTHENTICATED_ACTIVITY_DAILY (USER_ID,ACTIVITY_DATE)
VALUES ('ret-late-fact',DATE_SUB(CURDATE(),INTERVAL 56 DAY));
SET @inserted_fact=ROW_COUNT();
INSERT INTO WEB_RETENTION_FACT_HEALTH
  (FACT_NAME,FACT_DATE,SUCCESSFUL_WRITE_COUNT,EXPECTED_FACT_ROWS,LAST_SUCCESS_AT)
VALUES ('AUTHENTICATED_ACTIVITY_DAILY',DATE_SUB(CURDATE(),INTERVAL 56 DAY),1,@inserted_fact,NOW())
ON DUPLICATE KEY UPDATE SUCCESSFUL_WRITE_COUNT=SUCCESSFUL_WRITE_COUNT+1,
  EXPECTED_FACT_ROWS=EXPECTED_FACT_ROWS+VALUES(EXPECTED_FACT_ROWS),
  VERIFIED_AT=IF(VALUES(EXPECTED_FACT_ROWS)>0,NULL,VERIFIED_AT),
  EXTERNAL_EXPECTED_ROWS=IF(VALUES(EXPECTED_FACT_ROWS)>0,NULL,EXTERNAL_EXPECTED_ROWS),
  RECONCILIATION_SOURCE=IF(VALUES(EXPECTED_FACT_ROWS)>0,NULL,RECONCILIATION_SOURCE),
  RECONCILIATION_ID=IF(VALUES(EXPECTED_FACT_ROWS)>0,NULL,RECONCILIATION_ID),
  VERIFICATION_STATUS=IF(VALUES(EXPECTED_FACT_ROWS)>0,'OPEN',VERIFICATION_STATUS),
  LAST_SUCCESS_AT=NOW();
COMMIT;
'@)
    $lateFactOpened = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_RETENTION_FACT_HEALTH
WHERE FACT_NAME='AUTHENTICATED_ACTIVITY_DAILY'
  AND FACT_DATE=DATE_SUB(CURDATE(),INTERVAL 56 DAY)
  AND EXPECTED_FACT_ROWS=1 AND VERIFICATION_STATUS='OPEN'
  AND VERIFIED_AT IS NULL AND EXTERNAL_EXPECTED_ROWS IS NULL
  AND RECONCILIATION_SOURCE IS NULL AND RECONCILIATION_ID IS NULL;
'@
    Assert-RolloutContract ($lateFactOpened -eq 1) "$Label late fact did not atomically reopen and clear the verified audit"
    $lateFactReport = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $lateFactReport.Contains('INSUFFICIENT_HISTORY') "$Label report accepted a reopened day after a late fact"
    $afterLateFactInit = "SET @reporting_time_zone='$FixtureReportingTimeZone',@verified_fact_date=DATE_SUB(CURDATE(),INTERVAL 56 DAY),@external_expected_rows=1,@reconciliation_source='auth-gateway',@reconciliation_id='late-after-$Label'"
    [void](Invoke-ReconciliationScript $ContainerName $Password $afterLateFactInit)

    $mature = Invoke-FixtureSqlFileOutput $ContainerName $Password 'scripts/retention-report.sql'
    Assert-RolloutContract $mature.Contains('READY') "$Label mature report did not become ready"
    Assert-RolloutContract (
        [regex]::Matches($mature, '(?m)^monitored\t\d{4}-\d{2}-\d{2}\t5\t3\t60\.00\t4\t80\.00\r?$').Count -eq 1
    ) "$Label mature five-user cohort or return counts were incorrect: $mature"
    Assert-RolloutContract (
        [regex]::Matches($mature, '(?m)^monitored\t\d{4}-\d{2}-\d{2}\t').Count -eq 1
    ) "$Label included legacy NULL WATCH_CREATED_ON rows in the monitored cohort: $mature"
    Assert-RolloutContract (
        [regex]::Matches($mature, '(?m)^observed_non_monitored\t\d{4}-\d{2}-\d{2}\t14\t9\t64\.29\t9\t64\.29\r?$').Count -eq 1
    ) "$Label first-observed control or verified-window boundary was incorrect: $mature"
    Write-Host "RETENTION_REPORT_DATA|PASS|PATH=$Label|IMMATURE=EMPTY|CLOSED_DATE=REJECTED|AUDIT=REQUIRED|AUDITED_DAYS=119|GAP=INSUFFICIENT|CLEANUP=INSUFFICIENT|LATE_FACT=OPEN|MATURE=5|LEGACY_NULL=EXCLUDED|OBSERVED_CONTROL=14"
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
    $unknownSourceFreshness = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_MONITOR_SNAPSHOT
WHERE ID = 'legacy-source-freshness'
  AND CURRENT_OBSERVED_SOURCES IS NULL
  AND DOMAIN_LAST_SUCCESS_AT IS NULL
  AND DNS_LAST_SUCCESS_AT IS NULL
  AND SSL_LAST_SUCCESS_AT IS NULL
  AND WEBSITE_LAST_SUCCESS_AT IS NULL
'@
    Assert-RolloutContract ($unknownSourceFreshness -eq 1) 'legacy per-source freshness must remain NULL instead of guessing from CHECKED_AT'
    if ($HasLegacyWatchEmail) {
        $backfilled = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_DOMAIN_WATCH
WHERE (ID='w-seven' AND WATCH_CREATED_ON='2026-08-02')
   OR (ID IN ('w-none','w-no-email') AND WATCH_CREATED_ON='2026-08-01')
'@
        Assert-RolloutContract ($backfilled -eq 3) 'explicit UTC -> Shanghai legacy watch-date backfill was incorrect'
        $watchDateState = 'BACKFILLED=3'
    } else {
        $excluded = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_DOMAIN_WATCH
WHERE ID IN ('w-none','w-seven','w-no-email') AND WATCH_CREATED_ON IS NULL
'@
        Assert-RolloutContract ($excluded -eq 3) 'legacy watch dates without an explicit source zone must remain NULL'
        $watchDateState = 'NULL_EXCLUDED=3'
    }
    Write-Host "RETENTION_LEGACY_DATA|PASS|WATCH_EMAIL=$HasLegacyWatchEmail|ALLOWED=$allowed|CANCELLED=$cancelled|$watchDateState|SOURCE_FRESHNESS_NULL=1"
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
    $unbackfilled = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*) FROM WEB_DOMAIN_WATCH
WHERE ID IN ('pathb-valid','pathb-invalid') AND WATCH_CREATED_ON IS NULL
'@
    Assert-RolloutContract ($preserved -eq 1) 'PathB did not preserve and normalize its valid legacy watch recipient'
    Assert-RolloutContract ($rejected -eq 1) 'PathB did not fail closed for malformed legacy watch settings'
    Assert-RolloutContract ($unbackfilled -eq 2) 'PathB guessed legacy watch dates without an explicit source zone'
    Write-Host "RETENTION_PATHB_WATCH_DATA|PASS|PRESERVED=$preserved|REJECTED=$rejected|NULL_EXCLUDED=$unbackfilled"
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
            $legacyDateInit = if ($Path -eq 'LegacyWatchEmail') {
                "SET @legacy_watch_source_time_zone='+00:00'; SET @retention_reporting_time_zone='+08:00'"
            } else { '' }
            Invoke-FixtureSqlFile $containerName $password 'doc/alter_retention_notification_center_from_e73ff4d.sql' $legacyDateInit
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
