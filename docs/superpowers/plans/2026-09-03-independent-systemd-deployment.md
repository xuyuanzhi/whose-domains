# Independent Systemd Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the existing Web and Admin Jenkins jobs independently upload, deploy, verify, and roll back one JAR without placing source code on production.

**Architecture:** Web and Admin receive separate immutable release trees under `/usr/java/apps`, while a shared shell library resolves application-specific paths, services, JAR identities, metadata, and health contracts. One global `flock` serializes production mutations, but every deploy, check, rollback, and monitor action touches only the selected application. Infrastructure ships as a checksummed source-free archive; routine Jenkins jobs upload exactly one JAR into a restricted staging tree.

**Tech Stack:** Bash, GNU coreutils, `flock`, `realpath`, `unzip`, `tar`, `sha256sum`, systemd, Jenkins Declarative Pipeline, Maven, Java 17

**Spec:** `docs/superpowers/specs/2026-09-03-independent-systemd-deployment-design.md`

## Global Constraints

- `APP` is exactly `web` or `admin`; a release version contains only letters, numbers, dot, underscore, and hyphen.
- Web uses `wesite-web.service`, `wesite-web.jar`, `info.wesite.web.App`, port 8080, and `/api/readyz`; Admin uses the corresponding Admin values and port 8082.
- Routine Jenkins releases upload one JAR only and never reinstall infrastructure, edit production configuration, start Java directly, invoke the legacy watchdog, or restart the other application.
- A staged JAR must resolve below `/var/lib/wesite-deploy/incoming`, must not be a symlink, and is copied into a root-owned temporary release before archive and identity validation.
- New releases use strict JSON `UP` readiness. A release records its own health URL and response mode so an explicit legacy baseline remains verifiable during rollback.
- All deploy, rollback, and monitor mutations share `/run/lock/wesite-deploy.lock`.
- Production retains scripts, systemd units, configuration, and JARs only; it receives no Java source, tests, Maven files, Git metadata, or repository documentation.
- Existing live configuration contents must be preserved, and installation must not enable or start either application or the health timer.
- Existing `/domain/*` and `/blog/*` public URL shapes remain unchanged.

## File map

- `scripts/server/wesite-app-functions.sh`: resolve app constants, validate release metadata, and execute the recorded health contract.
- `scripts/server/check-wesite-app.sh`: report health for exactly one deployed application.
- `scripts/server/deploy-wesite-app.sh`: publish and automatically roll back one application release.
- `scripts/server/rollback-wesite-app.sh`: operator-only rollback of one application to `previous`.
- `scripts/server/deploy-wesite-release.sh`: fail-closed compatibility stub for the retired paired interface.
- `scripts/server/monitor-wesite-services.sh`: monitor deployed Web/Admin trees independently while sharing the deployment lock.
- `scripts/server/check-wesite-services.sh`: operator-wide wrapper over both single-application checks.
- `scripts/server/install-wesite-systemd.sh`: verify an extracted bundle and install users, paths, units, commands, and examples without activation.
- `scripts/server/install-wesite-deployment-bundle.sh`: verify the archive sidecar and member paths, extract into a private temporary directory, and invoke the bundled installer.
- `scripts/build-wesite-deployment-bundle.sh`: produce the source-free infrastructure archive and checksum sidecar.
- `deploy/config/*.example`: canonical production property templates moved out of application source directories.
- `deploy/sudoers/wesite-deploy`: reviewed least-privilege sudoers template.
- `deploy/jenkins/wesite-web.Jenkinsfile`, `deploy/jenkins/wesite-admin.Jenkinsfile`: independent job examples that upload one JAR.
- `deploy/systemd/*.service`: consume independent current links and no longer depend on `/usr/java/current`.
- `deploy/README.md`: one-time migration, Jenkins setup, baseline, verification, rollback, and cleanup runbook.

---

### Task 1: Shared application contract and single-application health check

**Files:**
- Create: `scripts/server/wesite-app-functions.sh`
- Create: `scripts/server/check-wesite-app.sh`
- Create: `scripts/server/test-check-wesite-app.sh`
- Modify: `scripts/server/wesite-health-functions.sh`

