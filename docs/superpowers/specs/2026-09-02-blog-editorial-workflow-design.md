# Blog Editorial Workflow Design

## Purpose

Whose.Domains currently generates AI blog content inside `wesite-web` and writes it directly to `WEB_BLOG_POST`. Generated content must remain unpublished until an administrator reviews it. The existing repository has no blog management workflow, uses a generic database `UPDATE_TIME` for sitemap dates, and exposes manual AI-generation endpoints that are not safely authorized.

This design adds a small editorial workflow to `wesite-admin`, removes the unsafe manual-generation surface from `wesite-web`, sanitizes generated and edited HTML, and makes sitemap `lastmod` represent editorial changes instead of page views.

## Goals

- Store AI-generated articles as drafts without a fabricated individual author or publication date.
- Allow administrators to search, review, edit, preview, publish, and unpublish blog posts in `wesite-admin`.
- Sanitize blog HTML before it is stored and again before publication.
- Ensure only administrator tokens can access blog-management endpoints.
- Remove public and ineffective manual AI-generation endpoints from `wesite-web`.
- Make sitemap `lastmod` reflect content changes rather than `VIEW_COUNT` updates.
- Preserve every existing `/blog/*` public URL and public rendering route.

## Non-goals

- Replacing Layui or introducing a new frontend framework.
- Adding collaborative editing, version history, scheduled publishing, media uploads, or hard deletion.
- Changing existing published article slugs or `/blog/*` route behavior.
- Adding an external CMS.
- Making the liveness endpoint depend on MySQL or third-party services.

## Architecture

The implementation spans the existing `wesite-core`, `wesite-web`, and `wesite-admin` modules:

- `wesite-core` owns the `BlogPost` model, a reusable HTML sanitizer, and an editorial service that applies AI-draft creation and save/publish state transitions.
- `wesite-web` keeps the scheduled AI generator and public blog renderer. The generator creates drafts through the editorial service. Public manual generation endpoints are removed.
- `wesite-admin` exposes administrator-only JSON endpoints and a Layui source editor with a sandboxed preview.
- The sitemap task reads an explicit editorial timestamp from `BlogPost`.

The editorial service is shared so creation, save, and publish rules are not duplicated or bypassed. Controllers and scheduled tasks map inputs into dedicated command objects; they do not mutate persistence entities directly from request bodies.

## Data Model and Migration

Add the nullable column below to `WEB_BLOG_POST`:

```sql
CONTENT_UPDATED_AT DATETIME NULL COMMENT 'Last material editorial content update'
```

`BlogPost` gains a `Date contentUpdatedAt` property.

The implementation updates the canonical fresh-install schema in `doc/create.sql` and adds a separate, idempotent alteration script for existing installations.

Migration behavior:

- Existing published rows receive `CONTENT_UPDATED_AT = PUBLISH_DATE` when `PUBLISH_DATE` is present and the new column is null.
- Existing drafts keep `CONTENT_UPDATED_AT` null unless they already contain content that is saved through the new workflow.
- `UPDATE_TIME` remains the generic row-operation timestamp and continues to change for non-editorial updates such as view counts.

Timestamp rules:

- Creating an AI draft sets `CONTENT_UPDATED_AT` to its creation time.
- Saving changed title, draft slug, summary, body, author, category, tags, meta title, or meta description sets `CONTENT_UPDATED_AT` to the current time.
- Saving without a material editorial change preserves `CONTENT_UPDATED_AT`.
- First publication sets `PUBLISH_DATE` when it is null.
- Re-publication preserves the original `PUBLISH_DATE`.
- Publication ensures `CONTENT_UPDATED_AT` is non-null.
- Unpublishing changes only status and normal row audit fields.

The sitemap emits `lastmod` from `CONTENT_UPDATED_AT`, falling back to `PUBLISH_DATE`. It omits `lastmod` when both are null. BlogPosting JSON-LD uses the same values for `dateModified`. `UPDATE_TIME` is never used for sitemap or structured-data editorial dates.

## HTML Sanitization

`wesite-core` provides one sanitizer based on jsoup. It accepts an HTML fragment and returns a safe fragment while preserving the article structure needed by current prompts.

Allowed elements:

- `p`, `h2`, `h3`, `h4`
- `ul`, `ol`, `li`
- `strong`, `em`, `code`, `pre`, `blockquote`, `br`
- `a`
- `table`, `thead`, `tbody`, `tr`, `th`, `td`

Allowed attributes and protocols:

- `a[href]` with relative URLs or `https`, `http`, and `mailto` protocols.
- `a[title]`.
- No inline style, class, ID, form, media, iframe, SVG, or event-handler attributes.

The sanitizer removes `script`, `style`, `iframe`, executable attributes, and unsafe URL protocols. Relative Whose.Domains tool links remain relative. Empty or fully stripped content cannot be published.

Sanitization occurs:

1. Before an AI-generated draft is persisted.
2. Whenever an administrator saves edited content.
3. Immediately before publication as defense in depth.
4. Before public rendering as the final trust boundary, including for historical rows created before this workflow.

