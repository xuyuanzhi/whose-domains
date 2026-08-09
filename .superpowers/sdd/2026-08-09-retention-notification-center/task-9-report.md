# Task 9 Report — Watchlist health and unread-event summaries

## Status

Completed.

## Delivered behavior

- `GET /api/domain-watch/list` now returns a `DomainWatchSummary` per active watch. Each summary preserves the full watch record and adds the persisted latest risk, current-user unread event count, most recent successful snapshot time, and latest event summary.
- The list endpoint uses bounded batch queries for all watches on the page: one event query, one successful-snapshot query, and at most one current-user unread-notification query. It does not issue per-watch lookups.
- Empty-history watches are explicitly quiet: `UNKNOWN` risk, zero unread events, no successful-check timestamp, and `No monitoring events yet`.
- Watch cards show health, last successful check, latest event, and an event-history link. The link carries the watched domain into the notification center.
- The notification center now honors its optional `domain` query parameter using the current user's active watch, so links cannot expose another user's event history. The original two-argument controller method remains as a compatibility delegate for access-control integrations.
- Add, edit, and delete watch payloads remain unchanged.

## TDD evidence

- RED: `DomainWatchSummaryTest` first failed to compile because the summary contract did not exist; the notification runtime test first failed because no domain filter was sent.
- RED: the full Maven suite identified the reflection-based compatibility requirement for `NotificationController.list(int, String)`; the delegated overload restores that existing contract.

## Verification

- `mvn -pl wesite-web -am test` — 357 tests passed (7 core, 350 web).
- `node --test wesite-web/src/test/js/*.test.js` — 76 tests passed.
- `git diff --check` — clean.

## Self-review

- Canonical risk is read from the persisted event field through the existing canonical-risk parser; it is never inferred from event wording or current domain state.
- Successful-check timestamps come only from active monitoring snapshots; failed diagnostic snapshots are excluded.
- Notification counts are constrained by the current user, unread state, known event IDs, and watches already selected for that current user.

## Review fix round 1

- Replaced controller-side history loading with `DomainWatchSummaryMapper`: exactly three fixed aggregate queries return one latest-event row, one latest-successful-check row, and one unread-count row per requested watch. The controller no longer loads all monitoring history or expands every event ID into a notification query.
- Latest event selection is deterministic in MySQL 8: `OCCURRED_AT DESC`, then persisted canonical-risk severity `CRITICAL > HIGH > MEDIUM > LOW > UNKNOWN`, then `ID DESC`. Successful snapshot ties use `CHECKED_AT DESC, ID DESC`.
- Added Mapper SQL-contract coverage plus a MySQL 8 integration test proving that simultaneous LOW and CRITICAL events select the CRITICAL row.
