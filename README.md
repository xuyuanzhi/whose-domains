# introduce

A Spring Boot 3 platform for domain research and webmaster tooling — 20+ online tools including WHOIS lookup, DNS analysis, SSL inspection, domain valuation, and bulk search, plus a blog and admin backend.

## Features

**Domain tools**
- WHOIS lookup / WHOIS compare / RDAP lookup
- Domain history / Reverse IP / Related domains
- Domain availability / Bulk search / Expiring domains
- Domain valuation / Domain score / Competitor analysis

**Network tools**
- DNS analyzer / Ping test / Port checker
- SSL certificate checker / Email validator / IP geolocation

**Developer tools**
- JSON / XML / HTML formatters
- Timezone converter

**Other**
- Blog system
- AI content generation (DeepSeek integration)
- SEO baked in (auto sitemap, structured data, OG images)

## Tech stack

- **Runtime**: Java 17, Maven
- **Backend**: Spring Boot 3.5, MyBatis-Plus 3.5, Thymeleaf
- **Data**: MySQL 8, Redis, HikariCP
- **Libraries**: MaxMind GeoIP2, DeepSeek API, JWT, dnsjava, jsoup
- **API docs**: SpringDoc OpenAPI

## Project layout

```
wesite-parent/
├── wesite-core/      # Shared module: entities, utilities, common services
├── wesite-web/       # Public site (port 80/8080)
├── wesite-admin/     # Admin backend (port 8082)
├── doc/              # SQL scripts and design documents
└── pom.xml
```

## Getting started

