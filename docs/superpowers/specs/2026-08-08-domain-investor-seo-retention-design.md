# Domain Investor SEO and Retention Design

## Objective

Turn Whose.Domains from a broad collection of domain tools and generic information pages into a focused domain-investment decision product. The primary user is a domain investor evaluating whether a domain is worth buying and monitoring promising candidates over time.

The product promise is:

> Evaluate whether a domain is worth buying, understand the evidence and risks, and monitor promising domains for meaningful changes.

The work covers three connected outcomes:

1. Restore technically consistent discovery and indexing.
2. Give indexable pages unique decision-making value.
3. Convert one-time lookups into saved candidates, alerts, and repeat visits.

## Scope and Delivery Order

Delivery is divided into four independently verifiable batches:

1. Technical indexing foundations.
2. Domain investment assessment report.
3. Investment candidate list and monitoring loop.
4. Content consolidation and investor-focused positioning.

The batches ship in this order. Technical correctness is established before Search Console results are used to evaluate content changes.

## 1. Technical Indexing Foundations

### Sitemap

`/sitemap_all.xml` remains the sole sitemap. Its name does not change.

The sitemap generator uses one canonical route registry instead of maintaining route strings that can drift from controller mappings. The generated sitemap includes only URLs that are:

- publicly accessible;
- expected to return HTTP 200;
- allowed to be indexed;
- canonical to themselves;
- intentionally supported as standalone search landing pages.

The generator must not publish temporary query results, failed lookups, empty domain reports, private user pages, or URLs whose only difference is a spelling or separator variant.

Automated tests reject known route drift, including underscore variants of routes whose public form uses hyphens. A deployment-level sitemap check verifies every listed URL after release.

### Robots discovery

The production `robots.txt` declares:

```text
Sitemap: https://whose.domains/sitemap_all.xml
```

The deployed response, rather than only the repository file, is tested.

### URL normalization

The canonical host and URL format are:

- HTTPS;
- `whose.domains` without `www`;
- `/` for the home page;
- no trailing slash for other HTML pages.

HTTP, `www`, and non-canonical trailing-slash variants permanently redirect to this format. Sitemap entries, canonical elements, Open Graph URLs, alternate links, navigation links, and generated share links use the same normalized URL.

Canonical URLs are produced by a shared URL normalizer. They are not constructed directly from the raw request URI.

### Index eligibility

Static tools and editorial pages have explicit index eligibility. Dynamic domain reports are eligible only when they contain enough stable, independent information to satisfy a visitor without requiring another search.

A domain report is `index,follow` when:

- domain syntax is valid;
- the lookup resolves to a real domain record or another supported registration state;
- sufficient core data is available to create an investment summary;
- the page is not an error, placeholder, or duplicate variant.

A report is `noindex,follow` when a lookup fails, critical data is unavailable, the domain input is invalid, or the report would primarily contain empty fields. Arbitrary user queries are not added to the sitemap merely because a URL was generated.

### Empty states

Tool landing pages do not render a large result panel filled with hyphens before a query. Before use, a page contains a concise explanation, supported inputs, methodology, and one clearly labelled real or deterministic example. Result markup appears after a successful query.

## 2. Domain Investment Assessment Report

### Report structure

The domain detail experience becomes a unified investment assessment report with five sections.

#### Investment conclusion

- overall investment score;
- valuation range;
- recommendation: watch, caution, or skip;
- confidence level;
- three to five decisive reasons.

The recommendation is deterministic and traceable to the displayed evidence. It is not presented as financial certainty.

#### Core metrics

- name length and character composition;
- TLD;
- registration age and expiry date;
- recent WHOIS and DNS changes;
- SSL, email, and baseline security state;
- hyphen, numeral, and potential trademark-risk signals.

#### Valuation evidence

- valuation formula and contributing coefficients;
- distinction between algorithmic valuation and comparable sales;
- source and timestamp for supporting data;
- explicit limitations and missing inputs.

The system never fabricates comparable sales. When verified comparable data is unavailable, the report says so and lowers confidence.

#### Risks and opportunities

- expiry, redemption, and deletion state;
- registrar locks and transfer restrictions;
- material DNS, website, and ownership-history changes;
- brandability, length, keyword category, and likely use cases;
- prioritized next actions.

#### Investor actions

- add to investment candidates;
- set a target acquisition price;
- add notes and tags;
- compare with another domain;
- export or share the report;
- enable expiry, WHOIS, DNS, and valuation-change monitoring.

### Unified assessment model

Controllers and templates consume a single domain-investment assessment object rather than independently interpreting raw WHOIS, RDAP, DNS, history, and valuation results.

The data flow is:

```text
Domain input
  -> normalization and validation
  -> WHOIS/RDAP, DNS, history, and valuation aggregation
  -> investment assessment model
  -> server-rendered indexable summary
  -> optional authenticated save and monitoring rules
```

The assessment model owns the conclusion, decisive reasons, confidence, source timestamps, missing-data state, and index eligibility. Data-source clients remain responsible only for retrieving and normalizing their source data.

### Partial failures

One unavailable source does not discard a useful report. Available sections render with their timestamps, while unavailable sections show a clear status and the last successful timestamp if one exists. Confidence decreases according to missing evidence.

If the remaining information cannot support a meaningful investment conclusion, the response remains usable for the visitor but is marked `noindex,follow`.

## 3. Investment Candidates and Retention

The existing Watchlist evolves into an investment candidate list. Existing saved domains remain compatible.

Each candidate supports:

- target acquisition price;
- private notes;
- user-defined tags;
- expiry countdown;
- current investment score and valuation range;
- last checked time;
- monitoring preferences.

