# Authentication Actions and Watchlist Signed-Out State Design

## Goal

Make authentication actions clearer and more cohesive by adding a recognizable Google icon, confirming sign-out inside the site’s visual system, and replacing the cluttered Watchlist signed-out prompt with a focused sign-in card.

## Scope

This change affects the shared authentication UI in `template.html`, its common styles, and the signed-out state in `user/domain-watch.html`. Authentication endpoints, login result codes, session behavior, and the signed-in Watchlist experience remain unchanged.

## Google Sign-In Button

The existing Google button retains its label and destination. An inline, accessible-hidden SVG rendering the official four-color Google “G” appears to the left of the label.

Inline SVG is used so the icon loads with the page, adds no network request, and requires no new asset or dependency. The button keeps a text label, so the icon is decorative and uses `aria-hidden="true"`.

When the Google binding flow changes the action text to `Finish with Google`, only the text span changes; the icon remains visible.

## Sign-Out Confirmation Dialog

Selecting `Sign Out` opens a custom confirmation dialog instead of immediately calling the endpoint. The dialog follows the existing authentication modal’s dark navy, cyan, glass-border, and rounded-card visual language.

The dialog contains:

- Title: `Sign out?`
- Explanation: `You’ll need to sign in again to access your Watchlist and account settings.`
- Secondary action: `Cancel`
- Destructive action: `Sign Out`
- A polite live status region for request failures

The dialog uses `role="dialog"`, `aria-modal="true"`, and a labelled title. Opening it stores the triggering element and focuses `Cancel`, the safer action. Tab and Shift+Tab remain inside the dialog. Escape, the backdrop, and `Cancel` close it and restore focus to the original Sign Out link.

Confirming sign-out disables both actions and changes the destructive label to `Signing out…`. A successful logout reloads the current page. A failed HTTP response or network error keeps the dialog open, restores the actions, and displays `Could not sign out. Please try again.` The page must not reload on failure.

Only one sign-out request can be active at a time.

## Watchlist Signed-Out State

The signed-out area uses the selected “single-focus sign-in card” layout:

- A compact lock/account icon in a cyan-tinted square
- Heading: `Sign in to view your Watchlist`
- Explanation: `Your monitored domains and alert settings are saved securely to your account.`
- Two brief benefits: `Expiry alerts` and `Up to 50 domains`
- One primary `Sign In` action that opens the existing authentication modal
- Supporting text: `New domains can be added after signing in.`

The current quick-domain input, quick-email input, `Email me a link`, duplicate `Create Account`, separator, quick-watch message, and their JavaScript/local-storage flow are removed. This avoids presenting two competing sign-in paths inside an already protected page.

When the Watchlist API reports an unauthenticated response, the loading state is hidden, the signed-in Watchlist UI is hidden, and the new signed-out card is shown. This prevents stale signed-in content from remaining visible if the session ends while the page is open.

## Visual Direction

The change extends the existing site rather than introducing a new visual theme. The Google button remains light for recognizable provider styling. The sign-out dialog and Watchlist card use existing CSS tokens and restrained cyan accents. The Watchlist card is centered with a narrow reading width and generous space around one clear action.

Both dialogs remain usable on small screens. The sign-out actions stack only if the available width cannot fit them comfortably.

## Error Handling

- Google sign-in behavior and existing callback-message routing do not change.
- Cancelling sign-out has no network effect.
- Sign-out success reloads only after a successful HTTP response.
- Sign-out failures are announced without closing the dialog or discarding the current UI state.
- Watchlist authentication-check failures show the signed-out card and hide authenticated content.

## Testing

Template and executable DOM tests will verify:

- The Google icon is present, decorative, and survives the `Finish with Google` text change.
- Sign Out opens the custom dialog without sending a request.
- Cancel, Escape, and backdrop close restore focus.
- Tab and Shift+Tab remain inside the sign-out dialog.
- Confirm sends one POST request and enters a loading state.
- Successful sign-out reloads; failed sign-out does not reload and exposes the live error.
- The Watchlist signed-out card contains the approved content and one sign-in action.
- The old quick-domain/email form and supporting JavaScript are removed.
- An unauthenticated transition hides loading and authenticated Watchlist content before showing the sign-in card.

The focused template/DOM tests and complete `wesite-web` reactor tests will run before completion.
