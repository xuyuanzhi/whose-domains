# MCP endpoint and configurable API plans

This document covers the AI-client (MCP) surface and the configurable plan/quota infrastructure
added to Whose.Domains. It records what is implemented, how to enable it, and what remains
external.

## 1. What is implemented

### 1.1 Shared domain intelligence service

`wesite-core` now has a single source of truth for registration status:

- `info.wesite.core.service.DomainIntelService` / `DomainIntelServiceImpl`
- `info.wesite.core.model.RegistrationStatus` — `registered`, `available`, `unregistered_candidate`,
  `reserved`, `unknown`
- `info.wesite.core.model.DomainIntelResult` — status + `source` + `checkedAt` + errors

Resolution order: local database → RDAP → WHOIS, with DNS only ever able to *upgrade* the result to
`registered`. Key rules:

- RDAP `404` (no record) is now distinguished from network/parse errors via
  `RdapUtils.fetch(...)` → `RdapOutcome{OK, NOT_FOUND, ERROR}`.
- A missing DNS record, an HTTP failure or a failed RDAP query **never** yields `available`.
- `available` requires an authoritative registrability/purchase source, which this build does not
  have, so it is intentionally never emitted yet. "No record anywhere" yields
  `unregistered_candidate`.
- WHOIS reads now have a 15s read timeout in addition to the 10s connect timeout.

The existing website controllers were left as-is (their behaviour is unchanged); new surfaces reuse
this service.

### 1.2 MCP endpoint

- Package `info.wesite.web.mcp`, built on the official **MCP Java SDK 2.0.1**
  (`io.modelcontextprotocol.sdk:mcp`, Streamable HTTP servlet transport). No Spring AI dependency.
- Endpoint: `POST/GET/DELETE /mcp` (configurable). Tools:
  - `check_domain` — one domain: status, source, timestamp, registrar, expiry.
  - `get_domain_info` — one domain, full detail: dates, registry statuses, name servers, DNSSEC,
    WHOIS/RDAP servers, provenance, errors.
  - `check_domains` — small batch (default max 10, hard cap 50); duplicates collapsed; per-domain
    failures reported individually without failing the whole call.
- All tools are declared `readOnlyHint=true`, `destructiveHint=false`, `openWorldHint=true`.
- Anonymous free tier: per-IP requests/minute plus a per-subnet daily budget, enforced by
  `McpQuotaFilter` (Redis + `RateLimitUtils`). Metering fails **open** so a Redis outage cannot take
  the connector down.

### 1.3 Configurable plans and quota

- Table `WEB_API_PLAN` (see `doc/alter_api_plan.sql` and the canonical `doc/create.sql`), plus
  `SYS_USER.PLAN_ID`.
- `ApiPlanService.resolveForUser(userId)` — account plan, falling back to the free plan.
- `ApiQuotaService.consume(subject, plan, idempotencyKey, units)`:
  - daily + monthly limits from the plan;
  - atomic increment with rollback on overage, so counters never pass a limit under concurrency;
  - `Idempotency-Key` header makes retries free (no double charge).
- `GET /api/v1/domains/{domainName}` now enforces plan limits and accepts `Idempotency-Key`.
  Its success/401/400/429/503 contract is unchanged; the `quota` object now also includes
  `plan`, `monthlyLimit`, `monthlyUsed`.
- API key creation respects the plan's `MAX_ACTIVE_KEYS`.

## 2. Enabling the MCP endpoint

The endpoint is **off by default** so existing deployments are unaffected. Set:

| Env var | Default | Meaning |
| --- | --- | --- |
| `WESITE_MCP_ENABLED` | `false` | Master switch |
| `WESITE_MCP_ENDPOINT` | `/mcp` | Endpoint path |
| `WESITE_MCP_MAX_DOMAINS_PER_BATCH` | `10` | Batch size cap |
| `WESITE_MCP_REQUESTS_PER_MINUTE` | `60` | Anonymous per-IP limit |
| `WESITE_MCP_DAILY_REQUEST_LIMIT` | `1000` | Anonymous per-subnet daily budget |

Then restart `wesite-web`. Verify locally with the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector
# Streamable HTTP -> http://localhost:8080/mcp
```

To use it in ChatGPT: Settings → Apps & Connectors → Advanced settings → enable **Developer mode**,
then Create a connector pointing at `https://whose.domains/mcp` (no authentication).

## 3. Database migration

Apply once (no migration framework):

```bash
mysql -u <user> -p <database> < doc/alter_api_plan.sql
```

