# 開発ガイド

UI・設定・投稿・プロフィールの現行仕様は [UI・機能仕様](ui-guidelines.md) を参照する。
本書は開発環境、実装の境界、状態管理、確認手順をまとめる。

## プロジェクト構成

| 項目 | 設定 |
| --- | --- |
| プロジェクト／アプリ名 | MastodonClient／Nagisa |
| 作業ツリーのバージョン | `2.0.0`（versionCode 6）。公開状況は [更新・リリース計画](release-plan.md) を参照 |
| 新規OAuth登録名（投稿元） | `Nagisa for Mastodon` |
| Namespace・application ID | `io.github.ponpokoo.mastodonclient` |
| OAuth redirect URI | `io.github.ponpokoo.mastodonclient://oauth/callback` |
| 言語・UI | Kotlin・Jetpack Compose・Material 3 |
| Android SDK | min 26、compile 37、target 37 |
| JVMバイトコード | Java 11（Gradle実行用JDKとは別） |
| ビルド設定 | Kotlin DSL・`gradle/libs.versions.toml` |

単一の`app`モジュールを使用する。依存関係はVersion Catalogに固定する。
Compose、Navigation、Lifecycle、Retrofit、OkHttp、Serialization、Coroutines、Coil、
DataStore、Custom Tabs、ZXing、Firebase Messaging、WorkManagerを使用する。
FirebaseとRelayの設定は[Android接続手順](firebase-android.md)を参照。
Roomは依存のみで、Entity・DAOを使った永続タイムラインキャッシュは未実装。
Hiltはカタログに定義があるが未導入で、依存は手動で組み立てる。

```text
UI → ViewModel → ユースケース／ドメインRepository → data Repository → remote／local

core/        通信・セキュリティ・設定・共通処理
data/       API DTO・マッピング・Repository実装
domain/     モデル・Repository契約・セッション共有
feature/    画面・ViewModel
navigation/ 型付き画面遷移
```

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
- コルーチンのキャンセルを一般エラーとして握りつぶさない。メディア取り込みのファイルI/Oは背景処理に分離する。

## メイン画面の責務とライフサイクル

4タブのViewModelは`Route.Timeline`のナビゲーションエントリに保持する。
タブ切替では状態を維持し、エントリ破棄時にViewModelとリクエストを終了する。
アカウント切替では各画面のデータを初期化するため、全アカウント分の一覧を保持する構造ではない。

| ViewModel | 責務 |
| --- | --- |
| MainSessionViewModel | 復元・切替・ログアウト、共通設定、前景／背景に応じた1本のストリーミング接続 |
| TimelineViewModel | ホーム・ローカル・連合、更新・追加取得、お知らせ |
| SearchViewModel | 検索語・検索結果、古い検索のキャンセル |
| NotificationsViewModel | 通知・追加取得・未読件数・既読位置 |
| OwnProfileViewModel | 自分のプロフィール・タブ・更新・追加取得 |
| StatusActionsViewModel | メイン4タブの投稿・アカウント操作、リスト選択 |
| SettingsMaintenanceViewModel | 画像キャッシュ削除の実行状態・結果、インストール済みAPKの版情報 |

`HomeTimelineScreen`は各状態を個別に購読する。別画面のプロフィール編集・関係操作は
`AccountProfileViewModel`が担当し、表示には共通の`ProfileUiState`を使う。

`MainSessionViewModel`が所有する`BrowsingSession`を他のViewModelへ渡す。
スナップショットはアカウントと世代番号を持ち、A→B→Aと切り替えても以前のAの応答を採用しない。
`SessionScopedViewModel`は切替時にリクエストをキャンセルして状態を初期化し、
結果反映前にキャンセル状態とスナップショットの一致を確認する。
検索語・プロフィールタブ・再取得の変更でも不要な要求をキャンセルする。

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
確認結果には対象・コマンド・結果を記録し、未実行項目を成功扱いにしない。

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

## 文書の維持

旧来のプロフィール仕様・設定／投稿仕様はUI・機能仕様へ、状態管理メモ・実機確認手順は本書へ統合した。
機能変更時は該当する文書を更新し、予定と実装済みを区別する。細かな実装値の正本はコードとし、
文書には利用者から見える設定・挙動、設計上の境界、確認に必要な情報を残す。
