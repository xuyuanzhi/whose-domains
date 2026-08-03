# API Key Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the temporary API Key page with a responsive developer console and make API Docs link directly to it.

**Architecture:** Keep the current JSON API unchanged. Replace the page's inline markup and script with semantic sections and scoped CSS, using browser `fetch` calls to the existing endpoints. Add only template regression tests.

**Tech Stack:** Spring MVC, Thymeleaf, vanilla JavaScript, CSS, JUnit 5.

## Global Constraints

- Keep `/user/api-keys` as the authenticated page and `/user/api-keys/list` as the list endpoint.
- Never render or store a full API key after its one-time creation response is dismissed.
- Preserve the existing dark theme and support mobile widths below 700px.

---

### Task 1: API Key console template and styling

**Files:**
- Modify: `wesite-web/src/main/resources/views/user/api-keys.html`
- Modify: `wesite-web/src/main/resources/static/style/common.css`
- Test: `wesite-web/src/test/java/info/wesite/web/view/ApiKeyConsoleTemplateTest.java`

- [ ] Write a failing template test for `.api-key-console`, the one-time key panel, the empty state, and `navigator.clipboard.writeText`.
- [ ] Run `mvn -pl wesite-web -am -Dtest=ApiKeyConsoleTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test` and confirm the test fails.
- [ ] Build semantic console markup, keyboard-accessible creation controls, loading/error/empty states, copy action, and revoke confirmation around the existing fetch endpoints.
- [ ] Add scoped responsive CSS for the console, key rows, status cards, buttons, and one-time secret panel.
- [ ] Run the same test and confirm it passes.

### Task 2: API documentation conversion path

**Files:**
- Modify: `wesite-web/src/main/resources/views/api_docs.html`
- Modify: `wesite-web/src/test/java/info/wesite/web/view/ApiKeyConsoleTemplateTest.java`

- [ ] Extend the failing template test to require a `/user/api-keys` management link in the Public API section.
- [ ] Run the targeted template test and confirm it fails.
- [ ] Add a compact three-step API start panel and management link without changing endpoint documentation.
- [ ] Run the targeted template test and confirm it passes.

### Task 3: Regression verification

**Files:**
- Test: `wesite-web/src/test/java/info/wesite/web/view/ApiKeyConsoleTemplateTest.java`

- [ ] Run `git diff --check`.
- [ ] Run `mvn -pl wesite-web -am test`.
- [ ] Confirm all tests pass before reporting completion.
