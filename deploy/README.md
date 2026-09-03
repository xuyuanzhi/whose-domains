# Whose.Domains systemd deployment

This runbook targets the current production shape:

- Ubuntu/Debian-style Linux with `systemd`
- Java 17 available as `/usr/bin/java`
- Nginx and Redis on the application host
- MySQL on a remote host
- public web bound to `127.0.0.1:8080`
- admin bound to `127.0.0.1:8082`

The scripts never build on the production host and never run old and new application JVMs at the same time. A release contains both JARs from one commit. The `current` symlink is switched atomically, admin is restarted and checked first, then web is restarted and checked. A failed check restores the previous symlink automatically.

## Why systemd

`systemd` owns the process PID, starts services after reboot, restarts unexpected exits, rate-limits crash loops, sends `SIGTERM` for Spring Boot shutdown, applies a non-root identity and filesystem restrictions, and puts logs in the journal. A `nohup`/PID/watchdog combination has multiple independent owners and can leave duplicate processes or a long `127.0.0.1:8080` outage after the watchdog itself fails.

The service units use `Restart=on-failure`, not `always`: an operator-requested stop stays stopped. `-XX:+ExitOnOutOfMemoryError` converts an unrecoverable heap OOM into a process exit that systemd can restart. A separate systemd timer checks MySQL/Redis readiness once per minute and restarts only a service that fails three consecutive checks; it uses the same lock as the release command and therefore never interferes with a deployment. The unit command line forces both applications to `127.0.0.1` on their fixed ports even when an older preserved properties file omits or overrides those settings. The installer deliberately does not enable or start the applications or timer; boot activation happens only after migration and production verification.

## Memory budget

The host has 3.5 GiB RAM, Redis is local, and MySQL is remote. The checked-in units use these conservative limits:

| Process | Heap | Metaspace | Direct memory | Practical total target |
| --- | ---: | ---: | ---: | ---: |
| `wesite-web` | 256–768 MiB | 192 MiB | 128 MiB | about 1.1–1.3 GiB |
| `wesite-admin` | 128–384 MiB | 160 MiB | 64 MiB | about 0.6–0.8 GiB |

The remainder is reserved for Redis, Nginx, JVM thread stacks, the kernel, and filesystem cache. A 2 GiB swap file is an emergency buffer, not normal working memory. Do not set Redis `maxmemory` until `redis-cli INFO memory` and the eviction/persistence requirements have been reviewed.

## 1. Preflight

Run these read-only checks first:

```bash
java -version
command -v java
test -x /usr/bin/java
command -v flock
systemctl is-active nginx redis-server
free -h
df -h /
redis-cli INFO memory | grep -E '^(used_memory_human|maxmemory_human|maxmemory_policy):'
pgrep -af 'java|watchdog|wesite'
systemctl list-units --type=service | grep -Ei 'wesite|watchdog'
systemctl list-timers --all | grep -Ei 'wesite|watchdog'
crontab -l
grep -R "watchdog" /etc/cron.d /etc/cron.daily /etc/systemd/system /usr/java 2>/dev/null
```

Before installing the new services, disable the existing Whose.Domains watchdog at its actual source (cron, timer, or service) and stop the old Java launcher. Do not use a broad `pkill -f java`; identify the exact PID or old unit first. Leaving two supervisors active can create duplicate JVMs and port conflicts.

If `command -v java` is not `/usr/bin/java`, either create the normal alternatives-managed `/usr/bin/java` entry or update both checked-in service units before installation.

## 2. Build and upload one release

Build in Jenkins from one commit; do not compile on the 3.5 GiB production host:

```bash
mvn clean test
mvn clean package -DskipTests
sha256sum wesite-web/target/wesite-web-1.0.0.jar \
  wesite-admin/target/wesite-admin-1.0.0.jar
```

Upload both JARs and a checkout/export of this entire repository at the same commit to a temporary server directory. The installer also reads the web and admin production-property templates, so copying only `deploy/` and `scripts/server/` is insufficient. Record the commit and checksums in the release record.

## 3. Add swap and install systemd assets

From the uploaded repository checkout:

```bash
sudo bash scripts/server/ensure-wesite-swap.sh
free -h
swapon --show

sudo bash scripts/server/install-wesite-systemd.sh
sudo systemd-analyze verify \
  /etc/systemd/system/wesite-admin.service \
  /etc/systemd/system/wesite-web.service \
  /etc/systemd/system/wesite-health-monitor.service \
  /etc/systemd/system/wesite-health-monitor.timer
```

The swap script refuses to overwrite an existing inactive `/swapfile` and writes the `/etc/fstab` entry at most once. The installer is also repeatable: it refreshes public service/script templates but preserves the contents of the three live configuration files. On every run it tightens the live environment file to mode `0600` and both live properties files to `0640`. It reloads systemd but does not enable or start any Whose.Domains unit.

