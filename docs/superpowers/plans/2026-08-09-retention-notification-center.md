# Retention Notification Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a unified monitoring event and notification center that turns domain-watch changes into in-app notifications and configurable immediate or digest email recall.

**Architecture:** Domain checks produce normalized snapshots, a detector compares the latest successful states and creates idempotent monitoring events, and a dispatcher always creates an in-app notification before applying the user's email preference. Existing domain-expiry mail is migrated onto this event pipeline so one fact has one event and cannot be mailed twice.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis-Plus, MySQL 8, Thymeleaf, vanilla JavaScript, JUnit 5, Mockito, MockMvc.

## Global Constraints

- Preserve the existing login, domain-watch, mail sender, domain-detail and `ResponseJson` conventions.
- First release supports domain expiry, SSL expiry, domain status changes, NS/A/AAAA/MX changes, website down and website recovered.
- A failed check never replaces the last successful snapshot and never masquerades as a state change.
- Website-down requires two consecutive failed checks; persistent failures notify only on confirmation, escalation or recovery.
- Every confirmed event creates an in-app notification; email delivery failure must not roll it back.
- Email modes are `IMMEDIATE`, `DAILY`, `WEEKLY`, and `IN_APP_ONLY`; high-risk events default to immediate email and ordinary changes default to daily digest.
- The first release adds no new third-party notification platform, payment feature, team collaboration, browser push or webhook.
- Do not include unrelated dirty-worktree changes in feature commits.

---

## File Map

New core files own persistence only: `MonitorSnapshot`, `MonitorEvent`, `UserNotification`, and `NotificationPreference` entities plus their mapper/service pairs. New web-domain files own normalization, comparison and dispatch: `MonitorState`, `MonitorEventDraft`, `MonitorChangeDetector`, `MonitorEventPublisher`, and `NotificationDispatcher`. Controllers expose only the current user's notifications and preferences. `DomainWatchTask` becomes orchestration rather than containing comparison and email-policy rules.

### Task 1: Add notification persistence schema and core models

**Files:**
- Create: `doc/alter_retention_notification_center.sql`
- Create: `wesite-core/src/main/java/info/wesite/core/entity/MonitorSnapshot.java`
- Create: `wesite-core/src/main/java/info/wesite/core/entity/MonitorEvent.java`
- Create: `wesite-core/src/main/java/info/wesite/core/entity/UserNotification.java`
- Create: `wesite-core/src/main/java/info/wesite/core/entity/NotificationPreference.java`
- Create: matching mapper, service and `service/impl` files for all four entities
- Test: `wesite-core/src/test/java/info/wesite/core/entity/NotificationModelContractTest.java`

**Interfaces:**
- Produces: MyBatis-Plus services `MonitorSnapshotService`, `MonitorEventService`, `UserNotificationService`, `NotificationPreferenceService`.
- Produces: unique event identity `(watchId, fingerprint)` and unique user notification identity `(userId, eventId)`.

- [ ] **Step 1: Write a failing model contract test**

```java
@Test
void notificationDefaultsAreStable() {
    NotificationPreference preference = NotificationPreference.defaultsFor("u1");
    assertEquals(NotificationPreference.MODE_DAILY, preference.getEmailMode());
    assertTrue(preference.getDomainExpiryEnabled());
    assertTrue(preference.getSslExpiryEnabled());
    assertFalse(preference.getDeleted());
}
```

- [ ] **Step 2: Run the test and confirm the missing types fail compilation**

Run: `mvn -pl wesite-core -Dtest=NotificationModelContractTest test`

Expected: compilation failure because `NotificationPreference` and the monitoring entities do not exist.

- [ ] **Step 3: Add the four entities and constants**

Use `@TableName` and extend `BaseEntity`. Store snapshot normalized JSON in `STATE_JSON`, event old/new values in text columns, and notification target as an internal path. Implement:

```java
public static NotificationPreference defaultsFor(String userId) {
    NotificationPreference value = new NotificationPreference();
    value.setUserId(userId);
    value.setEmailMode(MODE_DAILY);
    value.setDomainExpiryEnabled(true);
    value.setSslExpiryEnabled(true);
    value.setDomainStatusEnabled(true);
    value.setDnsChangeEnabled(true);
    value.setWebsiteAvailabilityEnabled(true);
    value.setDeleted(false);
    return value;
}
```

