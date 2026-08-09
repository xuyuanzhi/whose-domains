# Monitor Resolution Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound hostile HTTP framing and make every monitoring hostname resolution obey a shared deadline without unbounded threads or queued work.

**Architecture:** Keep `BoundHttpClient` as the focused parser/transport adapter, adding explicit aggregate header and chunk/body guards before allocation. Add one Spring-managed fixed-size resolver executor and a deadline-aware resolver component that submits `InetAddress.getAllByName` once, waits only for the caller's remaining budget, cancels on timeout, and rejects overload. Inject that resolver into RDAP, WHOIS, TLS, and website collectors so all target validation uses the already shared collector/watch deadline.

**Tech Stack:** Java 17, Spring Boot 3.5, `ThreadPoolTaskExecutor`, JUnit 5, Mockito, Maven.

## Global Constraints

- Work only in `C:\Users\Yuz\git\whose-domains\.worktrees\retention-notification-center`.
- Do not modify Google login logic.
- Do not use public network access in tests.
- Resolver pool and queue must both be explicitly bounded; a blocking `getAllByName` may occupy capacity but cannot create or queue unlimited work.
- Preserve Host, HTTPS SNI, certificate hostname verification, redirect revalidation, and one publisher call per due watch.

---

### Task 1: Bound HTTP chunk and header parsing

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/BoundHttpClient.java`
- Create: `wesite-web/src/test/java/info/wesite/web/monitor/BoundHttpClientParsingTest.java`

**Interfaces:**
- Consumes: existing private `readResponse(Socket, String, MonitorDeadline)` parser via test reflection.
- Produces: the same `BoundHttpClient.Response`, but rejects framing that exceeds `MAX_BODY_BYTES`, `MAX_HEADER_SECTION_BYTES`, or `MAX_HEADER_FIELDS` before large allocation or body reads.

- [ ] Write a failing parser test whose first chunk size is `7fffffff`; run Surefire with a small heap and require a normal `IOException` size-limit failure rather than `OutOfMemoryError`.
- [ ] Write a failing parser test with 101 small unique headers and require an immediate `IOException` header-limit failure.
- [ ] Add explicit 64 KiB aggregate header and 100 physical-field limits, accounting for repeated and continuation lines before semantic parsing.
- [ ] Parse chunk size without signed overflow, reject non-positive/oversized sizes before `readExactly`, and cap each append by `MAX_BODY_BYTES - body.size()`.
- [ ] Run `BoundHttpClientParsingTest` and `MonitorCollectorTest` until green.

### Task 2: Add a bounded deadline-aware resolver

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorResolverConfiguration.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorAddressResolver.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorDeadline.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/MonitorTargetPolicy.java`
- Create: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorAddressResolverTest.java`

**Interfaces:**
- Produces: `InetAddress[] MonitorAddressResolver.resolve(String host, MonitorDeadline deadline) throws IOException`.
- Produces: `MonitorTargetPolicy.resolvePublic(String rawHost, MonitorDeadline deadline, HostResolver resolver)` where `HostResolver` accepts the same deadline.

- [ ] Write a failing test with a blocking lookup and a 50 ms deadline; assert timeout is returned within a bounded wall-clock interval and the future is cancelled.
- [ ] Write a failing saturation test using one running slot and zero queue capacity; assert the next resolution fails as capacity exhausted rather than queuing.
- [ ] Configure a Spring-managed `ThreadPoolTaskExecutor` with fixed pool size, bounded queue, abort rejection, monitor thread names, and shutdown lifecycle.
- [ ] Implement `Future.get(remainingNanos, NANOSECONDS)`, timeout cancellation, interrupt preservation, checked exception propagation, and rejection mapping.
- [ ] Run `MonitorAddressResolverTest` until green.

### Task 3: Route every target lookup through the resolver and verify

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/BoundHttpClient.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/DomainMonitorCollector.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/SslMonitorCollector.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/WebsiteMonitorCollector.java`
- Modify: `wesite-web/src/test/java/info/wesite/web/monitor/MonitorCollectorTest.java`
- Modify: `.superpowers/sdd/2026-08-09-retention-notification-center/task-10-report.md`

**Interfaces:**
- Consumes: the Task 2 deadline-aware resolver.
- Produces: RDAP redirects, website redirects, WHOIS addresses, and TLS addresses resolved through the same bounded component and parent deadline.

- [ ] Update rebinding tests to pass the deadline into resolver lambdas and retain the public-then-private no-connect assertion.
- [ ] Inject `MonitorAddressResolver` into production collectors; reuse one resolver-aware `BoundHttpClient` per collector and pass the existing deadline to every redirect/WHOIS/TLS resolution.
- [ ] Run all focused monitoring/task tests.
- [ ] Run fresh `mvn test`, confirm core/web counts and zero failures/errors, append round2 evidence to the Task10 report, run `git diff --check`, and commit.

## Self-Review

- Spec coverage: Task 1 covers allocation-safe chunks and aggregate headers; Task 2 covers bounded Spring executor, timeout/cancel, and saturation; Task 3 covers WHOIS/SSL/HTTP/RDAP wiring and full regression.
- Placeholder scan: no deferred steps or unspecified error handling remain.
- Type consistency: all target resolution flows through `HostResolver.resolve(String, MonitorDeadline)` and preserves the original `ResolvedTarget` result used for address-bound connections.
