# Network Tool Probe Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Ping and Port Checker outbound probes use validated, IP-bound, deadline-limited connections with Spring-managed bounded concurrency.

**Architecture:** Add a public `SafeNetworkProbeService` facade inside the monitor package so controllers can reuse the existing package-private `MonitorTargetPolicy`, `MonitorAddressResolver`, `BoundHttpClient`, and `MonitorDeadline`. The service owns probe orchestration and exposes immutable API-oriented results; controllers retain input/rate-limit/history responsibilities and never perform DNS or socket work directly.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring MVC, `ThreadPoolTaskExecutor`, JUnit 5, Mockito, Maven Surefire.

## Global Constraints

- Preserve the existing JSON request shapes and successful response field names for `POST /api/tools/ping` and `POST /api/tools/port-check`.
- Use one non-resetting 15-second `MonitorDeadline` per request.
- Reject the whole target when any DNS answer is not globally reachable.
- Never connect by host name after validation; socket connections receive an approved `InetAddress`.
- Revalidate every HTTP redirect through `BoundHttpClient`.
- Reject null ports, ports outside `1..65535`, empty lists, and lists longer than 20 before DNS or socket work.
- Keep DNS resolution on the existing resolver executor; use a separate Spring-managed bounded executor for port probes.
- Do not return raw exception messages to clients.
- Do not change authentication, global exception handling, distributed rate limiting, or unrelated network tools in this plan.
- Every production behavior change requires a failing test observed before implementation.

---

### Task 1: Safe probe facade and managed port executor

**Files:**
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/SafeNetworkProbeService.java`
- Create: `wesite-web/src/main/java/info/wesite/web/monitor/NetworkToolProbeConfiguration.java`
- Create: `wesite-web/src/test/java/info/wesite/web/monitor/SafeNetworkProbeServiceTest.java`
- Modify: `wesite-web/src/main/java/info/wesite/web/monitor/BoundHttpClient.java`

**Interfaces:**
- Consumes: `MonitorTargetPolicy.resolvePublic(String, MonitorDeadline, HostResolver)`, `MonitorAddressResolver`, `BoundHttpClient.execute(URI, String, MonitorDeadline)`, and `MonitorDeadline.after(Duration)`.
- Produces:

```java
public PingProbeResult ping(String rawHost) throws IOException;
public PortProbeResult checkPorts(String rawHost, List<Integer> ports) throws IOException;

public record PingProbeResult(
    String host, String resolvedIp,
    boolean icmpReachable, long icmpResponseMs,
    boolean httpReachable, Integer httpStatus, Long httpResponseMs,
    boolean online, Long avgResponseMs, String speed) {}

public record PortProbeResult(String host, List<PortResult> ports) {}
public record PortResult(int port, boolean open, long responseMs) {}
```

- Produces Spring bean qualifier `NetworkToolProbeConfiguration.PORT_EXECUTOR_BEAN` with value `networkToolPortProbeExecutor`.
- Adds a package-private injectable HTTP transport seam to `BoundHttpClient` only if required by the service test; the production constructor and security behavior remain unchanged.

- [ ] **Step 1: Write failing validation and binding tests**

Create `SafeNetworkProbeServiceTest` with real `MonitorTargetPolicy` decisions and injected test seams. Before writing production code, name the production change that makes each test pass: creation of `SafeNetworkProbeService` and direct use of approved addresses.

```java
@Test
void rejectsInvalidPortsBeforeResolving() {
    AtomicInteger resolutions = new AtomicInteger();
    SafeNetworkProbeService service = serviceWithResolver((host, deadline) -> {
        resolutions.incrementAndGet();
        return new InetAddress[] { InetAddress.getByName("93.184.216.34") };
    });

    assertThrows(IllegalArgumentException.class,
        () -> service.checkPorts("example.com", List.of(0, 443)));
    assertEquals(0, resolutions.get());
}

