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
cp wesite-web/src/main/resources/application-prod.properties.example \
   wesite-web/src/main/resources/application-prod.properties
cp wesite-admin/src/main/resources/application-prod.properties.example \
   wesite-admin/src/main/resources/application-prod.properties
```

You can also override settings via environment variables without touching the config files:

| Variable | Description |
| --- | --- |
| `WD_DB_URL` | MySQL JDBC URL |
| `WD_DB_USERNAME` | Database username |
| `WD_DB_PASSWORD` | Database password |
| `REDIS_PASSWORD` | Redis password (may be empty) |
| `JWT_SECRET` | JWT signing secret |
| `DEEPSEEK_API_KEY` | DeepSeek API key (required for AI features) |

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

`application-prod.properties` is gitignored and is **not** bundled into the jar from a clean checkout, so supply it externally at runtime. Spring Boot automatically loads `application-prod.properties` from a `config/` directory (or the current directory) next to the jar, which overrides anything on the classpath:

```bash
mvn clean package -DskipTests -P prod

# Provide the prod config outside the jar (fill in real values, or leave ${ENV_VAR} refs)
mkdir -p config
cp wesite-web/src/main/resources/application-prod.properties.example config/application-prod.properties
# edit config/application-prod.properties as needed

# Secrets are best supplied as environment variables (see the table above)
export WD_DB_PASSWORD=... JWT_SECRET=... REDIS_PASSWORD=... BLOG_INTERNAL_SECRET=... DEEPSEEK_API_KEY=...

java -jar wesite-web/target/wesite-web-1.0.0.jar --spring.profiles.active=prod
```

Alternatively point Spring at any path with `--spring.config.additional-location=file:/etc/whosedomains/`. Never place real secrets in a tracked file — keep them in environment variables or the external, gitignored `application-prod.properties`.

### Post-deployment SEO contract

Run `pwsh -File scripts/check-seo.ps1 -BaseUrl https://whose.domains` after each production deployment. This is a production-only contract: `-BaseUrl` must normalize to the exact origin `https://whose.domains` (an optional trailing slash is accepted), and other hosts, schemes, non-default ports, paths, queries, or fragments are rejected before any HTTP request.

### Retention notification center operations

The watchlist, monitoring-event, in-app notification, and email-delivery pipeline is an additive production migration. Take a schema and data backup before starting, stop application instances that run scheduled workers, and apply the scripts with the same MySQL user that owns the application tables.

#### SQL preflight and mutually exclusive migration paths

Run this read-only preflight first. It lists the exact tables and retention columns that decide the path; it returns metadata only, never application data.

```bash
mysql -u root -p wesitedb -e "
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('WEB_DOMAIN_WATCH','WEB_DOMAIN_SNAPSHOT','WEB_DOMAIN_WATCH_NOTIFY_LOG','WEB_MONITOR_SNAPSHOT','WEB_MONITOR_EVENT','WEB_USER_NOTIFICATION','WEB_NOTIFICATION_DELIVERY_BATCH','WEB_USER_QUERY_HISTORY','WEB_API_USAGE_DAILY')
ORDER BY TABLE_NAME;
SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'WEB_MONITOR_SNAPSHOT' AND COLUMN_NAME IN ('SCHEMA_VERSION','OBSERVED_SOURCES'))
    OR (TABLE_NAME = 'WEB_MONITOR_EVENT' AND COLUMN_NAME IN ('RISK','SOURCE')))
ORDER BY TABLE_NAME, COLUMN_NAME;"
```

**Path A — current clean installation.** `doc/create.sql` already creates `WEB_DOMAIN_WATCH`, `WEB_DOMAIN_WATCH_NOTIFY_LOG`, `WEB_USER_QUERY_HISTORY`, and `WEB_API_USAGE_DAILY`. After the normal bootstrap command `mysql -u root -p wesitedb < doc/create.sql`, run **only**:

```bash
mysql -u root -p wesitedb < doc/alter_retention_notification_center.sql
```

Do **not** run `doc/alter_domain_watch_snapshot.sql` on Path A: its unguarded `CREATE TABLE WEB_DOMAIN_WATCH` duplicates an object that `create.sql` already created. The current retention script already creates `WEB_MONITOR_SNAPSHOT` with `SCHEMA_VERSION` and `OBSERVED_SOURCES`, and `WEB_MONITOR_EVENT` with `RISK` and `SOURCE`; do **not** run either later `ADD COLUMN` script after Path A.