- [ ] **Step 4: Add SQL with indexes and idempotency constraints**

Create `WEB_MONITOR_SNAPSHOT`, `WEB_MONITOR_EVENT`, `WEB_USER_NOTIFICATION`, and `WEB_NOTIFICATION_PREFERENCE`. Include `UNIQUE(WATCH_ID, FINGERPRINT)`, `UNIQUE(USER_ID, EVENT_ID)`, indexes for `(WATCH_ID, CHECKED_AT)`, `(USER_ID, READ_AT, CREATE_TIME)`, and digest selection `(EMAIL_STATE, CREATE_TIME)`.

- [ ] **Step 5: Add mapper and service boilerplate and run core tests**

Run: `mvn -pl wesite-core test`

Expected: all core tests pass.

- [ ] **Step 6: Commit the persistence slice**

```bash
git add doc/alter_retention_notification_center.sql wesite-core/src/main/java wesite-core/src/test/java/info/wesite/core/entity/NotificationModelContractTest.java
git commit -m "feat: add monitoring notification persistence"
```

### Task 2: Define normalized monitor state and deterministic fingerprints

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorState.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorEventType.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorRisk.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorEventDraft.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorFingerprint.java`
- Test: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorFingerprintTest.java`

**Interfaces:**
- Produces: immutable `MonitorState` record with domain status, expiry, SSL expiry, normalized DNS sets, website state and failure count.
- Produces: `String MonitorFingerprint.of(String watchId, MonitorEventDraft draft)` using SHA-256 over stable canonical fields.

- [ ] **Step 1: Write failing fingerprint and normalization tests**

```java
@Test
void dnsOrderDoesNotChangeFingerprint() {
    MonitorEventDraft a = MonitorEventDraft.dns("example.com", "A", List.of("2.2.2.2", "1.1.1.1"), List.of("3.3.3.3"));
    MonitorEventDraft b = MonitorEventDraft.dns("example.com", "A", List.of("1.1.1.1", "2.2.2.2"), List.of("3.3.3.3"));
    assertEquals(MonitorFingerprint.of("w1", a), MonitorFingerprint.of("w1", b));
}
```

- [ ] **Step 2: Run the targeted test and verify failure**

Run: `mvn -pl wesite-web -am -Dtest=MonitorFingerprintTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement canonical records and fingerprinting**

Canonicalize domains to lowercase, trim status strings, sort/deduplicate DNS values, represent missing optional values as empty strings, and hash `watchId|type|field|oldCanonical|newCanonical` with SHA-256 hex.

- [ ] **Step 4: Run tests and commit**

Run: `mvn -pl wesite-web -am -Dtest=MonitorFingerprintTest -Dsurefire.failIfNoSpecifiedTests=false test`

```bash
git add wesite-web/src/main/java/info/wesite/web/monitor wesite-web/src/test/java/info/wesite/web/monitor
git commit -m "feat: define normalized monitoring events"
```

### Task 3: Implement change detection and website failure state machine

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorChangeDetector.java`
- Test: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorChangeDetectorTest.java`

**Interfaces:**
- Consumes: `MonitorState previous`, `MonitorState current`, `Clock clock`.
- Produces: `List<MonitorEventDraft> detect(MonitorState previous, MonitorState current)`.

- [ ] **Step 1: Write table-driven failing tests**

Cover domain expiry thresholds 30/7/1, SSL thresholds 30/7/1, hold-status entry, DNS set changes, first website failure without an event, second failure producing `WEBSITE_DOWN`, repeated down without a duplicate, and recovery producing `WEBSITE_RECOVERED`.

```java
@Test
void secondConsecutiveWebsiteFailureCreatesDownEvent() {
    MonitorState previous = states().website(false, 1).build();
    MonitorState current = states().website(false, 2).build();
    assertEquals(MonitorEventType.WEBSITE_DOWN, detector.detect(previous, current).getFirst().type());
}
```

- [ ] **Step 2: Run the test and verify failures describe missing behavior**

Run: `mvn -pl wesite-web -am -Dtest=MonitorChangeDetectorTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement the detector as pure Java**