@Test
void portProbeConnectsToApprovedAddressInsteadOfResolvingHostAgain() throws Exception {
    InetAddress approved = InetAddress.getByName("93.184.216.34");
    AtomicReference<InetAddress> connected = new AtomicReference<>();
    SafeNetworkProbeService service = serviceWith(
        (host, deadline) -> new InetAddress[] { approved },
        (address, port, timeoutMillis) -> {
            connected.set(address);
            return true;
        });

    SafeNetworkProbeService.PortProbeResult result =
        service.checkPorts("example.com", List.of(443));

    assertEquals(approved, connected.get());
    assertTrue(result.ports().get(0).open());
}
```

Also cover:

- more than 20 ports and null port entries are rejected before resolution
- mixed public/private DNS answers are blocked and no connector runs
- IPv4-mapped, 6to4, and NAT64-embedded private answers are blocked
- result order matches the request even when task completion order differs
- executor rejection becomes an `IOException`
- deadline/interrupt cancels every unfinished Future
- Ping ICMP receives the approved `InetAddress`
- HTTPS failure falls back to HTTP under the same deadline
- private redirect is rejected by the existing `BoundHttpClient` path

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -pl wesite-web -am "-Dtest=SafeNetworkProbeServiceTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: test compilation fails because `SafeNetworkProbeService` and its result records do not exist. This is the intended RED, not a syntax or fixture failure.

- [ ] **Step 3: Implement the managed executor configuration**

Create `NetworkToolProbeConfiguration`:

```java
@Configuration(proxyBeanMethods = false)
class NetworkToolProbeConfiguration {
    static final String PORT_EXECUTOR_BEAN = "networkToolPortProbeExecutor";