**Path B — old installation upgrade.** Use this path only after the preflight above:

1. If **both** `WEB_DOMAIN_WATCH` and `WEB_DOMAIN_SNAPSHOT` are absent, run the legacy pair first. If exactly one is present, stop: `alter_domain_watch_snapshot.sql` contains two unguarded `CREATE TABLE` statements, so the partially provisioned schema must be reconciled manually rather than re-running it.

   ```bash
   mysql -u root -p wesitedb < doc/alter_domain_watch_snapshot.sql
   ```

2. Confirm `WEB_DOMAIN_WATCH_NOTIFY_LOG` exists, and that all four retention tables (`WEB_MONITOR_SNAPSHOT`, `WEB_MONITOR_EVENT`, `WEB_USER_NOTIFICATION`, and `WEB_NOTIFICATION_DELIVERY_BATCH`) are absent. Then apply the current retention baseline exactly once:

   ```bash
   mysql -u root -p wesitedb < doc/alter_retention_notification_center.sql
   ```

   If only some retention tables exist, stop and reconcile that partial migration from backup/change records; the baseline uses unguarded `CREATE TABLE` statements and must not be retried blindly.

3. If the retention baseline was applied by an **older release** and preflight confirms the two columns in each group are absent, apply the subsequent incremental migrations exactly once and only in this order:

```bash
mysql -u root -p wesitedb < doc/alter_monitor_snapshot_observation_sources.sql
mysql -u root -p wesitedb < doc/alter_monitor_event_canonical_risk.sql
```

The retention order is therefore: legacy watch/snapshot script only when both tables are absent → retention baseline once → snapshot-provenance increment only when both columns are absent → event-risk increment only when both columns are absent. Verify `WEB_USER_QUERY_HISTORY` and `WEB_API_USAGE_DAILY` before running the retention report; current `create.sql` provides both. Never re-run a `CREATE TABLE` or `ADD COLUMN` migration against a schema that already contains its objects. Record the applied script name, deploy version, operator, and UTC time in the production change record.

#### Worker schedule and SMTP dependency

The monitoring and delivery workers are active only in the `prod` and `mac` Spring profiles. All cron times are server-local except the digest window keys and rendered event timestamps, which use UTC.

| Worker | Spring cron | Behaviour |
| --- | --- | --- |
| Domain monitor | `0 0 3 * * ?` | Runs daily at 03:00; records successful snapshots and publishes deduplicated monitoring events. |
| Immediate delivery | `0 */5 * * * ?` | Every five minutes; claims queued immediate notifications in pages of 500. |
| Daily digest | `0 0 8 * * ?` | Daily at 08:00; one UTC-date batch per user. |
| Weekly digest | `0 0 8 * * MON` | Mondays at 08:00; one ISO-week batch per user. |

Email dispatch requires a configured `spring.mail.host` so that `MailSender` is available. For the bundled Resend SMTP example, set `RESEND_API_KEY`, `WESITE_MAIL_FROM`, and (when needed) `WESITE_MAIL_REPLY_TO`, then configure the host/port/TLS/auth values in the external `application-prod.properties` from the example. Keep `wesite.mail.enabled=false` for an in-app-only dry run; it records a successful no-send result, so do not use that mode to validate SMTP delivery. If no mail sender is configured, the scheduled delivery task leaves queued notifications untouched while in-app notifications remain available.

#### Legacy log handling and rollback

The migration preserves historical `WEB_DOMAIN_WATCH_NOTIFY_LOG` rows. Legacy failed rows without a durable batch identity are intentionally archived by setting `STATUS = 2` and `RETRY_COUNT = 3`; they remain audit-only and are never retried. It also converts old queued routes to the new mode/state representation and makes incompatible legacy in-progress states `IN_APP_ONLY`. Export those rows before migration if they must be restored exactly:

```bash
mysqldump -u root -p wesitedb WEB_DOMAIN_WATCH_NOTIFY_LOG WEB_USER_NOTIFICATION > retention-pre-migration.sql
```