**Interfaces:**
- Produces: `wesite_select_app APP`, which exports `WESITE_SELECTED_NAME`, `WESITE_SELECTED_BASE`, `WESITE_SELECTED_SERVICE`, `WESITE_SELECTED_JAR_NAME`, `WESITE_SELECTED_START_CLASS`, and `WESITE_SELECTED_DEFAULT_HEALTH_URL`.
- Produces: `wesite_load_release_metadata RELEASE_DIR`, which validates and exports `WESITE_RELEASE_STATUS`, `WESITE_RELEASE_HEALTH_MODE`, and `WESITE_RELEASE_HEALTH_URL` without evaluating metadata as shell code. It accepts only the known lifecycle states `deploying|successful|failed`; callers enforce the state allowed for their operation.
- Produces: `wesite_check_release_health APP RELEASE_DIR`, which permits a transaction-owned `deploying` candidate or terminal `successful` target and checks the selected service against that release's recorded contract.
- Produces: `wesite_check_app APP`, returning 0 only when `current` is a valid successful release, its service is active, and its recorded health contract passes.

- [ ] **Step 1: Write failing app-selection and single-health tests**

Create fixtures for independent Web/Admin trees and fake `systemctl`/`curl`. Assert all of these behaviors in `test-check-wesite-app.sh`:

```bash
bash "$CHECK_SCRIPT" web
grep -Fq 'systemctl is-active --quiet wesite-web.service' "$CALL_LOG"
! grep -Fq 'wesite-admin.service' "$CALL_LOG"

bash "$CHECK_SCRIPT" admin
grep -Fq 'http://127.0.0.1:8082/api/readyz' "$CALL_LOG"

if bash "$CHECK_SCRIPT" invalid; then
  fail 'invalid APP was accepted'
fi

if WESITE_TEST_RESPONSE_MODE=html bash "$CHECK_SCRIPT" web; then
  fail 'strict release accepted HTML'
fi
```

Also assert that missing `current`, a `current` target outside the selected app tree, missing metadata, non-success status, unsafe health mode, and inactive selected service fail without calling the other application.

- [ ] **Step 2: Run the new test and verify RED**

Run: `bash scripts/server/test-check-wesite-app.sh`

Expected: FAIL because `check-wesite-app.sh` and `wesite-app-functions.sh` do not exist.

- [ ] **Step 3: Implement strict app and metadata parsing**

Implement the selector with a closed case statement:

```bash
case "$1" in
  web)
    WESITE_SELECTED_NAME=web
    WESITE_SELECTED_BASE="${WESITE_APPS_BASE_DIR:-/usr/java/apps}/web"
    WESITE_SELECTED_SERVICE="${WESITE_WEB_SERVICE:-wesite-web.service}"
    WESITE_SELECTED_JAR_NAME=wesite-web.jar
    WESITE_SELECTED_START_CLASS=info.wesite.web.App
    WESITE_SELECTED_DEFAULT_HEALTH_URL=http://127.0.0.1:8080/api/readyz
    ;;
  admin)
    WESITE_SELECTED_NAME=admin
    WESITE_SELECTED_BASE="${WESITE_APPS_BASE_DIR:-/usr/java/apps}/admin"
    WESITE_SELECTED_SERVICE="${WESITE_ADMIN_SERVICE:-wesite-admin.service}"
    WESITE_SELECTED_JAR_NAME=wesite-admin.jar
    WESITE_SELECTED_START_CLASS=info.wesite.admin.App
    WESITE_SELECTED_DEFAULT_HEALTH_URL=http://127.0.0.1:8082/api/readyz
    ;;
  *) return 64 ;;
esac
```

Read each metadata file directly with `IFS= read -r`; do not `source` it. Validate APP against the selected app, VERSION against the release directory name, SHA256 as 64 lowercase hexadecimal characters, DEPLOYED_AT as UTC `YYYY-MM-DDTHH:MM:SSZ`, STATUS as `deploying|successful|failed`, health mode as `readiness|legacy-http-200`, and health URL as loopback HTTP on the selected fixed port. Resolve `current` with `realpath -e` and verify it remains below `$WESITE_SELECTED_BASE/releases/`. `wesite_check_app` additionally requires `STATUS=successful`; transaction-owned candidate checks require `deploying` and rollback targets require `successful`.