### Prerequisites
- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 6+
- MaxMind GeoLite2 databases ([download here](https://www.maxmind.com/) — free signup required)

### 1. Initialize the database

```bash
mysql -u root -p -e "CREATE DATABASE wesitedb DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p wesitedb < doc/create.sql
mysql -u root -p wesitedb < doc/insert.sql
mysql -u root -p wesitedb < doc/data_tld_content.sql
mysql -u root -p wesitedb < doc/data_blog_posts.sql
```

### 2. Configure

Copy the templates and fill in your local values (these files are gitignored, so they will not be committed):

```bash
cp deploy/config/wesite-web.application-prod.properties.example \
   wesite-web/src/main/resources/application-prod.properties
cp deploy/config/wesite-admin.application-prod.properties.example \
   wesite-admin/src/main/resources/application-prod.properties
```

You can also override settings via environment variables without touching the config files:

| Variable | Description |
| --- | --- |
| `WD_DB_URL` | MySQL JDBC URL; production overrides must preserve the documented explicit UTC connection/session options |
| `WD_DB_USERNAME` | Database username |
| `WD_DB_PASSWORD` | Database password |
| `REDIS_PASSWORD` | Redis password (may be empty) |
| `JWT_SECRET` | JWT signing secret |
| `DEEPSEEK_API_KEY` | DeepSeek API key (required for AI features) |
| `WESITE_SUPPORT_URL` | Optional override for the official HTTPS PayPal Payment Link; set it to an empty value to hide support links |
| `WESITE_NOTIFICATION_DELIVERY_IMMEDIATE_ENABLED` | Allows the immediate-mail job when `wesite.mail.enabled=true`; defaults to `false` |
| `WESITE_NOTIFICATION_DELIVERY_DIGEST_ENABLED` | Allows the daily/weekly digest jobs when `wesite.mail.enabled=true`; defaults to `false` |
| `WESITE_RETENTION_REPORTING_ZONE` | The single formal retention reporting calendar, as an IANA `ZoneId`; defaults to `Asia/Shanghai` |

### 3. Build and run

```bash
# Build everything
mvn clean install -DskipTests

# Start the public site
cd wesite-web
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Start the admin backend (in another terminal)
cd wesite-admin
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

URLs:
- Public site: http://localhost:80
- Admin backend: http://localhost:8082
- API docs: http://localhost:80/swagger-ui.html

### Production deployment

Production Web and Admin releases are independent. The Web Jenkins job builds
and uploads only the Web JAR and gates only Web health; the Admin job does the
same for Admin. Their Pipeline definitions are
[`deploy/jenkins/wesite-web.Jenkinsfile`](deploy/jenkins/wesite-web.Jenkinsfile)
and
[`deploy/jenkins/wesite-admin.Jenkinsfile`](deploy/jenkins/wesite-admin.Jenkinsfile).

Follow [`deploy/README.md`](deploy/README.md) for the ordered source-free
bootstrap, external live configuration, old-binary baselines, first releases,
Jenkins credential setup, checks, root-only rollback, logs, and cleanup.
Routine Jenkins releases never upload source or infrastructure, edit live
configuration, manage services directly, run database work, or perform
rollback. `application-prod.properties` remains external and gitignored; never
put real production secrets in a tracked file, Jenkins parameter, workspace,
or artifact.

### Post-deployment SEO contract

Run `pwsh -File scripts/check-seo.ps1 -BaseUrl https://whose.domains` after each production deployment. This is a production-only contract: `-BaseUrl` must normalize to the exact origin `https://whose.domains` (an optional trailing slash is accepted), and other hosts, schemes, non-default ports, paths, queries, or fragments are rejected before any HTTP request.

### Post-deployment production smoke

Run the read-only P0 smoke check immediately after each production deployment and at every traffic-expansion checkpoint:

```powershell
pwsh -NoProfile -File scripts/check-production-smoke.ps1 `
  -BaseUrl https://whose.domains `
  -TimeoutSeconds 20
```

The script sends GET requests only, refuses any origin other than the exact production HTTPS origin, disables redirects, and checks the homepage, tool catalog, WHOIS lookup, DNS analyzer, SSL checker, login page, and help center. Every endpoint must directly return HTTP 200 with a non-empty HTML document. It aggregates all failures, exits non-zero when any check fails, and reports elapsed time per successful endpoint. Use `-SelfTest` to validate the script offline without sending requests.

Use the following release gates:

1. Run the smoke check at 5%, 25%, and 100% traffic. Two consecutive smoke failures stop traffic expansion; investigate or roll back the application release.
2. Run `scripts/check-seo.ps1` after the P0 smoke check passes. Keep the broader sitemap crawl separate so a slow SEO audit does not delay the first availability decision.
3. Keep both notification-delivery rollout properties false for the first smoke pass. Validate an internal watch, snapshot, event, in-app notification, unread count, link, and user scope before enabling email delivery.
4. After enabling immediate delivery, disable that rollout property on any wrong recipient, duplicate message, or queue-age breach. Data corruption or cross-user access requires stopping workers and rolling back the application.

Record the deployed commit, operator, UTC time, traffic percentage, command output, and decision at each checkpoint. The public smoke check deliberately does not mutate account data or invoke domain-probe APIs; authenticated, notification, and external-provider journeys remain controlled internal-account checks.

### Retention notification center operations

The watchlist, monitoring-event, in-app notification, and email-delivery pipeline is an additive production migration. Take a schema and data backup before starting, stop application instances that run scheduled workers, and apply the scripts with the same MySQL user that owns the application tables.

#### SQL preflight and mutually exclusive migration paths

Run this read-only preflight first. It lists the exact tables and retention columns that decide the path; it returns metadata only, never application data.

```bash
mysql -u root -p wesitedb -e "
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('WEB_DOMAIN_WATCH','WEB_DOMAIN_SNAPSHOT','WEB_DOMAIN_WATCH_NOTIFY_LOG','WEB_MONITOR_SNAPSHOT','WEB_MONITOR_EVENT','WEB_USER_NOTIFICATION','WEB_NOTIFICATION_DELIVERY_BATCH','WEB_NOTIFICATION_PREFERENCE','WEB_AUTHENTICATED_ACTIVITY_DAILY','WEB_RETENTION_FACT_COLLECTION','WEB_RETENTION_FACT_HEALTH')
ORDER BY TABLE_NAME;
SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'WEB_MONITOR_SNAPSHOT' AND COLUMN_NAME IN ('SCHEMA_VERSION','OBSERVED_SOURCES','CURRENT_OBSERVED_SOURCES','DOMAIN_LAST_SUCCESS_AT','DNS_LAST_SUCCESS_AT','SSL_LAST_SUCCESS_AT','WEBSITE_LAST_SUCCESS_AT'))
    OR (TABLE_NAME = 'WEB_MONITOR_EVENT' AND COLUMN_NAME IN ('RISK','SOURCE'))
    OR (TABLE_NAME = 'WEB_USER_NOTIFICATION' AND COLUMN_NAME IN ('RECIPIENT_EMAIL','EMAIL_MODE','EMAIL_ATTEMPT_COUNT','EMAIL_CLAIM_TOKEN','DELIVERY_BATCH_ID'))
    OR (TABLE_NAME = 'WEB_NOTIFICATION_DELIVERY_BATCH' AND COLUMN_NAME IN ('RECIPIENT_EMAIL','CANCELLATION_REQUESTED'))
    OR (TABLE_NAME = 'WEB_DOMAIN_WATCH' AND COLUMN_NAME IN ('NOTIFY_EMAIL','SCAN_CLAIM_TOKEN','SCAN_CLAIM_UNTIL','WATCH_CREATED_ON')))