Do not query databases or send notifications. Treat set equality as order-insensitive. Assign `CRITICAL` to one-day expiry, hold status and confirmed website down; `HIGH` to seven-day expiry and SSL expiry; `MEDIUM` to DNS changes and recovery; `LOW` to 30-day reminders.

- [ ] **Step 4: Run tests and commit**

```bash
mvn -pl wesite-web -am -Dtest=MonitorChangeDetectorTest -Dsurefire.failIfNoSpecifiedTests=false test
git add wesite-web/src/main/java/info/wesite/web/monitor/MonitorChangeDetector.java wesite-web/src/test/java/info/wesite/web/monitor/MonitorChangeDetectorTest.java
git commit -m "feat: detect domain monitoring changes"
```

### Task 4: Persist snapshots and publish events idempotently

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorEventPublisher.java`
- Test: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorEventPublisherTest.java`

**Interfaces:**
- Consumes: `publish(DomainWatch watch, MonitorState current, boolean checkSucceeded)`.
- Produces: saved successful snapshot and zero or more `MonitorEvent`; failed checks save diagnostic status without advancing the comparison baseline.

- [ ] **Step 1: Write failing service tests with mocked MyBatis services**

Verify that a failed check does not call `MonitorChangeDetector`, duplicate-key publication reloads the existing event, and every new event creates exactly one `UserNotification` with target `/domain/{domainName}`.

- [ ] **Step 2: Run the test and confirm failure**

Run: `mvn -pl wesite-web -am -Dtest=MonitorEventPublisherTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement transactional publication**

Use `@Transactional`. Load the most recent successful snapshot, invoke the detector, compute each fingerprint, insert the event, tolerate `DuplicateKeyException` by loading the winner, and insert the user notification using its unique constraint. Persist the current snapshot only after event comparison is complete.

- [ ] **Step 4: Run tests and commit**

```bash
mvn -pl wesite-web -am -Dtest=MonitorEventPublisherTest -Dsurefire.failIfNoSpecifiedTests=false test
git add wesite-web/src/main/java/info/wesite/web/monitor/MonitorEventPublisher.java wesite-web/src/test/java/info/wesite/web/monitor/MonitorEventPublisherTest.java
git commit -m "feat: publish idempotent monitoring events"
```

### Task 5: Add preference resolution and notification dispatch policy

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/notification/NotificationPreferenceResolver.java`
- Create: `wesite-web/src/main/java/info/wesite/web/notification/NotificationDispatchDecision.java`
- Create: `wesite-web/src/main/java/info/wesite/web/notification/NotificationDispatcher.java`
- Test: `wesite-web/src/test/java/info/wesite/web/notification/NotificationDispatcherTest.java`

**Interfaces:**
- Consumes: `dispatch(MonitorEvent event, UserNotification notification)`.
- Produces: `IMMEDIATE_EMAIL`, `DAILY_DIGEST`, `WEEKLY_DIGEST`, or `IN_APP_ONLY`, persisted on the user notification delivery state.

- [ ] **Step 1: Write failing policy tests**

Test explicit modes, disabled event categories, missing preference fallback, missing email fallback to in-app only, and default high-risk immediate behavior.

- [ ] **Step 2: Run targeted tests**

Run: `mvn -pl wesite-web -am -Dtest=NotificationDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement policy resolution without sending mail**

The dispatcher updates delivery state only. Immediate mail transmission is isolated in Task 6 so persistence remains valid even when SMTP is unavailable.

- [ ] **Step 4: Run and commit**

```bash
mvn -pl wesite-web -am -Dtest=NotificationDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false test
git add wesite-web/src/main/java/info/wesite/web/notification wesite-web/src/test/java/info/wesite/web/notification
git commit -m "feat: add notification delivery policy"
```

### Task 6: Migrate expiry email and add immediate/digest delivery

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/task/NotificationDeliveryTask.java`
- Create: `wesite-web/src/main/resources/templates/email/monitor-event.html`
- Create: `wesite-web/src/main/resources/templates/email/monitor-digest.html`
- Modify: `wesite-web/src/main/java/info/wesite/web/task/DomainWatchTask.java`
- Modify: `wesite-core/src/main/java/info/wesite/core/entity/DomainWatchNotifyLog.java`
- Modify: `doc/alter_retention_notification_center.sql`
- Test: `wesite-web/src/test/java/info/wesite/web/task/NotificationDeliveryTaskTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/task/DomainWatchTaskEventMigrationTest.java`

