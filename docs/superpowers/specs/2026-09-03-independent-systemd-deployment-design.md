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
- After validating the staging path, the command copies the input into a root-owned temporary release directory on the application filesystem. ZIP integrity, SHA-256, and application identity are validated on that private copy so the unprivileged staging file cannot be changed between validation and publication. The JAR manifest must contain exactly `Start-Class: info.wesite.web.App` for Web or `Start-Class: info.wesite.admin.App` for Admin. A corrupt archive or a valid JAR for the wrong application is rejected before the final release directory or `current` link is created.
- A release directory is never reused or overwritten. During its one deployment transaction its metadata moves from `deploying` to either `successful` or `failed`; after that terminal status the directory is immutable.
- The command takes the existing global `/run/lock/wesite-deploy.lock`. Independent Jenkins jobs may run at different times, but simultaneous production mutations are rejected.
- After validation, the private JAR and metadata are made read-only and the temporary directory is renamed to the final release directory before the link switch.
- `current` is switched atomically, then only the selected systemd service is restarted.
- Web is verified at `http://127.0.0.1:8080/api/readyz`; Admin is verified at `http://127.0.0.1:8082/api/readyz`. The existing strict JSON `UP` parser remains authoritative.
- A failed restart or readiness check restores only that application's previous `current` target and restarts only that service. With no prior target, the selected service is stopped and its `current` link is removed.
- `previous` is updated only after the new application version passes readiness.
- Interruptions after the link switch use the same rollback path.

Every release records `APP`, `VERSION`, `SHA256`, `DEPLOYED_AT`, `STATUS`, `HEALTH_RESPONSE_MODE`, and `HEALTH_URL` next to its JAR. New releases always record strict mode and the selected application's `/api/readyz` URL. A legacy baseline records its explicitly supplied legacy mode and URL. Automatic and manual rollback read the target release's recorded health contract, so returning to an old Web baseline that predates `/api/readyz` is verifiable without weakening checks for new releases.

The installed `rollback-wesite-app APP` command provides operator-initiated rollback after a release passed readiness but later proved faulty. It takes the same global lock, validates that `previous` resolves to a terminal successful release in the selected application tree, atomically switches `current`, restarts and checks only that service using the target's recorded health contract, and then points `previous` to the version rolled back from. If the rollback target fails its check, the command restores the original `current`, restarts it, leaves `previous` unchanged, and exits non-zero.

The health monitor continues to share the global deployment lock so it cannot restart either application during a release.

`check-wesite-app APP` checks only the selected service and its current release's recorded readiness contract. An application Jenkins job uses this command and never uses the global two-service check as its release result. The global `check-wesite-services` command remains available for operator-wide status checks.

## Deployment bundle

Infrastructure installation is separate from routine application releases. A packaging command produces a deployment archive that contains only:

- systemd unit and timer files;
- server-side deployment, health, monitoring, swap, and installation scripts;
- Web and Admin production property templates;
- a `SOURCE_COMMIT` file and `SHA256SUMS` covering every packaged payload file except `SHA256SUMS` itself.

The archive excludes Java source, tests, Git metadata, Maven project files, documentation, and generated application JARs. The two Jenkins application jobs do not upload this infrastructure archive during a normal release; each uploads only its own generated JAR and invokes the already installed `deploy-wesite-app` command.

The archive has one top-level `wesite-deployment/` directory and a versioned `.tar.gz` filename, accompanied by a checksum sidecar for the archive. The runbook verifies the sidecar and rejects absolute paths, parent traversal, and entries outside the single top-level directory before extraction. After safe extraction, the included installer validates the checksum manifest before invoking `sha256sum`: every entry must be unique, relative, free of `..`, and resolve inside the extracted deployment root. It then verifies every `SHA256SUMS` entry before changing the host. It reads templates from this deployment directory rather than from `wesite-web/src` or `wesite-admin/src`, remains repeatable, preserves live production configuration, installs public assets with fixed modes, reloads systemd, and does not enable or start applications.

The infrastructure archive is installed manually during the initial migration or an explicit infrastructure maintenance operation. Routine application jobs never reinstall systemd units or operational scripts.

## Jenkins jobs

The Web job builds the Web module and required Maven dependencies, uploads only `wesite-web-1.0.0.jar`, and calls:

```text
deploy-wesite-app web <git-commit>-<build-number> <uploaded-web-jar>
```

The Admin job performs the equivalent flow for `wesite-admin-1.0.0.jar` and calls `deploy-wesite-app admin ...`. Neither job starts Java directly, invokes the legacy watchdog, overwrites a live JAR, restarts the other service, nor deploys the other application's artifact.

Application-level Jenkins concurrency settings are retained, while the server lock protects against cross-job overlap. A job removes its job-specific incoming directory after the remote deployment command returns, regardless of success or failure. Database migrations and infrastructure changes remain explicit maintenance operations rather than side effects of an application release.

Jenkins connects as a dedicated `wesite-deploy` account, not as root. The installer creates its private group, `/var/lib/wesite-deploy` home, `/bin/bash` login shell, and `/var/lib/wesite-deploy/incoming` staging directory, but does not generate credentials or change `sshd_config`. The operator installs one Jenkins-managed public key into a mode `0700` `.ssh` directory and mode `0600` `authorized_keys`, both owned by `wesite-deploy`, and verifies key-only login under the host's existing SSH policy. Jenkins pins the production host key and must not disable strict host-key checking.