- [ ] **Step 4: Run focused health tests**

Run: `bash scripts/server/test-check-wesite-app.sh`

Expected: PASS with a final count covering Web isolation, Admin isolation, invalid app, strict response, legacy response, metadata validation, path containment, and inactive service.

- [ ] **Step 5: Re-run the existing readiness parser tests**

Run: `bash scripts/server/test-wesite-health-functions.sh`

Expected: `Strict readiness function tests passed: 9.`

- [ ] **Step 6: Commit the shared contract**

```bash
git add scripts/server/wesite-app-functions.sh scripts/server/check-wesite-app.sh \
  scripts/server/test-check-wesite-app.sh scripts/server/wesite-health-functions.sh
git commit -m "feat: add single application health contract"
```

### Task 2: Independent deployment with metadata and automatic rollback

**Files:**
- Create: `scripts/server/deploy-wesite-app.sh`
- Create: `scripts/server/test-deploy-wesite-app.sh`
- Modify: `scripts/server/deploy-wesite-release.sh`
- Delete: `scripts/server/test-deploy-wesite-release.sh`

**Interfaces:**
- Consumes: `wesite_select_app`, `wesite_load_release_metadata`, and `wesite_readiness_check` from Task 1.
- Produces: `deploy-wesite-app APP VERSION JAR`, with optional baseline-only overrides `WESITE_HEALTH_RESPONSE_MODE` and `WESITE_APP_HEALTH_URL` when invoked by root.

- [ ] **Step 1: Write failing independent deployment tests**

Build fake `systemctl`, `curl`, and `unzip` commands. The fake `unzip -p` returns one of these manifests according to the fixture:

```text
Manifest-Version: 1.0
Start-Class: info.wesite.web.App
```

```text
Manifest-Version: 1.0
Start-Class: info.wesite.admin.App
```

Test Web and Admin in separate fixtures. Assert:

- successful Web deploy changes only `apps/web/current`, restarts only `wesite-web.service`, records all seven metadata files, and sets `previous` to the prior Web release;
- successful Admin deploy has the symmetric behavior;
- failed readiness marks the candidate `failed`, restores only the selected app, and leaves its prior `previous` link unchanged;
- a failed first release removes only the selected `current` and stops only the selected service;
- invalid app/version, source symlink, source outside incoming, corrupt ZIP, wrong `Start-Class`, existing version, lock contention, and SIGTERM after switching all fail safely;
- legacy health overrides are rejected when `SUDO_USER=wesite-deploy` and accepted only for a direct root-operated baseline;
- changing the unprivileged source after the private copy cannot change the published JAR or recorded digest.

- [ ] **Step 2: Run the deployment test and verify RED**

Run: `bash scripts/server/test-deploy-wesite-app.sh`

Expected: FAIL because the independent command does not exist.

- [ ] **Step 3: Implement staging containment and private-copy validation**

Validate with this sequence before publishing:

```bash
[[ -f "$INPUT_JAR" && ! -L "$INPUT_JAR" && -r "$INPUT_JAR" ]] || fail 'invalid staged JAR'
INCOMING_ROOT="$(realpath -e "${WESITE_INCOMING_DIR:-/var/lib/wesite-deploy/incoming}")"
INPUT_REAL="$(realpath -e "$INPUT_JAR")"
[[ "$INPUT_REAL" == "$INCOMING_ROOT"/* ]] || fail 'staged JAR is outside incoming root'
```

Take the global non-blocking lock, create `$APP_BASE/releases/.${VERSION}.tmp.$$`, copy the JAR there as root, run `unzip -tqq` on the copy, normalize CRLF from `META-INF/MANIFEST.MF`, require the selected exact `Start-Class`, calculate SHA-256 on the copy, record DEPLOYED_AT with `date -u +%Y-%m-%dT%H:%M:%SZ`, and write the metadata files without evaluating input text. When `SUDO_USER` is non-empty and not `root`, reject non-default health mode or URL overrides so the Jenkins sudo rule cannot manufacture a legacy release.

- [ ] **Step 4: Implement per-application switch and rollback traps**