**Interfaces:**
- Consumes: queued user notifications selected by delivery mode and state.
- Produces: mail log rows and terminal delivery state `SENT`, or retryable `FAILED` with `retryCount <= 3`.

- [ ] **Step 1: Write failing migration and delivery tests**

Verify expiry scanning publishes `DOMAIN_EXPIRING` instead of directly invoking `MailSender`; immediate delivery sends one mail; daily and weekly jobs group events by user; SMTP failure leaves the in-app notification intact; the fourth delivery attempt is not made.

- [ ] **Step 2: Run targeted tests and verify failure**

Run: `mvn -pl wesite-web -am -Dtest=NotificationDeliveryTaskTest,DomainWatchTaskEventMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement delivery jobs**

Use schedules `0 */5 * * * ?` for immediate queue draining, `0 0 8 * * ?` for daily digest, and `0 0 8 * * MON` for weekly digest. Claim rows atomically before sending, render only escaped event values, write a log per attempt, and mark successful notifications sent.

- [ ] **Step 4: Remove the old direct expiry-mail path**

Keep domain refresh orchestration in `DomainWatchTask`, route confirmed expiry events through `MonitorEventPublisher`, and remove or disable `sendExpiryNotifications()` so the same event cannot travel through two pipelines.

- [ ] **Step 5: Run task tests and commit**

```bash
mvn -pl wesite-web -am -Dtest=NotificationDeliveryTaskTest,DomainWatchTaskEventMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test
git add wesite-web/src/main/java/info/wesite/web/task wesite-web/src/main/resources/templates/email wesite-core/src/main/java/info/wesite/core/entity/DomainWatchNotifyLog.java doc/alter_retention_notification_center.sql wesite-web/src/test/java/info/wesite/web/task
git commit -m "feat: deliver monitoring event notifications"
```

### Task 7: Expose current-user notification and preference APIs

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/controller/api/NotificationController.java`
- Create: `wesite-web/src/main/java/info/wesite/web/controller/api/NotificationPreferenceController.java`
- Test: `wesite-web/src/test/java/info/wesite/web/controller/api/NotificationControllerTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/controller/api/NotificationPreferenceControllerTest.java`

**Interfaces:**
- Produces: `GET /api/notifications?page=&category=`, `GET /api/notifications/unread-count`, `PUT /api/notifications/{id}/read`, `PUT /api/notifications/read-all`, `DELETE /api/notifications/{id}`.
- Produces: `GET /api/notification-preferences` and `PUT /api/notification-preferences`.

- [ ] **Step 1: Write failing controller tests**

Use MockMvc and a populated `UserHolder`. Assert pagination, category allow-listing, ownership predicates on every mutation, safe internal targets, valid email modes, and rejection of another user's notification ID.

- [ ] **Step 2: Run the tests and verify missing endpoints**

Run: `mvn -pl wesite-web -am -Dtest=NotificationControllerTest,NotificationPreferenceControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement controllers with session access control**

Apply `@AccessControl(level = Level.SESSION)`. Return DTO maps rather than persistence entities so internal delivery state and error text are not exposed. Resolve missing preferences with `defaultsFor(userId)`.

- [ ] **Step 4: Run and commit**

```bash
mvn -pl wesite-web -am -Dtest=NotificationControllerTest,NotificationPreferenceControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
git add wesite-web/src/main/java/info/wesite/web/controller/api/NotificationController.java wesite-web/src/main/java/info/wesite/web/controller/api/NotificationPreferenceController.java wesite-web/src/test/java/info/wesite/web/controller/api
git commit -m "feat: expose notification center APIs"
```

### Task 8: Build notification bell, center and settings UI

**Files:**
- Create: `wesite-web/src/main/resources/views/user/notifications.html`
- Create: `wesite-web/src/main/resources/views/user/notification-settings.html`
- Create: `wesite-web/src/main/resources/static/js/notifications.js`
- Modify: `wesite-web/src/main/resources/views/template.html`
- Modify: `wesite-web/src/main/resources/static/style/common.css`
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/MainController.java`
- Test: `wesite-web/src/test/java/info/wesite/web/view/NotificationCenterTemplateTest.java`
- Test: `wesite-web/src/test/js/notification-runtime.test.js`

