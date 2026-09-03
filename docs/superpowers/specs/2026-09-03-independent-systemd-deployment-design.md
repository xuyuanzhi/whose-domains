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
- The input must be a readable regular file and a valid JAR/ZIP archive.
- A release directory is immutable and an existing version is never overwritten.
- The command takes the existing global `/run/lock/wesite-deploy.lock`. Independent Jenkins jobs may run at different times, but simultaneous production mutations are rejected.
- The JAR is installed into a temporary directory on the same filesystem, made read-only, and renamed to the final release directory before the link switch.
- `current` is switched atomically, then only the selected systemd service is restarted.
- Web is verified at `http://127.0.0.1:8080/api/readyz`; Admin is verified at `http://127.0.0.1:8082/api/readyz`. The existing strict JSON `UP` parser remains authoritative.
- A failed restart or readiness check restores only that application's previous `current` target and restarts only that service. With no prior target, the selected service is stopped and its `current` link is removed.
- `previous` is updated only after the new application version passes readiness.
- Interruptions after the link switch use the same rollback path.

The health monitor continues to share the global deployment lock so it cannot restart either application during a release.

## Deployment bundle

Infrastructure installation is separate from routine application releases. A packaging command produces a deployment archive that contains only:

- systemd unit and timer files;
- server-side deployment, health, monitoring, swap, and installation scripts;
- Web and Admin production property templates;
- a manifest with the source commit and file checksums.

The archive excludes Java source, tests, Git metadata, Maven project files, documentation, and generated application JARs. The two Jenkins application jobs do not upload this infrastructure archive during a normal release; each uploads only its own generated JAR and invokes the already installed `deploy-wesite-app` command.

The installer reads templates from the deployment archive rather than from `wesite-web/src` or `wesite-admin/src`. It remains repeatable, preserves live production configuration, installs public assets with fixed modes, reloads systemd, and does not enable or start applications.

## Jenkins jobs

The Web job builds the Web module and required Maven dependencies, uploads only `wesite-web-1.0.0.jar`, and calls:

```text
deploy-wesite-app web <git-commit>-<build-number> <uploaded-web-jar>
```

The Admin job performs the equivalent flow for `wesite-admin-1.0.0.jar` and calls `deploy-wesite-app admin ...`. Neither job starts Java directly, invokes the legacy watchdog, overwrites a live JAR, restarts the other service, nor deploys the other application's artifact.

Application-level Jenkins concurrency settings are retained, while the server lock protects against cross-job overlap. Database migrations and infrastructure changes remain explicit maintenance operations rather than side effects of an application release.

## Migration and compatibility

The one-time systemd migration installs the new independent layout before Jenkins takes ownership. The legacy cron watchdog is disabled only after configuration, artifacts, backups, and service units are ready. Existing public `/domain/*` and `/blog/*` URL shapes are unaffected.

The old two-application `deploy-wesite-release` command is removed from the installed interface and replaced by `deploy-wesite-app`. Existing shared release links are not silently reused because their paired-release semantics conflict with independent deployment. Operators must establish each application's first baseline explicitly or record that its first independent deployment has no automatic binary rollback target.

## Verification

- Shell tests must prove independent Web success, independent Admin success, per-application restart behavior, health rollback, first-release failure, version validation, invalid APP rejection, invalid JAR rejection, contention, signal rollback, and preservation of the other application's links and service state.
- Installer tests must prove the independent directory layout, updated unit paths, installed command, configuration preservation, and no automatic activation.
- Bundle tests must inspect the produced archive and prove that every required deployment asset is present and source/tests/Git/Maven files and application JARs are absent.
- Existing readiness, monitor, swap, installer, and full Maven tests must remain green. Shell syntax validation and `git diff --check` are required before commit.