Each Jenkins job creates a mode `0700` directory below `/var/lib/wesite-deploy/incoming`; the privileged deployment command rejects inputs resolved outside that tree. A checked-in sudoers template grants passwordless root execution only for `deploy-wesite-app web ...`, `deploy-wesite-app admin ...`, `check-wesite-app web`, and `check-wesite-app admin`. The operator installs it under `/etc/sudoers.d` only after `visudo -cf` succeeds. The account has no general `systemctl`, root-shell, configuration, rollback, pruning, or infrastructure-install sudo permission. Production secrets are never Jenkins parameters or build artifacts.

The application trees and release metadata are owned by `root:wesite`; directories use mode `0750` and terminal JAR/metadata files use read-only modes. Only the privileged commands mutate links or release contents. The `wesite` runtime account can read its JAR and configuration but cannot publish a release, while `wesite-deploy` can stage files but cannot mutate release trees without the validated sudo command.

## Migration and compatibility

The one-time systemd migration installs the new independent layout before Jenkins takes ownership. The legacy cron watchdog is disabled only after configuration, artifacts, backups, and service units are ready. Existing public `/domain/*` and `/blog/*` URL shapes are unaffected.

Each rollback baseline is established independently. The operator first records the exact source path and SHA-256, then copies the verified old JAR without modification into a root-created mode `0700` baseline directory below `/var/lib/wesite-deploy/incoming`; the same path and identity validation used by Jenkins releases therefore still applies. For an old Web binary that predates `/api/readyz`, the operator explicitly runs `deploy-wesite-app web` with `WESITE_HEALTH_RESPONSE_MODE=legacy-http-200` and the old homepage URL, then runs the same legacy-mode single-application check. A verified old Admin binary uses the equivalent Admin command and URL. If no verified old Admin binary exists, the operator does not invent one: configuration and filesystem backups are taken, Admin is recorded as having no binary rollback target, and a failed first Admin release stops only Admin. Strict readiness is mandatory for every new release.

The old two-application `deploy-wesite-release` command is retired and replaced by `deploy-wesite-app`. The installer replaces any installed legacy command with a fail-closed compatibility stub that explains the two new commands and exits with status 64; this makes a forgotten old Jenkins job fail visibly instead of performing paired deployment. Existing shared release links are not silently reused because their paired-release semantics conflict with independent deployment.

Independent deployment permits mixed Web/Admin commits, so shared contracts must remain compatible during the whole rollout window. Database changes follow expand, migrate, then contract: either application version must work after expansion, data migration is a separate maintenance operation, and destructive contraction occurs only after both applications have moved past the old contract. JWT claims, Redis key/value formats, internal blog APIs, and other shared payloads must be backward- and forward-compatible across the supported adjacent versions. A change that cannot meet this rule requires an explicit maintenance-window runbook and cannot be released by either ordinary application job.

The monitor no longer depends on the old shared `/usr/java/current`. It evaluates Web and Admin independently: if an application's `current` link does not exist, that application is reported as not deployed and skipped without incrementing failure state or attempting a restart. Once the link exists, existing failure-threshold behavior applies. An intentional stop still requires stopping the timer as documented in the maintenance runbook.

Release pruning is not part of the deployment transaction. After the observation window, an explicit root-only operator cleanup may retain `current`, `previous`, and the three most recent additional successful releases for each application. Terminal release metadata distinguishes successful and failed candidates. Cleanup must resolve and protect both link targets, operate on one fixed application tree, print its exact candidates before deletion, and refuse unchecked, empty, root, shared, non-terminal, or metadata-inconsistent release paths. Failed releases remain available for diagnosis until this explicit cleanup; Jenkins cannot prune them.

## Verification

- Shell tests must prove independent Web success, independent Admin success, per-application restart behavior, health rollback, first-release failure, version validation, invalid APP rejection, corrupt JAR rejection, wrong-application JAR rejection, staging-path and symlink rejection, contention, signal rollback, release metadata transitions, legacy-contract rollback, and preservation of the other application's links and service state.
- Manual rollback tests must prove successful per-application rollback, roll-forward link preservation, rejection of missing or invalid previous targets, and restoration of the original version when the rollback target is unhealthy.
- Single-application check tests must prove that Web checks never depend on Admin and Admin checks never depend on Web. Monitor tests must prove that an application without `current` is skipped without a restart or accumulated failure.
- Installer tests must prove the independent directory layout and ownership, updated unit paths, installed commands, fail-closed legacy stub, deployment-user and sudoers templates, configuration preservation, and no automatic activation.
- Bundle tests must inspect the produced archive, verify the sidecar and payload checksums, exercise unsafe archive-entry and unsafe checksum-entry rejection, and prove that every required deployment asset is present and source/tests/Git/Maven files and application JARs are absent.
- Existing readiness, monitor, swap, installer, and full Maven tests must remain green. Shell syntax validation and `git diff --check` are required before commit.
