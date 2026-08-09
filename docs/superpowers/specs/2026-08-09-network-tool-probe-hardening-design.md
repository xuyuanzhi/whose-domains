# Network Tool Probe Hardening Design

## Objective

Harden the public Ping and Port Checker APIs against server-side request forgery, DNS rebinding, unsafe redirects, unbounded work, and unmanaged executor lifecycles while preserving their existing request and successful-response shapes.

## Scope

This change covers only:

- `POST /api/tools/ping`
- `POST /api/tools/port-check`
- shared outbound target validation, HTTP probing, socket probing, deadlines, and executor configuration used by those endpoints

It does not redesign authentication, global API error envelopes, distributed rate limiting, or other domain-analysis endpoints. Those remain separate improvement slices.

## Architecture

Add a public `SafeNetworkProbeService` facade in `info.wesite.web.monitor`. The facade is the only outbound-network dependency used by the two controllers. Keeping it in the monitor package allows it to reuse the package-private, reviewed security primitives without making their internals public:

- `MonitorTargetPolicy` normalizes host names and rejects any address that is not globally reachable.
- `MonitorAddressResolver` performs bounded DNS resolution.
- `BoundHttpClient` connects to the exact approved IP while retaining the original Host header, TLS SNI, and hostname verification, and revalidates every redirect.
- `MonitorDeadline` supplies one monotonic total budget shared by DNS resolution and every subsequent network step.

The facade exposes narrow result records rather than raw sockets or internal monitor types:

```java
public PingProbeResult ping(String rawHost) throws IOException;
public PortProbeResult checkPorts(String rawHost, List<Integer> ports) throws IOException;
```

`PingProbeResult` retains the fields required by the current controller response: normalized host, approved IP, ICMP reachability and latency, HTTP reachability/status/latency, overall online state, average latency, and speed label. `PortProbeResult` contains the normalized host and ordered per-port results.

## Target Resolution and Connection Rules

Each operation creates one 15-second `MonitorDeadline`. Host normalization and DNS resolution happen through `MonitorTargetPolicy.resolvePublic`. A target is accepted only when every returned address is globally reachable; mixed public/private DNS answers fail closed.

Ping performs reachability checks against approved `InetAddress` objects, never by resolving the host again. HTTP HEAD first tries HTTPS and then HTTP only when HTTPS cannot produce a response, with both attempts sharing the same deadline. `BoundHttpClient` re-resolves and validates each redirect target and binds the connection to an approved IP.

Port checks resolve once, then connect directly to approved `InetAddress` objects. They never pass the original host to `Socket.connect`. Multiple approved public addresses may be tried within the same deadline; no connection attempt resets the budget.

The shared policy continues to fail closed for loopback, private, link-local, multicast, CGNAT, documentation, benchmarking, metadata-reachable special ranges, IPv4-mapped IPv6, 6to4, NAT64-embedded non-public IPv4, and unallocated IPv6 space.

## Input Validation

Controllers continue to accept the current JSON request bodies, but validation becomes explicit:

- host must be present and must normalize as a host name or IP literal; URL paths, user-info, and malformed IDNs are rejected
- port list must contain between 1 and 20 entries
- every port must be non-null and between 1 and 65535
- invalid input returns the existing `ResponseJson.failure(...)` envelope and performs no DNS or socket work

The implementation rejects an oversized port list instead of silently truncating it.

## Concurrency and Lifecycle

Add a Spring-managed `ThreadPoolTaskExecutor` dedicated to port probes:

- core pool size: 4
- maximum pool size: 8
- queue capacity: 32
- thread name prefix: `network-tool-probe-`
- wait for tasks on shutdown: enabled
- await termination: 10 seconds
- rejected work fails immediately

At most 20 tasks are submitted for one request. The service waits only for the operation's remaining deadline. Timeout, interruption, rejection, or another terminal failure cancels every unfinished future. Results remain in the same order as the requested ports.

DNS continues to use the existing separately bounded resolver executor; port work must not consume resolver capacity.

## Error Handling

The service uses typed `IOException` failures for invalid/blocked targets, resolution exhaustion, deadline expiry, rejected execution, and transport failure. Controllers translate these into stable user-facing failure messages without returning raw exception details. Server logs may retain the full exception with the normalized target where safe.

An individual closed or unreachable port is a normal port result, not a request failure. A total deadline or executor-capacity failure is a request failure. Ping returns a successful probe result when the target was safely resolved even if ICMP and HTTP are both unreachable.

## Test Strategy

All production changes follow red-green-refactor.

Service-level tests use injected resolvers, transports, socket connectors, and executors to prove real decision behavior without external network access:

- private, mixed public/private, IPv4-mapped, 6to4, and NAT64-embedded private targets are rejected before connection
- a resolver that changes from public to private cannot redirect a bound port connection to the private address
- HTTP redirects are re-resolved and a private redirect is rejected
- ICMP and port probes receive the approved `InetAddress`, not the original host
- invalid ports and more than 20 ports perform no resolution
- timeout and executor rejection cancel unfinished port tasks
- result order matches request order
- HTTPS-to-HTTP fallback shares one deadline

Controller tests verify request validation, stable response fields, rate-limit interaction, history recording, and sanitized failures. Existing monitor collector and HTTP parser tests remain unchanged and must continue to pass.

## Acceptance Criteria

- Neither controller calls `InetAddress.getByName`, `URL.openConnection`, nor connects a socket by host name.
- Every outbound connection uses an address approved by the shared target policy.
- Redirects are capped and revalidated.
- One non-resetting deadline bounds each complete request.
- Port concurrency is Spring-managed, bounded, cancellable, and shut down cleanly.
- Existing successful API response fields remain compatible.
- Targeted tests, the full Maven suite, JavaScript tests, and packaging pass.