Deployment also performs a one-time application-level sanitization backfill over existing, non-deleted posts. It is an explicit maintenance command, never an automatic startup action, and processes rows in bounded batches. The command supports a dry-run that reports the IDs and slugs whose content would change. It runs only after a database backup, is safe to rerun, and sets `CONTENT_UPDATED_AT` to the backfill time only for rows whose persisted HTML changes. Render-time sanitization remains in place after the backfill so direct database writes cannot introduce executable HTML.

The admin preview endpoint returns the sanitized fragment. The browser renders it in an iframe with a restrictive `sandbox` attribute and without script permissions.

## Editorial State Transitions

Supported states remain the existing constants:

- `0`: draft
- `1`: published

Supported operations:

### Create AI Draft

- Accepts a dedicated draft command rather than a persistence entity.
- Generates the ID and records `CREATE_BY = ai` and `CREATE_TIME`.
- Normalizes and validates the slug, rejects an empty normalized slug, and rejects duplicates.
- Sanitizes body HTML and rejects content that becomes empty.
- Forces draft status, a null publication date, and a zero view count.
- Sets `CONTENT_UPDATED_AT` to the creation time.
- Persists the draft and its audit fields in one transaction.

### Save

- Loads the existing row by ID; request bodies cannot create arbitrary published records.
- Validates a nonblank title and slug.
- Allows slug changes only while the post is a draft. Published posts require the stored slug to remain unchanged so existing public URLs cannot break.
- Normalizes a draft slug to lowercase ASCII letters, digits, and hyphens, rejects an empty result, and rejects a collision with another post.
- Copies only editable fields.
- Sanitizes body HTML.
- Preserves status and publication date.
- Compares normalized values that would actually be persisted, rather than unsanitized request text, when detecting a material editorial change.
- Updates `CONTENT_UPDATED_AT` only for a material editorial change.

### Publish

- Loads the post by ID.
- Revalidates title, slug, summary, body, and meta description.
- Sanitizes the stored body again.
- Rejects content that becomes empty after sanitization.
- Updates `CONTENT_UPDATED_AT` when defense-in-depth sanitization changes the persisted body.
- Sets status to published.
- Sets the publication date only on first publication.
- Ensures an editorial timestamp exists.

### Unpublish

- Loads the post by ID.
- Sets status to draft.
- Preserves publication and editorial timestamps for audit and later re-publication.

There is no hard-delete endpoint in this workflow.

Input limits match the database schema: slug 200 characters, title and meta title 300, summary and meta description 600, author and category 100, and tags 300. HTML source is limited to 1 MiB at the application boundary. Values are trimmed, blank optional values are normalized to null, and validation occurs before persistence. Character limits apply after slug normalization where applicable.

## Administrator Authorization

`wesite-admin` changes from implicit public access to a default-deny controller policy. Every admin `HandlerMethod` requires an authenticated administrator unless the method is explicitly annotated `AccessControl(Level.NONE)`. Only the SPA entry page and login endpoint are explicitly public; static resources and the existing error path remain interceptor exclusions. Existing Swagger and API-documentation exclusions are removed in production so they do not bypass the default-deny policy; documentation may be enabled only in a local development profile. Existing user, domain-management, contact-management, user-info, logout, and new blog endpoints are protected. This affects only the separately deployed admin application and does not change public `wesite-web` `/domain/*` or `/blog/*` routes.

JWT verification establishes token integrity and extracts the user ID, but it is not treated as sufficient role evidence. `AdminInterceptor` becomes a Spring-managed bean with constructor-injected `UserService`. For every protected request it reloads the user by ID and accepts the request only when the database row is non-deleted, active, and has `User.TYPE_ADMIN`. This immediately enforces account disabling and role removal. A valid public-site user token is insufficient.

Protected JSON endpoints always return a JSON `ResponseJson` envelope instead of redirecting based on `X-Requested-With`. For compatibility with the current Layui request wrapper, authentication failure uses HTTP 200 with envelope `code = 401`; the Layui `logout` response code is changed from `1001` to `401` so the client clears the token and returns to login. Protected HTML routes may retain redirect behavior. Login and the static SPA resources remain publicly reachable.

The following `wesite-web` endpoints are removed:

- `POST /blog/internal/generate`
- `POST /api/admin/ai/blog/generate`

Manual AI generation is not part of the editorial workflow. The existing scheduled task remains the only generator and writes drafts for later review.

## Admin API

The admin module adds a controller under `/blog` with these JSON operations:

- `POST /blog/list`: paginated search by keyword and optional status; list rows omit full body content. Page must be at least 1, limit must be between 1 and 100, status must be absent, draft, or published, and keyword is limited to 200 characters.
- `POST /blog/detail`: fetch one post by ID for editing.
- `POST /blog/save`: save editable fields without changing publication state.
- `POST /blog/preview`: return sanitized HTML without persisting it.
- `POST /blog/publish`: publish one post by ID.
- `POST /blog/unpublish`: return one published post to draft status.

All operations use dedicated request and response DTOs inside the repository's `ResponseJson` envelope; persistence entities are not bound directly. Invalid IDs, invalid state, field-length violations, slug collisions, blank required fields, and content stripped to empty produce a failure response without a partial update.

