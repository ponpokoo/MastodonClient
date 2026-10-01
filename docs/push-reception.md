# Push通知のAndroid接続・受信・復号・表示

Firebaseのビルド設定からFCMトークン取得、受信Worker、暗号文取得・復号、Android通知表示までをまとめる。
購読の登録・解除・再認証は[購読管理](push-settings.md)、通信形式は[Relay共通契約](relay-protocol.md)、
Workersの配置は[配置・運用手順](../relay/workers/setup.md)を参照する。
Firebaseなしでも受信ロジックを呼び出し、固定の暗号文による検証ができる。
FirebaseとRelay URLを設定したビルドでは、アカウントごとにPushを有効化できる。

## ビルド設定

`app/google-services.json`のパッケージ名とNagisaのapplication IDを照合し、
Google Services Gradle plugin、Firebase Messaging、WorkManagerを接続する。Analytics SDKは追加していない。

- `google-services.json`は開発環境ごとの設定としてGit管理から除外する。
  サーバー用サービスアカウント秘密鍵とは別のファイル。
- このファイルがある場合だけGoogle Services pluginを適用する。
  未配置の環境でもビルドでき、Firebaseの起動処理を省略する。
- 公開Relayへ接続する場合は、Git管理外の`local.properties`に
  `nagisa.relayUrl=https://公開Relayのホスト/`を設定して再ビルドする。
  Gradleの同名プロパティでも指定できる。HTTP、資格情報・クエリ付きURLはビルド時に拒否する。

## FCMトークン取得と同期

アプリプロセス起動時とFCMトークン変更時には、直列のWorkerでSDKから最新トークンを取得し、
暗号化保存と全対象購読の更新へ渡す。トークンをログ出力しない。
通信エラー時は再試行し、上限到達後は次回起動・トークン変更で再開する。

Relay v1の`fcmToken`契約に合わせ、登録トークン方式を使用する。
FIDを登録トークンとして送信する実装にはしていない。
宛先指定方式の変更にはRelay契約と送信側を合わせた変更が必要となる。

## FCM受信とWorkManager

`NagisaMessagingService`は非公開ServiceとしてFCMのdataメッセージを受け取り、
`FcmWorkScheduler`がWorkManagerへ暗号文と有効期限を保存する。
同じ登録・メッセージの実行中作業は重複登録しない。
取得が必要な通知はネットワーク接続を待つ。Android 12以上の高優先度通知は
expedited workを使用し、割当不足時は通常の作業として実行する。
Android 8〜11では通常の作業を使用するため、表示の即時性は未保証。

Workerから既存の`PushMessageHandler`へ渡し、購読照合・暗号文取得・復号・表示を行う。
受信時と表示直前に期限を確認し、前景では「起動中の通知」の設定にも従う。
無効な形式、未知フィールド、サイズ超過、期限切れ、TTL 0を受け付けない。
TTLは最大24時間。実行失敗時は指数バックオフで最大5回再試行する。
WorkManagerの入力にはFCMトークン・Mastodonトークン・復号済み通知本文を保存しない。

Relayは**dataのみ**で送信する必要がある。Firebase Consoleの通知作成画面から送る
notificationメッセージは、背景でSDKが直接表示するため、この復号経路の検証にはならない。

## 呼び出し口と境界

