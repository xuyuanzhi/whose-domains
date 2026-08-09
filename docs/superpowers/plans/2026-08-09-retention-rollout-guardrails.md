# Retention Rollout Guardrails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add safe, independently enabled notification delivery schedules and a reproducible local verifier for both retention migration paths.

**Architecture:** Keep delivery business logic in `NotificationDeliveryTask`, move Spring schedules into two conditional wrapper beans, and make absent properties disable registration. Add one PowerShell verifier that performs static checks plus isolated MySQL Path A/B fixtures with guaranteed cleanup.

**Tech Stack:** Java 17, Spring Boot 3.5 conditional configuration, JUnit 5, AssertJ, Mockito, PowerShell, Docker, MySQL 8.4.

## Global Constraints

- Work only in `C:\Users\Yuz\git\whose-domains\.worktrees\retention-notification-center`.
- Never access production, send real email, or operate on an existing database/container.
- Both delivery flags default to false; disabled workers must not claim or mutate queues.
- Every Docker fixture uses a unique name, read-only repository mount, `--rm`, and `finally` cleanup.
- Use TDD for every Java behavior and the script contract.

---

### Task 1: Make disabled SMTP explicit

**Files:**
- Create: `wesite-core/src/test/java/info/wesite/core/mail/SmtpMailSenderTest.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/mail/SmtpMailSender.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/mail/MailProperties.java`

**Interfaces:**
- Consumes: `MailProperties.enabled`, `MailSender.send(Mail)`.
- Produces: disabled send result `MailSendResult.fail("mail sending is disabled")` without touching `JavaMailSender`.

- [ ] **Step 1: Write the failing test**

Create a valid `Mail`, inject disabled `MailProperties` and a mock `JavaMailSender` with `ReflectionTestUtils`, then assert `result.isSuccess()` is false, the error is `mail sending is disabled`, and the Java sender has zero interactions.

- [ ] **Step 2: Run test to verify RED**

Run: `mvn -pl wesite-core -Dtest=SmtpMailSenderTest test`

Expected: FAIL because the current disabled branch returns success.

- [ ] **Step 3: Implement the minimal behavior**

Replace the disabled branch with:

```java
log.warn("[mail] disabled, reject sending to={}", mail.getTo());
return MailSendResult.fail("mail sending is disabled");
```

Update the `MailProperties.enabled` comment to describe failure/unavailability rather than successful no-send.

- [ ] **Step 4: Verify GREEN**

Run the same focused Maven command and require exit 0.

---

### Task 2: Split and condition the delivery schedules

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/task/ImmediateNotificationDeliveryJob.java`
- Create: `wesite-web/src/main/java/info/wesite/web/task/DigestNotificationDeliveryJob.java`
- Create: `wesite-web/src/test/java/info/wesite/web/task/NotificationDeliveryJobRegistrationTest.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/task/NotificationDeliveryTask.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/task/NotificationDeliveryTaskTest.java`

**Interfaces:**
- Consumes: `NotificationDeliveryTask.deliverImmediate()`, `deliverDailyDigest()`, `deliverWeeklyDigest()`.
- Produces: `ImmediateNotificationDeliveryJob.run()` and `DigestNotificationDeliveryJob.runDaily()/runWeekly()`.
- Properties: `wesite.notification-delivery.immediate-enabled`, `wesite.notification-delivery.digest-enabled`.

- [ ] **Step 1: Write failing registration tests**

Use `ApplicationContextRunner` with active `prod`, a mock `NotificationDeliveryTask`, and the two intended job classes. Assert:

```java
// no properties and explicit false
context.doesNotHaveBean(ImmediateNotificationDeliveryJob.class);
context.doesNotHaveBean(DigestNotificationDeliveryJob.class);
verifyNoInteractions(deliveryTask);

// immediate only
context.hasSingleBean(ImmediateNotificationDeliveryJob.class);
context.doesNotHaveBean(DigestNotificationDeliveryJob.class);

// digest only
context.doesNotHaveBean(ImmediateNotificationDeliveryJob.class);
context.hasSingleBean(DigestNotificationDeliveryJob.class);
```

Invoke enabled wrapper methods and verify only the matching service delegates. Assert exact cron annotations on wrapper methods.

- [ ] **Step 2: Run test to verify RED**

Run: `mvn -pl wesite-web -am "-Dtest=NotificationDeliveryJobRegistrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: test compilation fails because the intended wrapper classes do not exist.

- [ ] **Step 3: Implement conditional wrappers**

Annotate each wrapper with `@Component`, `@Profile({"prod", "mac"})`, and its own `@ConditionalOnProperty(... havingValue = "true", matchIfMissing = false)`. Put the existing immediate cron on `run()`, and daily/weekly crons on the digest wrapper. Remove `@Scheduled` and `@Profile` scheduling responsibility from `NotificationDeliveryTask`; keep it as the injected service.