Rename the validated temporary directory to `$APP_BASE/releases/$VERSION`, switch only `$APP_BASE/current` with a temporary symlink plus `mv -Tf`, restart only the selected service, and check the candidate's recorded contract. On failure or signal after switching, restore only the old selected target, check it with its recorded contract, mark the candidate failed, and preserve the old `previous`. On success, mark the candidate successful and set `previous` to the old target.

Replace `deploy-wesite-release.sh` with a stub containing only an explanatory error and `exit 64`:

```text
ERROR: paired deployment is retired; use deploy-wesite-app web or deploy-wesite-app admin
```

- [ ] **Step 5: Run deployment tests**

Run: `bash scripts/server/test-deploy-wesite-app.sh`

Expected: PASS for every independent success, validation, isolation, rollback, contention, and signal scenario.

- [ ] **Step 6: Verify the legacy command fails closed**

Run: `bash scripts/server/deploy-wesite-release.sh v2 web.jar admin.jar; test $? -eq 64`

Expected: the retirement message is printed and the final `test` succeeds.

- [ ] **Step 7: Commit independent deployment**

```bash
git add scripts/server/deploy-wesite-app.sh scripts/server/test-deploy-wesite-app.sh \
  scripts/server/deploy-wesite-release.sh scripts/server/test-deploy-wesite-release.sh
git commit -m "feat: deploy web and admin independently"
```

### Task 3: Operator-only manual rollback

**Files:**
- Create: `scripts/server/rollback-wesite-app.sh`
- Create: `scripts/server/test-rollback-wesite-app.sh`

**Interfaces:**
- Consumes: Task 1 app selection and recorded health contract.
- Produces: `rollback-wesite-app APP`, which swaps selected `current`/`previous` only after a healthy target is proven.

- [ ] **Step 1: Write failing rollback tests**

Assert that a successful Web rollback switches Web `current` to its prior successful release, points Web `previous` to the replaced version, restarts only Web, and does not touch Admin. Repeat symmetrically for Admin. Add failures for invalid app, missing previous link, previous outside the app tree, previous with `STATUS=failed`, held global lock, unhealthy rollback target, and SIGTERM.

For an unhealthy target, assert the original `current` is restored and restarted, `previous` remains unchanged, and the command exits non-zero.

- [ ] **Step 2: Run rollback tests and verify RED**

Run: `bash scripts/server/test-rollback-wesite-app.sh`

Expected: FAIL because `rollback-wesite-app.sh` does not exist.

- [ ] **Step 3: Implement atomic rollback and failed-rollback restoration**

Use the same selector, metadata parser, link containment, global lock, signal trap, and `mv -Tf` link switch as deployment. Load the target health contract before switching. Update `previous` only after the rollback target is healthy; if it is not, atomically restore the original target and its health contract.

- [ ] **Step 4: Run rollback and deployment tests**

```bash
bash scripts/server/test-rollback-wesite-app.sh
bash scripts/server/test-deploy-wesite-app.sh
```

Expected: both suites PASS.

- [ ] **Step 5: Commit manual rollback**

```bash
git add scripts/server/rollback-wesite-app.sh scripts/server/test-rollback-wesite-app.sh
git commit -m "feat: add isolated application rollback"
```

### Task 4: Independent monitoring, global status, and systemd paths

**Files:**
- Modify: `scripts/server/monitor-wesite-services.sh`
- Modify: `scripts/server/check-wesite-services.sh`
- Modify: `scripts/server/test-monitor-wesite-services.sh`
- Modify: `scripts/server/test-check-wesite-services.sh`
- Modify: `deploy/systemd/wesite-web.service`
- Modify: `deploy/systemd/wesite-admin.service`
- Modify: `deploy/systemd/wesite-health-monitor.service`

**Interfaces:**
- Consumes: Task 1 functions and independent app trees.
- Preserves: monitor threshold of three consecutive failures and the global deploy lock.

- [ ] **Step 1: Extend monitor and global-check tests first**

Add fixtures in which only Web has `current` and assert Admin is logged as `not deployed`, receives no failure file, and is never restarted. Add the symmetric Admin-only fixture. Assert deployed releases use their recorded health modes and URLs. Retain the existing three-failure, recovery reset, and held-lock cases.