**Interfaces:**
- Consumes: Task 7 APIs.
- Produces: `/user/notifications`, `/user/notification-settings`, notification bell `.notification-bell`, and accessible live unread count.

- [ ] **Step 1: Write failing template and JavaScript runtime tests**

Assert authenticated navigation contains the bell and links; notification rows escape API text; filters request only allowed categories; mark-read updates the count; settings submit only allowed preference fields.

- [ ] **Step 2: Run tests and confirm failure**

Run: `mvn -pl wesite-web -am -Dtest=NotificationCenterTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `node --test wesite-web/src/test/js/notification-runtime.test.js`

- [ ] **Step 3: Add page routes and accessible templates**

Add session-protected routes in `MainController`. Use buttons for read/delete actions, visible risk labels, `<time datetime>`, empty/error/loading states, and links that remain inside this origin.

- [ ] **Step 4: Add bell polling and UI behavior**

Fetch unread count after authenticated page load and after visibility returns. Do not poll more often than every 60 seconds. Render event content using `textContent`, never `innerHTML` with API values.

- [ ] **Step 5: Run tests and commit**

```bash
mvn -pl wesite-web -am -Dtest=NotificationCenterTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test wesite-web/src/test/js/notification-runtime.test.js
git add wesite-web/src/main/resources/views/user wesite-web/src/main/resources/views/template.html wesite-web/src/main/resources/static/js/notifications.js wesite-web/src/main/resources/static/style/common.css wesite-web/src/main/java/info/wesite/web/controller/MainController.java wesite-web/src/test
git commit -m "feat: add in-app notification center"
```

### Task 9: Enhance watchlist with health and unread-event summaries

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/api/DomainWatchController.java`
- Create: `wesite-web/src/main/java/info/wesite/web/controller/api/DomainWatchSummary.java`
- Modify: `wesite-web/src/main/resources/views/user/domain-watch.html`
- Test: `wesite-web/src/test/java/info/wesite/web/controller/api/DomainWatchSummaryTest.java`
- Test: `wesite-web/src/test/js/domain-watch-notification-runtime.test.js`

**Interfaces:**
- Changes: `/api/domain-watch/list` returns `ResponseJson<DomainWatchSummary>` values containing watch data, latest risk, unread count, last successful check and latest event summary.

- [ ] **Step 1: Write failing aggregation and rendering tests**

Test zero-event defaults, latest-risk selection, user-scoped unread counts, last successful check display, and links to filtered notifications for the watched domain.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -pl wesite-web -am -Dtest=DomainWatchSummaryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `node --test wesite-web/src/test/js/domain-watch-notification-runtime.test.js`

- [ ] **Step 3: Implement batched aggregation**

Fetch event and unread counts for all watch IDs in the current page using grouped queries; do not issue per-watch queries. Preserve existing add/edit/delete payload contracts.

- [ ] **Step 4: Update watchlist cards and run tests**

```bash
mvn -pl wesite-web -am -Dtest=DomainWatchSummaryTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test wesite-web/src/test/js/domain-watch-notification-runtime.test.js
git add wesite-web/src/main/java/info/wesite/web/controller/api wesite-web/src/main/resources/views/user/domain-watch.html wesite-web/src/test
git commit -m "feat: surface watchlist monitoring activity"
```

### Task 10: Add the remaining first-release check adapters

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/DomainMonitorCollector.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/DnsMonitorCollector.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/SslMonitorCollector.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/WebsiteMonitorCollector.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/task/DomainWatchTask.java`
- Test: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorCollectorTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/task/DomainWatchTaskTest.java`

**Interfaces:**
- Each collector produces a partial `MonitorState` result with explicit success/failure metadata.
- `DomainWatchTask` merges successful partial states, preserves previous values for failed sources, and calls `MonitorEventPublisher` once per watch.

- [ ] **Step 1: Write failing collector contract tests**

Stub RDAP/WHOIS, dnsjava, SSL socket and HTTP checks. Verify canonical DNS values, SSL date extraction, website HTTP success range, timeout failure metadata, and preservation of prior state when one source fails.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -pl wesite-web -am -Dtest=MonitorCollectorTest,DomainWatchTaskTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement focused adapters using existing utilities**