## 4. Configure secrets and application properties

Edit these files:

```bash
sudoedit /etc/wesite/wesite.env
sudoedit /usr/java/config/web/application-prod.properties
sudoedit /usr/java/config/admin/application-prod.properties
```

Required changes:

- replace every `CHANGE_ME` value in `/etc/wesite/wesite.env`
- reuse the existing tested remote-MySQL JDBC URL, then set its username and password; enable certificate-verified TLS only when the database endpoint and CA configuration support it
- set the Redis password, or leave `REDIS_PASSWORD=` empty when Redis has no password
- set one long random `JWT_SECRET`; both applications map it to the same `app.jwt.secret`
- set a separate long random internal blog secret
- set both MaxMind paths to the actual `.mmdb` files under `/usr/java/data` or their existing location
- keep notification delivery flags `false` for the first smoke pass
- keep `server.address=127.0.0.1`; only Nginx should expose the services

Do not `source /etc/wesite/wesite.env` in a shell. It uses systemd `EnvironmentFile` syntax and the JDBC URL contains `&`. Validate permissions and placeholders:

```bash
sudo chown root:wesite /etc/wesite/wesite.env \
  /usr/java/config/web/application-prod.properties \
  /usr/java/config/admin/application-prod.properties
sudo chmod 600 /etc/wesite/wesite.env
sudo chmod 640 /usr/java/config/web/application-prod.properties \
  /usr/java/config/admin/application-prod.properties

if sudo grep -R -n 'CHANGE_ME\|your-db-\|/path/to/' \
    /etc/wesite/wesite.env /usr/java/config/web /usr/java/config/admin; then
  echo 'Stop: production placeholders remain.' >&2
  exit 1
fi
```

## 5. Establish the first rollback baseline

The automatic rollback needs an existing `/usr/java/current` release. Before the first systemd-managed deployment, preserve the currently deployed web and admin JARs as a baseline. First stop the old watchdog/launcher so it cannot compete for ports, then locate the exact current artifacts:

```bash
find /usr/java -maxdepth 3 -type f -name 'wesite-*.jar' -print
```

After verifying the two paths and checksums, install that known-good pair. Use the homepage as the web check only if the old binary predates `/api/healthz`:

```bash
BASELINE_VERSION="pre-systemd-$(date -u +%Y%m%dT%H%M%SZ)"
sudo env WESITE_WEB_HEALTH_URL=http://127.0.0.1:8080/ \
  WESITE_ADMIN_HEALTH_URL=http://127.0.0.1:8082/ \
  WESITE_HEALTH_RESPONSE_MODE=legacy-http-200 \
  deploy-wesite-release \
  "$BASELINE_VERSION" \
  /exact/path/to/current-wesite-web.jar \
  /exact/path/to/current-wesite-admin.jar
sudo env WESITE_WEB_HEALTH_URL=http://127.0.0.1:8080/ \
  WESITE_ADMIN_HEALTH_URL=http://127.0.0.1:8082/ \
  WESITE_HEALTH_RESPONSE_MODE=legacy-http-200 \
  check-wesite-services
```

Do not guess the JAR paths. If no verified baseline artifacts exist, take a filesystem/configuration backup and explicitly record that the first new deployment has no automatic binary rollback target.

Disable and stop the baseline services before the database backup and maintenance window. Also stop the timer and any monitor invocation so a reboot or in-flight check cannot restart normal writers:

```bash
sudo systemctl disable --now \
  wesite-health-monitor.timer wesite-web.service wesite-admin.service
sudo systemctl stop wesite-health-monitor.service
systemctl is-enabled wesite-health-monitor.timer wesite-web.service wesite-admin.service
systemctl is-active wesite-health-monitor.timer wesite-health-monitor.service \
  wesite-web.service wesite-admin.service
```

## 6. Back up and migrate the blog table

Keep both normal application writers stopped. Use the remote database hostname and an account with the required schema permissions:

```bash
mysqldump --single-transaction -h MYSQL_HOST -u DB_USER -p wesitedb WEB_BLOG_POST \
  > web-blog-post-before-editorial-$(date -u +%Y%m%dT%H%M%SZ).sql
sha256sum web-blog-post-before-editorial-*.sql

mysql -h MYSQL_HOST -u DB_USER -p wesitedb \
  < doc/alter_blog_editorial_workflow.sql
mysql -h MYSQL_HOST -u DB_USER -p wesitedb \
  -e "SHOW COLUMNS FROM WEB_BLOG_POST LIKE 'CONTENT_UPDATED_AT';"
```

Follow the complete editorial migration and rollback contract in the repository root `README.md` before normal deployment.

## 7. Run the one-time sanitization

