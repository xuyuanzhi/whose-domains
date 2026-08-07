# Auth Modal Usability Design

## Goal

Improve the existing sign-in modal so desktop users can reposition it, its header is visually and semantically distinct, and Google and email-link guidance and results appear with the authentication method they belong to.

## Scope

The change is limited to the shared authentication modal in `wesite-web`. It preserves the existing authentication endpoints, query-string result codes, focus trap, Escape handling, backdrop close behavior, and visual theme.

## Structure

The modal card will contain:

1. A title bar with the existing `Sign In` heading and close button.
2. A Google sign-in section, rendered only when Google login is enabled, containing the Google button and a dedicated `googleLoginMsg` status region immediately below it.
3. The existing divider, rendered only when Google login is enabled.
4. An email-link section containing the password-free explanatory copy, email field, send button, and a dedicated `emailLoginMsg` status region immediately below the button.

The existing generic `loginMsg` region will be replaced by the two method-specific regions. Email request validation, request failures, and request success messages route to `emailLoginMsg`. Google callback and binding messages route to `googleLoginMsg`, except the existing non-Google `invalid` email-link result, which routes to `emailLoginMsg`.

## Title Bar and Dragging

The title bar is the only drag handle. It uses a move cursor on pointer-capable desktop layouts while buttons, links, and form controls retain their normal interaction behavior.

Dragging will use native Pointer Events without a new dependency:

- Dragging starts only from the title bar with the primary pointer.
- The initial pointer position and card rectangle are captured at drag start.
- Pointer movement updates the card position using viewport coordinates.
- The card remains fully reachable: its position is clamped within the viewport with a small edge gap.
- Pointer capture keeps the drag stable if the pointer leaves the title bar.
- Dragging ends on pointer up or pointer cancellation.
- Opening and closing the modal clears any translated position so every new session starts centered.
- Dragging is disabled at widths of 480 px or less, where the modal remains centered and touch scrolling is unaffected.

No drag state is persisted between modal openings or page loads.

## Accessibility

The existing dialog semantics, labelled title, initial focus, focus trapping, focus restoration, Escape close behavior, and polite live announcements remain intact. Both result regions use `role="status"`, `aria-live="polite"`, and `aria-atomic="true"`.

The title bar is a pointer drag handle, not a keyboard control; keyboard users do not need to reposition the modal to access any content. Visible focus styles remain unchanged.

## Visual Direction

The modal keeps the existing dark navy, cyan, and glass-border palette. The title bar adds hierarchy through spacing and a subtle bottom border rather than introducing a new decorative style. Authentication methods are separated structurally: each explanation or result sits inside its own method section. The close button remains in the title bar, aligned opposite the heading.

## Error and Result Routing

Email-link messages:

- Empty or invalid email feedback.
- Email request network and server failures.
- Successful email-link request confirmation.
- Invalid or expired email-link callback results.

Google messages:

- Generic Google callback failure.
- Invalid Google identity or unverified email.
- Account conflict or inactive account.
- Confirmation-email unavailable or expired Google flow.
- Google confirmation and binding progress messages.

When `google_bind_required` is present, the Google button text continues to change to `Finish with Google`, and the accompanying result is shown below that button.

## Testing

Template-level tests will verify:

- The title bar and drag handle exist.
- Google and email status regions are separate and accessible.
- Email request messages target `emailLoginMsg`.
- Google result codes target `googleLoginMsg`, while the email-link `invalid` code targets `emailLoginMsg`.
- Pointer-event drag handlers, viewport clamping, mobile guard, and position reset are present.
- Existing conditional Google rendering and accessible dialog behavior remain covered.

The focused template test suite and the wider `wesite-web` tests will be run after implementation.