    @Bean(name = PORT_EXECUTOR_BEAN)
    ThreadPoolTaskExecutor networkToolPortProbeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("network-tool-probe-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 4: Implement the minimal safe probe service**

Create `SafeNetworkProbeService` with constructor injection for the production resolver, qualified executor, bound HTTP client, reachability checker, port connector, and deadline factory. Keep test-only seams package-private; do not expose internal monitor types publicly.

Implementation rules:

```java
private static final Duration REQUEST_BUDGET = Duration.ofSeconds(15);
private static final int MAX_PORTS = 20;
private static final int CONNECT_STEP_TIMEOUT_MS = 2_000;

MonitorDeadline deadline = MonitorDeadline.after(REQUEST_BUDGET);
ResolvedTarget target = MonitorTargetPolicy.resolvePublic(rawHost, deadline, resolver);
```

For port work, submit one callable per requested port to the qualified executor. Each callable iterates only `target.addresses()` and invokes the connector with `deadline.timeoutMillis(CONNECT_STEP_TIMEOUT_MS)`. Wait using only the deadline's remaining budget, cancel all unfinished futures in `finally`, and rebuild results in request order.

For Ping, call the reachability seam on the first approved address, then use `BoundHttpClient` for HTTPS HEAD and HTTP fallback. A safe resolution followed by two unreachable protocols returns a normal result with `online=false`.

- [ ] **Step 5: Run service and existing monitor tests and verify GREEN**

Run:

```powershell
mvn -pl wesite-web -am "-Dtest=SafeNetworkProbeServiceTest,MonitorCollectorTest,MonitorAddressResolverTest,BoundHttpClientParsingTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all specified tests pass with zero failures and errors.

- [ ] **Step 6: Refactor without changing behavior**

Remove duplicate timing/cancellation code, keep public records immutable with `List.copyOf`, and ensure production error messages contain no resolved private address or raw exception. Re-run Step 5.

- [ ] **Step 7: Commit**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/monitor/SafeNetworkProbeService.java wesite-web/src/main/java/info/wesite/web/monitor/NetworkToolProbeConfiguration.java wesite-web/src/main/java/info/wesite/web/monitor/BoundHttpClient.java wesite-web/src/test/java/info/wesite/web/monitor/SafeNetworkProbeServiceTest.java
git commit -m "feat: add safe network probe service"
```

---

### Task 2: Migrate Ping API to the safe probe facade

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/api/PingTestController.java`
- Create: `wesite-web/src/test/java/info/wesite/web/controller/api/PingTestControllerTest.java`

**Interfaces:**
- Consumes: `SafeNetworkProbeService.ping(String)` and `PingProbeResult` from Task 1.
- Produces: the existing Ping response fields without controller-owned DNS, URL, socket, or timeout logic.

- [ ] **Step 1: Write failing controller tests**

Use `@WebMvcTest(PingTestController.class)` or a direct controller test with a mocked `SafeNetworkProbeService`. Name the production change that makes the tests pass: delegation to the facade and removal of raw exception details.

```java
@Test
void returnsProbeFieldsFromSafeService() throws Exception {
    when(probes.ping("example.com")).thenReturn(new PingProbeResult(
        "example.com", "93.184.216.34", false, 5,
        true, 200, 40L, true, 40L, "Excellent"));

    mockMvc.perform(post("/api/tools/ping")
            .contentType(APPLICATION_JSON)
            .content("{\"host\":\"example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.httpStatus").value(200))
        .andExpect(jsonPath("$.data.online").value(true));

    verify(probes).ping("example.com");
}

@Test
void sanitizesBlockedTargetFailure() throws Exception {
    when(probes.ping(anyString())).thenThrow(new IOException("internal resolver detail"));

    mockMvc.perform(post("/api/tools/ping")
            .contentType(APPLICATION_JSON)
            .content("{\"host\":\"blocked.example\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msg").value("Unable to probe this host."))
        .andExpect(content().string(not(containsString("internal resolver detail"))));
}
```

Also assert blank hosts fail without invoking the service, successful requests still record history, failed requests do not record a successful history item, and the current rate-limit call remains in place.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -pl wesite-web -am "-Dtest=PingTestControllerTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: failures show that the controller still performs direct DNS/HTTP work and does not delegate to `SafeNetworkProbeService`.

- [ ] **Step 3: Replace direct network work with delegation**

Constructor-inject `SafeNetworkProbeService` and `QueryHistoryRecorder`. Retain IP rate limiting and blank-host validation. Call `probes.ping(request.getHost())`, map every `PingProbeResult` field to the existing response keys, and record history only after a successful service result.

Delete controller imports and code for `InetAddress`, `URL`, `HttpURLConnection`, blocked-prefix lists, and duplicate timing/speed calculations. Catch `IllegalArgumentException` for `"Invalid host."`; catch `IOException` for `"Unable to probe this host."`; log the full failure server-side.

- [ ] **Step 4: Run Ping and security regression tests and verify GREEN**

Run:

```powershell
mvn -pl wesite-web -am "-Dtest=PingTestControllerTest,ProtectedApiAccessControlTest,SafeNetworkProbeServiceTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all tests pass, and static search shows no direct network primitive in the controller:

```powershell
rg -n "InetAddress|getByName|openConnection|new URL|HttpURLConnection" wesite-web/src/main/java/info/wesite/web/controller/api/PingTestController.java
```

Expected: no matches.

- [ ] **Step 5: Commit**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/controller/api/PingTestController.java wesite-web/src/test/java/info/wesite/web/controller/api/PingTestControllerTest.java
git commit -m "fix: bind ping probes to approved addresses"
```

---

### Task 3: Migrate Port Checker API to the safe probe facade

**Files:**
- Modify: `wesite-web/src/main/java/info/wesite/web/controller/api/PortCheckerController.java`
- Create: `wesite-web/src/test/java/info/wesite/web/controller/api/PortCheckerControllerTest.java`

**Interfaces:**
- Consumes: `SafeNetworkProbeService.checkPorts(String, List<Integer>)` and its `PortProbeResult`/`PortResult` records.
- Produces: the existing `host`, `ports`, `openCount`, and `closedCount` response fields with no controller-owned executor or socket.

- [ ] **Step 1: Write failing controller tests**

```java
@Test
void delegatesValidatedPortsAndPreservesResponseOrder() throws Exception {
    when(probes.checkPorts("example.com", List.of(443, 80))).thenReturn(
        new PortProbeResult("example.com", List.of(
            new PortResult(443, true, 12),
            new PortResult(80, false, 30))));

    mockMvc.perform(post("/api/tools/port-check")
            .contentType(APPLICATION_JSON)
            .content("{\"host\":\"example.com\",\"ports\":[443,80]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.openCount").value(1))
        .andExpect(jsonPath("$.data.ports[0].port").value(443));
}
```

Also assert:

- 21 ports are rejected instead of silently truncated
- port `0`, port `65536`, and null entries are rejected without service invocation
- empty/absent port lists and blank hosts are rejected
- raw `IOException` messages are not returned
- successful history records use the returned normalized host and counts

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -pl wesite-web -am "-Dtest=PortCheckerControllerTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: failures show silent list truncation, raw exception leakage, and direct executor/socket usage.

- [ ] **Step 3: Replace direct network and executor work with delegation**

Constructor-inject `SafeNetworkProbeService` and `QueryHistoryRecorder`. Validate request presence, host, list size, null ports, and port range before calling the service. Map service results to the existing port objects and add the current service-name labels in the controller through a pure `serviceName(int)` method.

Delete the controller's `ExecutorService`, `CompletableFuture`, `InetAddress`, `Socket`, blocked-prefix list, and `checkPort` method. Translate `IllegalArgumentException` to a stable validation message and `IOException` to `"Unable to check ports for this host."` while logging details server-side.

- [ ] **Step 4: Run Port and facade tests and verify GREEN**

Run:

```powershell
mvn -pl wesite-web -am "-Dtest=PortCheckerControllerTest,SafeNetworkProbeServiceTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Then run:

```powershell
rg -n "ExecutorService|CompletableFuture|InetAddress|getByName|new Socket|socket.connect" wesite-web/src/main/java/info/wesite/web/controller/api/PortCheckerController.java
```

Expected: no matches.

- [ ] **Step 5: Commit**

```powershell
git add -- wesite-web/src/main/java/info/wesite/web/controller/api/PortCheckerController.java wesite-web/src/test/java/info/wesite/web/controller/api/PortCheckerControllerTest.java
git commit -m "fix: bind port probes to approved addresses"
```

---

### Task 4: Integration verification and operational documentation

**Files:**
- Modify: `README.md`
- Test: `wesite-web/src/test/java/info/wesite/web/monitor/SafeNetworkProbeServiceTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/controller/api/PingTestControllerTest.java`
- Test: `wesite-web/src/test/java/info/wesite/web/controller/api/PortCheckerControllerTest.java`

**Interfaces:**
- Consumes: completed safe facade and migrated controllers.
- Produces: documented probe boundaries and complete verification evidence.

- [ ] **Step 1: Add an end-to-end probe-boundary regression test**

Add a service integration test with a resolver that first returns an approved public address and would return a private address on any later lookup. Inject a recording port connector and HTTP transport, execute Ping and Port Checker service operations, and assert that every connection receives only the originally approved `InetAddress`, the resolver call count is exactly the number of policy-required resolutions, and no connection receives a host name. Mutate the test connector locally to resolve the host again, run the test to observe RED, then restore the bound-address behavior and verify GREEN. This tests the externally meaningful connection boundary rather than controller source text.

- [ ] **Step 2: Document the security and capacity contract**

Add a concise README section stating:

- Ping and Port Checker only connect to policy-approved public IPs
- mixed DNS answers and unsafe redirects fail closed
- one request has a 15-second total budget
- port requests accept 1-20 ports in range `1..65535`
- port worker capacity is bounded at 8 threads plus a 32-task queue
- rate limiting remains per-instance pending a separate distributed-limiter change

- [ ] **Step 3: Run targeted tests**

```powershell
mvn -pl wesite-web -am "-Dtest=SafeNetworkProbeServiceTest,PingTestControllerTest,PortCheckerControllerTest,MonitorCollectorTest,MonitorAddressResolverTest,BoundHttpClientParsingTest,ProtectedApiAccessControlTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all targeted tests pass with zero failures/errors.

- [ ] **Step 4: Run complete Java and JavaScript verification**

```powershell
mvn test
$failed = 0
Get-ChildItem wesite-web/src/test/js -Filter '*.test.js' | ForEach-Object {
    node --test $_.FullName
    if ($LASTEXITCODE -ne 0) { $failed++ }
}
if ($failed -gt 0) { exit 1 }
```

Expected: the Maven reactor and every JavaScript test file pass.

- [ ] **Step 5: Package and inspect the final diff**

```powershell
mvn clean package -DskipTests
git diff --check
git status --short
git diff main...HEAD --stat
```

Expected: package succeeds, `git diff --check` is clean, and only files in this plan plus the approved spec/plan are changed.

- [ ] **Step 6: Commit documentation and final tests**

```powershell
git add -- README.md wesite-web/src/test/java/info/wesite/web/monitor/SafeNetworkProbeServiceTest.java wesite-web/src/test/java/info/wesite/web/controller/api/PingTestControllerTest.java wesite-web/src/test/java/info/wesite/web/controller/api/PortCheckerControllerTest.java
git commit -m "docs: record network probe safety contract"
```
