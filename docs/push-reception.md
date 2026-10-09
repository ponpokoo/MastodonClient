# Push通知のAndroid接続・受信・復号・表示

Firebaseのビルド設定からFCMトークン取得、受信Worker、暗号文取得・復号、Android通知表示までをまとめる。
購読の登録・解除・再認証は[購読管理](push-settings.md)、通信形式は[Relay共通契約](relay-protocol.md)、
Workersの配置は[配置・運用手順](../relay/workers/setup.md)を参照する。
Firebaseなしでも受信ロジックを呼び出し、固定の暗号文による検証ができる。
FirebaseとRelay URLを設定したビルドでは、アカウントごとにPushを有効化できる。
RelayとAndroidの責務を分ける背景は、
[ADR 0013：ハイブリッド配送と情報境界](adr/0013-adopt-hybrid-push-delivery.md)を参照する。

2026-10-07にハイブリッド方式を採用し、Androidにinline／旧fetch／v2 `sync_required`の受信を実装した。
2026-10-08に旧APKの大通知欠落を許容して本番Relayを直接切替済み。
[小通知5件の実FCM測定](investigations/relay-live-fcm-measurement-20261008.md)に続き、合成大通知の実FCM受信と隔離した同期処理を確認した。今回のPush試験は2026-10-09で終了し、残る通常経路・欠落回復・端末負担・購読移行は実運用で確認する。
確認済みの範囲と実運用での確認事項は[採用仕様](../relay/workers/hybrid.md#採用後の対応と確認)、
Relay側のテストと測定記録は[検証ガイド](../relay/workers/testing.md)を参照する。

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

Relay登録v2も`fcmToken`を使用し、端末の登録トークン方式を維持する。
登録v2対応Relayを先に配置する。登録APIの版と配送エンベロープの版は別に扱う。
現行の本番配送はStringエンベロープのv1 inlineとv2 sync_requiredを使う。Androidは旧v1 fetchの受信・本文取得にも対応するが、本番ハイブリッドの本文GETは404を返す。
購読ごとの公開鍵の確定は[購読管理](push-settings.md#mastodonとの接続順序)に従う。
FIDを登録トークンとして送信する実装にはしていない。
宛先指定方式の変更にはRelay契約と送信側を合わせた変更が必要となる。

## FCM受信とWorkManager

`NagisaMessagingService`は非公開ServiceとしてFCMのdataメッセージを受け取り、
`FcmWorkScheduler`がWorkManagerへ暗号文または同期要求エンベロープと有効期限を保存する。
同じ登録・メッセージの実行中作業は重複登録しない。
取得が必要な通知はネットワーク接続を待つ。Android 12以上の高優先度通知は
expedited workを使用し、割当不足時は通常の作業として実行する。
Android 8〜11では通常の作業を使用するため、表示の即時性は未保証。

Workerから`PushMessageHandler`へ渡し、inline／fetchは購読照合・暗号文取得・復号・表示、sync_requiredは下記の同期Work投入を行う。
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

## ハイブリッドの差分同期と欠落回復

v2は`version`・`registrationId`・`messageId`・`transport=sync_required`だけを許す。
本文・URL・since_idなどの追加項目、v1 sync_required、v2 inlineは拒否する。
Relay messageIdは配送識別子として扱い、Mastodon通知IDに転用しない。
`DefaultPushSyncRepository`が保存済みのACTIVE購読と認証を照合し、要求世代を暗号化保存してから
`nagisa-push-sync-{sessionId}`のUnique Workを永続投入する。小さいinlineには同期APIを追加しない。

- 3秒の待機で短時間の要求を集約する。APPEND_OR_REPLACEで同じアカウントの同期を直列化し、
  処理済み世代の後続WorkはAPIを呼ばず終了する。実行中の新要求は次の世代として残る。
  KEEPだけでは終了直前の要求を失い得るため、Work数そのものは1件へ限定しない。
- 対象アカウントの`GET /api/v1/notifications?since_id=…&max_id=…&limit=40`を利用する。
  IDはStringのまま扱い、最初のページの先頭IDを次の同期位置とする。短いページでも終了とせず、
  空ページまたは保存済み位置まで取得する。循環するカーソルは失敗とし、位置を進めない。
- 全ページ取得後に古い順で既存の通知表示へ渡す。確定済み購読の通知種類を適用する。
  旧保存情報に種類がない間は全種類を対象にし、次の購読照合で更新する。
  共有の500件履歴に加え、同期途中の表示処理済みIDを暗号化保存し、再実行時の重複を抑える。
  同期位置・完了世代は表示処理後にまとめて更新する。本文は永続保存しない。
- 新規購読はMastodon購読作成前に最新1件から開始位置を保存する。空の履歴も初期化済みとして扱う。
  旧購読で開始位置がない場合は直近1ページを起点にする。通常の初期化は表示せず、
  sync_required／FCM欠落による初期化はそのページを表示対象にする。初期化前の全履歴回復は保証しない。
- ネットワーク接続を待ち、1実行は最大120秒。通信・保存・表示失敗や時間切れは指数バックオフで最大5回再試行する。
  失敗しても要求世代・同期位置を保持し、次のPush・前景復帰・画面更新で再投入する。
  受信期限内に受理した同期要求は履歴回復として扱い、同期WorkにFCMの残りTTLを引き継がない。
- MainSessionViewModelによる起動・前景復帰とNotificationsViewModelによる初回取得・更新で、
  未完了の要求、未初期化、または最後の同期から15分経過したACTIVE購読を補完する。
  周期ポーリングは追加しない。`onDeletedMessages()`はこの間隔を無視して全ACTIVE購読を補完する。
- 解除・ログアウト・再認証では解除中の保存後にアカウントの同期Workを取り消す。
  API取得前後・表示直前・保存前にも現在の資格と登録を照合する。閲覧アカウントの切替は
  他アカウント宛てPushを無効にしない。旧登録を指定した遅延Workは終了する。

前景設定・OS許可により表示しない場合も同期は完了とする。後から許可を与えて過去通知を再掲する機能ではない。
プロセス停止とOS表示の間の完全な原子性、サーバーで削除された履歴、500件より古いinlineとの重複排除は保証しない。
待機・OSの実行制限・APIページ数により同期通知には遅延がある。実通知での通信量・電池・表示遅延は別途測定する。
設計の理由・初期化と集約の制約は[ADR 0014](adr/0014-android-hybrid-push-sync.md)を参照する。
APIとWorkの根拠は[Mastodon通知API](https://docs.joinmastodon.org/methods/notifications/)、
[WorkManagerの競合方針](https://developer.android.com/reference/androidx/work/ExistingWorkPolicy)、
[FCM欠落コールバック](https://firebase.google.com/docs/cloud-messaging/android/receive-messages#override_ondeletedmessages)。

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

### 2026-10-07：Androidハイブリッド同期の確認

単体テスト全体401件（失敗・スキップ0）とDebug APKビルドが成功した。
PushSyncRepository／Source、PushMessage、PushRegistration／Control、暗号化Store、FCM形式、
回復を呼び出すViewModelの追加・関連テストを先に実行した。
集中した要求のAPI集約、短いページと不透明ID、途中失敗・再生成・取消、トークン更新との競合、
資格／購読変更後の遅延応答、初期化、前景補完の間隔、通知種類と重複を確認した。

Pixel_10aエミュレーター（Android 17）でHybridPushDeviceTest 2件、PushNotificationDeviceTest 1件が成功した。
人工の購読と公開暗号文fixture、模擬APIページを使い、Keystoreへの同期位置保存・再読込、
inlineと同期の共通ID重複排除、Android通知表示、Unique Workの取消後の再投入を確認した。
既存ログイン情報や本番購読を試験用に書き換えていない。実Mastodon→Relay→FCMの大通知配送、
onDeletedMessagesの実FCM発火、通信量・電池・遅延・2時間障害・本番切替は未検証。

再現コマンドはリポジトリルートで実行する。実行前に対象端末を選び、実際の結果のクラス名を確認する。
端末指定を変更したときの設定キャッシュ再利用を避け、クラスを個別に指定する。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache '-Pandroid.testInstrumentationRunnerArguments.class=io.github.ponpokoo.mastodonclient.HybridPushDeviceTest'
.\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache '-Pandroid.testInstrumentationRunnerArguments.class=io.github.ponpokoo.mastodonclient.PushNotificationDeviceTest'
```

### 2026-10-08：本番Relayのハイブリッド切替

利用者の指示により既存origin・D1・購読・FCM設定を継承して本番を切り替え、公開capabilitiesと日次Cronの保存を確認した。
旧APKの大通知欠落を許容する判断、実FCM測定の未完了範囲、差し戻し先は[本番切替記録](investigations/relay-production-hybrid-cutover-20261008.md)を参照する。
この配置確認は実通知・大通知の端末表示を確認した試験ではない。

### 2026-10-08：エミュレーターでの簡易再確認

利用者の依頼でPushSyncRepositoryTest 13件と、接続したPixel_10a（Android 17）のHybridPushDeviceTest 2件を再実行し、
失敗・スキップ0で成功した。全単体テスト・実FCMは今回の対象にしていない。
模擬ページで5件の同期要求を2回のページ取得（データ1ページ＋空ページ）へ集約し、
inlineとの重複排除、同期位置の暗号化保存・再読込、Work取消後の再投入を確認した。
途中失敗・再生成後の回復と資格変更の抑止は単体テストで確認した。
実FCMの大通知・自然な欠落コールバック、通信量・配信遅延の実測には、実FCMを設定した専用Relayとテスト用アカウントが必要。

実行：`:app:testDebugUnitTest --tests '*PushSyncRepositoryTest' :app:connectedDebugAndroidTest --no-configuration-cache`に
`-Pandroid.testInstrumentationRunnerArguments.class=io.github.ponpokoo.mastodonclient.HybridPushDeviceTest`を指定した。

### 2026-10-08：実FCMの通信量・表示遅延

本番ハイブリッドと背景のエミュレーターで小通知5件の受信・復号・OS表示を測定した。
実測値・時計のずれ・匿名原票と再現手順は[測定記録](investigations/relay-live-fcm-measurement-20261008.md)を参照する。
今回の少量調査は終了。大通知同期、自然な欠落コールバック、電池・物理端末・長時間運用は未測定。

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

### 少量の実通知の観測

既存購読の移行を確認する場合、同じ観測クラスの`livePush=migrationBaseline`で更新前の登録情報のハッシュを
アプリの非公開保存領域へ保持し、更新後に`livePush=migrationCheck`でアプリ起動時の自動移行を観測する。
登録ID・配送先・暗号鍵等の保持と、公開鍵紐付け済み・ACTIVEへの遷移を確認する。比較値や秘密値は出力しない。
通知が観測枠の終了後に届いた場合は、`livePush=migrationDelivery`、`expectedText`、配置後のUnixミリ秒を
`postedAfter`で指定し、表示済み通知の本文・対象アカウント・表示時刻を照合できる。
この照合だけで、観測枠内の新しい受信Workとの対応やPushの所要時間を証明しない。

[`PushLiveDeliveryDeviceTest`](../app/src/androidTest/java/io/github/ponpokoo/mastodonclient/PushLiveDeliveryDeviceTest.kt)は、
`livePush=preflight`で選択中のアカウントと有効なPush購読を確認し、
`livePush=observe`で開始後に追加された実FCM受信Workの成功と、そのアカウントの新しいOS通知を3分間待つ。
`expectedText`を指定すると通知本文にその文字列があることも確認する。
通常の実行ではスキップする。観測テストは通知を自作・送信せず、購読や権限を変更しない。
トークン、鍵、管理用秘密値、配送URL、受信した通知本文は出力しない。

別の管理下アカウントからテスト用メンションを1件送って観測する。
受信側のアプリを背景に置き、観測が終わるまで通知を開かない。
同じアカウントから自分への投稿を実通知の代わりにしない。
受信Workの成功だけでは復号・表示の成功とは扱わず、本文を指定したOS通知と合わせて判定する。
観測時間内にテスト通知を送れなかった場合は、時間切れを配送性能の不合格と扱わない。
インストール済みAPKと公開Relayの対応版を記録し、旧版での成功を新しい候補版の成功と扱わない。

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
