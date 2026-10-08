# アプリからのアカウント登録削除と残留データ

調査日：2026-10-08。下記の調査本文は修正前の記録。2026-10-08に改善対象1〜3を実装した。

## 実装後の扱い

登録削除で対象の下書き・入力・添付コピー・アカウント別設定・履歴・通知と補助記録を削除する。
共通設定と他アカウントのデータを保持し、保存先の失敗は削除待ちとして起動・前景復帰時に再試行する。
添付取り込み・遅延保存・簡易通知の遅延応答でデータが復活しないよう登録照合と同期を追加した。
新しい添付はアカウント別に保存する。旧形式で既に参照を失っていた孤立ファイルの一括回収は対象外。
改善対象4（Push解除待ちの期限）、共有画像キャッシュ、サーバーのOAuth失効、過去のバックアップ消去は変更していない。
現行仕様は[UI仕様](../ui-guidelines.md#登録済みアカウントの管理)、判断は[ADR 0016](../adr/0016-account-local-data-removal.md)を参照。

## 統合前の作業ツリーでの検証（2026-10-08）

- 影響する単体テストを先行実行し、その後 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline` を実行。単体388件が成功し、失敗・エラー・スキップは0件。Debug本体・端末テスト用APKの生成も成功。
- `Mastodon_API_37`（Android 17）で `AccountDataRemovalDeviceTest` 1件が成功。対象の保存済み／未保存添付・下書き・通知・補助記録の削除、他アカウント・共通設定の保持、削除後の取り込み・通知の拒否を確認。
- 同エミュレーターで `SystemNotificationDismissalDeviceTest`、`PushNotificationDeviceTest`、`SharedMediaReceptionDeviceTest`、`ComposeDraftDeviceTest`、`AccountManagementDeviceTest` の計10件が成功。Gradleへの複数クラス指定では先頭1件のみ実行されたため、残りは生成済みAPKを使い `adb shell am instrument -w -r -e class <上記5クラス> io.github.ponpokoo.mastodonclient.test/androidx.test.runner.AndroidJUnitRunner` で実行した。
- 単体テストでは遅延した保存・通知履歴更新の拒否、投稿元削除時のアップロード／待機中投稿のキャンセル、削除失敗・プロセス再生成後の再試行、旧添付の参照保持、元ファイルと別アカウントの保護を確認。
- 最終調整で所有参照の追加保存を旧形式の平坦な添付だけに限定し、新形式の投稿済み添付パスが蓄積しないようにした。調整後に `AccountDataRemovalTest`・`ComposeMediaFlowTest` の単体22件と `AccountDataRemovalDeviceTest` 1件を再実行し、すべて成功。Debug本体も再生成済み。
- 変更した仕様・設計文書のリンクと対象ファイルの `git diff --check` を確認。Releaseビルド・本番Relay操作・サイト配布は行っていない。

## 修正前の対象と結論

設定画面の「現在のアカウントをこのアプリから削除」を調査した。
これはNagisaの登録削除であり、利用先サーバーのアカウント・投稿を削除する操作ではない。

通常の登録情報、ホーム・通知DBキャッシュは削除される。
一方、下書き・添付、アカウント別設定・履歴、通知の補助記録、画像キャッシュなどは残る。
特に**下書きが取り出せなくなること、削除後も端末通知が残る／遅延表示されること**は改善対象。

## 削除経路

`AccountRemovalButton` → `PushSettingsViewModel.logout(expected)` → `DefaultAuthRepository.removeConfirmedAccount`。
確認時の認証情報を照合し、Push解除準備・解除試行、登録削除、通知DB削除、ホームDB削除の順で実行する。
別の登録が残ればその登録へ移り、最後の登録ならログイン画面へ移る。
DB削除の例外は捕捉され、登録削除自体は成功として返す。

## 保存先ごとの扱い

| データ | 削除時の扱い・残る条件 | 根拠 |
| --- | --- | --- |
| 通常の登録情報・アクセストークン・表示名・アイコンURL | 対象の登録を暗号化ストアから削除。他アカウントの登録は保持 | [認証ストア][auth-store] `removeSession` |
| ホーム投稿、通知一覧・カテゴリ、既読位置・既読記録 | 対象セッションのDB行を削除。ディスク障害などで削除失敗した場合は残留し得る | [削除処理][auth-repo]、[ホーム保存][home]、[通知保存][notifications] |
| 下書き本文・CW・返信先・引用先・投票・添付情報・アップロード済みメディアID | `user_preferences` に残る。登録削除からの削除呼び出し、期限・件数上限はない | [設定保存][preferences] `ComposeDraft` / `saveDraft` |
| 下書きの添付コピー | 内部領域 `files/draft_media/` に残る。写真アプリ等の元ファイルとは別のコピー | [添付保存][draft-media]、[投稿画面][composer] |
| 未保存の投稿入力・その添付 | 投稿画面終了時にメモリ内の `composeBuffers` へ保持し得る。本文バッファはプロセス終了で失われるが、添付コピーの一括回収はない | [投稿画面][composer] `retainInput` / `onCleared`、[設定保存][preferences] |
| アカウント別設定・ワードミュート・リアクション履歴・投稿絵文字履歴 | 古いセッションIDをキーに残る。再ログイン時に新しいIDへ移行する処理はない | [設定保存][preferences] |
| Nagisa全体のテーマ・表示・通知・リンク設定 | 保持される。これは端末OS全体の設定ではなくアプリ共通設定 | [設定保存][preferences] |
| Androidに表示済みの通知 | 削除経路からキャンセルされない。本文・表示名等が通知欄に残り得る | [端末通知][system-notifications]、[削除処理][auth-repo] |
| 通知の重複防止ID・簡易通知の取得位置 | `delivered_notifications` / `notification_poll_markers` のSharedPreferencesに残る。本文ではなくセッションID・通知IDの記録 | [端末通知][system-notifications]、[簡易通知][polling] |
| 画像キャッシュ・プロフィール編集用の一時画像 | 登録削除では回収しない。アプリの「キャッシュ削除」はCoil画像キャッシュのみで、プロフィール用一時ファイルや下書き添付を対象にしない | [キャッシュ削除][maintenance]、[プロフィール編集][profile] |
| 投稿等のメモリキャッシュ | Repository内の投稿キャッシュは最大500件。登録削除で明示的に消さず、追い出し・Repository破棄で失われる。インスタンスとセッションIDで区分される | [投稿Repository][timeline] `StatusCacheKey` / `statusCache` |
| Push解除待ちの旧認証情報・購読鍵・管理トークン | 解除失敗時は暗号化して保持し、再試行成功後に削除。保存失敗なら登録削除を中止。期限は実装されていない | [Push制御][push-control]、[Push保存][push-store]、[購読解除][push-registration]、[通知設定仕様](../push-settings.md) |
| OAuthアプリ登録情報・認証途中の情報・共通暗号化鍵 | サーバー別のclient ID/secretと共通Keystore鍵は保持。削除対象の再認証Pendingだけ削除し、別の認証途中情報は削除しない | [認証ストア][auth-store]、[削除処理][auth-repo]、[暗号化鍵][cipher] |
| アプリ共通FCMトークン・受信処理の待機データ | 全アカウント削除でも共通FCMトークンの消去はない。受信Workの暗号化ペイロード／取得用IDは明示取消されず、期限・登録照合により通知対象から除外する | [Push制御][push-control]、[受信Work登録][fcm-scheduler]、[受信検証][push-message] |
| Androidバックアップ・端末移行 | DB・認証・Pushストアは除外。通常設定／下書き、`draft_media`、通知用SharedPreferencesは除外されておらず、端末設定・容量等に応じてバックアップ対象になり得る | [バックアップ規則][backup]、[移行規則][extraction]、[Android公式資料](https://developer.android.com/identity/data/autobackup) |
| 利用先サーバー上のアカウント・投稿・先行アップロードされた添付 | この操作では削除しない。OAuthトークンの失効APIも呼んでいない。サーバーによる失効・保存期間は今回未確認 | [削除処理][auth-repo]、[API定義][api]、[投稿画面][composer] |
| Relay上のデータ | 解除成功時はFCMトークン・配送ID・鍵・保存通知を削除し、登録IDと管理秘密値のハッシュ等の解除済み記録を保持。解除失敗時は残り得る。解除済み記録は無期限保持する仕様 | [Workers保存処理][relay-store] `remove`、[Workers仕様](../../relay/workers/README.md) |

## 改善対象

### 1. 下書き・添付が通常UIで取り出せない状態で残る

下書き一覧・復元・個別削除は、選択中の `sessionId` と一致するものだけが対象。
通常のログイン完了では新しいUUIDを発行するため、同じサーバー・アカウントへ再ログインしても以前の下書きは一覧に戻らない。
旧下書き・添付・設定・履歴は期限なく蓄積する。画像キャッシュ削除でも回収できない。
別アカウントへの自動表示・復元はこの経路では見つからなかったが、ローカル残留と削除手段の欠如が問題。

推奨：登録削除で対象の下書き・添付・入力バッファ・アカウント別設定／履歴を削除し、共通設定を保持する。
下書きを保持する方針なら、再取得・個別削除できる仕組みを先に用意する必要がある。

### 2. 表示済み通知が消えず、簡易通知は削除後も表示し得る

登録削除では `dismissForAccount` を呼んでいない。既存通知は残り、タップ時には登録の存在確認で画面遷移が拒否される。
さらに簡易通知サービスは開始時に登録一覧を取得し、通信後の通知表示では前景状態・通知設定だけを確認する。
過去の取得位置が保存済みで新着がある状態で通信中に削除すると、取得完了後に旧アカウントの本文・名前を表示し得る。
通常Push受信には登録・鍵・状態の再照合があり、同じ簡易通知の欠落とは区別する。

推奨：削除対象の表示済み通知・補助記録を消し、簡易通知の表示直前にも登録・認証情報を再照合する。
通知表示と削除が競合した場合に、表示またはキャンセルのどちらかが遅れて残らない同期も必要。

### 3. DB削除失敗が利用者から見えず、再試行されない

ホーム・通知DBの削除例外を捕捉した後、成功を返す。永続的な削除待ち記録や起動時の孤立行回収は見つからなかった。
通常の読み書きは登録存在確認で拒否されるため、残留行がそのまま別アカウントの一覧へ出る経路は見つからなかった。
ただし、削除できなかった投稿本文等はDB内に残り得る。

推奨：認証の削除完了を維持したまま、残留データ削除の失敗を記録・再試行する。

### 4. Push解除待ちの保存期間に上限がない

通信障害中の一時保持は既存仕様。ただし解除が恒久的に成功しない場合も、旧 `AccountSession` と鍵等を保持し続ける。
記録にはトークンに加えて表示名・アイコンURL等も含まれる。失敗終了・手動破棄・期限の方針は未実装。

推奨：保持情報を解除に必要なものへ絞り、認証失効やサーバー停止時の終了条件と保存期間を決める。
端末側を破棄してもサーバー購読が消えない場合があるため、リモート側への影響も含めて設計する。

## 公開文面への影響

「設定や下書き・添付はすべて消える対象ではありません」という説明自体は現行実装に合うが、
下書きが再ログインで取り出せない点や、認証情報を例外的に保持する条件までは伝わらない。
また「端末全体の設定」ではなく「アプリ共通設定」が正確。

サーバーのアカウント削除とアプリ登録削除は別操作として説明する。
OAuth連携も終了させる方針なら、Mastodonには [トークン失効API](https://docs.joinmastodon.org/methods/oauth/#revoke) があるが、
Push解除用の認証情報を先に失効させない順序、通信失敗時の扱い、各サーバーとの互換性を検討する必要がある。

## 初回調査の確認範囲と次の検証

- 保存先・削除呼び出し・再ログインID生成・下書きの表示／削除条件をソースで追跡した。
- バックアップ対象の判断はAndroid公式資料と両XML規則を照合した。実際の端末バックアップの存在は未確認。
- アプリ実装・サイトは変更していない。ビルド／テスト／実アカウント削除／本番Relay操作は実施していない。
- 修正時は下書きと添付の回収、同じアカウントの再登録、他アカウントの保持、通信中の簡易通知、DB削除失敗と起動後再試行、解除待ちの恒久エラーを重点確認する。
- サーバー側・ブラウザー・Firebase等の運用データの実際の保存状況、バックアップの消去、ストレージの物理消去は本調査の保証範囲外。

[auth-store]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/security/SecureAuthStore.kt
[auth-repo]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultAuthRepository.kt
[home]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/HomeTimelineLocalDataSource.kt
[notifications]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/NotificationLocalDataSource.kt
[preferences]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/preferences/UserPreferencesStore.kt
[draft-media]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/DraftMediaDataSource.kt
[composer]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/compose/ComposePostViewModel.kt
[system-notifications]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/notification/SystemNotificationDataSource.kt
[polling]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/notification/NotificationPollingJobService.kt
[maintenance]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/AppMaintenanceDataSource.kt
[profile]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/profile/AccountProfileScreen.kt
[timeline]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultTimelineRepository.kt
[push-control]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultPushControlRepository.kt
[push-store]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/PushRegistrationStore.kt
[push-registration]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultPushRegistrationRepository.kt
[cipher]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/security/KeystoreCipher.kt
[fcm-scheduler]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/notification/FcmWorkScheduler.kt
[push-message]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultPushMessageRepository.kt
[backup]: ../../app/src/main/res/xml/backup_rules.xml
[extraction]: ../../app/src/main/res/xml/data_extraction_rules.xml
[api]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonApi.kt
[relay-store]: ../../relay/workers/src/store.mjs
