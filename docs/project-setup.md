# 開発ガイド

UI・設定・投稿・プロフィールの現行仕様は [UI・機能仕様](ui-guidelines.md) を参照する。
本書は開発環境、実装の境界、状態管理、確認手順をまとめる。
設計判断の背景・採用理由・見直し条件は[ADR一覧](adr/README.md)を参照する。

## プロジェクト構成

| 項目 | 設定 |
| --- | --- |
| プロジェクト／アプリ名 | MastodonClient／Nagisa |
| リリース準備の対象版 | `2.4.1`（versionCode 16）。対象版・確認結果は[更新・リリース計画](release-plan.md)、公開状況は同計画書からリンクするGitHub Releaseを参照 |
| 旧事前検証用成果物の版番号 | Relay移行の事前検証APK/AABは`2.4.0`（versionCode 15）。2.4.1の配布候補とは区別する |
| 新規OAuth登録名（投稿元） | `Nagisa for Mastodon` |
| Namespace・application ID | `io.github.ponpokoo.mastodonclient` |
| OAuth redirect URI | `io.github.ponpokoo.mastodonclient://oauth/callback` |
| 言語・UI | Kotlin・Jetpack Compose・Material 3 |
| Android SDK | min 26、compile 37、target 37 |
| JVMバイトコード | Java 11（Gradle実行用JDKとは別） |
| ビルド設定 | Kotlin DSL・`gradle/libs.versions.toml` |

