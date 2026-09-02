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

- `wesite-core` owns the `BlogPost` model, a reusable HTML sanitizer, and an editorial service that applies save/publish state transitions.
- `wesite-web` keeps the scheduled AI generator and public blog renderer. Generated content is sanitized and stored as a draft. Public manual generation endpoints are removed.
- `wesite-admin` exposes administrator-only JSON endpoints and a Layui source editor with a sandboxed preview.
- The sitemap task reads an explicit editorial timestamp from `BlogPost`.

The editorial service is shared so save and publish rules are not duplicated across controllers. Controllers map requests and responses; they do not mutate persistence entities directly from request bodies.

## Data Model and Migration

Add the nullable column below to `WEB_BLOG_POST`:

```sql
CONTENT_UPDATED_AT DATETIME NULL COMMENT 'Last material editorial content update'
```

`BlogPost` gains a `Date contentUpdatedAt` property.

Migration behavior:

- Existing published rows receive `CONTENT_UPDATED_AT = PUBLISH_DATE` when `PUBLISH_DATE` is present.
- Existing drafts keep `CONTENT_UPDATED_AT` null unless they already contain content that is saved through the new workflow.
- `UPDATE_TIME` remains the generic row-operation timestamp and continues to change for non-editorial updates such as view counts.

Timestamp rules:

- Saving changed title, summary, body, author, category, tags, meta title, or meta description sets `CONTENT_UPDATED_AT` to the current time.
- Saving without a material editorial change preserves `CONTENT_UPDATED_AT`.
- First publication sets `PUBLISH_DATE` when it is null.
- Re-publication preserves the original `PUBLISH_DATE`.
- Publication ensures `CONTENT_UPDATED_AT` is non-null.
- Unpublishing changes only status and normal row audit fields.

The sitemap emits `lastmod` from `CONTENT_UPDATED_AT`, falling back to `PUBLISH_DATE`. It omits `lastmod` when both are null. `UPDATE_TIME` is never used for sitemap dates.

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

The admin preview endpoint returns the sanitized fragment. The browser renders it in an iframe with a restrictive `sandbox` attribute and without script permissions.

## Editorial State Transitions

Supported states remain the existing constants:

- `0`: draft
- `1`: published

Supported operations:

### Save

- Loads the existing row by ID; request bodies cannot create arbitrary published records.
- Validates a nonblank title and slug.
- Normalizes the slug to lowercase ASCII letters, digits, and hyphens and rejects a collision with another post.
- Copies only editable fields.
- Sanitizes body HTML.
- Preserves status and publication date.
- Updates `CONTENT_UPDATED_AT` only for a material editorial change.

### Publish

- Loads the post by ID.
- Revalidates title, slug, summary, body, and meta description.
- Sanitizes the stored body again.
- Rejects content that becomes empty after sanitization.
- Sets status to published.
- Sets the publication date only on first publication.
- Ensures an editorial timestamp exists.

### Unpublish

- Loads the post by ID.
- Sets status to draft.
- Preserves publication and editorial timestamps for audit and later re-publication.

There is no hard-delete endpoint in this workflow.

## Administrator Authorization

The new blog-management controller is protected at controller level with the existing `AccessControl` mechanism. `AdminInterceptor` is tightened so a verified token is accepted for protected admin routes only when its user has `User.TYPE_ADMIN`. A valid public-site user token is insufficient.

Login and static SPA resources remain publicly reachable so the existing admin login flow continues to work. The new blog JSON endpoints return the existing unauthorized response contract when the administrator token is absent or invalid.

The following `wesite-web` endpoints are removed:

- `POST /blog/internal/generate`
- `POST /api/admin/ai/blog/generate`

Manual AI generation is not part of the editorial workflow. The existing scheduled task remains the only generator and writes drafts for later review.

## Admin API

The admin module adds a controller under `/blog` with these JSON operations:

- `POST /blog/list`: paginated search by keyword and optional status; list rows omit full body content.
- `POST /blog/detail`: fetch one post by ID for editing.
- `POST /blog/save`: save editable fields without changing publication state.
- `POST /blog/preview`: return sanitized HTML without persisting it.
- `POST /blog/publish`: publish one post by ID.
- `POST /blog/unpublish`: return one published post to draft status.

All operations use the repository's `ResponseJson` envelope. Invalid IDs, invalid state, slug collisions, blank required fields, and content stripped to empty produce a failure response without a partial update.

## Admin UI

The existing Layui SPA receives a `博客管理` menu entry and two views:

- List view: keyword search, draft/published filter, pagination, status badges, edit, publish, unpublish, and public-view action for published posts.
- Edit view: title, slug, summary, author, category, tags, meta title, meta description, and HTML source fields.

The edit view provides:

- Save draft/save changes.
- Preview sanitized content in a sandboxed iframe.
- Publish only after a successful save.
- Clear error and success messages using existing Layui patterns.

All dynamic values inserted into table markup are escaped. Full body content is fetched only by the detail endpoint.

## Public Rendering and SEO

Public `/blog`, `/blog/category/{category}`, and `/blog/{slug}` routes remain unchanged and continue to query only published rows. Drafts cannot be rendered through the public blog controller.

The existing organization fallback remains the structured-data author when a post has no individual author. The removal of fabricated tool ratings remains unchanged.

## Error Handling and Concurrency

- Editorial service operations use transactions.
- Updates target the loaded row ID and validate current state before mutation.
- A failed validation or persistence operation returns failure without changing status.
- Duplicate slugs are rejected by application validation and the existing unique database index.
- The implementation does not add optimistic-lock columns; the admin UI is assumed to have one editor. Database uniqueness remains the final collision guard.
- AI-generation failures remain logged and do not create partial posts.

## Testing

Tests exercise behavior rather than source-text implementation details:

- Sanitizer preserves the allowed article structure and relative tool links while removing scripts, event handlers, iframes, styles, and unsafe protocols.
- AI generation persists sanitized, unowned draft content with no publication date.
- Public manual AI-generation routes return 404 after removal.
- Admin authorization rejects missing, invalid, and non-admin tokens and accepts an administrator token.
- Editorial save sanitizes content and updates the editorial timestamp only for material changes.
- Publish validates content, sets first publication time, preserves it on re-publication, and prevents empty sanitized content.
- Unpublish preserves publication and editorial timestamps.
- Sitemap uses editorial time, falls back to publication time, and never uses generic update time.
- Admin list/detail/preview endpoints return the expected `ResponseJson` contracts.
- Admin templates expose the approved list, edit, preview, publish, and unpublish controls.

Targeted tests run after every red-green cycle. Final verification runs the complete Maven reactor tests for `wesite-core`, `wesite-web`, and `wesite-admin`, followed by `git diff --check`.

## Deployment

1. Back up `WEB_BLOG_POST`.
2. Apply the additive `CONTENT_UPDATED_AT` migration and backfill published rows.
3. Deploy the updated admin and web artifacts.
4. Verify an administrator can list and preview drafts.
5. Publish a controlled draft and verify its public page and sitemap `lastmod`.
6. Verify both removed manual-generation URLs return 404.
7. Keep the current `/domain/*` and `/blog/*` public URL behavior unchanged.

Rollback may deploy the previous binaries while leaving the nullable additive column in place. The column does not break earlier application versions.
