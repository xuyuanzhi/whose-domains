# Independent Systemd Deployment Design

## Goal

Allow the public Web application and the Admin application to be built and deployed by two independent Jenkins jobs. Each job uploads exactly one application JAR, restarts only its own systemd service, verifies that service, and rolls back only that application when verification fails. Production receives no source tree.

## Production layout

Each application owns an independent immutable release tree:

```text
/usr/java/apps/web/
├── current -> releases/<version>
├── previous -> releases/<previous-version>
└── releases/<version>/wesite-web.jar

/usr/java/apps/admin/
├── current -> releases/<version>
├── previous -> releases/<previous-version>
└── releases/<version>/wesite-admin.jar
```

The systemd units read their JAR only through their application's `current` link. Production configuration remains outside the release trees under `/etc/wesite` and `/usr/java/config`. Installed operational scripts remain under `/usr/local/sbin`.

## Deployment contract

The installed command is:

```text
deploy-wesite-app APP VERSION JAR
```

- `APP` is exactly `web` or `admin`.
- `VERSION` contains only letters, numbers, dot, underscore, and hyphen.
- The input must resolve inside `/var/lib/wesite-deploy/incoming`, be a readable regular file, and not be a symbolic link. Jenkins uploads to a mode `0700` job-specific directory owned by the deployment user.
- Archive validation must prove both ZIP integrity and application identity before any release directory is created. The JAR manifest must contain exactly `Start-Class: info.wesite.web.App` for Web or `Start-Class: info.wesite.admin.App` for Admin. A valid JAR for the wrong application is rejected.
- A release directory is immutable and an existing version is never overwritten.
- The command takes the existing global `/run/lock/wesite-deploy.lock`. Independent Jenkins jobs may run at different times, but simultaneous production mutations are rejected.
- The JAR is installed into a temporary directory on the same filesystem, made read-only, and renamed to the final release directory before the link switch.
- `current` is switched atomically, then only the selected systemd service is restarted.
- Web is verified at `http://127.0.0.1:8080/api/readyz`; Admin is verified at `http://127.0.0.1:8082/api/readyz`. The existing strict JSON `UP` parser remains authoritative.
- A failed restart or readiness check restores only that application's previous `current` target and restarts only that service. With no prior target, the selected service is stopped and its `current` link is removed.
- `previous` is updated only after the new application version passes readiness.
- Interruptions after the link switch use the same rollback path.

The health monitor continues to share the global deployment lock so it cannot restart either application during a release.

`check-wesite-app APP` checks only the selected service and readiness URL. An application Jenkins job uses this command and never uses the global two-service check as its release result. The global `check-wesite-services` command remains available for operator-wide status checks.

## Deployment bundle

Infrastructure installation is separate from routine application releases. A packaging command produces a deployment archive that contains only:

- systemd unit and timer files;
- server-side deployment, health, monitoring, swap, and installation scripts;
- Web and Admin production property templates;
- a `SOURCE_COMMIT` file and `SHA256SUMS` covering every packaged payload file except `SHA256SUMS` itself.

The archive excludes Java source, tests, Git metadata, Maven project files, documentation, and generated application JARs. The two Jenkins application jobs do not upload this infrastructure archive during a normal release; each uploads only its own generated JAR and invokes the already installed `deploy-wesite-app` command.

The archive has one top-level `wesite-deployment/` directory and a versioned `.tar.gz` filename, accompanied by a checksum sidecar for the archive. The runbook verifies the sidecar and rejects absolute paths, parent traversal, and entries outside the single top-level directory before extraction. After safe extraction, the included installer verifies every `SHA256SUMS` entry before changing the host. It reads templates from this deployment directory rather than from `wesite-web/src` or `wesite-admin/src`, remains repeatable, preserves live production configuration, installs public assets with fixed modes, reloads systemd, and does not enable or start applications.

The infrastructure archive is installed manually during the initial migration or an explicit infrastructure maintenance operation. Routine application jobs never reinstall systemd units or operational scripts.

## Jenkins jobs

The Web job builds the Web module and required Maven dependencies, uploads only `wesite-web-1.0.0.jar`, and calls:

```text
deploy-wesite-app web <git-commit>-<build-number> <uploaded-web-jar>
```