Reuse `RdapUtils`, `WhoisUtils`, dnsjava and existing SSL/HTTP utilities. Configure bounded timeouts. Do not interpret NXDOMAIN, socket timeout or parser error as a change without a successful source-specific result.

- [ ] **Step 4: Wire the scheduled orchestration and run tests**

```bash
mvn -pl wesite-web -am -Dtest=MonitorCollectorTest,DomainWatchTaskTest -Dsurefire.failIfNoSpecifiedTests=false test
git add wesite-web/src/main/java/info/wesite/web/monitor wesite-web/src/main/java/info/wesite/web/task/DomainWatchTask.java wesite-web/src/test/java/info/wesite/web
git commit -m "feat: monitor domain dns ssl and availability"
```

### Task 11: Add retention analytics events and operational documentation

**Files:**
- Create: `wesite-web/src/main/resources/static/js/retention-analytics.js`
- Modify: `wesite-web/src/main/resources/views/template.html`
- Modify: `wesite-web/src/main/resources/views/user/domain-watch.html`
- Modify: `wesite-web/src/main/resources/views/user/notifications.html`
- Modify: `README.md`
- Modify: `doc/alter_retention_notification_center.sql`
- Test: `wesite-web/src/test/js/retention-analytics-runtime.test.js`

**Interfaces:**
- Produces GA4 events: `watch_created`, `notification_opened`, `notification_action_clicked`, `notification_preferences_saved`, and `watchlist_return_visit`.

- [ ] **Step 1: Write a failing analytics runtime test**

Verify analytics is no-op when `gtag` is absent, sends no email/domain value, and records only event type, category, risk and UI source.

- [ ] **Step 2: Run the test and verify failure**

Run: `node --test wesite-web/src/test/js/retention-analytics-runtime.test.js`

- [ ] **Step 3: Implement privacy-safe event helpers and hook confirmed actions**

Only emit after successful API operations. Never include domain names, email addresses, notification text, event IDs or user IDs in GA4 parameters.

- [ ] **Step 4: Document migration and verification**

Add the SQL application order, schedules, SMTP dependency, rollback behavior, and a production checklist to `README.md`. Document how to compare 7-day and 30-day return rates for monitored versus non-monitored users.

- [ ] **Step 5: Run tests and commit**

```bash
node --test wesite-web/src/test/js/retention-analytics-runtime.test.js
git add wesite-web/src/main/resources/static/js/retention-analytics.js wesite-web/src/main/resources/views README.md doc/alter_retention_notification_center.sql wesite-web/src/test/js/retention-analytics-runtime.test.js
git commit -m "feat: measure notification retention funnel"
```

### Task 12: Full verification and rollout guardrails

**Files:**
- Modify only files required to fix failures introduced by Tasks 1-11.

**Interfaces:**
- Produces: a deployable notification center with migration, automated tests and a duplicate-mail-safe rollout procedure.

- [ ] **Step 1: Run all Java tests**

Run: `mvn test`

Expected: all modules pass with no new failures.

- [ ] **Step 2: Run all standalone JavaScript tests**

Run: `Get-ChildItem wesite-web/src/test/js/*.test.js | ForEach-Object { node --test $_.FullName; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }`

Expected: every runtime test passes.

- [ ] **Step 3: Run packaging and SEO regression checks**

Run: `mvn clean package -DskipTests`

Run after deployment to staging: `pwsh -File scripts/check-seo.ps1 -BaseUrl https://whose.domains`

Expected: package succeeds; the production-origin SEO contract passes when executed against the deployed origin.

- [ ] **Step 4: Perform a staged notification smoke test**

Create one test watch and synthetic events for domain expiry, DNS change and website down. Confirm one in-app notification per event, unread count changes, immediate mail is sent once, daily digest excludes already-sent immediate items, links open the expected domain detail, and another user cannot access the records.

- [ ] **Step 5: Verify rollout order**

Apply schema first, deploy code with new delivery jobs disabled, run snapshot/event generation for a test account, enable in-app notifications, then enable immediate and digest jobs. Confirm the old `sendExpiryNotifications()` schedule is absent before enabling the new mail jobs.

- [ ] **Step 6: Commit any verification-only fixes**

Inspect `git diff --name-only`, stage each file changed specifically to correct a failed verification check, and commit with `git commit -m "test: verify notification center rollout"`. Skip this commit when verification requires no changes.