Update global status tests so both deployed apps passing succeeds, either deployed app failing returns non-zero, and an app without `current` is reported as not deployed without converting the other app's single check into a failure.

- [ ] **Step 2: Run both suites and verify RED**

```bash
bash scripts/server/test-monitor-wesite-services.sh
bash scripts/server/test-check-wesite-services.sh
```

Expected: FAIL because the scripts still use fixed shared assumptions.

- [ ] **Step 3: Refactor monitoring around current-link presence**

Source `wesite-app-functions.sh`, loop over literal values `admin web`, and skip an app before reading failure state when `$APP_BASE/current` does not exist. For a deployed app, call the single-app health function, retain the threshold counter, and restart only that selected service. Keep the non-blocking global lock.

Make `check-wesite-services.sh` an operator wrapper that reports each app independently and exits non-zero only when at least one deployed app is unhealthy; an entirely undeployed host exits non-zero with a clear message.

- [ ] **Step 4: Point systemd units at independent links**

Set Web `WorkingDirectory=/usr/java/apps/web/current` and JAR path `/usr/java/apps/web/current/wesite-web.jar`. Set Admin to `/usr/java/apps/admin/current/wesite-admin.jar`. Remove `ConditionPathExists=/usr/java/current` from the monitor unit because the monitor now handles each absent current link itself. Preserve loopback binding, JVM limits, restart policy, hardening, and writable paths.

- [ ] **Step 5: Run monitor, status, and syntax tests**

```bash
bash scripts/server/test-monitor-wesite-services.sh
bash scripts/server/test-check-wesite-services.sh
bash -n scripts/server/*.sh
```

Expected: both behavior suites PASS and Bash syntax exits 0.

- [ ] **Step 6: Commit independent runtime supervision**

```bash
git add scripts/server/monitor-wesite-services.sh scripts/server/check-wesite-services.sh \
  scripts/server/test-monitor-wesite-services.sh scripts/server/test-check-wesite-services.sh \
  deploy/systemd/wesite-web.service deploy/systemd/wesite-admin.service \
  deploy/systemd/wesite-health-monitor.service
git commit -m "feat: supervise application releases independently"
```

### Task 5: Source-free installer, canonical templates, users, and sudoers

**Files:**
- Modify: `scripts/server/install-wesite-systemd.sh`
- Modify: `scripts/server/test-install-wesite-systemd.sh`
- Create: `deploy/config/wesite-web.application-prod.properties.example`
- Create: `deploy/config/wesite-admin.application-prod.properties.example`
- Delete: `wesite-web/src/main/resources/application-prod.properties.example`
- Delete: `wesite-admin/src/main/resources/application-prod.properties.example`
- Create: `deploy/sudoers/wesite-deploy`

**Interfaces:**
- Consumes: the production scripts and units from Tasks 1–4.
- Produces: an installed host with runtime user `wesite`, SSH staging user `wesite-deploy`, app trees, commands, examples, and no activated application units.

- [ ] **Step 1: Move configuration templates to their canonical deployment location**

Use `git mv` so content history remains visible:

```bash
mkdir -p deploy/config deploy/sudoers
git mv wesite-web/src/main/resources/application-prod.properties.example \
  deploy/config/wesite-web.application-prod.properties.example
git mv wesite-admin/src/main/resources/application-prod.properties.example \
  deploy/config/wesite-admin.application-prod.properties.example
```

Update all references to the old locations in scripts and documentation.

- [ ] **Step 2: Write failing installer and manifest-validation tests**

Extend `test-install-wesite-systemd.sh` to build an extracted bundle fixture with `SOURCE_COMMIT` and `SHA256SUMS`, then run the copied installer under `WESITE_ROOT_PREFIX`. Assert installation of:

```text
/usr/local/sbin/deploy-wesite-app
/usr/local/sbin/rollback-wesite-app
/usr/local/sbin/check-wesite-app
/usr/local/sbin/check-wesite-services
/usr/local/sbin/monitor-wesite-services
/etc/wesite/wesite-deploy.sudoers.example
/usr/java/apps/web/releases
/usr/java/apps/admin/releases
/var/lib/wesite-deploy/incoming
```

