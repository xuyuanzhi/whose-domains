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
