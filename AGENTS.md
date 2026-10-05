# Mastodon Client implementation rules

This repository is a generic Android Mastodon client. It must not assume that the server is
`mastodon.social` or that all instances run the same Mastodon version.

Use [`docs/index.md`](docs/index.md) to find project documentation. Keep the index current when
adding, moving, or removing documentation entry points.

## Required boundaries

- UI -> ViewModel -> Use case/domain repository -> data repository -> remote/local data source.
- Exception: simple UI preference reads and writes may access `UserPreferencesStore` directly
  from Compose, using lifecycle-aware Flow collection and suspend store methods. Do not add
  wrapper layers solely for these operations. Authentication, network requests, and multi-step
  operations still go through ViewModels and domain repositories.
- When changing authentication, account switching, or asynchronous processing, follow the session
  isolation and cancellation policies in [`docs/project-setup.md`](docs/project-setup.md).
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

## Push and Relay

- Follow [`docs/relay-protocol.md`](docs/relay-protocol.md) for the shared Relay wire contract,
  [`docs/push-settings.md`](docs/push-settings.md) for subscription lifecycle,
  [`docs/push-reception.md`](docs/push-reception.md) for Android/Firebase setup, FCM integration,
  reception, and decryption, and [`relay/workers/setup.md`](relay/workers/setup.md) for Workers deployment.
- Never send Mastodon access tokens, OAuth client secrets, Web Push private keys, or auth secrets
  to Relay. Use separate Relay HTTP clients without Mastodon authentication interceptors.
- Never log Relay management tokens, FCM registration tokens, Web Push private keys or auth secrets,
  FCM service-account private keys, or delivery endpoint URLs.
- The local mock and Workers Relay are separate implementations. Follow
  [`relay/README.md`](relay/README.md) or [`relay/workers/README.md`](relay/workers/README.md)
  for their delivery contracts, runtime setup, and tests. Run `npm test` in each affected Relay
  directory; Relay-only changes do not require Android builds or Gradle tests.

## Development priorities

Follow [`docs/release-plan.md`](docs/release-plan.md) for current release scope and future development
priorities. Distinguish planned candidates from implemented features.

When an API detail is uncertain, verify it against the official Mastodon API documentation before
implementing it.

## Architecture decision records

- Keep lightweight ADRs in [`docs/adr/`](docs/adr/README.md). Record decisions whose rationale is
  needed to safely revisit them: shared architectural boundaries, migration or compatibility
  impacts, meaningful alternatives with accepted costs, or future extension and review conditions.
- Add or update the relevant ADR alongside the implementation change. If an existing document
  already explains the rationale sufficiently, reference it instead of duplicating the record.
- Routine visual adjustments and ordinary bug fixes belong in the relevant specification, PR,
  investigation, or validation record. Add an ADR when they also change an architectural decision
  meeting the criteria above. API requirements alone do not require an ADR.
- Use one short Markdown file per decision, with a stable sequential number and descriptive name.
  Aim for 20–40 lines covering status and recording date, context, decision and rationale,
  alternatives, accepted costs or constraints, review conditions, and related documents.
- Keep current behavior and implementation rules in the existing specifications and guides;
  link them and the ADR in both directions. Keep proposed decisions distinct from accepted ones.
- When replacing a decision, create a new ADR, mark the old one as superseded, and link both
  records. Preserve the old rationale and never reuse its number. Update the ADR index.
- For retrospective records, distinguish verified facts from historical rationale. Mark unknown
  adoption dates or reasons as unknown; label newly assessed alternatives and review conditions
  as current analysis rather than claiming they were considered at the time.
