# Mastodon Client implementation rules

This repository is a generic Android Mastodon client. It must not assume that the server is
`mastodon.social` or that all instances run the same Mastodon version.

## Required boundaries

- UI -> ViewModel -> Use case/domain repository -> data repository -> remote/local data source.
- API DTOs never cross into UI code. Map DTOs to domain models first.
- Mastodon IDs are always `String`; never parse them as numeric IDs.
- Retrofit base URLs come from the selected account session. Never hard-code an instance.
- Configure JSON with `ignoreUnknownKeys = true`; version-dependent fields should be nullable.
- Never log access tokens, authorization codes, client secrets, or Authorization headers.
- Release builds must not enable HTTP body logging.
- Prefer current, non-deprecated Mastodon endpoints (for example `/api/v2/media`).
- Follow `docs/ui-guidelines.md` for phone navigation, status rendering, theming, and timeline
  customization. Tablet-specific UI is out of scope for the MVP.
- Keep the project compiling and existing tests valid. Choose validation by change scope using
  `docs/project-setup.md`; do not run the full suite after every small edit.
- Documentation-only edits need content/link/diff checks, not Gradle builds or tests.
- Add or update tests for changed behavior, meaningful failure cases, and regressions. Avoid tests
  that duplicate coverage, mirror implementation details, or only assert static text/layout values.
- Run affected tests first. Expand to the full suite for shared infrastructure changes, release
  candidates, or evidence of wider impact. Do not repeat successful checks without relevant changes.

## MVP order

1. Instance discovery and URL normalization
2. App registration, OAuth Authorization Code + PKCE, encrypted token storage
3. Credential verification and account session
4. Home/local/federated timelines and cursor pagination
5. Status rendering and actions
6. Composer, replies, media upload and ALT text
7. Profile, notifications and search
8. Streaming, Room cache and resilient error handling

When an API detail is uncertain, verify it against the official Mastodon API documentation before
implementing it.
