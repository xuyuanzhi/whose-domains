# Whose.Domains independent systemd production runbook

This runbook migrates and operates the production host at `47.76.125.96`.
Web and Admin have independent immutable release trees, services, health checks,
rollbacks, and Jenkins jobs:

```text
/usr/java/apps/web/{current,previous,releases/}
/usr/java/apps/admin/{current,previous,releases/}
```

The public Web service binds to `127.0.0.1:8080`; Admin binds to
`127.0.0.1:8082`. Jenkins connects only as `wesite-deploy`. A routine job
builds and uploads one application JAR, then invokes that application's
allowlisted deploy and check commands. Infrastructure installation, live
configuration, database work, rollback, service administration, and release
cleanup are operator procedures and never Jenkins job side effects.

The host has 3.5 GiB RAM. The checked-in units reserve up to 768 MiB of heap
for Web and 384 MiB for Admin, with explicit metaspace and direct-memory caps.
A 2 GiB swap file is an emergency buffer, not normal working memory.

## 1. Preflight Java, unzip, and flock

Open a maintenance record with operator, UTC time, source commit, and planned
rollback. Run these read-only checks through the approved production operator
account:

```bash
java -version
command -v java
test -x /usr/bin/java
command -v unzip
command -v flock
systemctl is-active nginx redis-server
free -h
df -h /
pgrep -af 'java|watchdog|wesite'
systemctl list-units --type=service | grep -Ei 'wesite|watchdog'
systemctl list-timers --all | grep -Ei 'wesite|watchdog'
crontab -l
sudo crontab -l
```

Stop if `/usr/bin/java`, `unzip`, or `flock` is unavailable. Identify the exact
legacy launcher and watchdog owner now; do not use a broad process-kill
command. Confirm that Nginx and Redis are healthy and that ports 8080 and 8082
are owned only by the expected legacy JVMs.

## 2. Verify swap

```bash
free -h
swapon --show
grep -F '/swapfile none swap sw 0 0' /etc/fstab
df -h /
```

Record the result and do not enter configuration or application migration on
this small host until the expected 2 GiB swap is active and persistent, or an
operator has approved an alternative. If swap is missing, the first approved
write can use the audited helper after the bundle is installed in step 4.
`/usr/local/sbin/ensure-wesite-swap` refuses to overwrite an inactive existing
file.

## 3. Build and upload only the infrastructure bundle

On a trusted build/operator machine, from the reviewed commit:

```bash
INFRA_COMMIT="$(git rev-parse --verify HEAD)"
[[ "$INFRA_COMMIT" =~ ^[0-9a-f]{7,64}$ ]]
INFRA_OUTPUT="$(mktemp -d)"
scripts/build-wesite-deployment-bundle.sh "$INFRA_COMMIT" "$INFRA_OUTPUT"
ls -l "$INFRA_OUTPUT"
(
  cd "$INFRA_OUTPUT"
  sha256sum --check "wesite-deployment-$INFRA_COMMIT.tar.gz.sha256"
)
```

The output must contain exactly these three files:

```text
wesite-deployment-<commit>.tar.gz
wesite-deployment-<commit>.tar.gz.sha256
install-wesite-deployment-bundle.sh
```

Set `PRODUCTION_OPERATOR` to the approved privileged operator account. It is
not `wesite-deploy`. The validated commit is safe to use in the temporary path:

```bash
REMOTE_INFRA="/tmp/wesite-infrastructure-$INFRA_COMMIT"
ssh "$PRODUCTION_OPERATOR@47.76.125.96" \
  "umask 077 && mkdir -m 0700 -- '$REMOTE_INFRA'"
scp "$INFRA_OUTPUT/wesite-deployment-$INFRA_COMMIT.tar.gz" \
  "$INFRA_OUTPUT/wesite-deployment-$INFRA_COMMIT.tar.gz.sha256" \
  "$INFRA_OUTPUT/install-wesite-deployment-bundle.sh" \
  "$PRODUCTION_OPERATOR@47.76.125.96:$REMOTE_INFRA/"
```

Do not transfer a repository checkout, `.git`, source code, Maven files,
tests, application JARs, or live secrets with the infrastructure bundle.

## 4. Run the standalone bootstrap

On the production host, inspect the three uploaded files, then run only the
standalone bootstrap as root. It verifies the archive sidecar, rejects unsafe
archive members, verifies the internal payload manifest, preserves existing
live configuration, installs the commands and units, and reloads systemd. It
does not enable or start an application or timer.