Assert preserved live config, modes `0600` and `0640`, updated unit paths, no enable/start calls, and the fail-closed legacy stub. Fake `getent`, `groupadd`, `useradd`, `id`, `usermod`, and `chown` to assert the exact `wesite-deploy` home and `/bin/bash` shell contract without modifying the test host.

Add negative fixtures for missing checksum files, checksum mismatch, duplicate checksum entries, absolute checksum paths, `..` entries, and resolved paths outside the extracted root. Each must fail before the fake `systemctl daemon-reload` call.

- [ ] **Step 3: Run installer tests and verify RED**

Run: `bash scripts/server/test-install-wesite-systemd.sh`

Expected: FAIL because the installer still reads application source paths and installs the paired command.

- [ ] **Step 4: Implement verified installation and least-privilege assets**

Rename internal `REPOSITORY_ROOT` terminology to `DEPLOYMENT_ROOT`. Require `SOURCE_COMMIT` and `SHA256SUMS`, validate every checksum path before calling `sha256sum -c`, and only then create or modify host paths.

Create `wesite-deploy` with private group, home `/var/lib/wesite-deploy`, and shell `/bin/bash`; create incoming as `wesite-deploy:wesite-deploy` mode `0750`, with Jenkins job directories required to be `0700`. Create Web/Admin release roots as `root:wesite` mode `0750`. Install new commands and the legacy stub. Install the sudoers file as an example under `/etc/wesite`; do not activate it automatically.

The checked-in sudoers template contains only these command families and relies on the scripts' closed validation:

```sudoers
wesite-deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-wesite-app web *, /usr/local/sbin/deploy-wesite-app admin *, /usr/local/sbin/check-wesite-app web, /usr/local/sbin/check-wesite-app admin
```

- [ ] **Step 5: Run installer and prior shell suites**

```bash
bash scripts/server/test-install-wesite-systemd.sh
bash scripts/server/test-check-wesite-app.sh
bash scripts/server/test-deploy-wesite-app.sh
bash scripts/server/test-rollback-wesite-app.sh
```

Expected: all suites PASS.

- [ ] **Step 6: Commit the installer boundary**

```bash
git add scripts/server/install-wesite-systemd.sh scripts/server/test-install-wesite-systemd.sh \
  deploy/config deploy/sudoers wesite-web/src/main/resources/application-prod.properties.example \
  wesite-admin/src/main/resources/application-prod.properties.example
git commit -m "feat: install source-free deployment assets"
```

### Task 6: Reproducible infrastructure deployment bundle

**Files:**
- Create: `scripts/build-wesite-deployment-bundle.sh`
- Create: `scripts/server/install-wesite-deployment-bundle.sh`
- Create: `scripts/server/test-build-wesite-deployment-bundle.sh`

**Interfaces:**
- Produces: `dist/wesite-deployment-COMMIT.tar.gz`, `dist/wesite-deployment-COMMIT.tar.gz.sha256`, and `dist/install-wesite-deployment-bundle.sh`.
- Bundle root: exactly `wesite-deployment/` with whitelisted `deploy/systemd`, `deploy/config`, `deploy/sudoers`, and production `scripts/server` files plus `SOURCE_COMMIT` and `SHA256SUMS`.

- [ ] **Step 1: Write a failing bundle-content test**

Create a temporary output directory, invoke the builder with source commit `684d9b8`, verify the sidecar using `sha256sum -c`, list the archive, and assert required production files exist. Reject any archive member matching:

```text
.git/
src/
target/
test-
pom.xml
README.md
.java
.jar
```

Invoke the bootstrap installer against a prefixed fake root. Add crafted archives and checksum-manifest fixtures proving checksum mismatch, absolute archive paths, archive `..`, entries outside the single root, absolute checksum paths, checksum `..`, outside-root checksum paths, and duplicates are rejected before the bundled installer mutates the fake host.

- [ ] **Step 2: Run bundle tests and verify RED**

Run: `bash scripts/server/test-build-wesite-deployment-bundle.sh`

Expected: FAIL because the bundle builder does not exist.

- [ ] **Step 3: Implement whitelist-only packaging**

