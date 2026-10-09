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
- Local checks remain scoped to the change; push/PR CI runs the common checks selected by changed paths
  in `docs/project-setup.md`. On later changes, check whether CI paths, commands, or tool versions need
  updating and briefly record that judgment in the PR or commit description.

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

## Documentation maintenance

- Use a lightweight maintenance workflow for ordinary changes, including after Google Play
  publication. Update an existing specification or guide only when the change makes its behavior,
  contract, setup, or operating instructions inaccurate. Internal refactors and routine visual
  fixes do not require documentation updates unless they affect those instructions.
- Routine change descriptions and validation results belong in the PR or commit description.
  Report the checks performed and relevant limitations briefly; do not add a Markdown report,
  per-document validation history, or release-plan entry for every task.
- Update the release plan when preparing a release or changing release scope or priorities.
  Keep ongoing work in Issues; do not duplicate its progress across specifications and plans.
- Create a separate investigation or validation record only when reproducible evidence is needed
  for an unresolved problem, a compatibility or migration decision, or production operation and
  recovery. Keep the evidence in one place and link to it where needed.
- Prefer extending an existing document over adding a new one. Keep implementation details in
  code and tests, current instructions in the relevant guide, and change history in Git.
  Preserve useful existing records without appending routine history to them.
- Authentication and session isolation, data retention and migration, Relay contracts, and
  production setup/recovery instructions must remain accurate when changed. Record deployed
  versions and migration/recovery results once where needed for safe operation; never record secrets.

## Architecture decision records

- Keep ADRs in [`docs/adr/`](docs/adr/README.md) for lasting choices about shared boundaries,
  storage/migration, compatibility, or production infrastructure whose rationale cannot be
  recovered adequately from code and a short change description. A change in these areas alone
  does not require an ADR; record a consequential choice with alternatives or accepted costs.
- Ordinary features, bug fixes, visual adjustments, API requirements, test additions, and
  documentation/process changes do not require an ADR unless they also make such a choice.
  If an existing guide or ADR explains the rationale sufficiently, update or reference it.
- Use one short Markdown file per decision, with a stable sequential number and descriptive name.
  Include status/date, context, decision and rationale, relevant alternatives and constraints.
  Add review conditions and related links only when useful; there is no required line count or
  fixed section template. Add the record to the ADR index and link from the affected guide when
  needed to understand its rule. Keep proposed decisions distinct from accepted ones.
- When replacing a decision, create a new ADR, mark the old one as superseded, and link both
  records. Preserve the old rationale and never reuse its number. Update the ADR index.
- For retrospective records, distinguish verified facts from historical rationale. Mark unknown
  adoption dates or reasons as unknown; label newly assessed alternatives and review conditions
  as current analysis rather than claiming they were considered at the time.