```bash
INFRA_COMMIT=0123456789abcdef0123456789abcdef01234567
[[ "$INFRA_COMMIT" =~ ^[0-9a-f]{7,64}$ ]]
cd "/tmp/wesite-infrastructure-$INFRA_COMMIT"
sudo ./install-wesite-deployment-bundle.sh \
  "wesite-deployment-$INFRA_COMMIT.tar.gz" \
  "wesite-deployment-$INFRA_COMMIT.tar.gz.sha256"
sudo systemd-analyze verify \
  /etc/systemd/system/wesite-web.service \
  /etc/systemd/system/wesite-admin.service \
  /etc/systemd/system/wesite-health-monitor.service \
  /etc/systemd/system/wesite-health-monitor.timer
sudo stat -c '%U:%G %a %n' /run/lock/wesite
```

The bundled `tmpfiles.d` policy recreates the volatile deployment lock
directory at every boot. It must report `root:root 755`; deployment, rollback,
and health recovery refuse an unsafe or replaceable lock path.

If step 2 identified missing swap and the approved plan is the bundled helper,
run it now and repeat the swap checks before proceeding:

```bash
sudo /usr/local/sbin/ensure-wesite-swap
free -h
swapon --show
```

After a successful bootstrap, remove only the three exact temporary files and
their now-empty directory. Keep the archive digest in the maintenance record.

## 5. Migrate and preserve live configuration

The installer creates examples and creates a live file only when it is absent.
It never replaces existing live values. Compare the old production settings
with these destinations and merge values deliberately:

```text
/etc/wesite/wesite.env
/usr/java/config/web/application-prod.properties
/usr/java/config/admin/application-prod.properties
```

Use `sudoedit`; never evaluate `/etc/wesite/wesite.env` as shell input. Its
JDBC URL can contain shell metacharacters. Preserve the tested remote-MySQL
URL, including the explicit UTC connection/session options, and configure the
database, Redis, JWT, internal blog, mail, and MaxMind values. Keep notification
delivery disabled for the first release and keep both services bound to
loopback.

```bash
sudoedit /etc/wesite/wesite.env
sudoedit /usr/java/config/web/application-prod.properties
sudoedit /usr/java/config/admin/application-prod.properties

sudo chown root:wesite /etc/wesite/wesite.env \
  /usr/java/config/web/application-prod.properties \
  /usr/java/config/admin/application-prod.properties
sudo chmod 0600 /etc/wesite/wesite.env
sudo chmod 0640 /usr/java/config/web/application-prod.properties \
  /usr/java/config/admin/application-prod.properties

if sudo grep -R -n 'CHANGE_ME\|your-db-\|/path/to/' \
    /etc/wesite/wesite.env \
    /usr/java/config/web/application-prod.properties \
    /usr/java/config/admin/application-prod.properties; then
  printf 'Stop: production placeholders remain.\n' >&2
  exit 1
fi
```

Back up the three reviewed live files outside the release trees. Production
secrets are never Jenkins parameters, workspaces, archives, or artifacts.

## 6. Install the Jenkins SSH public key

Create an SSH key credential in the Jenkins secret store and transfer only its
reviewed public key to a temporary operator-owned path. Verify its fingerprint
against the maintenance record before installation. The bootstrap creates the
dedicated `wesite-deploy` account but intentionally does not change SSH daemon
configuration or generate credentials.

```bash
ssh-keygen -lf /tmp/whose-domains-prod-ssh.pub
sudo install -d -o wesite-deploy -g wesite-deploy -m 0700 \
  /var/lib/wesite-deploy/.ssh
sudo install -o wesite-deploy -g wesite-deploy -m 0600 \
  /tmp/whose-domains-prod-ssh.pub \
  /var/lib/wesite-deploy/.ssh/authorized_keys
sudo stat -c '%U:%G %a %n' \
  /var/lib/wesite-deploy/.ssh \
  /var/lib/wesite-deploy/.ssh/authorized_keys
```

Test key-only login as `wesite-deploy` under the host's existing SSH policy.
Do not grant root login, a root shell, or access to an operator key.

## 7. Validate and explicitly activate sudoers

The bootstrap installs an inactive example. Validate that exact file first,
then explicitly activate it and validate the complete sudoers policy:

```bash
sudo visudo -cf /etc/wesite/wesite-deploy.sudoers.example
sudo install -o root -g root -m 0440 \
  /etc/wesite/wesite-deploy.sudoers.example \
  /etc/sudoers.d/wesite-deploy
sudo visudo -cf /etc/sudoers.d/wesite-deploy
sudo visudo -cf /etc/sudoers
sudo -l -U wesite-deploy
```

The only passwordless commands must be single-application Web/Admin deploys
and checks. The account must have no infrastructure install, configuration,
service-manager, rollback, pruning, arbitrary root command, or shell access.

## 8. Prepare independent old-JAR baseline releases

Before stopping the legacy supervisor, locate each exact known-good JAR,
record its provenance and SHA-256, and copy each one without modification into
its own root-owned mode `0700` incoming directory. Do not guess paths or
substitute one application's artifact for the other.

```bash
find /usr/java -maxdepth 4 -type f -name 'wesite-*.jar' -print

BASELINE_ID="$(date -u +%Y%m%dT%H%M%SZ)"
[[ "$BASELINE_ID" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]
sudo mkdir -m 0700 \
  "/var/lib/wesite-deploy/incoming/baseline-web-$BASELINE_ID" \
  "/var/lib/wesite-deploy/incoming/baseline-admin-$BASELINE_ID"
sudo install -o root -g root -m 0400 /absolute/reviewed/old-wesite-web.jar \
  "/var/lib/wesite-deploy/incoming/baseline-web-$BASELINE_ID/wesite-web.jar"
sudo install -o root -g root -m 0400 /absolute/reviewed/old-wesite-admin.jar \
  "/var/lib/wesite-deploy/incoming/baseline-admin-$BASELINE_ID/wesite-admin.jar"
sudo sha256sum \
  "/var/lib/wesite-deploy/incoming/baseline-web-$BASELINE_ID/wesite-web.jar" \
  "/var/lib/wesite-deploy/incoming/baseline-admin-$BASELINE_ID/wesite-admin.jar"
```

These staged candidates become the two independent baseline releases in step
10, after the legacy watchdog and launcher can no longer race systemd. If an
old artifact cannot be verified, record that application as having no initial
binary rollback target and take configuration/filesystem backups instead.

## 9. Disable the legacy watchdog and launcher

Enter the maintenance window. Using the exact source identified in step 1,
disable and stop the legacy watchdog (cron entry, timer, or service), then stop
the exact legacy Java launcher. Do not kill unrelated Java processes and do not
leave two supervisors able to own the same port.

Verify the result before activating a baseline:

```bash
pgrep -af 'java|watchdog|wesite'
systemctl list-units --type=service | grep -Ei 'wesite|watchdog'
systemctl list-timers --all | grep -Ei 'wesite|watchdog'
grep -R "watchdog" /etc/cron.d /etc/cron.daily /etc/systemd/system 2>/dev/null
ss -lntp | grep -E '127\.0\.0\.1:(8080|8082)' || true
```

Record the exact unit, timer, cron file, or launcher that was disabled. Removal
of that legacy mechanism is a one-time operator action, never a Jenkins step.

## 10. Activate baselines and perform the first independent releases

Activate and check each verified old binary separately. Legacy HTTP-200 health
overrides require direct root invocation and are only for old binaries that
predate readiness endpoints:

```bash
sudo -i env -u SUDO_USER -u SUDO_UID -u SUDO_GID \
  WESITE_HEALTH_RESPONSE_MODE=legacy-http-200 \
  WESITE_APP_HEALTH_URL=http://127.0.0.1:8080/ \
  /usr/local/sbin/deploy-wesite-app web \
  "baseline-web-$BASELINE_ID" \
  "/var/lib/wesite-deploy/incoming/baseline-web-$BASELINE_ID/wesite-web.jar"
sudo /usr/local/sbin/check-wesite-app web

sudo -i env -u SUDO_USER -u SUDO_UID -u SUDO_GID \
  WESITE_HEALTH_RESPONSE_MODE=legacy-http-200 \
  WESITE_APP_HEALTH_URL=http://127.0.0.1:8082/ \
  /usr/local/sbin/deploy-wesite-app admin \
  "baseline-admin-$BASELINE_ID" \
  "/var/lib/wesite-deploy/incoming/baseline-admin-$BASELINE_ID/wesite-admin.jar"
sudo /usr/local/sbin/check-wesite-app admin
```

Remove only each baseline input file and its empty staging directory after its
command has returned. Never remove its immutable release directory.

Build the new Web and Admin candidates on CI or another build host. Run and
record the two module builds separately; production receives JARs, not source:

