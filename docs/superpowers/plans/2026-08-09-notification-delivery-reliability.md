# Notification Delivery Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make expiry publication cursor-safe and email delivery durable, leased, retry-bounded, and atomic per digest user/window.

**Architecture:** Successful monitor snapshots provide the expiry threshold cursor. Email routing persists mode separately from state, and a new delivery-batch row owns one outbound message, its lease, and its total attempt count. Batch claim plus a pending attempt log is one transaction; SMTP runs only after that transaction commits.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis-Plus, MySQL 8, Thymeleaf, JUnit 5, Mockito, Testcontainers.

## Global Constraints

- No new notification channels or preference modes.
- SMTP is at-least-once; maximum total started attempts is exactly three.
- A pending attempt log must exist before SMTP starts.
- Digest uniqueness is `(USER_ID, EMAIL_MODE, WINDOW_KEY)` and all eligible items for that user/window are assigned without a row cap.
- The old direct expiry-mail path remains removed.
- All implementation occurs in the existing isolated worktree and no subagents are used.

---

### Task 1: Cursor-safe expiry thresholds

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorChangeDetector.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorEventPublisher.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/task/DomainWatchTask.java`
- Test: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorChangeDetectorTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorEventPublisherTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/task/DomainWatchTaskEventMigrationTest.java`

**Interfaces:**
- Produces: `detect(MonitorState previous, MonitorState current, Instant previousCheckedAt, Instant currentCheckedAt)`.
- Preserves: `detect(MonitorState previous, MonitorState current)` for existing pure detector callers.

- [ ] **Step 1: Add failing first-baseline and crossing tests**

```java
assertEquals(Set.of("domainExpiry:30", "domainExpiry:7", "domainExpiry:1"),
    fields(detector.detect(previous, current, previousAt, currentAt)));
```

Add a real `MonitorEventPublisher` test using the real detector, null previous snapshot, fixed clock, and 7-day expiry. Assert one `DOMAIN_EXPIRING` event and one dispatched notification. Add a task test where publisher throws and assert `lastCheckTime` is not persisted.

- [ ] **Step 2: Run RED**

Run: `mvn -pl wesite-web -am '-Dtest=MonitorChangeDetectorTest,MonitorEventPublisherTest,DomainWatchTaskEventMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: baseline/crossing assertions fail and the task advances `lastCheckTime` before publisher success.

- [ ] **Step 3: Implement threshold cursor and post-publication watch update**

Use previous successful snapshot `CHECKED_AT` for the prior date. For no baseline, emit only exact 30/7/1 thresholds. For an existing baseline, emit each threshold satisfying `previousDays > threshold && currentDays <= threshold`. Save the watch only after `eventPublisher.publish(...)` returns.

- [ ] **Step 4: Run GREEN**

Run the Task 1 target command and require zero failures.

### Task 2: Persist delivery mode, lease, attempts, and batches

**Files:**
- Create: `wesite-core/src/main/java/info/wesite/core/entity/NotificationDeliveryBatch.java`
- Create: `wesite-core/src/main/java/info/wesite/core/mapper/NotificationDeliveryBatchMapper.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/entity/UserNotification.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/entity/DomainWatchNotifyLog.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/notification/NotificationDispatcher.java`
- Modify: `doc/alter_retention_notification_center.sql`
- Test: `wesite-core/src/test/java/info/wesite/core/entity/NotificationModelContractTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/notification/NotificationDispatcherTest.java`

**Interfaces:**
- Notification state constants: `QUEUED`, `CLAIMED`, `SENT`, `FAILED`, `IN_APP_ONLY`.
- Batch state constants: `CLAIMED`, `SENT`, `FAILED`.
- Attempt log states: `SEND_STATUS_PENDING`, `SEND_STATUS_SUCCESS`, `SEND_STATUS_FAIL`.

- [ ] **Step 1: Add failing model/dispatcher tests**

Assert the dispatcher writes `emailMode`, maps email routes to `QUEUED`, maps in-app-only to `IN_APP_ONLY`, sets attempt count zero, and clears claim/batch fields.

- [ ] **Step 2: Run RED**

Run: `mvn -pl wesite-web -am '-Dtest=NotificationModelContractTest,NotificationDispatcherTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: missing batch and notification delivery fields fail compilation.

- [ ] **Step 3: Add models and migration**

Create `WEB_NOTIFICATION_DELIVERY_BATCH` with unique `(USER_ID, EMAIL_MODE, WINDOW_KEY)` and indexes on `(EMAIL_MODE, STATE, NEXT_ATTEMPT_AT, CLAIM_UNTIL)`. Add notification mode/attempt/claim/batch columns. Add log `BATCH_ID`, pending state, nullable generic watch/event fields, and archive legacy failed rows with no new identity.

- [ ] **Step 4: Run GREEN**

Run the Task 2 target command and require zero failures.