- [ ] **Step 4: Move cron assertions and verify GREEN**

Remove the obsolete schedule-reflection test from `NotificationDeliveryTaskTest`; the new context test owns registration and cron contracts. Run both job registration and task tests and require exit 0.

---

### Task 3: Add safe configuration and rollout documentation

**Files:**
- Modify: `wesite-web/src/main/resources/application.properties`
- Modify: `wesite-web/src/main/resources/application-prod.properties.example`
- Modify: `README.md`

**Interfaces:**
- Consumes: environment variables `WESITE_NOTIFICATION_DELIVERY_IMMEDIATE_ENABLED` and `WESITE_NOTIFICATION_DELIVERY_DIGEST_ENABLED`.
- Produces: explicit false defaults and phased operator instructions.

- [ ] **Step 1: Add safe defaults and rewrite rollout**

Add both properties to common and prod example configuration. Add both environment variables to the README configuration table. Replace the `wesite.mail.enabled=false` dry-run guidance with the exact order: both delivery flags false for in-app verification, SMTP enabled/configured, immediate true, then digest true. State that `wesite.mail.enabled=false` is unavailable/failure and never a job switch.

- [ ] **Step 2: Inspect the effective contract**

Read the modified property files and rollout section together. Confirm the two names match the conditional beans exactly, both environment-backed defaults are false, and the rollout order is all delivery false → immediate true → digest true. The executable static verifier in Task 4 turns this inspection into a repeatable check.

---

### Task 4: Add the repeatable migration/rollout verifier

**Files:**
- Create: `scripts/verify-retention-rollout.ps1`
- Create: `wesite-web/src/test/java/info/wesite/web/task/RetentionRolloutVerifierScriptTest.java`

**Interfaces:**
- Command: `powershell.exe -NoProfile -File scripts/verify-retention-rollout.ps1 -Fixture All`.
- Parameters: `-Fixture Static|PathA|PathB|All`, `-DockerImage`, `-StartupTimeoutSeconds`.
- Output: static PASS, one PASS/cleanup record per selected path, final PASS; nonzero exit on any contract or cleanup failure.

- [ ] **Step 1: Write failing script behavior test**

Launch `powershell.exe -NoProfile -File ../scripts/verify-retention-rollout.ps1 -Fixture Static` with `ProcessBuilder`, capture merged output, and assert exit 0 plus the literal terminal marker `RETENTION_ROLLOUT_VERIFY|PASS|FIXTURE=Static`. This exercises the script as a real command instead of asserting on its source text.

- [ ] **Step 2: Run test to verify RED**

Expected: FAIL because PowerShell cannot find the script and returns nonzero.

- [ ] **Step 3: Implement static verification and fixtures**

Implement repository-root resolution from `$PSScriptRoot`; static assertions for create/legacy/baseline/increment contracts, README order, safe property defaults, conditional job declarations, and legacy source absence. For each fixture, start a unique MySQL container with repository mounted read-only, wait for `MySQL init process done` plus authenticated query, execute the selected SQL sequence, query four retention tables/four canonical columns/four audit columns, then stop in `finally` and assert no matching container remains.

For Path B, execute `create.sql`, drop exactly `WEB_DOMAIN_WATCH` and `WEB_DOMAIN_SNAPSHOT`, execute `alter_domain_watch_snapshot.sql`, then the retention baseline.

- [ ] **Step 4: Verify GREEN and both paths**

Run the Java contract test, then run the PowerShell command with `-Fixture All`. Require Path A and Path B exit 0 and zero leftover verifier containers.

---

### Task 5: Full verification, report, and commit

**Files:**
- Modify: `.superpowers/sdd/2026-08-09-retention-notification-center/task-12-report.md` (ignored task report)

**Interfaces:**
- Produces: fresh verification evidence and a committed review fix.

- [ ] **Step 1: Run all required commands**

Run `mvn test`, the exact standalone JavaScript loop from the Task 12 brief, `mvn clean package -DskipTests`, and `powershell.exe -NoProfile -File scripts/verify-retention-rollout.ps1 -Fixture All`.

- [ ] **Step 2: Inspect repository state**

Run `git diff --check`, `git diff --name-only`, and `git status --short`. Confirm no temporary container remains and no production URL was invoked.

- [ ] **Step 3: Append the report**

Record RED/GREEN evidence, final commands, exits, counts, both migration fixture results, cleanup results, and unchanged external production steps.

- [ ] **Step 4: Commit**

Stage only the review-fix source/tests/config/docs/script files and commit with `fix: gate notification delivery rollout`.
