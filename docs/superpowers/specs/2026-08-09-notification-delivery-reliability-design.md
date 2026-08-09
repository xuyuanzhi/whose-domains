# Notification Delivery Reliability Design

## Scope

This fix hardens the monitoring notification pipeline without adding new product channels or preference modes. It fixes first-scan expiry events, threshold-crossing detection, durable delivery attempts, lease recovery, and atomic per-user digest grouping.

## Expiry publication and scan cursor

`MonitorEventPublisher` remains the transaction boundary for the successful monitoring cursor. It passes both the previous successful snapshot timestamp and the current check timestamp to `MonitorChangeDetector`.

Expiry behavior is deterministic per threshold:

- With no prior successful snapshot, an expiry exactly 30, 7, or 1 day away creates the corresponding event.
- With a prior successful snapshot, a threshold is crossed when the previous successful check saw more than the threshold's remaining days and the current successful check sees at most that many days.
- A delayed scan can cross multiple thresholds; each crossed threshold creates its own draft whose `field` includes the threshold. Existing fingerprint uniqueness makes replay idempotent.
- Status, DNS, and availability comparisons still require a prior state; an empty baseline does not fabricate those changes.

`DomainWatchTask` mutates the in-memory watch with newly collected domain data, calls the publisher, and only after publisher/dispatcher success writes `lastCheckTime` and the refreshed watch. If publication fails, neither the successful snapshot cursor nor the watch suppression timestamp advances. If the watch update fails after publication, a retry is safe because event and notification fingerprints are unique.

## Notification routing state

Routing mode and delivery state are separate persisted concepts.

- `EMAIL_MODE`: `IMMEDIATE_EMAIL`, `DAILY_DIGEST`, `WEEKLY_DIGEST`, or `IN_APP_ONLY`.
- `EMAIL_STATE`: `QUEUED`, `CLAIMED`, `SENT`, `FAILED`, or `IN_APP_ONLY`.
- `EMAIL_ATTEMPT_COUNT`: total started delivery attempts, initially zero and never greater than three.
- `EMAIL_CLAIM_TOKEN`, `EMAIL_CLAIM_UNTIL`: the active lease identity and expiry.
- `DELIVERY_BATCH_ID`: the durable outbound-message group.

`NotificationDispatcher` persists mode and state together. Email routes become `QUEUED`; in-app-only routes become `IN_APP_ONLY`. It clears old delivery metadata for a newly routed notification.

## Delivery batches

`WEB_NOTIFICATION_DELIVERY_BATCH` is the authority for one outbound message. It stores user, mode, deterministic window key, state, attempt count, claim token/lease, retry time, and completion time.

- Immediate window key: notification ID. One batch owns one notification.
- Daily window key: UTC calendar date of the scheduled run.
- Weekly window key: ISO week-based year and week of the scheduled run.
- Unique key: `(USER_ID, EMAIL_MODE, WINDOW_KEY)`.

Digest batch creation is transactional. A worker inserts the unique user/mode/window batch and assigns every eligible, unassigned notification for that user and cutoff with one unbounded `UPDATE`; there is no global 500-event cap. Concurrent workers can list the same user, but only one unique insert wins, so a user's window cannot split into multiple emails.

Immediate candidate IDs use repeated keyset/bounded reads. Claimed rows become assigned and disappear from the next page, so queues larger than 500 drain without offset starvation.

## Claim, attempt, and lease protocol

Claiming and starting an attempt occur in one database transaction:

1. Lock or create the delivery batch.
2. Increment the batch attempt count, rejecting values above three.
3. Persist the batch and notification claim token/lease and mirrored attempt count.
4. Insert a `SEND_STATUS_PENDING` attempt log row for the batch and attempt number.
5. Commit, then resolve recipients/events, render, and call SMTP.

If the attempt-log insert fails, the entire claim transaction rolls back and SMTP is not called. Log persistence errors are never swallowed.

Completion is another transaction that first updates the exact attempt log, then conditionally finishes the batch and all notifications using the claim token. SMTP success produces `SENT`; dependency or SMTP failure produces `FAILED` and a retry time. Completion failure leaves the durable `CLAIMED` lease and `PENDING` log for recovery.

An expired lease with fewer than three attempts is reclaimed atomically: the prior pending log is marked failed as lease-expired, the attempt count increments, and a new pending attempt log is inserted. An expired third attempt is finalized `FAILED`; it is never sent a fourth time. This is at-least-once SMTP delivery because a process can crash after SMTP accepts a message but before completion commits.

## Attempt logs and legacy failures

`WEB_DOMAIN_WATCH_NOTIFY_LOG` becomes a generic delivery-attempt audit row by adding `BATCH_ID`; `WATCH_ID`, `NOTIFICATION_ID`, and `EVENT_ID` are optional because one digest can include many events. `RETRY_COUNT` is the one-based attempt number. Send status values are pending, success, and failure.

The migration marks old failed rows with no notification/batch identity inactive and sets their retry count to three. They remain available for audit but never enter the new retry path.

## Failure semantics

- Publisher or dispatcher failure does not advance the successful monitoring cursor.
- A claimed attempt cannot remain permanently claimed because every claim has a persisted lease.
- Event/user/template dependencies failing after claim complete the durable attempt as failed; if completion itself fails, lease recovery handles it.
- Missing/failed attempt-log persistence prevents SMTP from starting.
- Successful immediate notifications cannot enter a digest because their mode/batch/state are already terminal.
- Digest retries reuse the existing batch membership, so a retry sends the same atomic window rather than mixing later notifications.

## Verification

Tests cover:

- Real publisher path with no previous snapshot at 30/7/1 days and fingerprint replay.
- Delayed scans crossing 30/7/1 thresholds.
- Watch suppression time advancing only after publisher success.
- Attempt-log insert failure rolling back before SMTP.
- Dependency exceptions after claim and lease recovery.
- Exactly three started attempts and no fourth attempt.
- Two workers competing for the same user/mode/window and producing one digest batch.
- A digest containing more than 500 notifications in one email and immediate pagination beyond 500 without starvation.
- Existing template escaping and the complete core/web regression suite.