If `WEB_API_PLAN` is missing, the API falls back to a documented default free plan
(`wesite.api.fallback-daily-limit`, default `100`, matching the previous behaviour) and logs a
warning. Prices and limits are operator-editable data; business logic never references literals.

## 4. Deployment states (do not conflate)

1. **MCP service deployed** — achieved once `WESITE_MCP_ENABLED=true` and `/mcp` is reachable.
2. **Usable as a custom ChatGPT connector** — user-side (developer mode).
3. **Submitted for public review** — not done.
4. **Approved and publicly discoverable** — not done.

This phase targets states 1 and 2 only, per the agreed scope.

## 5. External dependencies / explicitly out of scope

- **OAuth 2.1 for per-user MCP identity** — **partially implemented** (see section 8). The resource
  server, protected-resource metadata and per-user quota plumbing are done and tested; the
  authorization code/token flow now passes a local filter-chain integration test and remains gated
  off by default. A real browser/client OAuth connection still needs deployment verification.
  Note the official portal does **not** support API-key auth for MCP connections, so OAuth (or `noauth`) is required for any future
  public submission.
- **Payments** — no integration. Only the plan/quota infrastructure exists. Per OpenAI plugin
  guidelines, digital subscriptions/credits may not be sold or advertised inside a plugin; paid API
  plans must be sold on whose.domains via external checkout.
- **Public directory submission** — requires verified organization identity, domain verification
  (`.well-known/openai-apps-challenge`), an OAuth or noauth server, listing URLs (website/support/
  privacy/terms), a square icon, 5 positive + 3 negative test cases, a demo video and a
  non-MFA reviewer test account.
- **UI** — implemented: `/pricing` (plan cards from `WEB_API_PLAN`), `/user/usage` (plan + remaining
  allowance + recent history via `GET /api/user/usage`), `/ai-connection` (MCP connection guide), plus
  navigation/footer links. Payments/checkout and billing pages are intentionally absent; plan changes
  are handled offline. The pages reuse the existing Thymeleaf layout.

## 6. Tests

Run offline (no database required):

```bash
mvn -pl wesite-web -am test
```

New coverage: `DomainIntelServiceImplTest`, `DomainNameValidatorTest`, `ApiQuotaServiceImplTest`,
`ApiPlanServiceImplTest`, `DomainIntelMcpToolsTest`, `McpQuotaFilterTest`,
`PublicDomainApiControllerTest`, `UsageControllerTest`, `PricingPagesTemplateTest`,
`McpEndpointEndToEndTest` and `McpOAuthAuthorizationFlowTest`.

`McpEndpointEndToEndTest` is a real end-to-end check of the connector wire protocol: it boots an
embedded servlet container hosting the Streamable HTTP transport and drives it with the official MCP
Java client (`initialize` → `tools/list` → `tools/call`), using a stubbed domain service. It needs no
MySQL, Redis or Spring context, so it runs anywhere:

```bash
mvn -pl wesite-web -am test -Dtest=McpEndpointEndToEndTest
```

Integration against a live MySQL and a real ChatGPT session are **not** covered automatically.

## 7. Local end-to-end run

Prerequisites: Java 17, MySQL and Redis reachable from `application-dev.properties` (default
`localhost:3306` / `localhost:6379`). Start Redis if needed:

```bash
docker run --rm -p 6379:6379 redis:7
```

Enable MCP and run the web app on a free port with the dev profile:

```bash
# Windows PowerShell
$env:WESITE_MCP_ENABLED = 'true'
mvn -pl wesite-web -am spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=18080
```

Then either use the MCP Inspector, or the bundled smoke script that performs the JSON-RPC
handshake directly:

```bash
npx @modelcontextprotocol/inspector     # Streamable HTTP -> http://localhost:18080/mcp

# pwsh (PowerShell 7) if available:
pwsh scripts/check-mcp.ps1 -BaseUrl http://localhost:18080 -Domain example.com
# Windows PowerShell 5.1 fallback:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-mcp.ps1 -BaseUrl http://localhost:18080 -Domain example.com
```

`check-mcp.ps1` calls `initialize`, `notifications/initialized`, `tools/list` and `tools/call`, so a
passing run proves a client can connect and invoke the tools against a live server.

### Verified live

This flow was executed against the packaged app (dev profile, MCP enabled, port 18080) with MySQL
and Redis running. `initialize` reported server `whose-domains 1.0.0` (protocol `2025-06-18`), all
three tools were listed, and `check_domain`, `check_domains` and `get_domain_info` each returned
`{"status":"registered","source":"database", ...}` for `example.com`. Redis-backed anonymous quota
and per-IP rate limiting were active during the calls.