Require an exact commit argument containing only hexadecimal characters and an empty/new output file. Copy only explicit production assets into a temporary `wesite-deployment/` directory; never copy directories recursively from repository root. Write `SOURCE_COMMIT`, generate sorted relative `SHA256SUMS` for every payload except itself, create the tarball, create its checksum sidecar, copy the standalone bootstrap installer beside it, and remove the temporary staging directory through a trap.

Implement the bootstrap so it verifies the sidecar, parses `tar -tzf` output before extraction, requires every member below exactly `wesite-deployment/`, rejects absolute paths and any `..` component, extracts with ownership/permission restoration disabled into a mode `0700` temporary directory, invokes the included `scripts/server/install-wesite-systemd.sh`, and removes only that verified temporary directory through a trap.

- [ ] **Step 4: Run bundle and installer tests**

```bash
bash scripts/server/test-build-wesite-deployment-bundle.sh
bash scripts/server/test-install-wesite-systemd.sh
```

Expected: both suites PASS; archive inspection reports no source, test, Maven, Git, documentation, or JAR member.

- [ ] **Step 5: Commit reproducible packaging**

```bash
git add scripts/build-wesite-deployment-bundle.sh scripts/server/install-wesite-deployment-bundle.sh \
  scripts/server/test-build-wesite-deployment-bundle.sh
git commit -m "feat: package source-free deployment bundle"
```

### Task 7: Two independent Jenkins job definitions and production runbook

**Files:**
- Create: `deploy/jenkins/wesite-web.Jenkinsfile`
- Create: `deploy/jenkins/wesite-admin.Jenkinsfile`
- Modify: `deploy/README.md`
- Modify: `README.md`

**Interfaces:**
- Web job uploads only `wesite-web/target/wesite-web-1.0.0.jar` and calls `deploy-wesite-app web`.
- Admin job uploads only `wesite-admin/target/wesite-admin-1.0.0.jar` and calls `deploy-wesite-app admin`.
- Both use SSH credential `whose-domains-prod-ssh` and file credential `whose-domains-prod-known-hosts` for host `47.76.125.96` as user `wesite-deploy`.

- [ ] **Step 1: Add a failing static Jenkins contract test**

Create `scripts/server/test-jenkins-deployment-contract.sh`. Assert each file contains its own Maven module, artifact path, app argument, `sudo -n`, strict host checking, unique incoming directory, and cleanup. Assert the Web file contains neither the Admin artifact nor `deploy-wesite-app admin`; assert the Admin file contains neither the Web artifact nor `deploy-wesite-app web`. Reject `nohup`, `pkill`, `killall`, `web-watchdog`, `StrictHostKeyChecking=no`, root SSH, paired deployment, and direct `systemctl`.

- [ ] **Step 2: Run the Jenkins contract test and verify RED**

Run: `bash scripts/server/test-jenkins-deployment-contract.sh`

Expected: FAIL because the two Jenkins definitions do not exist.

- [ ] **Step 3: Add the independent Web Pipeline**

Use `disableConcurrentBuilds()`, Maven command:

```bash
mvn -B -pl wesite-web -am clean verify
```

Bind the SSH private key and pinned known-hosts file from Jenkins Credentials. Set version to `${GIT_COMMIT}-${BUILD_NUMBER}`, create `/var/lib/wesite-deploy/incoming/web-${GIT_COMMIT}-${BUILD_NUMBER}` with mode `0700`, upload exactly the Web JAR, run `sudo -n /usr/local/sbin/deploy-wesite-app web`, then `sudo -n /usr/local/sbin/check-wesite-app web`. Preserve the remote exit status while removing only the exact uploaded file and empty job directory with `rm -f` plus `rmdir`.

- [ ] **Step 4: Add the independent Admin Pipeline**

Use the symmetric Maven command:

```bash
mvn -B -pl wesite-admin -am clean verify
```

Upload exactly the Admin JAR into an `admin-${GIT_COMMIT}-${BUILD_NUMBER}` directory and invoke only the Admin deploy/check commands. Use the same pinned host-key and cleanup rules.

- [ ] **Step 5: Rewrite the runbook around the source-free first migration**