The Admin job performs the equivalent flow for `wesite-admin-1.0.0.jar` and calls `deploy-wesite-app admin ...`. Neither job starts Java directly, invokes the legacy watchdog, overwrites a live JAR, restarts the other service, nor deploys the other application's artifact.

Application-level Jenkins concurrency settings are retained, while the server lock protects against cross-job overlap. A job removes its job-specific incoming directory after the remote deployment command returns, regardless of success or failure. Database migrations and infrastructure changes remain explicit maintenance operations rather than side effects of an application release.

Jenkins connects as a dedicated `wesite-deploy` account, not as root. Its SSH client uses a pinned production host key and must not disable strict host-key checking. Its designated persistent staging area is `/var/lib/wesite-deploy/incoming`; the privileged deployment command rejects inputs resolved outside that tree. The account receives passwordless sudo permission only for the exact `deploy-wesite-app` and `check-wesite-app` commands and has no general `systemctl`, root-shell, configuration, or infrastructure-install sudo permission. Production secrets are never Jenkins parameters or build artifacts.

## Migration and compatibility

The one-time systemd migration installs the new independent layout before Jenkins takes ownership. The legacy cron watchdog is disabled only after configuration, artifacts, backups, and service units are ready. Existing public `/domain/*` and `/blog/*` URL shapes are unaffected.

Each rollback baseline is established independently. For an old Web binary that predates `/api/readyz`, the operator may explicitly run `deploy-wesite-app web` with `WESITE_HEALTH_RESPONSE_MODE=legacy-http-200` and the old homepage URL, then run the same legacy-mode single-application check. A verified old Admin binary uses the equivalent Admin command and URL. The exact source path and SHA-256 of every baseline JAR are recorded before deployment. If no verified old Admin binary exists, the operator does not invent one: configuration and filesystem backups are taken, Admin is recorded as having no binary rollback target, and a failed first Admin release stops only Admin. Strict readiness is mandatory for every new release.

The old two-application `deploy-wesite-release` command is retired and replaced by `deploy-wesite-app`. The installer replaces any installed legacy command with a fail-closed compatibility stub that explains the two new commands and exits with status 64; this makes a forgotten old Jenkins job fail visibly instead of performing paired deployment. Existing shared release links are not silently reused because their paired-release semantics conflict with independent deployment.

Independent deployment permits mixed Web/Admin commits, so shared contracts must remain compatible during the whole rollout window. Database changes follow expand, migrate, then contract: either application version must work after expansion, data migration is a separate maintenance operation, and destructive contraction occurs only after both applications have moved past the old contract. JWT claims, Redis key/value formats, internal blog APIs, and other shared payloads must be backward- and forward-compatible across the supported adjacent versions. A change that cannot meet this rule requires an explicit maintenance-window runbook and cannot be released by either ordinary application job.

The monitor no longer depends on the old shared `/usr/java/current`. It evaluates Web and Admin independently: if an application's `current` link does not exist, that application is reported as not deployed and skipped without incrementing failure state or attempting a restart. Once the link exists, existing failure-threshold behavior applies. An intentional stop still requires stopping the timer as documented in the maintenance runbook.

Release pruning is not part of the deployment transaction. After the observation window, an explicit operator cleanup may retain `current`, `previous`, and the three most recent additional successful releases for each application. Cleanup must resolve and protect both link targets, operate on one fixed application tree, print its exact candidates before deletion, and refuse unchecked, empty, root, or shared release paths.

## Verification

- Shell tests must prove independent Web success, independent Admin success, per-application restart behavior, health rollback, first-release failure, version validation, invalid APP rejection, corrupt JAR rejection, wrong-application JAR rejection, staging-path and symlink rejection, contention, signal rollback, and preservation of the other application's links and service state.
- Single-application check tests must prove that Web checks never depend on Admin and Admin checks never depend on Web. Monitor tests must prove that an application without `current` is skipped without a restart or accumulated failure.
- Installer tests must prove the independent directory layout, updated unit paths, installed commands, fail-closed legacy stub, deployment-user permissions, configuration preservation, and no automatic activation.
- Bundle tests must inspect the produced archive, verify checksums, exercise unsafe-entry rejection, and prove that every required deployment asset is present and source/tests/Git/Maven files and application JARs are absent.
- Existing readiness, monitor, swap, installer, and full Maven tests must remain green. Shell syntax validation and `git diff --check` are required before commit.
