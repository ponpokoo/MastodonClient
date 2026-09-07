# Real-device test

## Preconditions

- Android 8.0 (API 26) or newer device
- Internet connection and a browser on the device
- USB debugging or wireless debugging connected to Android Studio
- A test account on a Mastodon instance

Do not share screenshots that contain an OAuth authorization code, access token, or client secret.

## Install and run

1. Open this repository in Android Studio and wait for Gradle Sync to finish.
2. Connect the device and confirm it appears in the device selector.
3. Select the `app` run configuration and press Run.
4. If an older prototype using `com.example.mastodonclient` is installed, it is a separate app and
   can be uninstalled manually.

The current application ID is `io.github.ponpokoo.mastodonclient`.

## OAuth smoke test

1. Enter an instance domain such as `mastodon.social` without a path.
2. Tap **接続を確認**.
3. Confirm the instance title appears, then tap **ブラウザでログイン**.
4. Sign in and authorize the requested `read:accounts read:statuses` scopes.
5. Confirm the browser redirects to the app.
6. Confirm **ログイン完了**, the account display name, account name and avatar are shown.
7. Force-stop and reopen the app; confirm the login session is restored.
8. Tap **ログアウト**, reopen the app and confirm the instance input screen is shown.

## Home timeline smoke test

1. Log in with an account that follows at least one active account.
2. Confirm that Home opens automatically after the OAuth callback.
3. Verify author avatars/names, HTML body text, relative times, counts and media previews.
4. Verify boosted posts show the boosting account above the original author.
5. Verify content-warning text stays collapsed until **表示する** is tapped.
6. Tap refresh and confirm the current page reloads without leaving Home.
7. Scroll near the end and confirm the next page is appended without duplicate rows.
8. Force-stop and reopen the app and confirm the encrypted session and Home restore.

## Negative checks

- `http://mastodon.social` is rejected because instance traffic must use HTTPS.
- A URL with a path, query, fragment or embedded credentials is rejected.
- Cancelling authorization does not create a session.
- A callback with an invalid `state` is rejected and does not exchange a token.

## Current completion boundary

This build validates login through `GET /api/v1/accounts/verify_credentials` and reads the home
timeline through `GET /api/v1/timelines/home`, including `max_id` pagination. Reply, boost,
favourite and posting mutations are intentionally deferred until the matching write scopes and
confirmation/error behavior are implemented. The access token and OAuth registration secret are
encrypted with an AES-GCM key held by Android Keystore; only ciphertext is written to DataStore.
