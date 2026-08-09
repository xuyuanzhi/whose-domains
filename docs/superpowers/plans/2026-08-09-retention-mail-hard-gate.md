# Retention Mail Hard Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require both the matching notification-delivery flag and `wesite.mail.enabled=true` before Spring registers either scheduled delivery job.

**Architecture:** Keep the existing profile restriction and use one all-match `@ConditionalOnProperty` per job with `prefix = "wesite"`. Prove the condition at the application-context boundary so a disabled combination never creates a scheduler bean or calls `NotificationDeliveryTask`; then encode the same contract in rollout documentation and the static verifier.

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5, AssertJ, Mockito, PowerShell.

## Global Constraints

- Work only in `C:\Users\Yuz\git\whose-domains\.worktrees\retention-notification-center`.
- Never access production, send real email, or operate on a non-disposable database/container.
- Both delivery flags remain false by default.
- A false or missing mail flag must prevent Bean registration before any queue claim or consumption.
- The recommended rollout starts with both delivery flags false even though the mail flag is also a hard gate.

---

### Task 1: Prove the mail gate at Bean registration

**Files:**
- Test: `wesite-web/src/test/java/info/wesite/web/task/NotificationDeliveryJobRegistrationTest.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/task/ImmediateNotificationDeliveryJob.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/task/DigestNotificationDeliveryJob.java`

**Interfaces:**
- Consumes: `wesite.notification-delivery.immediate-enabled`, `wesite.notification-delivery.digest-enabled`, `wesite.mail.enabled`.
- Produces: zero delivery-job beans unless the matching delivery flag and mail flag are both true.

- [ ] **Step 1: Write the failing combination test**

Add a context case with both delivery flags true and `wesite.mail.enabled=false`; assert neither job Bean exists and `verifyNoInteractions(deliveryTask)`. Update each enabled case to set its matching flag plus `wesite.mail.enabled=true`.

- [ ] **Step 2: Run the focused test and require RED**

Run:

```powershell
mvn -pl wesite-web -am "-Dtest=NotificationDeliveryJobRegistrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL because the existing jobs ignore `wesite.mail.enabled` and are registered in the new disabled-mail case.

- [ ] **Step 3: Implement one all-match condition per job**

Use the following immediate condition, with `digest-enabled` substituted in the digest job:

```java
@ConditionalOnProperty(
    prefix = "wesite",
    name = {"notification-delivery.immediate-enabled", "mail.enabled"},
    havingValue = "true",
    matchIfMissing = false)
```

- [ ] **Step 4: Run the focused test and require GREEN**

Run the Step 2 command again and require exit 0, including zero task interactions in all disabled contexts.

### Task 2: Publish and statically verify the hard-gate contract

**Files:**
- Modify: `README.md`
- Modify: `scripts/verify-retention-rollout.ps1`
- Modify: `.superpowers/sdd/2026-08-09-retention-notification-center/task-12-report.md` (ignored evidence report)

**Interfaces:**
- Consumes: the registration properties from Task 1.
- Produces: an operator rollout sequence and an executable source-contract check for the two-property condition.

- [ ] **Step 1: Update rollout documentation**

State that `wesite.mail.enabled=false` removes both delivery jobs at registration time, but operators should first set both delivery flags false. Preserve the order: validate in-app behavior, enable mail plus immediate delivery, then enable digest delivery.

- [ ] **Step 2: Extend static verification**

Require both job source files to contain `prefix = "wesite"`, their matching delivery property name, and `"mail.enabled"`, while retaining the existing legacy-sender and migration-order assertions.

- [ ] **Step 3: Clean and append the report**

Replace the stale top-level claim that no source or commits changed with a cumulative Task 12 result. Record round2 RED/GREEN evidence, final commands, exits, test counts, container cleanup, and all external steps not run.

### Task 3: Fresh verification and commit

**Files:**
- Verify all files from Tasks 1 and 2.

**Interfaces:**
- Produces: reproducible local evidence and one scoped round2 commit.

- [ ] **Step 1: Run required verification**

Run the focused test, `mvn test`, verifier `-Fixture Static`, and verifier `-Fixture All`. Require exit 0; Path A and Path B must use disposable containers and clean up in `finally`.

- [ ] **Step 2: Inspect the patch**

Run `git diff --check`, inspect `git diff`, and confirm `git status --short` contains only expected round2 files. Confirm the legacy `sendExpiryNotifications` symbol remains absent.

- [ ] **Step 3: Commit**

Stage only the scoped source, tests, README, verifier, design, and plan files, then commit:

```powershell
git commit -m "fix: require mail before delivery jobs"
```
