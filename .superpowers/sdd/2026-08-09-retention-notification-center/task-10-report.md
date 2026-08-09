# Task 10 Report — Domain, DNS, SSL, and website collectors

## Status

Completed.

## Delivered behavior

- Added focused collectors for WHOIS/RDAP domain state, DNS records, TLS certificate expiry, and HTTP availability.
- Every collector returns `MonitorCollectorResult` with an explicit source, success flag, partial canonical `MonitorState`, or typed failure metadata. Failed results cannot carry state.
- Domain collection tries bounded RDAP first and falls back to bounded WHOIS. Existing `RdapUtils` and `WhoisUtils` parsers are reused. Blank, invalid, or non-comparable parser output is a failure rather than an empty observation.
- DNS collection uses dnsjava with a five-second resolver timeout and canonical NS, A, AAAA, and MX values. NXDOMAIN and retry/timeout outcomes are failures; NODATA for an individual type remains a successful empty value.
- TLS collection uses bounded TCP and handshake reads, hostname verification, and UTC certificate-expiry extraction.
- Website collection uses bounded HEAD probes, a three-redirect ceiling, and HTTPS-to-HTTP fallback. HTTP 200–399 resets `websiteFailureCount`; a completed 400–599 response increments it; timeout/lookup failure preserves the previous website state and count.
- `DomainWatchTask` starts from the latest successful snapshot, replaces only successful source fields, and publishes exactly once for each due watch. A first partial check becomes a failed diagnostic snapshot rather than establishing unknown empty values as a successful baseline.

## Safety and failure semantics

- RDAP redirects and HTTP redirects are validated at every hop.
- RDAP, WHOIS, TLS, and website targets reject loopback, private, link-local, multicast, carrier-grade NAT, documentation, and IPv6 unique-local ranges before outbound connection.
- Connect/read/lookup timeouts are explicit and bounded. Responses from RDAP/WHOIS are size-limited.
- NXDOMAIN, socket/HTTP timeout, blocked targets, lookup failures, and parser failures never replace the last successful source value.

## TDD evidence

- RED: focused tests initially failed to compile because the four collectors, shared result, and eight-dependency task constructor did not exist.
- RED: follow-up regression tests proved that parser success with no comparable state was incorrectly accepted and dnsjava `query timeout` was not classified as `TIMEOUT`.
- GREEN: `mvn -pl wesite-web -am "-Dtest=MonitorCollectorTest,DomainWatchTaskTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` — 13 tests passed.
- Full regression: `mvn -pl wesite-web -am test` — 9 core tests and 364 web tests passed, with no failures.
- Tests stub external WHOIS/RDAP, DNS, TLS, and HTTP boundaries and do not depend on public network access.

## Baseline note

Before Task 10 changes, the first full-suite run had one existing timing-dependent failure in `GoogleLoginMySqlConcurrencyTest` (database deadlock exception instead of the expected business conflict). The exact test passed on immediate isolated rerun, and the final full suite also passed. No Google-login code was changed.

## Commit

- Planned implementation commit: `feat: monitor domain dns ssl and availability`