## Admin UI

The existing Layui SPA receives a `博客管理` menu entry and two views:

- List view: keyword search, draft/published filter, pagination, status badges, edit, publish, unpublish, and public-view action for published posts.
- Edit view: title, slug, summary, author, category, tags, meta title, meta description, and HTML source fields. Slug is editable for drafts and read-only for published posts.

The edit view provides:

- Save draft/save changes.
- Preview sanitized content in a sandboxed iframe.
- Publish only after a successful save.
- Clear error and success messages using existing Layui patterns.

All dynamic values inserted into table markup are escaped. Full body content is fetched only by the detail endpoint.

## Public Rendering and SEO

Public `/blog`, `/blog/category/{category}`, and `/blog/{slug}` routes remain unchanged and continue to query only published rows. Drafts cannot be rendered through the public blog controller.

Public rendering passes stored article HTML through the shared sanitizer before assigning it to the view model. The database value is not rewritten during an ordinary page request.

BlogPosting JSON-LD is built as a server-side structured object and serialized as JSON instead of concatenating author JSON inside the template. A blank author is normalized to null and produces the existing Whose.Domains organization fallback. `dateModified` uses `CONTENT_UPDATED_AT`, falls back to `PUBLISH_DATE`, and never uses `UPDATE_TIME`. The removal of fabricated tool ratings remains unchanged.

## Error Handling and Concurrency

- Editorial service operations use transactions.
- Audit fields identify the actor: `ai` for scheduled generation and the current administrator ID for admin saves, publication, and unpublication.
- Save, publish, and unpublish load the target row with a database row lock inside the transaction, then validate its current state before mutation. This preserves the first publication timestamp and prevents double-submit state races without adding an optimistic-lock column.
- A failed validation or persistence operation returns failure without changing status.
- Duplicate slugs are rejected by application validation and the existing unique database index.
- The implementation does not add optimistic-lock columns; concurrent editors remain last-writer-wins after serialized row locking. The admin UI disables action buttons while a request is pending, and database uniqueness remains the final collision guard.
- AI-generation failures remain logged and do not create partial posts.

## Testing

Tests exercise behavior rather than source-text implementation details:

- Sanitizer preserves the allowed article structure and relative tool links while removing scripts, event handlers, iframes, styles, and unsafe protocols.
- AI generation persists sanitized, unowned draft content with no publication date.
- After removal, `POST /blog/internal/generate` returns the framework-native 405 because the public GET slug route still matches the path, while `POST /api/admin/ai/blog/generate` returns 404 because its controller mapping no longer exists.
- Admin authorization rejects missing, invalid, inactive, deleted, and non-admin users; accepts an active administrator resolved from the database; keeps only login and the SPA entry explicitly public; protects existing admin controllers by default; and prevents production API documentation from bypassing the policy.
- Admin authentication failures return the JSON envelope code expected by the Layui client, which clears the stored token.
- AI draft creation uses the editorial service, rejects empty or duplicate slugs and stripped-empty HTML, records audit fields, and initializes the editorial timestamp.
- Editorial save sanitizes content and updates the editorial timestamp only for material changes.
- Published article saves cannot change slug; draft slug changes update the editorial timestamp.
- Publish validates content, sets first publication time, preserves it on re-publication, and prevents empty sanitized content.
- Unpublish preserves publication and editorial timestamps.
- Sitemap and BlogPosting JSON-LD use editorial time, fall back to publication time, and never use generic update time.
- Historical and newly stored unsafe HTML is neutralized before public rendering; the explicit migration command is not run at startup, supports dry-run and bounded idempotent batches, reports changed rows, and advances editorial time only when persisted HTML changes.
- Admin list/detail/preview endpoints return the expected `ResponseJson` contracts.
- Admin list validation enforces pagination, status, keyword, field-length, and request-size bounds.
- Admin templates expose the approved list, edit, preview, publish, and unpublish controls.

Targeted tests run after every red-green cycle. Final verification runs the complete Maven reactor tests for `wesite-core`, `wesite-web`, and `wesite-admin`, followed by `git diff --check`.

## Deployment

1. Back up `WEB_BLOG_POST`.
2. Apply the additive, idempotent `CONTENT_UPDATED_AT` migration and backfill published rows whose new column is null.
3. Run the historical HTML sanitizer in dry-run mode and retain its changed-row report.
4. Run the sanitizer backfill, then deploy the updated admin and web artifacts.
5. Verify the admin SPA and login remain public while existing and new admin data endpoints reject unauthenticated and non-admin users, and production API-documentation paths are not publicly exposed.
6. Verify an administrator can list, edit, and safely preview drafts, and cannot change a published slug.
7. Publish a controlled draft and verify its public page, sanitized output, BlogPosting `dateModified`, and sitemap `lastmod`.
8. Verify both removed manual-generation URLs no longer accept POST generation requests.
9. Keep the current public `/domain/*` and `/blog/*` URL behavior unchanged.

Binary rollback may deploy the previous artifacts while leaving the nullable additive column in place because the column does not break earlier application versions. Rolling back the one-time HTML content changes requires restoring the backed-up content for the affected IDs from the dry-run report.
