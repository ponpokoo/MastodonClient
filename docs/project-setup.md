# Android Studio project settings

These values are fixed for the first public application identity.

| Setting | Value |
|---|---|
| Project name | `MastodonClient` |
| User-facing app name | `Mastodon Client` |
| Repository | `ponpokoo/MastodonClient` |
| Namespace / application ID | `io.github.ponpokoo.mastodonclient` |
| OAuth redirect URI | `io.github.ponpokoo.mastodonclient://oauth/callback` |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Minimum SDK | API 26 (Android 8.0) |
| Compile SDK | API 37 |
| Target SDK | API 37 |
| JVM bytecode | Java 11 |
| Build scripts | Kotlin DSL + Gradle Version Catalog |

## Architecture

The MVP uses a single Android app module with feature/layer packages. This keeps builds reasonable
on an 8 GB development Mac while retaining boundaries that can later be extracted into modules.

```text
UI -> ViewModel -> domain repository -> data repository -> remote / local / streaming
```

Package roots:

```text
core/       networking, security, database and common utilities
data/       Retrofit DTOs, mappers and repository implementations
domain/     UI-independent models, repository contracts and use cases
feature/    screen-specific UI and ViewModels
navigation/ typed Compose navigation routes
```

## Dependency policy

Production dependencies are pinned in `gradle/libs.versions.toml`; dynamic versions are forbidden.
The first slice installs Compose, Navigation, Lifecycle, Retrofit, OkHttp, kotlinx.serialization,
coroutines, Coil, Room runtime, DataStore and browser Custom Tabs. Hilt coordinates are reserved in
the catalog and will be activated with its compiler when dependency injection is introduced.

Room's compiler is intentionally deferred until the first database entity is added. This avoids an
unused annotation-processing step and makes the first implementation slice easier to verify.

## First implementation slice

- Normalize user-entered domains to a strict HTTPS base URL.
- Reject credentials, paths, queries and fragments in instance input.
- Call `GET /api/v2/instance` through an instance-specific Retrofit client.
- Ignore unknown JSON fields and map the response DTO into a domain model.
- Present a Compose instance-discovery screen backed by a StateFlow ViewModel.
- Reserve a typed navigation graph for login and timeline destinations.

## Device-testable OAuth slice

- Register the app per instance through `POST /api/v1/apps` and reuse the registration.
- Generate PKCE using SHA-256 / S256 and validate a random OAuth `state` value.
- Open authorization in a browser Custom Tab and receive the registered deep-link callback.
- Exchange the authorization code through `POST /oauth/token`.
- Verify the token through `GET /api/v1/accounts/verify_credentials`.
- Encrypt pending OAuth state, client credentials and account sessions with Android Keystore
  AES-GCM before writing ciphertext to DataStore.
- Restore the session after process restart and support local logout.

See `docs/device-testing.md` for the real-device smoke test.

The phone-first visual and interaction contract is defined in `docs/ui-guidelines.md`.
