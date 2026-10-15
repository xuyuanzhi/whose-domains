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

- **OAuth 2.1 for per-user MCP identity** — deferred. The plugin is anonymous/read-only for now.
  Note the official portal does **not** support API-key auth for MCP connections, so OAuth (or
  `noauth`) is required for any future public submission.
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
`PublicDomainApiControllerTest`. Integration against a live MySQL and an end-to-end MCP client
session with ChatGPT are **not** covered here and remain to be validated.