## 8. OAuth 2.1 (local authorization flow verified)

All OAuth support is gated behind `wesite.mcp.oauth.enabled` (**default false**).

Implemented and verified:

- **Resource server** — `/mcp` validates bearer JWTs; an invalid token returns `401` (verified live),
  while anonymous requests still work (mixed auth).
- **Protected resource metadata** — `GET /.well-known/oauth-protected-resource` (RFC 9728) returns
  `resource`, `authorization_servers` and `scopes_supported` (verified live).
- **Authorization server metadata** — `GET /.well-known/oauth-authorization-server` (served by Spring
  Authorization Server) advertises PKCE `S256` (verified live).
- **Per-user identity** — `McpRequestContextExtractor` reads the token subject (user id) and passes it
  to the tools through the MCP transport context; authenticated calls are metered against the
  account's plan (`ApiQuotaService`) instead of the anonymous subnet budget. Unit-tested.
- `CookieAuthenticationFilter` authenticates the authorization endpoint with the existing session
  cookie so a signed-in user does not have to log in twice.

**Fixed on 2026-10-15:** signed-in requests to `/oauth2/authorize` previously fell through to a
404 because cookie authentication ran after `OAuth2AuthorizationEndpointFilter`. The cookie filter
now runs immediately after `SecurityContextHolderFilter`, so the authorization endpoint sees the
account identity before processing the request.

`McpOAuthAuthorizationFlowTest` first reproduced the 404 and then passed after the ordering fix. It
uses the real Spring Security filter chains, a signed site cookie, an S256 challenge and the token
endpoint to verify authorization-code exchange and JWT subject/audience/scope. It also checks code
replay rejection, incorrect PKCE rejection, and login redirects for missing or invalid cookies.
These are MockMvc integration checks, not a deployed browser/ChatGPT OAuth acceptance test.

Verification on 2026-10-15: `mvn -o test` passed across all three modules: 932 tests,
0 failures, 0 errors, 24 skipped (including all four new OAuth flow tests passing).

### Login continuation (2026-10-15)

Anonymous browser authorization now redirects through the existing `/login?returnTo=...` gateway
with a short `/mcp/authorize/continue/{random-id}` return address. The original authorization
query (including state, PKCE and callback parameters) remains server-side, so it is not truncated
by the 500-character login/email return-target limit. Google and email login keep using their
existing return-target plumbing; direct `/oauth2/*` return targets remain prohibited.

Continuations expire after 10 minutes, are consumed once after authentication, and require a
separate HttpOnly, SameSite=Lax browser-binding cookie scoped to that individual continuation.
The cookie is Secure on HTTPS. Continuations survive HTTP-session invalidation during Google
login. Email sign-in must finish in the same browser that started the MCP connection; a different
browser, expired request or replay receives an error instructing the user to restart the connection.
No-cache and no-referrer headers protect the continuation responses.

The store is bounded to 1,000 pending requests per application instance and authorization queries
are capped at 8,192 characters. Full capacity returns 503 without evicting active requests; expired
entries are reclaimed on creation. Like the current authorization-code storage, this store is
in memory: application restarts invalidate pending connections, and multi-instance deployment
requires sticky routing or a shared store before rollout. No database migration is needed.

Tests cover session-independent login continuation through code exchange, preservation of a long
state, missing/wrong browser cookies, expiry, single-use consumption, concurrent login isolation,
capacity recovery, Google return-target propagation and email login return/cookie issuance.
A combined-security-chain test also drives Google login initiation, the real success handler and
session cleanup, then feeds its actual site cookie into continuation, authorization and PKCE token
exchange, asserting the resulting account subject. Only the external Google identity/token exchange
and account lookup are stubbed. These are local tests, not a real Google/ChatGPT acceptance run.

Verification: `mvn -o test` passed with 941 tests, 0 failures/errors and 24 skipped. After adding
the combined Google/MCP test, a focused rerun of `McpOAuthAuthorizationFlowTest` passed all 6 tests.


### ChatGPT deployment preflight (2026-10-15)

The resource-server decoder now verifies the configured issuer, audience containing the configured
MCP resource, expiration, nonblank account subject, and at least one configured read scope. Both
space-delimited and array scope claims are accepted. Signed tokens for other issuers/resources or
without an allowed scope are rejected. Anonymous access remains available as before.

An explicitly configured `jwk-file` that is missing or not a regular file now fails startup instead
of silently generating a temporary signing key. Leaving the setting blank still generates a key
for local development. Automated tests verify that a token remains valid after loading the same
persisted signing key again; this does not imply in-memory authorization codes survive restarts.

