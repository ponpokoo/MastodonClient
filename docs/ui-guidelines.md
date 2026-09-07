# UI implementation guidelines

## Direction

The Android UI follows the interaction model and visual rhythm of the official Mastodon Android
app, while using our own implementation and assets. The references are the
[Google Play listing](https://play.google.com/store/apps/details?id=org.joinmastodon.android&hl=ja)
and the user-provided recording of Mastodon for Android 2.13.3.

This is a phone-first product. The MVP does not provide tablet-specific layouts, navigation rails,
two-pane screens, foldable postures, or landscape-specific composition. The app may still run on a
large screen, but tablet optimization is not an acceptance criterion.

## Navigation model

Use the four destinations shown in the official app:

1. Home
2. Explore
3. Notifications
4. Profile

The selected bottom-navigation item uses a rounded tonal indicator behind the icon. Keep labels
visible. The profile item uses the current account avatar and exposes account switching without
adding another permanent destination.

Home has a title/dropdown at the top left and settings at the top right. The dropdown contains Home,
Local/real-time feeds, Lists, followed hashtags, and later any server-supported feeds. Local and
federated timelines therefore remain MVP features without occupying separate bottom-navigation
slots.

A rounded compose floating action button sits above the bottom navigation at the lower right. It
must not cover the final status action row or system navigation inset.

## Visual language

- Use Material 3 components and semantics, but match the official app's compact, content-first
  composition rather than card-heavy default styling.
- Use neutral page surfaces, thin separators, rounded pills/sheets, and purple as the primary accent.
  Use Mastodon purple `#6364FF` as the non-dynamic fallback.
- Support System, Light, and Dark themes. Dynamic color is an optional user setting; it is never the
  only way to distinguish state.
- Dark mode uses a near-black page surface, a slightly lifted bottom bar/menu surface, subdued
  dividers, and pale purple selected states.
- Use edge-to-edge drawing with correct status/navigation bar insets.
- Recreate layout patterns only. Do not copy Mastodon illustrations, icons, screenshots, or other
  protected assets into the project.

## Timeline and status anatomy

Each status is one continuous row separated by a thin divider, not an elevated card:

1. Avatar and identity row: circular avatar, display name, relative time, full account address, and
   overflow menu.
2. Optional context row: boosted-by, reply-to, pinned, or notification context.
3. Content: body text, content warning control, link preview, poll, and media/ALT presentation.
4. Action row: reply, boost, favorite, and share by default. Bookmark and other actions remain in
   overflow unless enabled for the row.

Recommended baseline dimensions:

- Horizontal content padding: 16 dp
- Vertical status padding: 12 dp
- Avatar: 40 dp
- Visible action icon: 20-24 dp inside a minimum 48 dp touch target
- Divider: 1 physical pixel or the closest density-safe equivalent
- Default body text: 16 sp with 22 sp line height

Never shrink touch targets when the user chooses a compact display. Counts may appear next to action
icons, but a zero count should not add visual noise.

## Timeline display customization

Add a dedicated `Timeline display` screen under Settings > Display. Changes are persisted per app,
applied immediately, and shown in a live sample-status preview.

### Text size

Provide four presets. Apply them on top of the Android system font scale and clamp only where layout
would otherwise become unusable.

| Preset | Body size | Default line height |
|---|---:|---:|
| Small | 14 sp | 18 sp |
| Standard | 16 sp | 22 sp |
| Large | 18 sp | 26 sp |
| Extra large | 20 sp | 30 sp |

### Line spacing

Provide Compact, Standard, and Relaxed line-spacing presets. They adjust line height independently
of the selected font-size preset while preserving at least 1.2 times the effective text size.

### Action icons

- Allow reply, boost, favorite, bookmark, and share to be shown or hidden individually.
- Allow visible actions to be reordered, with reply/boost/favorite/share as the default order.
- Provide a separate Show counts toggle.
- Hidden actions must remain available from the status overflow menu so customization never removes
  functionality.
- Use familiar Material/Mastodon-style symbols and accessible labels. Do not substitute decorative
  custom artwork for standard actions.

Represent these values as an immutable `TimelineDisplayPreferences` model and persist them with
DataStore. Expose a `Flow` to Compose so the timeline and preview update without restarting the app.
Include Reset to defaults.

## Screen-specific patterns

### Explore

Use a rounded full-width search field with a QR entry point when implemented. Below it, use a
scrollable tab row for Posts, Hashtags, News, and Recommended. Loading, empty, and retry states stay
inside the content area and do not move the bottom navigation.

### Notifications

Use a top title and a two-option segmented control for All and Mentions. Notification context is
shown above or around the referenced status, using the same status renderer as the timeline.

### Profile

Use header media, an overlapping avatar, name/address, counts, bio/link rows, edit/follow controls,
and Timeline/Media tabs. Put secondary operations such as share, copy link, QR, browser, favorite,
bookmark, and account settings in a single overflow surface.

### Settings

The top level contains Accounts followed by Actions, Display, and About. Per-account settings open
as a separate screen or rounded modal surface and contain privacy, filters, notifications, posting
defaults, server information, account removal, and logout. Destructive actions use the error color
and require an explicit confirmation.

Settings use plain rows with a leading icon and trailing switch/value. Avoid unnecessary cards.

## Accessibility and content safety

- Every icon-only action has a localized content description and a 48 dp minimum target.
- Selected, boosted, and favorited states differ by semantics/icon treatment as well as color.
- Respect system font scaling, screen readers, reduced motion, and contrast settings.
- Content-warning and sensitive-media controls must remain obvious at every display density.
- Preserve author-supplied ALT text and expose it to TalkBack before media actions.
- Keep focus order aligned with the visual order: identity, content, media, actions, overflow.

## Initial Compose components

Implement and test these reusable pieces before assembling every feature screen:

- `MastodonTheme`
- `AppBottomBar`
- `TimelineTopBar`
- `StatusCard`
- `StatusActionRow`
- `ComposeButton`
- `TimelineDisplayPreferences`
- `TimelineDisplaySettingsScreen`

`StatusCard` receives domain models and display preferences; it must never receive Retrofit DTOs.

## MVP acceptance criteria

- Phone portrait layouts at 360-430 dp widths match the official app's information hierarchy.
- Home, Explore, Notifications, and Profile retain stable bottom navigation.
- Light, Dark, and System themes render without clipped or illegible content.
- Timeline font size, line spacing, action visibility/order, and counts can be changed and restored
  after process death.
- Hidden actions remain available in overflow, and all actions meet accessibility target sizes.
- No tablet-only resources, navigation rail, or two-pane implementation are required.