The monitoring loop is:

```text
First assessment
  -> save candidate
  -> record target price, intended use, notes, and tags
  -> scheduled checks
  -> material-change detection
  -> deduplicated email alert
  -> before/after comparison and refreshed assessment
```

Material changes include expiry-state transitions, WHOIS ownership or registrar changes, nameserver changes, meaningful valuation changes, and monitoring failures that persist long enough to affect the user's decision.

Alerts are deduplicated by candidate, change type, and observed state. Email delivery failure is retryable and does not block later monitoring jobs. Users can access and modify only their own candidates and alert settings.

The primary action on an assessment report is “Add to candidates.” Related tools are presented as contextual next steps rather than a generic tool directory.

## 4. Content and Positioning

### Investor content clusters

Editorial content supports the investor's decision journey:

- finding and filtering expiring domains;
- judging whether a valuation is credible;
- understanding the effects of age, length, TLD, and keywords;
- reading WHOIS states and the deletion lifecycle;
- performing acquisition due diligence;
- reviewing reproducible domain-assessment case studies.

Every retained core guide includes, where applicable:

- a named author or reviewer;
- original publication and substantive update dates;
- primary sources;
- a reproducible example or first-hand analysis;
- limitations and common misinterpretations;
- a contextual path into the corresponding assessment tool.

### Existing content classification

Each existing information page is assigned one action:

- **Keep and improve:** directly supports an investor decision and can provide distinct value.
- **Merge and redirect:** overlaps substantially with a stronger guide; the old URL permanently redirects to the consolidated page.
- **Exclude from indexing:** is useful for navigation or support but lacks standalone search value.
- **Remove:** is obsolete, incorrect, or serves no user task; linked references are updated first.

Pages are not expanded merely to reach a word count. Dates change only after substantive edits. Keyword-swapped or mass-produced pages are not added.

### Homepage and navigation

The homepage leads with domain-investment assessment rather than an undifferentiated tool collection. It explains the evidence used, shows an example outcome, and directs visitors to assess a domain.

The first release changes positioning, information hierarchy, copy, and primary actions without a large visual redesign. Navigation gives prominence to Assessment, Expiring Domains, Candidates, Comparisons, and Investor Guides. Utility tools remain accessible but do not define the product.

## Error Handling and Trust

User-visible conclusions distinguish among:

- confirmed source data;
- calculated estimates;
- inferred signals;
- unavailable data.

Every external-data section displays freshness and source information. Stale cached data is labelled rather than presented as real time. Invalid input produces a clear validation response and does not create an indexable result URL.

Assessment and monitoring failures are observable through structured logs containing the normalized domain, source, failure category, and request or job identifier without storing sensitive user data in logs.

## Testing Strategy

### Technical indexing tests

- sitemap format and canonical route membership;
- absence of known non-canonical route variants;
- sitemap URLs resolve successfully in deployment checks;
- robots contains the production sitemap directive;
- canonical, Open Graph, alternate, and sitemap URLs agree;
- host, scheme, and trailing-slash redirects are permanent and converge in one hop where infrastructure permits;
- thin and failed reports emit `noindex,follow`.

### Assessment tests

- unit tests for score, valuation inputs, recommendation, confidence, decisive reasons, and index eligibility;
- boundary tests for missing and contradictory source data;
- integration tests for partial source failures;
- template tests proving that the key assessment summary is server-rendered;
- regression fixtures representing aged premium names, new brandable names, hyphenated names, unsupported TLDs, expired states, and incomplete records.

### Candidate and monitoring tests

- candidate creation, update, removal, notes, tags, target price, and authorization;
- material-change detection and before/after snapshots;
- alert deduplication and retry behavior;
- isolation between users;
- compatibility with existing Watchlist records.

### Content migration tests

- redirect map coverage;
- absence of redirect chains and loops;
- internal-link and canonical validation;
- structured-data validation for retained templates.

## Success Metrics

Search and technical metrics are evaluated over 8–12 weeks after the relevant batch ships:

- 100% of submitted sitemap URLs return the intended canonical HTTP 200 page;
- Google-selected canonicals match declared canonicals for sampled core pages;
- index coverage increases for core assessment, tool, and investor-guide pages;
- impressions and qualified clicks per indexed core page increase;
- crawl errors and duplicate canonical variants decline.

Product metrics include:

- successful assessment completion rate;
- assessment-to-candidate conversion rate;
- use of comparison, export, and share actions;
- 7-day and 30-day return rates;
- alert-driven return visits;
- candidate monitoring retention.

Index count alone is not a success criterion. A smaller set of useful, discoverable pages is preferred over a large set of thin indexed URLs.

## Rollout and Search Console Use

The rollout is gradual. Existing pages are not removed in one bulk change.

1. Deploy and verify technical indexing foundations.
2. Submit the corrected sitemap and inspect representative URLs in Search Console.
3. Release the assessment report for a controlled set of report states.
4. Release candidate metadata and monitoring improvements.
5. Consolidate editorial content in measured batches with redirect maps.

Search Console is used to compare exclusion reasons, canonical selection, crawl outcomes, and page-type performance before and after each batch. Repeated manual submission of every URL is avoided.

## Out of Scope for the First Implementation Cycle

- purchasing or bidding on domains;
- registrar marketplace integration;
- claiming exact market prices without reliable comparable-sales data;
- a full visual redesign;
- mass generation of domain or keyword landing pages;
- support for multiple primary audiences beyond domain investors;
- replacing all existing tools unrelated to the investment journey.