Document, in order: Java/unzip/flock preflight, swap verification, upload of the infrastructure archive, sidecar, and bootstrap installer, bootstrap execution, live config migration, `wesite-deploy` SSH key installation, `visudo -cf` and sudoers activation, independent old-JAR baselines, watchdog disablement, first Web/Admin releases, enabling systemd/timer, single/global checks, Jenkins credential creation, manual rollback, journal inspection, and root-only release cleanup. Remove every instruction that uploads a repository checkout or requires paired JAR deployment.

Keep the blog database migration and sanitization maintenance procedure, but state explicitly that it is a coordinated maintenance operation outside both ordinary Jenkins jobs.

- [ ] **Step 6: Run static Jenkins and documentation scans**

```bash
bash scripts/server/test-jenkins-deployment-contract.sh
rg -n 'checkout/export of this entire repository|deploy-wesite-release|/usr/java/current' deploy/README.md README.md
```

Expected: Jenkins contract PASS; the search returns no live instruction using the retired deployment flow. Any historical explanation must clearly say it is retired.

- [ ] **Step 7: Commit Jenkins and operations documentation**

```bash
git add deploy/jenkins deploy/README.md README.md \
  scripts/server/test-jenkins-deployment-contract.sh
git commit -m "docs: define independent Jenkins deployments"
```

### Task 8: Full regression, artifact audit, and final review

**Files:**
- Modify only files required to fix failures found by the complete verification; do not broaden scope.

**Interfaces:**
- Verifies every shell, systemd, packaging, Maven, and documentation contract before merge.

- [ ] **Step 1: Run all server shell tests**

```bash
for test_script in scripts/server/test-*.sh; do
  bash "$test_script"
done
```

Expected: every script exits 0 and prints its PASS summary.

- [ ] **Step 2: Run Bash syntax and systemd verification**

```bash
bash -n scripts/build-wesite-deployment-bundle.sh scripts/server/*.sh
systemd-analyze verify deploy/systemd/wesite-web.service \
  deploy/systemd/wesite-admin.service \
  deploy/systemd/wesite-health-monitor.service \
  deploy/systemd/wesite-health-monitor.timer
```

Expected: both commands exit 0. If `systemd-analyze` is unavailable on the Windows workstation, run it inside the existing Linux Jenkins container and attach that output to the review.

- [ ] **Step 3: Run the complete Maven test suite**

Run: `mvn -B clean test`

Expected: reactor SUCCESS for `wesite-parent`, `wesite-core`, `wesite-web`, and `wesite-admin`, with zero failures and zero errors.

- [ ] **Step 4: Build and inspect the final infrastructure archive**

```bash
COMMIT="$(git rev-parse --short=12 HEAD)"
bash scripts/build-wesite-deployment-bundle.sh "$COMMIT" dist
sha256sum -c "dist/wesite-deployment-${COMMIT}.tar.gz.sha256"
tar -tzf "dist/wesite-deployment-${COMMIT}.tar.gz"
```

Expected: checksum PASS; list contains only the whitelisted infrastructure root and no JAR or source member.

- [ ] **Step 5: Audit spec coverage and repository diff**

```bash
rg -n 'deploy-wesite-release|/usr/java/current|wesite-web/src/main/resources/application-prod.properties.example|wesite-admin/src/main/resources/application-prod.properties.example' \
  deploy scripts README.md
git diff --check
git status --short
```

Expected: only the intentional fail-closed legacy stub/test/documentation mentions remain; `git diff --check` exits 0; unrelated `.claude/` and `sitemap_all.xml` remain untracked and untouched.

- [ ] **Step 6: Review security and failure paths line by line**

Confirm from code and tests that input containment happens before privileged copying, validation happens on the private copy, metadata is never sourced, only selected services restart, rollback uses recorded target health, checksum paths cannot escape, installer never activates services, Jenkins has no rollback/systemctl/root shell permission, and cleanup protects current/previous targets.

- [ ] **Step 7: Commit any verification-only corrections**

If Step 1–6 required tracked corrections, commit exactly those files:

```bash
git add deploy scripts README.md
git commit -m "fix: close independent deployment verification gaps"
```

If no tracked corrections were required, do not create an empty commit.
