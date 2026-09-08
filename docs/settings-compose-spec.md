# Settings and composer feature specification

## Scope

This specification uses the supplied Mastodon for Android recording as an interaction reference,
not as a screen-for-screen clone. The app keeps familiar Mastodon concepts while adding account,
timeline-display, media, streaming, and drafting controls of its own. Phone portrait is the target;
tablet-specific layouts are outside scope.

## Account switching

### Browsing account

- Place an account-switch button in the Home top bar and show the active account avatar.
- The selection sheet shows avatar, display name, full account address, instance, current selection,
  and `Add account`.
- Changing the browsing account changes the authenticated session used by Home, Explore,
  Notifications, Profile, announcements, search, streaming, and subsequent navigation.
- Keep independent timeline and tab scroll state per account where practical. Stop the previous
  account's stream before starting the newly selected account's stream.
- Never mix cached statuses, notifications, drafts, or preferences between accounts.

### Posting account

- Place a separate posting-account switch button in the composer identity row.
- Opening a new composer initially selects the current browsing account. A reply initially selects
  an account that can address the replied-to status and clearly reports when it cannot.
- Changing the posting account affects only that composer/draft. It does not change the browsing
  account in Home.
- Always show the selected posting account's avatar and full account address. Revalidate character,
  media, poll, visibility, language, and server-feature limits when it changes.
- If switching would invalidate existing attachments or poll choices, explain the conflict before
  discarding or converting anything.

## Settings information architecture

The top level contains Accounts, Timeline display, Media, Timeline and network, Composer, Safety and
confirmations, and About. Use simple rows with leading icons and trailing values/switches. Dynamic
Color is intentionally omitted.

Settings are app-wide unless explicitly marked per account. Account-specific rows show the full
account address so their scope is unambiguous.

### Timeline display — app-wide

1. Font size: Small, Standard, Large, Extra large.
2. Line spacing: Compact, Standard, Relaxed.
3. Status action icons:
   - size preset: Small, Standard, Large;
   - reorder reply, boost, favorite, reaction, bookmark, and share;
   - show/hide each action;
   - show/hide counts.
4. Link-preview and media-thumbnail size: Compact, Standard, Large.

Provide a live sample-status preview and Reset to defaults. Visible icons may be smaller, but every
touch target remains at least 48 dp. Hiding an action does not disable the feature; keep it available
from an overflow surface.

### Media — app-wide

- GIF autoplay: Always, Wi-Fi only, Never.
- Video autoplay: Always, Wi-Fi only, Never.
- Autoplay is muted by default, respects Android data-saver and reduced-motion preferences, and
  never bypasses a content warning or sensitive-media cover.
- Preserve animation support for GIF avatars and custom emoji independently from status-media
  autoplay, with a separate animated-avatar/custom-emoji switch if needed.

### Timeline and network

- Streaming is configurable per account: On, Wi-Fi only, Off.
- Provide an app-wide `Pause streaming in background` switch, enabled by default.
- Pull-to-refresh and manual refresh continue to work when streaming is off or disconnected.
- Show a recoverable connection state without replacing already loaded timeline content.

### Composer

- Draft autosave: app-wide On/Off, enabled by default.
- Default visibility is stored per account: Public, Quiet public/Unlisted where supported,
  Followers only, or Mentioned people only/Direct.
- ALT-text assistance is app-wide and includes:
  - a visible missing-ALT indicator on every image/video attachment;
  - a pre-send reminder when media has no description;
  - direct access to the ALT editor from each attachment;
  - preservation of entered ALT text through draft save, upload retry, and account-safe restoration.
- A draft is namespaced by posting account and composer context. Reply drafts also retain the target
  status identity. Never upload or submit a draft under another account silently.

## Composer screen

Keep Close, title, and Send in the top bar. Below it show visibility, posting identity, and the main
editor. The bottom tool row contains media, poll, emoji, content warning, and language, followed by
the current character count.

Required capabilities:

- switch posting account;
- select visibility, initialized from that account's default;
- write a new post or reply;
- attach supported images and video, reorder/remove them, upload them, and edit ALT text;
- create and validate a poll;
- insert Unicode emoji and server-provided custom emoji where posting syntax is supported;
- add a content warning;
- select post language;
- show server-derived character and attachment limits;
- save, restore, discard, and successfully clear drafts;
- prevent duplicate submission and preserve recoverable input after API/upload errors.

The Send control remains disabled while required validation fails or media is still uploading. When
closing a composer with meaningful content, autosave silently when enabled; otherwise offer Save
draft, Discard, and Continue editing.

## Persistence model

- Store app-wide settings in DataStore as immutable preference models exposed through `Flow`.
- Store per-account preferences by stable local session ID, not username alone.
- Store draft metadata per local session and keep attachment files in app-private storage until the
  draft is sent or explicitly discarded.
- Removing an account must offer to remove its drafts and cached media, without affecting other
  accounts.

## Delivery order

1. Introduce a multi-account session registry and browsing-account switcher.
2. Add the posting-account selector and per-account default visibility.
3. Complete the composer primitives: visibility, media upload/ALT, poll, emoji, CW, and language.
4. Add account-safe draft autosave and recovery.
5. Implement timeline display and thumbnail customization.
6. Add GIF/video autoplay and streaming policies.
7. Build the final Settings screens and live preview around the working preference models.

## Acceptance criteria

- Home can switch accounts without leaking timeline, notification, profile, or streaming state.
- A composer can use a different posting account without changing the Home browsing account.
- The posting identity and visibility are obvious before Send, and server limits are revalidated
  after account changes.
- All eight independent features requested in this specification persist across process death.
- Draft text, context, attachments, ALT text, CW, language, poll, visibility, and posting account are
  restored together and cleared only after successful posting or explicit discard.
- Display settings update the timeline preview and real statuses consistently while retaining
  accessible touch targets.
- Autoplay and streaming fall back cleanly when network, server capability, or Android policy
  prevents their preferred mode.