There is no destructive automatic down migration. To roll back an application release, stop the new instances/workers, deploy the previous application binary, and leave the additive tables and columns in place. To reverse the legacy-row data updates as well, restore the pre-migration backup during the rollback window; do not guess prior delivery state or replay archived logs. A delivery attempt is at-least-once: a process failure after SMTP accepts a message but before completion is persisted can result in a duplicate email after the lease expires.

#### Phased enablement and production checklist

Use the following controlled rollout, with a rollback checkpoint between phases:

1. Apply and verify the SQL while application workers are stopped; deploy the code in a profile other than `prod`/`mac` to validate read paths only.
2. Start `prod`/`mac` with `wesite.mail.enabled=false`; verify one monitoring run creates snapshots, events, and in-app notifications without sending mail.
3. Configure SMTP credentials and sender identity, enable mail, and validate one internal recipient for immediate, daily, and weekly modes.
4. Expand to production traffic while monitoring failed attempts, expired claims, queue age, and duplicate-email reports; retain the backup until the first full digest cycle completes.

Before each phase, verify the application health check, migration record, profile, server clock/time zone, SMTP credentials, sender-domain authorization, and an authenticated watchlist/notification-center round trip. After enabling delivery, verify one `WEB_NOTIFICATION_DELIVERY_BATCH` row and its corresponding audit log for each mode, plus that unread counts and notification links remain user-scoped.

#### Retention measurement (7-day and 30-day)

Use GA4 only with the privacy-safe custom events `watch_created`, `watchlist_return_visit`, `notification_opened`, `notification_action_clicked`, and `notification_preferences_saved`. The client sends only allowlisted `type`, `category`, `risk`, and UI `source` values after successful API responses. It never sends a domain, email, user ID, event ID, notification text, or any free-form UI value; missing `gtag` is a no-op.

The account-level source of record is the local MySQL aggregate report, not GA4. Run it with a database account allowed to create temporary tables; it selects only daily/overall aggregates and never emits a user ID:

```bash
mysql -u retention_reporter -p wesitedb < scripts/retention-report.sql
```

The script uses these persisted fields, deduplicated by `(USER_ID, activity_date)` in a temporary table:

| Purpose | Persisted source |
| --- | --- |
| Monitored cohort | `WEB_DOMAIN_WATCH.CREATE_TIME`; cohort date is each user's first watch creation date, including a watch later soft-deleted. |
| Authenticated activity | `WEB_USER_QUERY_HISTORY.CREATE_TIME` where `USER_ID` is present; the recorder writes only for authenticated users. |
| Notification interaction | `WEB_USER_NOTIFICATION.READ_AT`; this is a durable successful read action. A notification-link click is not currently persisted and is intentionally not inferred. |
| API activity | `WEB_API_USAGE_DAILY.USAGE_DATE` where `REQUEST_COUNT > 0`. |

The monitored cohort contains every user whose first watch was created on the cohort day. The non-monitored control contains users on their first observed authenticated-activity day who do not create a watch through the following 30 days. This prevents a control user who soon becomes monitored from contaminating the 30-day comparison. Both cohorts are closed at least 30 UTC days before execution, so their 7-day and 30-day outcomes are complete. A return is any later persisted activity above on days 1–7 or 1–30, counted at most once per user per window before the script aggregates it.

```text
7-day return rate  = returning cohort users on days 1-7  / eligible cohort users
30-day return rate = returning cohort users on days 1-30 / eligible cohort users
lift                = monitored return rate - comparison return rate
```

The first result set is a daily closed-cohort trend; the second is the 90-day aggregate comparison. `cohort_users` is the denominator, `returned_users_7d`/`returned_users_30d` are unique returning users, and `return_rate_*_pct` is their percentage. Compute monitored-minus-control lift from the two aggregate rows. GA4 may still show an anonymous privacy-safe funnel trend, but it must not be used for an account-level cohort comparison because no user ID is sent. This is an observational comparison, not a causal claim; repeat it weekly and inspect both absolute lift and confidence intervals before changing notification policy.

## Contributing

Issues and PRs are welcome. Before submitting:
- Make sure the project builds locally (`mvn clean install`)
- Follow the existing code style
- Never commit configuration files containing real credentials

## License

[MIT](LICENSE) © 2026 Yuanzhi Xu