Make the uploaded admin JAR readable by the `wesite` user. `systemd-run` safely loads the systemd environment file without evaluating it as shell code:

```bash
sudo install -o root -g wesite -m 0440 \
  /tmp/wesite-admin-1.0.0.jar /usr/java/wesite-admin-maintenance.jar

set -o pipefail
sudo systemd-run --quiet --wait --collect --pipe \
  --unit="wesite-blog-sanitize-dry-$(date +%s)" \
  --property=User=wesite --property=Group=wesite \
  --property=EnvironmentFile=/etc/wesite/wesite.env \
  --property=WorkingDirectory=/usr/java \
  /usr/bin/java -Xms128m -Xmx384m \
  -jar /usr/java/wesite-admin-maintenance.jar \
  --spring.profiles.active=prod,blog-sanitize \
  --spring.main.web-application-type=none \
  --spring.config.additional-location=file:/usr/java/config/admin/ \
  --wesite.blog.sanitization.mode=dry-run \
  --wesite.blog.sanitization.batch-size=100 \
  2>&1 | sudo tee /usr/java/logs/blog-sanitize-dry-run.log
```

Inspect every reported change or a recorded sample for a large result. Then repeat the command with a new unit name, `mode=apply`, and `blog-sanitize-apply.log`. Run dry-run once more; it must report zero changed posts. A non-zero exit or missing completion report stops the release.

## 8. Deploy both applications

Use the newly built Git commit as the immutable version. The release directory must not already exist:

```bash
RELEASE_VERSION='<new-git-commit>'
sudo deploy-wesite-release \
  "$RELEASE_VERSION" \
  /tmp/wesite-web-1.0.0.jar \
  /tmp/wesite-admin-1.0.0.jar
```

The command performs this sequence:

1. copy both JARs to `/usr/java/releases/<version>/`
2. atomically switch `/usr/java/current`
3. restart and check `wesite-admin.service`
4. restart and check `wesite-web.service`
5. set `/usr/java/previous` only after both checks pass
6. restore the old `current` target and restart both services if either check fails

The command holds `/run/lock/wesite-deploy.lock` for the complete transaction. A concurrent release is rejected before it creates a release directory, and HUP/INT/TERM or another unsuccessful exit after switching `current` triggers the same rollback. Never reuse a version name or edit a release directory in place.

## 9. Verify production

```bash
sudo systemctl enable --now \
  wesite-admin.service wesite-web.service wesite-health-monitor.timer
sudo check-wesite-services
systemctl status wesite-admin.service wesite-web.service --no-pager
systemctl status wesite-health-monitor.timer --no-pager
systemctl list-timers wesite-health-monitor.timer --no-pager
journalctl -u wesite-admin.service -u wesite-web.service --since '-15 min' --no-pager
ss -lntp | grep -E '127\.0\.0\.1:(8080|8082)'
curl --fail --silent --show-error http://127.0.0.1:8080/api/healthz
curl --fail --silent --show-error http://127.0.0.1:8080/api/readyz
curl --fail --silent --show-error http://127.0.0.1:8082/api/readyz
free -h
swapon --show
nginx -t
```

From the repository operator machine, also run:

```powershell
pwsh -NoProfile -File scripts/check-production-smoke.ps1 -BaseUrl https://whose.domains
pwsh -NoProfile -File scripts/check-seo.ps1 -BaseUrl https://whose.domains
```

Check the admin blog save/preview/publish/unpublish workflow and the blog sitemap. Keep `/domain/*` and `/blog/*` public URL shapes unchanged.

## Logs and routine operation

```bash
journalctl -u wesite-web.service -f
journalctl -u wesite-admin.service -f
journalctl -u wesite-health-monitor.service -f
systemctl restart wesite-web.service
systemctl restart wesite-admin.service
systemctl stop wesite-web.service wesite-admin.service
```

Before a planned maintenance window, stop both the timer and any currently running monitor, then confirm both are inactive so intentional service stops cannot be counted as failures:

```bash
sudo systemctl stop wesite-health-monitor.timer wesite-health-monitor.service
systemctl is-active wesite-health-monitor.timer wesite-health-monitor.service
```

If the maintenance window may span a host reboot, disable all three boot units and stop any in-flight monitor:

```bash
sudo systemctl disable --now \
  wesite-health-monitor.timer wesite-web.service wesite-admin.service
sudo systemctl stop wesite-health-monitor.service
```

Confirm all three boot units report `disabled`. After maintenance and a successful deployment, restore boot activation with `sudo systemctl enable --now wesite-admin.service wesite-web.service wesite-health-monitor.timer`.

Do not delete `/usr/java/previous` or the database backup until the release has passed its observation window. After stability is confirmed, remove old release directories individually; never recursively delete `/usr/java`, `/usr/java/releases`, or a path derived from an unchecked variable.