本体は`app`モジュールを使用する。調査用の独立した`transition-prototype`モジュールもあるが、本体への採用は未確定。依存関係はVersion Catalogに固定する。
Compose、Navigation、Lifecycle、Retrofit、OkHttp、Serialization、Coroutines、Coil、
DataStore、Custom Tabs、ZXing、Firebase Messaging、WorkManagerを使用する。
FirebaseとRelayの設定は[Android接続手順](push-reception.md#ビルド設定)を参照。
Roomは通知とホームの永続キャッシュに使用する。ローカル・連合は通信取得とメモリ上の状態管理を使う。
Hiltはカタログに定義があるが未導入で、依存は手動で組み立てる。

```text
UI → ViewModel → ユースケース／ドメインRepository → data Repository → remote／local

core/        通信・セキュリティ・設定・共通処理
data/       API DTO・マッピング・Repository実装
domain/     モデル・Repository契約・セッション共有
feature/    画面・ViewModel
navigation/ 型付き画面遷移
```

テーマ・表示などの単純な設定値の購読・保存は、Composeから`UserPreferencesStore`を直接利用してよい。
Flowはライフサイクルに従って購読し、保存はStoreのsuspendメソッドを使う。
このためだけにViewModelやRepositoryのラッパーを追加しない。
認証・API通信・複数段階の処理は、引き続きViewModelとドメインRepositoryを経由する。

### 通知関連文書

| 内容 | 記載先 |
| --- | --- |
| 利用者向けの通知動作・設定・通知キャッシュ | [UI・機能仕様](ui-guidelines.md#設定と初期値) |
| Push購読の登録・更新・解除・再認証・再開 | [通知設定と購読管理](push-settings.md) |
| AndroidのFirebase設定・FCMトークン同期・受信・復号・表示 | [Android接続と受信処理](push-reception.md) |
| Androidと両Relay実装の共通通信形式 | [Relay共通契約](relay-protocol.md) |
| Node.jsローカル模擬Relayの起動・保存・制限・テスト | [ローカルRelay](../relay/README.md) |
| Workers版Relayの開発・配送・保存・制限 | [Workers版Relay](../relay/workers/README.md) |
| Relayのテスト配置・測定履歴・実行方法 | [Relay検証ガイド](../relay/workers/testing.md) |
| 採用したハイブリッド配送と未完了作業 | [ハイブリッド仕様](../relay/workers/hybrid.md) |
| Workersの公開配置・変数・Secrets・監視・停止 | [配置・運用手順](../relay/workers/setup.md) |

通常の修正の検証結果はPR・コミットの説明に簡潔に残し、各技術文書へ履歴を追記しない。
リリース準備の確認は[更新・リリース計画](release-plan.md)、運用や移行の再現に必要な証跡は該当する記録に一度だけ残す。
過去の結果を現在の作業ツリーや配布APKの確認結果として扱わない。

## 通信・認証・保存の境界

- インスタンス入力をHTTPSのベースURLに正規化する。認証情報・パス・クエリ・フラグメントを拒否する。
- サーバー情報は`/api/v2/instance`で取得する。特定のインスタンスや均一なサーバーバージョンを前提にしない。
- アプリ登録、PKCE（S256）、OAuth state検証、トークン交換、認証情報検証を行う。
  現在の要求スコープは`read write`。古い権限のセッションでは必要に応じて再認証する。
- OAuth登録名の変更は新規登録から反映される。保存済みのアプリ登録を使う既存アカウントの投稿元名は変わらない。
- OAuth関連の秘密情報とアカウントセッションはAndroid KeystoreのAES-GCMで暗号化してDataStoreに保存する。
- 複数アカウントの復元・切替・ローカルログアウトを扱う。APIのベースURLは選択セッションから決める。
- API DTOをUIに渡さずドメインモデルへ変換する。Mastodon IDは常にString。
  JSONは未知フィールドを許容し、バージョン依存項目を必須と決めつけない。
- トークン、認可コード、client secret、Authorizationヘッダーをログへ出さない。
  リリースでHTTP本文ログを有効にしない。メディアアップロードは`/api/v2/media`を使用する。
- 投稿キャッシュはインスタンス・セッション・投稿IDで区別する上限付きメモリキャッシュ。
  設定・下書きの保存と、タイムラインの永続保存を混同しない。
- 通知は`BrowsingDatabase`（Room）に、セッション・インスタンス・タブ別で最新200件まで保存する。
  「すべて」は既存の通知テーブル、種類別は専用テーブルを使い、各タブの最新ページから取得範囲を確立する。
  閲覧済みIDは独立したテーブルに最新1,000件と「すべて」の閲覧位置を保存する。一覧の置換で既読記録を消さない。
  投稿・通知元は通知のJSONに内包し、画像本体・認証情報は保存しない。既読位置は別テーブルに保持する。
  保存は一覧スナップショットの置換と件数制限を同じトランザクションで行い、通信結果やStreaming・投稿操作の反映後に更新する。
  DB読み書き・JSON処理はUIスレッド外で行う。ログアウト時は対象セッションを削除し、遅延書き込みは登録済みアカウントの確認で拒否する。
  DBはバックアップ・端末移行から除外する。容量は件数で制限し、MB単位の固定上限は設けない。
  ホームは同じDBの専用テーブルへ、セッション・インスタンス別に最大500行を保存する。ブーストはタイムライン行IDで区別し、サーバーの順序を保持する。
  初回・追加取得は20件。DBを先に表示し、通常の通信取得1回で同じページを更新する。閲覧位置の復元も保存済み範囲を優先する。
  保存済み末尾と取得ページの末尾が重なる場合だけ履歴を接続し、未取得区間を飛ばさない。保存上限をサーバー履歴の末尾として扱わない。
  通信できないことが分かる場合は自動通信を省く。保存済み表示中の自動取得失敗は静かに扱い、手動更新・保存なしの失敗は再試行可能なエラーにする。
  投稿操作・Streamingの編集、追加、削除もDBへ反映する。通信中の変更を応答へ反映し直し、古い反応状態へ戻さない。
  スキーマv2への移行は通知を保持し、v3では種類別通知キャッシュと閲覧記録を追加して通知・既読位置・ホームを保持する。
  今後の拡張も`app/schemas`のスキーマを基準にマイグレーションする。
  今回はRepository経由の表示キャッシュに留め、全画面の唯一のデータ源への移行や投稿・アカウントの共通テーブル化は行わない。
- コルーチンのキャンセルを一般エラーとして握りつぶさない。メディア取り込みのファイルI/Oは背景処理に分離する。

永続キャッシュの対象とRepository経由の保存境界については、
[ADR 0002：永続キャッシュの範囲](adr/0002-persistent-browsing-cache.md)に判断を記録する。
通知の種類別取得、一覧と既読の分離、閲覧記録と不確定な新着件数の扱いは
[ADR 0007](adr/0007-notification-category-pages-and-read-state.md)を参照する。

## メイン画面の責務とライフサイクル

4タブのViewModelは`Route.Timeline`のナビゲーションエントリに保持する。
タブ切替では状態を維持し、エントリ破棄時にViewModelとリクエストを終了する。
アカウント切替では各画面のデータを初期化するため、全アカウント分の一覧を保持する構造ではない。

| ViewModel | 責務 |
| --- | --- |
| MainSessionViewModel | 復元・切替・ログアウト、共通設定、前景／背景に応じた1本のストリーミング接続 |
| TimelineViewModel | ホーム・ローカル・連合、更新・追加取得、お知らせ |
| SearchViewModel | 検索語・検索結果、古い検索のキャンセル、探索4一覧とタグ購読 |
| NotificationsViewModel | 通知・追加取得・未読件数・既読位置 |
| OwnProfileViewModel | 自分のプロフィール・タブ・更新・追加取得 |
| StatusActionsViewModel | メイン4タブの投稿・アカウント操作、リスト選択 |
| SettingsMaintenanceViewModel | 画像キャッシュ削除の実行状態・結果、インストール済みAPKの版情報 |
| ModerationManagementViewModel | 管理対象アカウントの隔離、サーバーのミュート・ブロック一覧と解除・取り消し、端末保存のワード管理 |

`HomeTimelineScreen`は各状態を個別に購読する。別画面のプロフィール編集・関係操作は
`AccountProfileViewModel`が担当し、表示には共通の`ProfileUiState`を使う。

`MainSessionViewModel`が所有する`BrowsingSession`を他のViewModelへ渡す。
設定の管理画面は閲覧セッションを変更せず管理対象を選ぶ。専用ViewModel内で同じキャンセルと世代番号の方針を適用する。[ADR 0008](adr/0008-word-mutes-in-notification-lists.md)を参照。
スナップショットはアカウントと世代番号を持ち、A→B→Aと切り替えても以前のAの応答を採用しない。
`SessionScopedViewModel`は切替時にリクエストをキャンセルして状態を初期化し、
結果反映前にキャンセル状態とスナップショットの一致を確認する。
検索語・プロフィールタブ・再取得の変更でも不要な要求をキャンセルする。

キャンセルと世代番号の照合を併用する判断は、
[ADR 0001：セッション隔離](adr/0001-session-isolation.md)を参照する。

ストリームと操作成功のイベントにはセッションを添え、該当するタブへ配信する。
投稿の反応更新ではブースト行の識別情報を保ち、削除時は一覧と通知の参照を更新する。
この経路はメイン画面内の同期用であり、全画面共通の永続キャッシュではない。

お気に入り・ブーストは共有の`StatusActionManager`で操作中状態と即時更新・確定・巻き戻しを管理し、
メイン4タブ以外の詳細・ハッシュタグ・保存済み投稿にも反映する。
前景判定はActivityのライフサイクルに従い、詳細や設定へ移動してもストリームを維持する。
前景のAndroid通知は`SystemNotificationRepository`経由で配信し、簡易通知と配信済みIDを共有する。

## ビルドと確認

Android Studioでルートを開き、SDK 37とプロジェクトで使用するJDKを設定する。
SDKの場所はローカルの`local.properties`で管理する。

変更に対応する最小限の確認を選ぶ。各編集のたびに全テストや全APKを生成しない。

| 変更範囲 | 基本の確認 |
| --- | --- |
| 文書のみ | 記載とコードの整合、参照先、`git diff --check`。Gradle・実機テストは不要 |
| Workers版Relay | `relay/workers`でNode.js 22以降の`npm ci`・`npm test`。D1・workerdで検証。Android変更がなければGradle不要 |
| 文言・色・余白など表示のみ | 変更画面の表示確認と必要なコンパイル／Debugビルド。固定値を再記述する単体テストは追加しない |
| ViewModel・Repositoryなどの振る舞い | 影響する既存テストを選択実行し、未カバーの振る舞い・失敗条件のみ追加。アプリのコンパイルも確認 |
| 認証・セッション・共有状態・通信基盤、依存関係・ビルド設定 | 影響範囲に応じて単体テスト全体とDebugビルド。OS連携へ影響する場合は該当する端末確認 |
| 通知許可・共有Intent・ジェスチャーなどOS／UI連携 | 該当する端末テストまたは手動確認。テストAPKを使う場合だけ生成 |
| 配布候補 | 単体テスト全体、Releaseビルド、署名・版番号・APK確認、変更機能の端末確認 |

連続するUI調整では編集ごとにGradleを実行しない。まず差分と関連コードを確認し、変更をまとめる。
確認用APKは変更後の画面をエミュレーター・実機で見る段階で必要な場合に生成する。
コンパイル確認だけで足りる場合は対象のコンパイルを選び、同じ変更に対してDebug APKビルドと重ねて実行しない。
APK生成後に関連コードが変わっていなければ再ビルドしない。配布候補では表の確認を行う。

例えば通知ViewModelだけを変更した場合は、次のように対象を指定する（Windows PowerShell）。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NotificationsViewModelTest'
```

新しいテストは利用者から見える挙動、データの整合、実際の不具合の再発防止を検証する。
同じ条件を重ねたテスト、実装をそのまま写した期待値、単なる定数・文言の一致確認は増やさない。
既存テストを削る場合は、重複先または不要になった仕様を確認する。件数だけを理由に削除しない。
成功後の再実行は関連コードの変更、失敗、未解決の懸念がある場合に限る。

`assembleDebugAndroidTest`は実機テスト用APKのビルドであり、実機テストの実行ではない。
実行する場合は端末を接続し、`connectedDebugAndroidTest`で対象を絞るか、必要な操作を手動確認する。
確認結果はPR・コミットの説明に対象・コマンド・結果を簡潔にまとめ、未実行項目を成功扱いにしない。
リリース・運用・移行の記録が必要な場合も、同じ結果を複数の文書へ転記しない。

配布用Release APKは、Git管理外の`release-signing.properties`がある場合だけ自動署名する。
このファイルには`storeFile`（PKCS12鍵の絶対パス）、`passwordFile`（パスワードを1行で保存したファイルの絶対パス）、
`keyAlias`を指定する。鍵とパスワードは別の安全な場所へバックアップし、GitやReleasesにはアップロードしない。

```sh
./gradlew testDebugUnitTest assembleRelease
```

署名済みAPKは`app/build/outputs/apk/release/app-release.apk`に出力される。
署名設定がない場合は`app-release-unsigned.apk`となり、そのまま配布しない。
Debug版とRelease版は署名が異なるため、同じapplication IDのまま上書きインストールできない。

既存の回帰テストはアカウント切替・ログアウト後の遅延応答、検索のやり直し、
プロフィールのタブ切替、通知再取得と追加取得の競合、既読位置の分離、
投稿更新・削除のタブ間反映、ストリーミング接続の開始・停止を対象にする。
キャンセルを無視するテスト用要求でも古い応答が反映されないことを確認する。

実機確認が必要な変更では、Android 8.0以上の端末とテスト用アカウントを使用する。
以下は確認の候補であり、毎回すべてを実施するチェックリストではない。変更した経路を選ぶ。

1. インスタンス入力→接続確認→ブラウザー認証→アプリ復帰→タイムラインを確認する。
2. 再起動時の復元、アカウント追加・切替・ログアウトを確認し、別アカウントの情報が混ざらないことを確認する。
3. タイムライン更新・追加取得、検索、通知、自分／他人のプロフィールと戻る操作を確認する。
4. UI変更では投稿・詳細・中央ダイアログ、設定プレビュー、文字倍率とライト／ダーク表示を確認する。
5. 投稿関連の変更では投稿元、返信、添付・ALT、下書きの明示保存と再起動後の復元、失敗時の入力保持を確認する。
6. HTTP入力、不正なURL要素、認証中断、不正なstateでセッションが作成されないことを確認する。

テストのために投稿・フォロー・通報などを行う場合は、利用するアカウントと操作範囲を明確にする。
認証情報を含むログや画面を共有しない。

### Custom Tabsの確認

リンク閲覧はCustom Tabsを使用する。起動処理・設定オフ・未対応ブラウザー・起動失敗・不正URLは
`WebLinkLauncherDeviceTest`で確認する。実際のChromeから外部アプリへ移動する経路は
`CustomTabsBrowserDeviceTest`で確認し、起動Intentの検査だけでブラウザー動作を確認済みとしない。
後者はChromeとYouTubeがインストールされたエミュレーター用で、Mastodonのアカウントは不要。

`tools/web-browser-fixture.mjs`をNode.jsで起動したまま、別のターミナルから実行する。
以下のAPKは事前に`:app:assembleDebug :app:assembleDebugAndroidTest`で生成する。

```powershell
node tools/web-browser-fixture.mjs
```

```powershell
adb -s emulator-5554 reverse tcp:8765 tcp:8765
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class io.github.ponpokoo.mastodonclient.WebLinkLauncherDeviceTest,io.github.ponpokoo.mastodonclient.CustomTabsBrowserDeviceTest -e webTestBaseUrl http://127.0.0.1:8765 io.github.ponpokoo.mastodonclient.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 reverse --remove tcp:8765
```

検証ページはHTTPのループバック接続であり、アプリの平文通信設定は変更しない。
外部アプリの自動起動可否はブラウザーの判断に従う。起動直後のJavaScriptによる遷移でも
ブラウザーが外部起動を許可する場合があり、Nagisa側で一律に禁止する仕様ではない。

実機では通常の投稿リンクからCustom Tabsを開き、ページ移動と戻る操作、閉じた後の
元の画面・スクロール位置、ページ内のアプリ起動リンク、未導入アプリのフォールバック、
設定オフの場合の外部表示を確認する。端末・Android版・ブラウザー名／版・対象URLと結果を記録する。
ブラウザーのCookie・サイトログイン状態はMastodonのアカウント切替とは別に管理される。

## 文書の維持

旧来のプロフィール仕様・設定／投稿仕様はUI・機能仕様へ、状態管理メモ・実機確認手順は本書へ統合した。
文書の更新・追加基準は[AGENTS.md](../AGENTS.md#documentation-maintenance)に従う。
Play公開後を含む通常保守では、既存の説明が実際の挙動・契約・操作手順と食い違う箇所だけ更新する。
内部リファクタリングや通常の表示調整には、仕様の変更がなければ文書更新・調査報告・計画書への追記は不要。
予定と実装済みを区別し、細かな実装値はコードとテスト、変更履歴はGit、継続課題はIssuesに置く。
ADRは長期に影響する設計上の選択がある場合に限り、[記録基準](../AGENTS.md#architecture-decision-records)に従う。