ORDER BY TABLE_NAME, COLUMN_NAME;"
```

**Path A — current clean installation.** `doc/create.sql` already creates `WEB_DOMAIN_WATCH`, `WEB_DOMAIN_WATCH_NOTIFY_LOG`, `WEB_USER_QUERY_HISTORY`, and `WEB_API_USAGE_DAILY`. After the normal bootstrap command `mysql -u root -p wesitedb < doc/create.sql`, run **only**:

```bash
mysql -u root -p wesitedb < doc/alter_retention_notification_center.sql
```

Do **not** run `doc/alter_domain_watch_snapshot.sql` on Path A: its unguarded `CREATE TABLE WEB_DOMAIN_WATCH` duplicates an object that `create.sql` already created. The current retention script already creates `WEB_MONITOR_SNAPSHOT` with schema version 3, cumulative `OBSERVED_SOURCES`, scan-local `CURRENT_OBSERVED_SOURCES`, and all four source-specific `*_LAST_SUCCESS_AT` fields; it also creates `WEB_MONITOR_EVENT` with `RISK` and `SOURCE`. Do **not** run any later `ADD COLUMN` script after Path A.

`WEB_MONITOR_SNAPSHOT.OBSERVED_SOURCES` is a compatibility name for cumulative established-baseline provenance, not a list of collectors that succeeded only on the current scan. Once a source has produced a reliable value, successful snapshots retain both that value and its source membership across later collector failures; a source that has never succeeded is absent until its first successful observation. This observed-ever distinction prevents recovery from restarting an expiry-threshold episode while still allowing a genuinely new DOMAIN or SSL source to emit its single most urgent applicable reminder.

Current-scan freshness is deliberately separate. `CURRENT_OBSERVED_SOURCES` contains only collectors that succeeded in that scan, while `DOMAIN_LAST_SUCCESS_AT`, `DNS_LAST_SUCCESS_AT`, `SSL_LAST_SUCCESS_AT`, and `WEBSITE_LAST_SUCCESS_AT` retain each source's own last successful collection time. A partial scan updates only successful sources and carries the other source timestamps forward. The detail page therefore labels retained values as stale/last known after a collector failure instead of presenting the snapshot-wide `CHECKED_AT` as their observation time. Legacy snapshots are upgraded with all five freshness fields left `NULL`: their per-source freshness cannot be reconstructed reliably, so operators and the application must not infer it from `CHECKED_AT`.

**Path B — old installation upgrade.** Use this path only after the preflight above:

1. If **both** `WEB_DOMAIN_WATCH` and `WEB_DOMAIN_SNAPSHOT` are absent, run the legacy pair first. If exactly one is present, stop: `alter_domain_watch_snapshot.sql` contains two unguarded `CREATE TABLE` statements, so the partially provisioned schema must be reconciled manually rather than re-running it.

   ```bash
   mysql -u root -p wesitedb < doc/alter_domain_watch_snapshot.sql
   ```

2. Confirm `WEB_DOMAIN_WATCH_NOTIFY_LOG` exists, and that all five notification tables (`WEB_MONITOR_SNAPSHOT`, `WEB_MONITOR_EVENT`, `WEB_USER_NOTIFICATION`, `WEB_NOTIFICATION_DELIVERY_BATCH`, and `WEB_NOTIFICATION_PREFERENCE`) plus `WEB_AUTHENTICATED_ACTIVITY_DAILY`, `WEB_RETENTION_FACT_COLLECTION`, and `WEB_RETENTION_FACT_HEALTH` are absent. Then apply the current retention baseline exactly once:

   ```bash
   mysql -u root -p wesitedb < doc/alter_retention_notification_center.sql
   ```

   If only some retention tables exist, stop and reconcile that partial migration from backup/change records; the baseline uses unguarded `CREATE TABLE` statements and must not be retried blindly.

The Path B order is therefore: legacy watch/snapshot script only when both tables are absent → current retention baseline once. Never re-run a `CREATE TABLE` or `ADD COLUMN` migration against a schema that already contains its objects. Record the applied script name, deploy version, operator, and UTC time in the production change record.

**Legacy e73ff4d upgrade — old notification baseline already present.** If preflight shows the four e73ff4d notification tables (`WEB_MONITOR_SNAPSHOT`, `WEB_MONITOR_EVENT`, `WEB_USER_NOTIFICATION`, and `WEB_NOTIFICATION_PREFERENCE`) but `WEB_NOTIFICATION_DELIVERY_BATCH`, `WEB_AUTHENTICATED_ACTIVITY_DAILY`, `WEB_RETENTION_FACT_COLLECTION`, and `WEB_RETENTION_FACT_HEALTH` are all absent, and the new claim/recipient columns are also absent, do not run the current baseline or the historical incremental scripts. The checked-in e73ff4d schema has no `WEB_DOMAIN_WATCH.NOTIFY_EMAIL`; the same increment also accepts operational installations that independently added that one legacy field. Back up the tables, stop workers, and apply the single complete increment:

```bash
mysql -u root -p wesitedb < doc/alter_retention_notification_center_from_e73ff4d.sql
```

This increment adds every schema change through the current release. On the exact e73ff4d shape it adds an empty watch recipient field and safely converts every old email route to in-app only. On the operational legacy-field shape it validates and preserves watch recipients, freezes them onto still-compatible queued notifications, and cancels ambiguous, malformed, or opted-out routes. It never derives a recipient from `SYS_USER.EMAIL`. Apart from the explicitly supported presence or absence of that one watch field, if the preflight does not match the old baseline, stop and reconcile the partial migration rather than guessing which statements to skip.

`WATCH_CREATED_ON` is a nullable `DATE` because a legacy `CREATE_TIME DATETIME` has no timezone attached. New watches always write it from `WESITE_RETENTION_REPORTING_ZONE`. For old rows, never run `DATE(CREATE_TIME)` or assume the database/JVM timezone. If the wall-clock source zone is known, prefix the selected migration in the same MySQL session with both explicit zones:

```bash
# Example: legacy wall times were UTC and the formal reporting calendar is Shanghai.
{ printf "%s\n" \
    "SET @legacy_watch_source_time_zone='+00:00';" \
    "SET @retention_reporting_time_zone='+08:00';"; \
  cat doc/alter_retention_notification_center_from_e73ff4d.sql; } \
  | mysql -u root -p wesitedb
