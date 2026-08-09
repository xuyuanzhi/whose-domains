# Task 7 Report

## Status

Implemented the current-user notification and preference APIs: notification listing, unread count, individual and bulk read mutations, deletion, and preference retrieval and update.

## Security boundaries

- Both controllers require session access control. Every notification read and mutation is scoped to the current `UserHolder` user.
- Categories accept only `all`, `expiry`, `security`, `domain-change`, and `investment`; unrecognized values never enter SQL.
- The API returns display DTOs only. It excludes delivery state, retry count, lease, batch, and internal error fields.
- `targetPath` is returned only when it is a single-slash, same-origin path.
- Preference updates accept only a validated email mode and explicit boolean preference fields; ownership fields in a request are ignored.

## Verification

```powershell
mvn -pl wesite-web -am '-Dtest=NotificationControllerTest,NotificationPreferenceControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: 10 tests, 0 failures, 0 errors.

## Concern

The current persistence model has no separate notification-recipient email field. This task validates the `emailMode` allow-list; supporting an address different from the account email requires a `NotificationPreference` schema/model extension.

## Fix round 1

- `PUT /api/notifications/{id}/read` now uses an atomic field-level update that sets only `READ_AT`, constrained by notification ID, current user, `DELETED = 0`, and an unread state. It never writes delivery-state, claim, or batch fields. A zero-row result is idempotent success only when a follow-up current-user query confirms the record is already read; missing, deleted, and other-user records fail.
- `PUT /api/notifications/read-all` first checks for current-user unread rows. No unread rows is idempotent success. If the atomic update reports failure, it rechecks unread rows: zero remaining rows is treated as a concurrent successful read, while remaining rows returns failure. This avoids classifying a concurrent completion as a write failure.
- Added regression coverage for atomic ownership predicates, excluded delivery fields, update failure, zero-unread idempotence, concurrent completion, all four valid email modes, both controller access-control declarations, and the existing interceptor's unauthenticated API response behavior.

Focused verification after the fix:

```powershell
mvn -pl wesite-web -am '-Dtest=NotificationControllerTest,NotificationPreferenceControllerTest,ProtectedApiAccessControlTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: 19 tests, 0 failures, 0 errors.

Full web verification after the fix:

```powershell
mvn -pl wesite-web -am test
```

Result: 337 tests, 0 failures, 0 errors.
