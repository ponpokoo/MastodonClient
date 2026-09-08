# Profile feature specification

## Scope

The profile screen supports both the signed-in account and other local or remote accounts. The
visual hierarchy follows the supplied Mastodon references while remaining a phone-only Compose UI.

## Profile header

- Show header image, animated/transparent avatar, display name, full account address, bio, profile
  fields, join date when available, and post/following/follower counts.
- Render account and field custom emoji. A field with `verified_at` is shown as a verified link and
  keeps the server-provided URL as its target.
- Tapping the avatar or header opens the existing full-screen media viewer.
- Tapping following or follower counts opens a paginated account list.
- Show an `Edit profile` button for the signed-in account. Other accounts show the current
  relationship action: Follow, Requested, Following, or Unfollow.

## Relationship and safety actions

- Load the relationship before enabling actions.
- Follow supports immediate follow and locked-account follow requests. A pending request can be
  cancelled with the same control.
- The overflow menu for another account contains mute/unmute, block/unblock, and report. Muting and
  blocking require confirmation; reporting opens a reason/comment screen before submission.
- Relationship state is refreshed after every successful mutation and errors remain recoverable.

## Content tabs

1. Posts: posts excluding replies.
2. Posts and replies: posts including replies.
3. Media: posts containing media.

Pinned posts appear in a labelled section above the selected tab. Every list uses Mastodon cursor
pagination, preserves its own scroll position, and supports retry and pull-to-refresh states.

## Signed-in account menu

The overflow menu contains the options shown in the supplied official-app reference:

- Share profile
- Copy link
- View QR code
- Open in browser
- Favourites
- Bookmarks
- Followed hashtags
- Account settings

Share uses the Android share sheet, Copy link uses the system clipboard, and Open in browser always
uses an external browser regardless of the general in-app-link preference. Favourites, bookmarks,
and followed hashtags open native paginated screens. Account settings opens this app's per-account
settings; server-only settings may link to the instance website when no API exists.

## Profile editing

- Edit display name, bio, avatar, header, profile fields, follow-request approval (`locked`), and
  discoverability where supported by the server.
- Start from the plain-text values returned by credential verification, not rendered profile HTML.
- Preview selected avatar/header media and preserve existing media when the user makes no change.
- Validate server limits and keep unsaved input after a recoverable error.

## API and authorization notes

- Account status filters, pinned posts, followers/following, relationships, follow, mute, block, and
  credential updates use the corresponding `/api/v1/accounts` methods.
- Profile editing and safety/relationship mutations require expanding the OAuth write scopes. A
  session created with the previous scopes must be re-authorized once before these controls are
  enabled.
- Server/version-dependent fields remain nullable and unsupported controls are hidden instead of
  shown as broken actions.

## Delivery order

1. Complete profile model mapping, custom emoji/verified fields, header media, and paginated tabs.
2. Add relationships, follow requests, followers/following lists, and pinned posts.
3. Add share/copy/QR/browser and native favourites/bookmarks/followed-tags destinations.
4. Add mute/block/report flows.
5. Add profile editing and account settings after the expanded OAuth session is available.

## Acceptance criteria

- Own and other-account profiles expose only valid actions for the current relationship and server.
- Animated GIF avatars animate and transparent PNG pixels retain alpha in timeline and profile UI.
- Each tab and account list paginates without duplicates and restores scroll position.
- Avatar/header enlargement, share, copy, QR, and external-browser actions work on a physical phone.
- Destructive moderation actions require explicit confirmation and never leave stale relationship UI.