### Task 3: Transactional batch coordinator and lease recovery

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/notification/DeliveryBatchClaim.java`
- Create: `wesite-web/src/main/java/info/wesite/web/notification/DeliveryAttemptDetails.java`
- Create: `wesite-web/src/main/java/info/wesite/web/notification/NotificationDeliveryCoordinator.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/mapper/UserNotificationMapper.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/mapper/DomainWatchNotifyLogMapper.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/mapper/NotificationDeliveryBatchMapper.java`
- Test: `wesite-web/src/test/java/info/wesite/web/notification/NotificationDeliveryCoordinatorTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/notification/NotificationDeliveryCoordinatorMySqlConcurrencyTest.java`

**Interfaces:**
- `Optional<DeliveryBatchClaim> startImmediate(String notificationId, Instant now, Instant leaseUntil)`.
- `Optional<DeliveryBatchClaim> startDigest(String userId, String mode, String windowKey, Instant cutoff, Instant now, Instant leaseUntil)`.
- `Optional<DeliveryBatchClaim> retryNext(String mode, Instant now, Instant leaseUntil)`.
- `DeliveryAttemptDetails` contains recipient, subject, optional notification/event/watch/domain fields, and the final error message.
- `void complete(DeliveryBatchClaim claim, boolean success, DeliveryAttemptDetails details, Instant completedAt, Instant nextAttemptAt)`.
- `void finalizeExpiredExhausted(String mode, Instant now)`.

- [ ] **Step 1: Add failing coordinator tests**

Test log insert false/exception rolls back before returning a claim; dependency completion failure leaves a reclaimable lease; expired attempt one becomes attempt two with a new pending log; expired attempt three becomes terminal failed and cannot return a fourth claim.

- [ ] **Step 2: Add failing real MySQL two-worker digest test**

Run two transactions against the same user/mode/window. Assert one batch row, every notification has that batch ID, and only one worker receives a claim.

- [ ] **Step 3: Run RED**

Run: `mvn -pl wesite-web -am '-Dtest=NotificationDeliveryCoordinatorTest,NotificationDeliveryCoordinatorMySqlConcurrencyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: coordinator and batch SQL do not exist.

- [ ] **Step 4: Implement transactional coordinator**

Create the batch, assign membership, mirror the claim on notifications, and insert the pending log in the same `@Transactional` public method. Retry uses `FOR UPDATE SKIP LOCKED`; expired pending logs are failed before the next attempt is inserted. Completion conditionally matches batch ID and claim token.

- [ ] **Step 5: Run GREEN**

Run the Task 3 target command and require zero failures, including the MySQL concurrency case when Docker is available.

### Task 4: Drive delivery without caps or permanent claims

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/task/NotificationDeliveryTask.java`
- Test: `wesite-web/src/test/java/info/wesite/web/task/NotificationDeliveryTaskTest.java`

**Interfaces:**
- Immediate candidates: repeated bounded ID reads until empty.
- Digest candidates: keyset pages of distinct user IDs; each winning batch owns all eligible items for that user's cutoff.

- [ ] **Step 1: Replace old task tests with failing reliable-delivery tests**

Test: pending log creation occurs before SMTP; coordinator start failure means no SMTP; event/user lookup exception completes failed or leaves only a leased claim; one digest mail contains 501 events; two candidate pages drain without skipping; retry attempt three is sent once and no fourth claim is consumed.

- [ ] **Step 2: Run RED**

Run: `mvn -pl wesite-web -am '-Dtest=NotificationDeliveryTaskTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: the task still uses global 500-row selection and direct log-derived attempts.

- [ ] **Step 3: Implement coordinator-driven task**

Process retryable/expired batches first, then new immediate IDs or digest user pages. Fetch batch members after durable start. Convert every dependency/SMTP result into coordinator completion; never swallow persistence failures. Keep the existing three cron expressions and escaped templates.

- [ ] **Step 4: Run target and full regression**

Run:

```powershell
mvn -pl wesite-web -am '-Dtest=MonitorChangeDetectorTest,MonitorEventPublisherTest,DomainWatchTaskEventMigrationTest,NotificationDispatcherTest,NotificationDeliveryCoordinatorTest,NotificationDeliveryCoordinatorMySqlConcurrencyTest,NotificationDeliveryTaskTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl wesite-web -am test
git diff --check
```

- [ ] **Step 5: Append report and commit**

Append fix-round architecture, migrations, RED/GREEN evidence, MySQL concurrency results, legacy-log policy, and at-least-once SMTP caveat to `.superpowers/sdd/2026-08-09-retention-notification-center/task-6-report.md`.

Commit implementation with:

```powershell
git add doc wesite-core/src/main/java wesite-core/src/test/java wesite-web/src/main/java wesite-web/src/test/java
git commit -m "fix: harden notification delivery reliability"
```