```bash
mvn -B -pl wesite-web -am clean verify
mvn -B -pl wesite-admin -am clean verify
```

Stage and deploy Web first with its commit-based version, run its single check,
then independently stage and deploy Admin and run its single check. New
releases use the default strict readiness contract; do not apply legacy health
overrides. Transfer only the two built JARs to separate operator-owned
temporary paths. On production, validate the recorded commit, create two
private incoming directories, and install each exact artifact:

```bash
NEW_COMMIT=0123456789abcdef0123456789abcdef01234567
[[ "$NEW_COMMIT" =~ ^[0-9a-f]{7,64}$ ]]
sudo mkdir -m 0700 \
  "/var/lib/wesite-deploy/incoming/first-web-$NEW_COMMIT" \
  "/var/lib/wesite-deploy/incoming/first-admin-$NEW_COMMIT"
sudo install -o root -g root -m 0400 \
  "/tmp/wesite-web-$NEW_COMMIT.jar" \
  "/var/lib/wesite-deploy/incoming/first-web-$NEW_COMMIT/wesite-web-1.0.0.jar"
sudo install -o root -g root -m 0400 \
  "/tmp/wesite-admin-$NEW_COMMIT.jar" \
  "/var/lib/wesite-deploy/incoming/first-admin-$NEW_COMMIT/wesite-admin-1.0.0.jar"
```

Invoke and check the releases one at a time:

```bash

sudo /usr/local/sbin/deploy-wesite-app web \
  "$NEW_COMMIT-first-web" \
  "/var/lib/wesite-deploy/incoming/first-web-$NEW_COMMIT/wesite-web-1.0.0.jar"
sudo /usr/local/sbin/check-wesite-app web

sudo /usr/local/sbin/deploy-wesite-app admin \
  "$NEW_COMMIT-first-admin" \
  "/var/lib/wesite-deploy/incoming/first-admin-$NEW_COMMIT/wesite-admin-1.0.0.jar"
sudo /usr/local/sbin/check-wesite-app admin
```

A failed deploy rolls back only that application. Stop and investigate a
failure; do not make the other application part of its health gate or rollback.

## 11. Enable services and the health timer

After both first-release checks have passed:

```bash
sudo systemctl enable --now \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl is-enabled \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl is-active \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl list-timers wesite-health-monitor.timer --no-pager
```

The timer evaluates each deployed application independently. Before an
intentional application stop, stop the timer and any in-flight monitor so it
cannot treat maintenance as a failure.

## 12. Production operation

### Single-application and global checks

```bash
sudo /usr/local/sbin/check-wesite-app web
sudo /usr/local/sbin/check-wesite-app admin
sudo /usr/local/sbin/check-wesite-services
curl --fail --silent --show-error http://127.0.0.1:8080/api/healthz
curl --fail --silent --show-error http://127.0.0.1:8080/api/readyz
curl --fail --silent --show-error http://127.0.0.1:8082/api/readyz
nginx -t
```

From the operator machine:

```powershell
pwsh -NoProfile -File scripts/check-production-smoke.ps1 -BaseUrl https://whose.domains
pwsh -NoProfile -File scripts/check-seo.ps1 -BaseUrl https://whose.domains
```

### Jenkins credentials and two independent jobs

In Jenkins Credentials, create:

- an SSH Username with private key credential named
  `whose-domains-prod-ssh`, with username `wesite-deploy`;
- a Secret file credential named `whose-domains-prod-known-hosts`.

Obtain the production host public key through a trusted console or provider
channel. Compare its fingerprint out of band before putting the exact
`47.76.125.96` known-hosts line in the Secret file. `ssh-keyscan` output alone
does not authenticate a host key.

Create two Pipeline-from-SCM jobs:

- Web uses `deploy/jenkins/wesite-web.Jenkinsfile`;
- Admin uses `deploy/jenkins/wesite-admin.Jenkinsfile`.

Each job disables same-job concurrency, validates `GIT_COMMIT` as lowercase
hexadecimal and `BUILD_NUMBER` as decimal digits, pins the host key, creates a
mode `0700` app/commit/build staging directory, uploads exactly its own JAR,
and invokes only its own non-interactive deploy and check commands. Cleanup in
the same remote shell removes only the uploaded file and empty job directory,
then returns the saved deploy/check status.