The production configuration example now includes both disabled feature switches and an external
signing-key path (`/usr/java/config/web/mcp-signing.jwks.json`). No production key has been created,
no server configuration changed, and no release deployed in this preflight.

Read-only inspection found the running web JAR at `/usr/java/jar/wesite-web-1.0.0.jar`, using
`/usr/java/config/web/application-prod.properties`. Neither the process environment nor that
external file currently specifies MCP/OAuth switches, signing-key path or client callbacks.
The running layout differs from the newer immutable-release layout described in `deploy/README.md`;
confirm the actual service/restart mechanism before deploying rather than blindly using that runbook.

ChatGPT's exact redirect URI must be copied from its MCP connection management page. Do not guess
or allowlist a wildcard. Its callback may be connection-specific or stable depending on issuer
identification support. Client registration compatibility (manually configured client versus the
client-registration mode offered by the actual ChatGPT connection) must be checked before rollout.
Reference: https://developers.openai.com/plugins/build/auth (checked 2026-10-15).

Preflight verification: `mvn -o test` passed (949 tests, 0 failures/errors, 24 skipped),
followed by a successful `mvn -o -pl wesite-web -am package -DskipTests`. The resulting local JAR is
`wesite-web/target/wesite-web-1.0.0.jar`, SHA-256
`4f2e2dbc634150bdb0b01fc988e6b26a9abfb941c4870ae8805e9b20f0b6e3cb`.
At the time of the check, public `/mcp` and `/.well-known/oauth-authorization-server` both returned
404. The user confirmed that the ChatGPT connection has not yet been created. Deployment,
production signing-key provisioning and real ChatGPT authorization are still pending.


Next deployment checks: configure the actual client callback URI and persistent signing key; verify
the browser login/return flow and a real client connection before enabling OAuth in production.
Authorization and consent records are currently in memory and do not survive restarts.

Configuration (`application.properties`, all overridable by env):

| Property | Env var | Default |
| --- | --- | --- |
| `wesite.mcp.oauth.enabled` | `WESITE_MCP_OAUTH_ENABLED` | `false` |
| `wesite.mcp.oauth.issuer` | `WESITE_MCP_OAUTH_ISSUER` | `https://whose.domains` |
| `wesite.mcp.oauth.resource` | `WESITE_MCP_OAUTH_RESOURCE` | `https://whose.domains/mcp` |
| `wesite.mcp.oauth.client-id` | `WESITE_MCP_OAUTH_CLIENT_ID` | `chatgpt` |
| `wesite.mcp.oauth.client-secret` | `WESITE_MCP_OAUTH_CLIENT_SECRET` | (blank = public client) |
| `wesite.mcp.oauth.redirect-uris` | `WESITE_MCP_OAUTH_REDIRECT_URIS` | (none) |
| `wesite.mcp.oauth.scopes` | `WESITE_MCP_OAUTH_SCOPES` | `domain.read` |
| `wesite.mcp.oauth.jwk-file` | `WESITE_MCP_OAUTH_JWK_FILE` | (blank = ephemeral key) |

### MCP code-review fixes (2026-10-15)

Login continuations now allow at most 20 pending entries per source IP and 20 creations per
one-minute window, with HTTP 429 on either source limit. Consuming a continuation does not reset
its creation counter. Pending entries still expire after ten minutes and remain globally capped
at 1,000; the expiring source-window map is separately capped at 2,000. Sources use the servlet
remote address resolved by the configured trusted-proxy handling, not arbitrary forwarding
headers. This limits individual IPs; distributed and IPv6-subnet abuse still needs edge protection.

The JWT resource security chain now reads `wesite.mcp.endpoint`, matching the MCP servlet's
configured endpoint. A custom-path regression verifies that an invalid Bearer token returns 401.
Configured JWK files must pass an actual RS256 signing and verification probe during startup;
empty, public-only and encryption-only key sets fail startup instead of breaking token issuance
later. Probe tokens are neither returned nor logged.

Six regression cases first failed against the previous implementation and then passed with the
fixes. All 24 focused MCP OAuth tests passed. Full-suite/package verification is recorded below.

Final verification: `mvn -o test` passed with 955 tests, zero failures/errors and 24 skipped
(core: 146, web: 654, admin: 155). `mvn -o -pl wesite-web -am package -DskipTests` also passed.
The updated local JAR SHA-256 is
`c507041d67f38908ec5a4ce9fa0ec61dc5755edaccd895f1c60bc92655332460`.
This supersedes the earlier preflight artifact hash. No production deployment was performed.