```

Use the same prefix with `doc/alter_retention_notification_center.sql` on Path A/B when it contains legacy watch rows. For zones with historical daylight-saving changes, use named zones such as `America/New_York` only after loading MySQL timezone tables; a fixed current offset is not a reliable historical conversion. If `@legacy_watch_source_time_zone` is unknown, omit both variables: the migration reports `LEGACY_WATCH_DATES_LEFT_NULL_AND_EXCLUDED`, leaves those rows null, and the retention report excludes their users from both cohorts. After migration, explicitly audit `SELECT COUNT(*) FROM WEB_DOMAIN_WATCH WHERE WATCH_CREATED_ON IS NULL`; do not invent a backfill later from an undocumented timezone.

The rollout verifier exercises the three deployment paths in disposable MySQL 8.4 containers; `LegacyUpgrade` runs both the exact e73ff4d schema and its operational watch-email variant. One fixture proves conservative null exclusion; the other explicitly converts a UTC `2026-08-01 16:30` wall time to Shanghai `2026-08-02`:

```powershell
powershell.exe -NoProfile -File scripts/verify-retention-rollout.ps1 -Fixture PathA
powershell.exe -NoProfile -File scripts/verify-retention-rollout.ps1 -Fixture PathB
powershell.exe -NoProfile -File scripts/verify-retention-rollout.ps1 -Fixture LegacyUpgrade
```

#### Worker schedule and SMTP dependency

The monitoring worker is active only in the `prod` and `mac` Spring profiles. Each delivery worker additionally requires both its explicit rollout property and `wesite.mail.enabled=true`; a missing or false property means the job bean is not registered and cannot claim or consume a queued notification. All cron times are server-local except the digest window keys and rendered event timestamps, which use UTC.

| Worker | Enable property | Spring cron | Behaviour |
| --- | --- | --- | --- |
| Domain monitor | `prod`/`mac` profile | `0 0 3 * * ?` | Runs daily at 03:00; records successful snapshots and publishes deduplicated monitoring events. |
| Immediate delivery | `wesite.notification-delivery.immediate-enabled=true` and `wesite.mail.enabled=true` | `0 */5 * * * ?` | Every five minutes; claims queued immediate notifications in pages of 500. |
| Daily digest | `wesite.notification-delivery.digest-enabled=true` and `wesite.mail.enabled=true` | `0 0 8 * * ?` | Daily at 08:00; one UTC-date batch per user and frozen watch recipient. |
| Weekly digest | `wesite.notification-delivery.digest-enabled=true` and `wesite.mail.enabled=true` | `0 0 8 * * MON` | Mondays at 08:00; one ISO-week batch per user and frozen watch recipient. |
| Digest recovery | Same digest properties | `0 */5 * * * ?` | Every five minutes; terminalizes expired cancellations and retries only already-created durable digest batches. |

Email dispatch requires a configured `spring.mail.host` so that `MailSender` is available. For the bundled Resend SMTP example, set `RESEND_API_KEY`, `WESITE_MAIL_FROM`, and (when needed) `WESITE_MAIL_REPLY_TO`, then configure the host/port/TLS/auth values in the external `application-prod.properties` from the example. `wesite.mail.enabled=false` is a hard registration gate: neither delivery job exists even if its rollout property is true, and a direct disabled-sender call returns failure. Operators should still turn both delivery properties `false` first because they are the explicit phase controls; this keeps the in-app-only phase independent of SMTP configuration and guarantees that no job can claim or mutate queued notifications.

#### Legacy log handling and rollback

The migration preserves historical `WEB_DOMAIN_WATCH_NOTIFY_LOG` rows. Legacy failed rows without a durable batch identity are intentionally archived by setting `STATUS = 2` and `RETRY_COUNT = 3`; they remain audit-only and are never retried. It also converts old queued routes to the new mode/state representation and makes incompatible legacy in-progress states `IN_APP_ONLY`. Export those rows before migration if they must be restored exactly:

```bash
mysqldump -u root -p wesitedb WEB_DOMAIN_WATCH_NOTIFY_LOG WEB_USER_NOTIFICATION > retention-pre-migration.sql
```

There is no destructive automatic down migration. To roll back an application release, stop the new instances/workers, deploy the previous application binary, and leave the additive tables and columns in place. To reverse the legacy-row data updates as well, restore the pre-migration backup during the rollback window; do not guess prior delivery state or replay archived logs. A delivery attempt is at-least-once: a process failure after SMTP accepts a message but before completion is persisted can result in a duplicate email after the lease expires. Changing the global preference to in-app only immediately cancels queued/failed work. A batch whose SMTP attempt is already claimed is the safety boundary: an accepted success is recorded as sent, while failure or lease expiry terminalizes it as cancelled and it is never retried. `CANCELLED` means only that Whose.Domains will not retry; it does not prove that SMTP had not already accepted the message.

#### Phased enablement and production checklist

Use the following controlled rollout, with a rollback checkpoint between phases:

1. Apply and verify the SQL while application workers are stopped. First deploy with both `wesite.notification-delivery.immediate-enabled=false` and `wesite.notification-delivery.digest-enabled=false`; verify read paths before changing either mail setting or delivery flag.
2. Start `prod`/`mac` with both delivery properties still `false`. Run monitoring for one internal watch and verify snapshots, events, in-app notifications, unread counts, links, and user scoping. No delivery job is registered, regardless of `wesite.mail.enabled`, so no queued notification is claimed.
3. Configure SMTP credentials and sender identity with `wesite.mail.enabled=true`. Validate SMTP independently, then set only `wesite.notification-delivery.immediate-enabled=true` and verify one newly created internal immediate notification, delivery batch, and audit log.
4. After immediate delivery is stable, set `wesite.notification-delivery.digest-enabled=true`. Verify newly created internal daily and weekly fixtures and confirm immediate-routed items are excluded from each digest.
5. Expand to production traffic while monitoring failed attempts, expired claims, queue age, and duplicate-email reports; retain the backup until the first full digest cycle completes.

Before each phase, verify the application health check, migration record, profile, server clock/time zone, SMTP credentials, sender-domain authorization, and an authenticated watchlist/notification-center round trip. After enabling delivery, verify one `WEB_NOTIFICATION_DELIVERY_BATCH` row and its corresponding audit log for each mode, plus that unread counts and notification links remain user-scoped.

Use the dependency-free liveness endpoint for process supervisors and watchdogs:

```bash
curl --fail --silent --show-error http://127.0.0.1:8080/api/healthz
```

It returns `{"status":"UP"}` when the web process can serve requests. It deliberately does not check MySQL or external services, so a downstream outage does not create a restart loop. Because `/api/` bypasses canonical redirects, the watchdog does not need to send a public `Host` header.

#### Retention measurement (7-day and 30-day)

Use GA4 only with the privacy-safe custom events `watch_created`, `watchlist_return_visit`, `notification_opened`, `notification_action_clicked`, `notification_preferences_saved`, and `domain_detail_cta_clicked`. The client sends only allowlisted `type`, `category`, `risk`, and UI `source` values. It never sends a domain, email, user ID, event ID, notification text, or any free-form UI value; missing `gtag` is a no-op. `notification_opened` means the in-app center loaded successfully; email-open tracking is not implemented and must not be inferred from it.

The account-level source of record is the local MySQL aggregate report, not GA4. Run it with a database account allowed to create temporary tables; it selects only daily/overall aggregates and never emits a user ID.

`WESITE_RETENTION_REPORTING_ZONE` is the only formal reporting-calendar configuration. The application creates one named reporting `Clock` from that IANA `ZoneId`; new-watch `WATCH_CREATED_ON`, authenticated-activity facts, and fact-failure dates all consume that same clock. This remains correct across UTC day boundaries and for non-`+08:00` zones. Every SQL caller must derive the matching MySQL session offset from that same ZoneId: `Asia/Shanghai` maps to `+08:00`. For a DST ZoneId, calculate the offset effective for the invocation and record that mapping in the change/run log; the SQL offset is derived session state, not a second configuration. Never use the host, JVM, container, or database default as a substitute.

The JDBC instant contract is separate from the reporting calendar. Both production property examples set Connector/J `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`, so timezone-less operational `DATETIME` reads/writes do not depend on a host or database default. Any `WD_DB_URL` override must preserve those options (or an operationally equivalent explicit UTC contract). Cohort dates never come from those timestamps: they use persisted `DATE` facts.

Initialize the report in one session by either setting `@reporting_time_zone` before the script or using the MySQL connection init command:

```bash
# Caller variable; the prefixed SET and report execute in the same session.
{ printf "%s\n" "SET @reporting_time_zone='+08:00';"; cat scripts/retention-report.sql; } \
  | mysql -u retention_reporter -p wesitedb

