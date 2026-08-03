# API Key Console Design

## Goal

Make API-key creation and management understandable, safe, and usable on desktop and mobile while keeping Whose.Domains' existing dark developer-oriented visual system.

## Audience and page job

The audience is a signed-in developer who wants a key for the public domain API. The page's job is to show the shared quota, let the developer create or revoke a key, and make the one-time secret easy to copy without ever displaying it again after dismissal.

## Selected approach

Use a focused developer-console layout rather than a generic settings form. A compact status strip communicates the shared 100-request daily allowance and three-key maximum. Below it, a single clear primary action opens an inline creation panel; existing keys are displayed as rows with a prefix, creation date, last-used date, and a deliberately separated destructive revoke action.

## Visual system

- Preserve the existing navy background and cyan primary accent from `common.css`.
- Use an API-key-specific CSS namespace (`.api-key-*`) rather than inline styles.
- Use a monospace treatment only for key prefixes, response examples, and endpoint paths.
- Use a restrained cyan-to-violet accent line as the page signature; do not introduce a second visual theme.
- Keep focus rings, readable contrast, and responsive single-column behavior below 700px.

## Interaction design

- The create button reveals an inline labelled text field; Enter creates a key and Escape/cancel closes the panel.
- Creation disables the submit control while the request is pending.
- The newly created full key appears in a warning-styled one-time panel with a Copy key action and a dismiss action. It is never retained in page state after dismissal.
- Empty, loading, and request-failure states give specific next actions.
- Revoke uses a confirmation dialog before issuing the existing DELETE request, then reloads the list.

## Related API Docs changes

The Public API block gains a prominent signed-in destination link to API Key management and a short three-step usage path. Existing endpoint documentation remains intact.

## Data and API constraints

- Continue to use `GET /user/api-keys/list`, `POST /user/api-keys`, and `DELETE /user/api-keys/{id}`.
- Continue to expose only key prefix and metadata after creation.
- Enforce the existing three-active-key and shared 100-per-day account limits server-side; the UI explains these limits but does not duplicate enforcement.

## Verification

- Template tests assert that the management link, one-time key warning, empty state, and copy interaction markup remain present.
- Run `mvn -pl wesite-web -am test` and `git diff --check` after implementation.