Routine jobs must not upload infrastructure or source, install units, edit
configuration, start Java, manage services directly, invoke a watchdog,
perform rollback, prune releases, or run database maintenance. Independent
releases can temporarily run mixed commits, so changes to schemas, JWT claims,
Redis values, internal APIs, and other shared contracts must be backward- and
forward-compatible across adjacent versions. Incompatible changes require a
coordinated maintenance window.

### Root-only manual rollback

Jenkins has no rollback permission. An operator rolls back one application and
checks that same application before deciding whether a separate rollback is
needed elsewhere:

```bash
sudo /usr/local/sbin/rollback-wesite-app web
sudo /usr/local/sbin/check-wesite-app web

sudo /usr/local/sbin/rollback-wesite-app admin
sudo /usr/local/sbin/check-wesite-app admin
```

The rollback swaps that application's successful `current` and `previous`
targets. If the target fails its recorded health contract, the command restores
the original links and original version.

### Journal inspection

```bash
journalctl -u wesite-web.service --since '-15 min' --no-pager
journalctl -u wesite-admin.service --since '-15 min' --no-pager
journalctl -u wesite-health-monitor.service --since '-15 min' --no-pager
journalctl -u wesite-web.service -f
journalctl -u wesite-admin.service -f
```

### Root-only old-release cleanup

Release pruning is a separate, reviewed operator change. For one application
at a time, resolve and record its `current` and `previous` targets, list the
terminal release metadata, and print exact candidates before deletion:

```bash
sudo realpath -e /usr/java/apps/web/current
sudo realpath -e /usr/java/apps/web/previous
sudo find /usr/java/apps/web/releases -mindepth 1 -maxdepth 1 -type d -print

sudo realpath -e /usr/java/apps/admin/current
sudo realpath -e /usr/java/apps/admin/previous
sudo find /usr/java/apps/admin/releases -mindepth 1 -maxdepth 1 -type d -print
```

Retain both link targets and at least the three most recent additional
successful releases. Keep failed releases until diagnosis is complete. Only
root may remove one fully resolved, literal release directory after checking
its `APP`, `VERSION`, `STATUS`, and link exclusion. Never delete an unchecked
variable, symlink target, application root, shared parent, or all releases with
a wildcard. Once those checks are recorded, remove only the reviewed literal
path, for example
`sudo rm -rf -- /usr/java/apps/web/releases/0123456789abcdef-123`; never paste
the example without replacing and rechecking its release identity.

## Coordinated blog database migration and sanitization

This is database/application maintenance outside both routine Jenkins jobs.
It is not a hidden side effect of either release. Schedule a coordinated
maintenance window, keep normal writers stopped from backup through apply, and
retain all output with the change record.

Stop the monitor and both services deliberately:

```bash
sudo systemctl stop \
  wesite-health-monitor.timer wesite-health-monitor.service \
  wesite-web.service wesite-admin.service
```

From the trusted operator machine that contains the reviewed SQL file, back up
and migrate `WEB_BLOG_POST` directly against the remote database using a
schema-authorized account. Do not copy the repository to production:

```bash
mysqldump --single-transaction -h MYSQL_HOST -u DB_USER -p wesitedb \
  WEB_BLOG_POST > web-blog-post-before-editorial-$(date -u +%Y%m%dT%H%M%SZ).sql
sha256sum web-blog-post-before-editorial-*.sql

mysql -h MYSQL_HOST -u DB_USER -p wesitedb \
  < doc/alter_blog_editorial_workflow.sql
mysql -h MYSQL_HOST -u DB_USER -p wesitedb \
  -e "SHOW COLUMNS FROM WEB_BLOG_POST LIKE 'CONTENT_UPDATED_AT';"
```

Copy the reviewed Admin maintenance JAR as a root-owned, runtime-readable
file. Run it through a transient root-created systemd unit so the environment
file is parsed by systemd rather than a shell:

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

Inspect every proposed change, or an explicitly recorded sample for a large
set. Repeat with a new unit name, `mode=apply`, and a separate apply log. A
non-zero exit or missing completion report stops the change. Run dry-run again;
it must report zero changes.

Release the tested, compatibility-matched Web and Admin binaries through two
separate application deployment invocations, checking each independently.
Leave the additive database column in place during binary rollback. Content
rollback restores only affected IDs and their original `CONTENT` and
`CONTENT_UPDATED_AT` values from the recorded backup; never overwrite unrelated
post edits. Restore services and the timer only after application, editorial,
public page, P0 smoke, and SEO checks pass.
