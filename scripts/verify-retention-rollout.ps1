[CmdletBinding()]
param(
    [ValidateSet('Static', 'PathA', 'PathB', 'All')]
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
            'WEB_NOTIFICATION_DELIVERY_BATCH')) {
        Assert-Contains $baseline "CREATE TABLE ``$table``" "retention baseline missing $table"
    }
    foreach ($column in @('SCHEMA_VERSION', 'OBSERVED_SOURCES', 'RISK', 'SOURCE')) {
        Assert-Contains $baseline "``$column``" "retention baseline missing $column"
    }

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
    $snapshotPosition = $pathB.IndexOf('alter_monitor_snapshot_observation_sources.sql')
    $eventPosition = $pathB.IndexOf('alter_monitor_event_canonical_risk.sql')
    Assert-RolloutContract (
        $legacyPosition -ge 0 -and
        $legacyPosition -lt $baselinePosition -and
        $baselinePosition -lt $snapshotPosition -and
        $snapshotPosition -lt $eventPosition
    ) 'README Path B migration order is not legacy -> baseline -> snapshot -> event'
    Assert-Contains $readme 'If exactly one is present, stop' 'README missing partial legacy stop guard'
    Assert-Contains $readme 'If only some retention tables exist, stop' 'README missing partial baseline stop guard'

    $immediateLine = 'wesite.notification-delivery.immediate-enabled=${WESITE_NOTIFICATION_DELIVERY_IMMEDIATE_ENABLED:false}'
    $digestLine = 'wesite.notification-delivery.digest-enabled=${WESITE_NOTIFICATION_DELIVERY_DIGEST_ENABLED:false}'
    foreach ($properties in @($application, $productionExample)) {
        Assert-Contains $properties $immediateLine 'immediate delivery must have an environment-backed false default'
        Assert-Contains $properties $digestLine 'digest delivery must have an environment-backed false default'
    }

    Assert-Contains $immediateJob 'wesite.notification-delivery.immediate-enabled' 'immediate job property mismatch'
    Assert-Contains $immediateJob 'matchIfMissing = false' 'immediate job must be absent when its property is missing'
    Assert-Contains $digestJob 'wesite.notification-delivery.digest-enabled' 'digest job property mismatch'
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
  AND TABLE_NAME IN ('WEB_MONITOR_SNAPSHOT','WEB_MONITOR_EVENT','WEB_USER_NOTIFICATION','WEB_NOTIFICATION_DELIVERY_BATCH')
'@
    $canonicalColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND ((TABLE_NAME = 'WEB_MONITOR_SNAPSHOT' AND COLUMN_NAME IN ('SCHEMA_VERSION','OBSERVED_SOURCES'))
    OR (TABLE_NAME = 'WEB_MONITOR_EVENT' AND COLUMN_NAME IN ('RISK','SOURCE')))
'@
    $auditColumns = Get-FixtureCount $ContainerName $Password @'
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'wesitedb'
  AND TABLE_NAME = 'WEB_DOMAIN_WATCH_NOTIFY_LOG'
  AND COLUMN_NAME IN ('NOTIFICATION_ID','BATCH_ID','EVENT_ID','DELIVERY_MODE')
'@

    Assert-RolloutContract ($tables -eq 4) "$Label expected four retention tables, found $tables"
    Assert-RolloutContract ($canonicalColumns -eq 4) "$Label expected four canonical columns, found $canonicalColumns"
    Assert-RolloutContract ($auditColumns -eq 4) "$Label expected four delivery audit columns, found $auditColumns"
    Write-Host "RETENTION_ROLLOUT_FIXTURE|PASS|PATH=$Label|TABLES=$tables|CANONICAL_COLUMNS=$canonicalColumns|AUDIT_COLUMNS=$auditColumns"
}

function Invoke-MySqlFixture([ValidateSet('PathA', 'PathB')][string]$Path) {
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

        Invoke-FixtureSqlFile $containerName $password 'doc/create.sql'
        if ($Path -eq 'PathB') {
            [void](Invoke-FixtureQuery $containerName $password 'DROP TABLE WEB_DOMAIN_WATCH, WEB_DOMAIN_SNAPSHOT')
            Invoke-FixtureSqlFile $containerName $password 'doc/alter_domain_watch_snapshot.sql'
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

Write-Host "RETENTION_ROLLOUT_VERIFY|PASS|FIXTURE=$Fixture"