# Equivalent connection initialization; the report consumes @@session.time_zone.
mysql -u retention_reporter -p \
  --init-command="SET time_zone='+08:00'" \
  wesitedb < scripts/retention-report.sql
```

Reconciliation uses the same derived offset. The init command runs before the here-document, so `CURDATE()` and any date expression in the reconciliation inputs are evaluated only after the reporting session zone is active:

```bash
mysql -u retention_operator -p \
  --init-command="SET time_zone='+08:00'" wesitedb <<'SQL'
SET @reporting_time_zone='+08:00';
SET @verified_fact_date=DATE_SUB(CURDATE(), INTERVAL 1 DAY);
SET @external_expected_rows=1234;
SET @reconciliation_source='auth-gateway';
SET @reconciliation_id='auth-gateway-2026-08-08-v1';
SOURCE scripts/verify-retention-fact-day.sql;
SQL
```

The operator rejects `@verified_fact_date >= CURDATE()` in that reporting session, so only a fully closed date can be verified. A verified row is reportable only while its actual distinct fact count equals both `EXPECTED_FACT_ROWS` and `EXTERNAL_EXPECTED_ROWS`, its status is `VERIFIED`, and `VERIFIED_AT`, `RECONCILIATION_SOURCE`, and `RECONCILIATION_ID` remain non-empty. A later newly inserted fact atomically reopens that day and clears its audit fields; the report then returns `INSUFFICIENT_HISTORY` until independent reconciliation runs again.

The script uses these persisted fields, deduplicated by `(USER_ID, activity_date)` in a temporary table:

| Purpose | Persisted source |
| --- | --- |
| Monitored cohort | `WEB_DOMAIN_WATCH.WATCH_CREATED_ON`; cohort date is each user's first reliable reporting-calendar watch date, including a watch later soft-deleted. Any user with a legacy null watch date is conservatively excluded. |
| Authenticated activity | `WEB_AUTHENTICATED_ACTIVITY_DAILY.ACTIVITY_DATE`; the interceptor writes at most one minimal `(USER_ID, ACTIVITY_DATE)` fact per authenticated user/day and stores no path, domain, email, or request payload. Keep these facts for at least 120 days. |
| Observation boundary | Migration leaves `WEB_RETENTION_FACT_COLLECTION.COLLECTION_STARTED_ON` null. The writer records only non-user-level observed counts/heartbeats in `WEB_RETENTION_FACT_HEALTH`; rows remain `OPEN` and cannot prove their own completeness. Export the daily distinct-authenticated-user count from an independent source such as the authentication gateway, then set `@reporting_time_zone`, `@verified_fact_date`, `@external_expected_rows`, `@reconciliation_source`, and a unique `@reconciliation_id` before sourcing `scripts/verify-retention-fact-day.sql`. Never derive the external expected count from this application database. Reports require 120 continuous `VERIFIED` days. Java uses `WESITE_RETENTION_REPORTING_ZONE` (default `Asia/Shanghai`); SQL callers must map that ZoneId to the matching offset (`+08:00` for Shanghai). Fixed offsets are unsuitable for DST zones, so deployments using one must supply the date-appropriate offset. |

The monitored cohort contains every user whose first reliable watch `DATE` falls on the cohort day. The observed non-monitored cohort contains users on their first authenticated-activity fact inside the continuous verified collection window who have no reliable watch through the following 30 days. It is deliberately named an observed cohort: it does not claim that the date is account creation or the user's lifetime first visit, and it never reads `SYS_USER.CREATE_TIME`. Activity before the verified window is ignored when selecting the first observed date. A user with any null legacy watch date is excluded from both sides because historical monitored status is ambiguous. This also prevents a control user who soon becomes monitored from contaminating the 30-day comparison. Both cohorts must begin inside the continuous verified fact window and close at least 30 days in the configured reporting offset before execution, so their 7-day and 30-day outcomes are complete. A return is any later persisted activity above on days 1–7 or 1–30, counted at most once per user per window before the script aggregates it.

```text
7-day return rate  = returning cohort users on days 1-7  / eligible cohort users
30-day return rate = returning cohort users on days 1-30 / eligible cohort users
lift                = monitored return rate - comparison return rate
```

The first result set is a single readiness row, the second is a daily closed-cohort trend, and the third is the 90-day aggregate comparison. A 90-day window of cohorts whose 30-day outcomes are closed requires at least 120 days of retained facts. Until `DATEDIFF(CURDATE(), COLLECTION_STARTED_ON)` reaches the configured minimum, the readiness row is `INSUFFICIENT_HISTORY` and both cohort result sets are intentionally empty. Missing metadata similarly returns `MISSING_COLLECTION_METADATA`; neither state may be presented as zero retention. Once ready, both cohort result sets suppress any emitted cohort with fewer than five users (`@minimum_cohort_size = 5`) so the report never exposes tiny groups. `cohort_users` is the denominator, `returned_users_7d`/`returned_users_30d` are unique returning users, and `return_rate_*_pct` is their percentage. Compute monitored-minus-control lift from the two aggregate rows. GA4 may still show an anonymous privacy-safe funnel trend, but it must not be used for an account-level cohort comparison because no user ID is sent. This is an observational comparison, not a causal claim; repeat it weekly and inspect both absolute lift and confidence intervals before changing notification policy.

## Blog editorial workflow deployment

Blog attribution: new AI drafts store `Whose.Domains` as their author. Public
bylines and JSON-LD share the same attribution rules: blank authors, the site
name, and the legacy generator's random pen names (`James Chen`, `Mark Zhang`)
display as `Whose.Domains` with schema type `Organization`. Other named authors
remain `Person`. The legacy mapping is a read-time compatibility rule; it does
not rewrite stored authors or require a database migration.

Articles with persisted `AI_GENERATED=1`, `CREATE_BY=ai`, or a legacy generator
pen name show an AI-assisted disclosure. Apply `doc/alter_blog_ai_provenance.sql`
before deploying: it backfills known AI origins so future byline edits cannot
erase the disclosure. A blank or organization byline alone is not evidence of AI use.
Only enter a personal byline after that person has substantially edited and
verified the article. No reviewer attribution is inferred automatically.

Treat the editorial schema migration and one-time HTML sanitization as a
coordinated maintenance operation outside both routine Jenkins jobs. Keep
normal writers stopped from the backup through the apply step and retain every
command output with the maintenance record. Database migration, sanitization,
and service coordination must never be added as side effects of either
application Pipeline.

Follow this order exactly:

1. **Back up WEB_BLOG_POST before changing the schema or content.** Record the deployed commit, operator, UTC time, database name, and backup checksum.

   ```bash
   mysqldump --single-transaction -u root -p wesitedb WEB_BLOG_POST \
     > web-blog-post-before-editorial-20260902T130000Z.sql
   ```

2. Apply the idempotent editorial timestamp migration, then confirm the nullable column exists. The migration initializes published legacy posts from `PUBLISH_DATE`; it does not make the column mandatory.

   ```bash
   mysql -u root -p wesitedb < doc/alter_blog_editorial_workflow.sql
   mysql -u root -p wesitedb -e "SHOW COLUMNS FROM WEB_BLOG_POST LIKE 'CONTENT_UPDATED_AT';"
   ```

3. Build the release and run the new admin JAR in non-web, read-only dry-run mode. It scans with keyset pagination, reports the IDs and slugs whose stored HTML would change, performs no updates, and exits after logging one JSON report.

   ```bash
   set -o pipefail
   mvn clean package -DskipTests -P prod

   java -jar wesite-admin/target/wesite-admin-1.0.0.jar \
     --spring.profiles.active=prod,blog-sanitize \
     --spring.main.web-application-type=none \
     --wesite.blog.sanitization.mode=dry-run \
     --wesite.blog.sanitization.batch-size=100 \
     2>&1 | tee blog-sanitize-dry-run.log
   ```

4. **Inspect the dry-run report before permitting writes.** Confirm the scanned count against the live, non-deleted post count; inspect every reported ID/slug or an explicitly recorded sample when the list is large; and stop if a legitimate element would be removed. Keep the report with the database backup because it defines the affected-row rollback scope.

5. Run the same artifact in apply mode. Each changed batch is one independent transaction, every row must update exactly once, and one timestamp is shared within a batch. A non-zero exit or missing completion report is a failed maintenance run: stop, investigate, and do not deploy. A successful repeat must report zero changed posts.

   ```bash
   set -o pipefail
   java -jar wesite-admin/target/wesite-admin-1.0.0.jar \
     --spring.profiles.active=prod,blog-sanitize \
     --spring.main.web-application-type=none \
     --wesite.blog.sanitization.mode=apply \
     --wesite.blog.sanitization.batch-size=100 \
     2>&1 | tee blog-sanitize-apply.log

   java -jar wesite-admin/target/wesite-admin-1.0.0.jar \
     --spring.profiles.active=prod,blog-sanitize \
     --spring.main.web-application-type=none \
     --wesite.blog.sanitization.mode=dry-run \
     --wesite.blog.sanitization.batch-size=100
   ```

6. **Release both compatibility-matched binaries through separate application deployments.** Deploy and check Admin independently, then deploy and check Web independently, without the `blog-sanitize` profile or any `wesite.blog.sanitization.mode` property. Confirm both processes remain healthy before restoring traffic and editorial writes. This maintenance gate does not turn the two routine Jenkins jobs into a paired deployment.

7. Complete these release checks:

   - Unauthenticated admin editorial API calls are denied by default, a non-admin account is denied, and an administrator can list and edit drafts.
   - Saving, previewing, publishing, and unpublishing work; a published slug is locked; the preview iframe sandbox has no script or same-origin capability; and repeated publish does not replace the first publication date.
   - A post containing a representative safe table, link, code block, and previously unsafe markup renders correctly. Inspect the public HTML and JSON-LD, parse the JSON-LD as JSON, and confirm neither output can execute stored script or event-handler markup.
   - The blog sitemap contains only published posts, uses `CONTENT_UPDATED_AT` with `PUBLISH_DATE` fallback for `lastmod`, and the existing public `/blog/*` and `/domain/*` URLs are unchanged.
   - Run the production P0 smoke and SEO contract commands documented above.

### Editorial rollback

For a binary rollback, a root operator invokes
`rollback-wesite-app admin` and `rollback-wesite-app web` separately and checks
each application after its command returns. **Leave the nullable column in
place.** The additive `CONTENT_UPDATED_AT` column is safe for the previous
binary to ignore; dropping it during an incident adds unnecessary risk.

A binary rollback does not undo sanitized article content. For a content rollback, stop editorial writers and use the saved dry-run/apply report to select only affected IDs. From the pre-change backup, **restore CONTENT and the original CONTENT_UPDATED_AT** for those rows, or restore `NULL` when the column did not exist before this release. Do not restore the entire table over unrelated post edits. Validate the affected rows and public pages before restarting writers, and retain the backup until the rollback window closes.

## Network probe safety and capacity

Ping and Port Checker connect only to public IP addresses approved by the target policy. Mixed DNS answers and redirects to unsafe targets fail closed. Each request has one 15-second total time budget; port checks accept 1–20 ports, each in the range `1..65535`, and run with at most 8 worker threads plus a 32-task queue. Rate limiting remains per application instance until a separate distributed-limiter change is made.

## Contributing

Issues and PRs are welcome. Before submitting:
- Make sure the project builds locally (`mvn clean install`)
- Follow the existing code style
- Never commit configuration files containing real credentials

## License

[MIT](LICENSE) © 2026 Yuanzhi Xu
