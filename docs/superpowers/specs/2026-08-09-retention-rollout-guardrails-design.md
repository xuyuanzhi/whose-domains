# Retention Rollout Guardrails Design

## Goal

Prevent notification delivery workers from claiming or consuming queued notifications until an operator explicitly enables the immediate and digest phases, and make the migration/rollout contract reproducible against disposable MySQL fixtures.

## Delivery architecture

`NotificationDeliveryTask` remains the delivery service and owns claiming, batching, SMTP invocation, retry, and completion behavior. It no longer owns Spring schedules.

Two small scheduling components delegate to that service:

- `ImmediateNotificationDeliveryJob` registers only for the `prod` or `mac` profile when both `wesite.notification-delivery.immediate-enabled=true` and `wesite.mail.enabled=true`. It owns the five-minute immediate schedule.
- `DigestNotificationDeliveryJob` registers only for the `prod` or `mac` profile when both `wesite.notification-delivery.digest-enabled=true` and `wesite.mail.enabled=true`. It owns the daily and weekly schedules.

Both properties default to `false`, including in the production configuration example. A disabled job has no Spring bean and therefore cannot invoke a mapper, claim a batch, mutate a notification, or send mail. Monitoring, event publication, and in-app notification creation remain independent and continue to run through `DomainWatchTask`.

`wesite.mail.enabled=false` is also a hard registration gate for both jobs. It protects against enabling a delivery flag before SMTP is available, while the two delivery flags remain the recommended operator-facing rollout controls and should be turned off first. If a disabled `SmtpMailSender` is invoked directly outside these scheduled jobs, it returns an explicit failed result instead of a successful no-send result.

## Rollout sequence

1. Stop workers, back up the database, run the read-only schema preflight, and apply exactly one documented migration path.
2. Deploy with both delivery flags false. Run monitoring for an internal watch and verify snapshots, events, in-app notifications, unread state, links, and user scoping. No delivery job is registered, regardless of the mail flag.
3. Configure and validate SMTP, set `wesite.mail.enabled=true`, then enable only the immediate flag. Verify one new internal immediate notification, delivery batch, and audit log.
4. Enable the digest flag only after immediate delivery is stable. Verify new daily and weekly internal fixtures and confirm immediate items are excluded.
5. Expand traffic while monitoring retries, expired claims, queue age, and duplicate-mail reports.

## Reproducible verifier

`scripts/verify-retention-rollout.ps1` supports `-Fixture Static`, `PathA`, `PathB`, or `All` (default). Static checks validate SQL contracts, README ordering, safe property defaults, conditional job registration declarations, and removal of the legacy expiry sender.

Path A creates a fresh `mysql:8.4.0` container, applies `doc/create.sql` and the retention baseline, then checks the required tables and canonical columns. Path B creates another fresh container, applies `create.sql` to supply the historical supporting schema, removes the watch/snapshot pair to model the documented old-install preflight, applies the legacy pair and retention baseline, then checks the same contract. Every fixture uses a unique container name, a read-only repository mount, `--rm`, and a `finally` cleanup. The verifier never calls an application origin or production service.

## Tests

- A Spring context registration test proves both jobs are absent by default, when explicitly false, and when both delivery flags are true but mail is false, so the delivery service receives no calls and the queue boundary remains untouched.
- Separate enabled-context cases prove each delivery property registers only its matching job when mail is also true, and verify the exact cron annotations and delegate method.
- A mail sender test proves disabled SMTP returns failure without creating or sending a MIME message.
- A script contract test ensures the reusable verifier exposes both fixture paths and contains no production HTTP command.
- Fresh verification runs the full Maven suite, all standalone JavaScript tests, packaging, and the verifier with both MySQL paths.

## Compatibility and failure behavior

Existing deployments become delivery-safe after upgrade because missing job properties mean false. Operators must opt in explicitly. If SMTP is absent or disabled, both scheduling beans are absent even if a delivery flag is mistakenly enabled, so no queue can be claimed or mutated. The sender's explicit disabled failure remains a defense for direct, non-scheduled callers.