`PushMessageHandler(context).receive(data)`が受信の呼び出し口。
dataは[Relay共通契約](relay-protocol.md#fcmエンベロープ)のString値のMapで、DTOを画面へ渡さない。
`DefaultPushMessageRepository`が購読・セッションを確認し、暗号文を取得・復号して
ドメインの`PushNotification`へ変換し、既存の`SystemNotificationDataSource`へ渡す。
HTTP取得と復号はIOコンテキストで処理する。

- `PROCESSED`: 通知表示処理に渡した。重複・OS許可拒否では表示されない場合もある。
- `IGNORED`: 対象購読なし・解除中・未ログイン・期限切れなどで処理対象外。
- `REJECTED`: 未対応形式、不正データ、暗号認証失敗など。
- 通信エラー・キャンセル・保存情報の破損は呼び出し側へ伝える。
  FCM受信Workerが期限内に時間を置いて再試行する。

FCMアダプターは長い取得を受信コールバック内に抱えず、実行制限に対応するWorkerへ渡す。
アプリ独自の公開BroadcastReceiverや外部から通知表示を起動するIntentは追加していない。

## 暗号文の取得と復号

`inline`では受信本文を直接使用し、`fetch`では端末に保存したRelay識別子を接続先にする。
受信MapのURLは接続先として採用しない。管理用トークンだけを送信し、Mastodonトークンを送らない。
HTTPの本番設定とリダイレクトを拒否し、取得JSONは100,000 bytes、暗号文は64 KiBまでに制限する。
取得応答の登録ID・メッセージIDも要求と照合する。404／410は表示しない。

標準の`aes128gcm`（RFC 8291）と旧`aesgcm`（draft-04）の単一レコードに対応。
P-256の公開点、ECDH、HKDF-SHA256、AES-GCM認証、各形式のパディングを検証する。
未対応の符号化・複数レコード・曖昧な暗号ヘッダー・改ざん・鍵違いは表示しない。
独自の暗号アルゴリズムを作らず、JCAの暗号プリミティブを使用する。

Mastodon Push本文はREST通知とは別DTOで解析する。
`notification_id`は数値表現の場合もStringとして桁を保ち、未知の通知種類・追加項目を許容する。
本文内の`access_token`は取り込まず、保存・ログ出力しない。
タイトル・本文は平文として表示し、HTMLの解釈や追加の通知詳細API取得は行わない。

## 表示と重複防止

登録IDに対応するログイン済みアカウントを選び、閲覧中アカウントには依存しない。
ACTIVEかつ認証情報のフィンガープリントが一致する購読だけを扱う。
取得後と表示直前にも状態を再確認するため、取得中のログアウト・再認証・解除は表示を抑止する。
最終表示は購読管理と共通Mutexで直列化する。[ログアウト時の購読解除](push-settings.md)も接続済み。

Streaming・簡易通知・Pushは同じ配信コーディネーターと
`delivered_notifications`のセッション別履歴を使用する。
通知IDで直近500件を重複排除し、同時受信でも同じアカウントの同一通知を重ねて表示しない。
通知権限がない場合や表示に失敗した場合は配信済みにしない。
履歴の保存前にプロセスが停止した場合や500件を超える古い再送の完全な重複排除は保証しない。

既存チャンネル・アイコン・通知グループ・アプリを開くタップ動作を共用する。
PushにもOSの通知許可とチャンネル設定が適用される。
Push表示も前景では「起動中のAndroid通知」に従う。[設定画面](push-settings.md)では、Firebase・Relay接続が未設定のビルドに限り「準備中」として有効化できない。

## 確認方法と残る課題

公開RFC・draftの既知ベクトルに加え、Node.jsの独立したHKDF/AES実装による固定暗号文を使用する。
`relay/tools/encryption-fixtures.mjs`を実行すると単体・Androidテスト用の資材を再生成できる。
資材の鍵は公開仕様の例で、利用者の鍵ではない。

### 公開Relayへの接続テスト

`RelayLiveConnectionDeviceTest`は、Androidの実FCMトークンで公開Relayへの登録、
同じ登録の再送、不正な管理用秘密値の拒否、未知メッセージの取得、解除・墓標を確認する。
既存アカウントの購読は変更せず、検証用登録をfinallyで解除する。
1回の実行で1件の墓標を残す。トークン、管理用秘密値、配送URLをログへ出さない。
実際のFCM送信やMastodonからの通知は、このテストの確認範囲に含まれない。

通常のテストではスキップし、次の指定でのみ公開Relayへ接続する。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=io.github.ponpokoo.mastodonclient.RelayLiveConnectionDeviceTest' '-Pandroid.testInstrumentationRunnerArguments.liveRelay=true'
```

公開Relayへの登録テストとMastodonからの実配信確認は別の確認範囲とする。
実配信時は、設定済みのビルドでPushを有効化し、必要ならpush権限の再認証を行って通知の到着を確認する。
`onDeletedMessages`からの欠落補完、背景での無効化・ログアウト解除の定期再試行、
Push有効時の簡易通知スケジュール調整、物理端末のDoze・強制停止は残課題。

## 過去の検証記録

以下は当時のコード・設定・確認範囲の記録。今回の文書整理ではテストを再実行していない。当時の未確認事項は、後の実配信報告や現行版の確認結果と区別する。

### 2026-09-21：受信・復号の固定暗号文テスト

- 復号・受信・取得・重複排除の単体テスト20件追加。
- 関連テスト30件成功後、単体テスト全体122件成功（失敗・エラー・スキップなし）。
- Debugビルド成功。
- Pixel_10a AVD / Android 17で`PushNotificationDeviceTest`の1件成功。
  実際のAndroid Keystoreで保存・復元し、固定暗号文を復号してOS通知を表示。
  平文表示、Streamingと共通の重複排除、解除中の受信抑止を確認した。
- 実FCM配信、実Mastodonからの送信、物理端末のDoze・通常終了・強制停止は未確認。

### 2026-09-21：Firebase接続

- 単体テスト133件成功、Debugビルド成功。
- Pixel_10a AVD / Android 17で端末テスト25件成功。
  既存のホーム2件は未ログインのため前提条件未充足で対象外。
- 配置されたFirebaseプロジェクトで実トークン取得と暗号化保存を確認。
  ローカル投入した暗号文エンベロープをWorkManagerで処理し、未登録宛先を表示しないことも確認。
- Windowsの単体保存テストはAndroid用FileStorageの置換で失敗したため、
  テストのみOkioStorageを明示。暗号化・復元・削除・改ざん検出の検証は維持した。
- 実FCMメッセージ配送、Mastodonからの通知、物理端末は未検証。
  公開Relayへの登録・解除は下記の追加確認で成功した。

当時のローカル設定・Debug APKの記録。現在の各環境の設定を保証するものではない。

- ローカル開発環境では`https://nagisa-relay.ponta3921.workers.dev`を設定済み。
  Firebase有効・Relay URL設定済みのDebug APKを生成した。
  URLはGit管理外の設定から読み込み、Androidのソースには固定しない。
  Firebase設定とURLの配置だけではMastodonからの実通知確認は完了しない。

当時の登録トークン方式の設計記録。

Firebase Messaging 25.1.3では`getToken`／`onNewToken`は非推奨だが提供されている。
推奨されるFirebase Installation ID方式への移行は、送信側の宛先指定とRelay契約を
合わせて変更する必要があるため、公開運用前の設計課題として残す。
FIDを登録トークンとして黙って送信する実装にはしていない。

### 2026-09-21～2026-09-23：公開Relay接続と実配信

初回の接続では`/health`が`configured`でも登録・解除がHTTP 500になった。
D1の`daily_usage`テーブルが欠けていることを確認した。
このテーブルも[初期化SQL](../relay/workers/migrations/0001_initial.sql)に含まれる。
不足分の適用後、Pixel_10a AVD / Android 17から接続テスト1件が成功した（2026-09-21）。
実FCMトークンでの登録・再登録、403認証拒否、未知メッセージの404、解除と遅延PUTの410を確認。
検証用登録は解除済み。接続URLを含むDebug APKをエミュレーターへインストールした。
登録テスト後、アプリの「リアルタイム通知」を有効化し、必要ならpush権限の再認証を行って、
Mastodonからの通知でFCM受信・復号・表示を確認する段階へ進んだ。

同日、利用者が再認証後に「有効」になったことと、Mastodonからの通知がNagisaに届いたことを確認した。
今回の試験では公式アプリと同程度の待ち時間だったとの報告。配信遅延を計測・保証したものではない。
実配信後の利用者による通知の継続試験は2026-09-23に完了した。
画面消灯・長時間放置、重複・欠落、通知タップなど個別条件は未確認。
公開WorkersのCPU時間と無料枠使用量は利用者が確認済み。具体的な計測値はこの文書に記録していない。

## 参照

- [RFC 8291](https://www.rfc-editor.org/rfc/rfc8291.html)
- [旧Web Push暗号化draft-04](https://datatracker.ietf.org/doc/html/draft-ietf-webpush-encryption-04)
- [Mastodon Push本文の公式実装](https://github.com/mastodon/mastodon/blob/main/app/serializers/web/notification_serializer.rb)
- [Firebase Android設定](https://firebase.google.com/docs/android/setup)
- [FCM受信とWorkManagerへの引き継ぎ](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)
- [登録トークンとFID](https://firebase.google.com/docs/cloud-messaging/android/get-started)
- [WorkManager](https://developer.android.com/jetpack/androidx/releases/work)
