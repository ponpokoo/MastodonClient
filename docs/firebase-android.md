# FirebaseのAndroid接続

`app/google-services.json`のパッケージ名とNagisaのapplication IDを照合し、
Google Services Gradle plugin、Firebase Messaging、WorkManagerを接続した。
Analytics SDKは追加していない。

## ビルド設定

- `google-services.json`は開発環境ごとの設定としてGit管理から除外する。
  サーバー用サービスアカウント秘密鍵とは別のファイル。
- このファイルがある場合だけGoogle Services pluginを適用する。
  未配置の環境でもビルドでき、Firebaseの起動処理を省略する。
- Relayが公開運用可能になった後、Git管理外の`local.properties`に
  `nagisa.relayUrl=https://公開Relayのホスト/`を設定して再ビルドする。
  Gradleの同名プロパティでも指定できる。HTTP、資格情報・クエリ付きURLはビルド時に拒否する。
- ローカル開発環境では`https://nagisa-relay.ponta3921.workers.dev`を設定済み。
  Firebase有効・Relay URL設定済みのDebug APKを生成した。
  URLはGit管理外の設定から読み込み、Androidのソースには固定しない。
  Firebase設定とURLの配置だけではMastodonからの実通知確認は完了しない。

## 受信・トークン更新

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

アプリプロセス起動時とFCMトークン変更時には、直列のWorkerでSDKから最新トークンを取得し、
暗号化保存と全対象購読の更新へ渡す。トークンをログ出力しない。
通信エラー時は再試行し、上限到達後は次回起動・トークン変更で再開する。

Relayは**dataのみ**で送信する必要がある。Firebase Consoleの通知作成画面から送る
notificationメッセージは、背景でSDKが直接表示するため、この復号経路の検証にはならない。

## 現行契約と残る作業

Relay v1の`fcmToken`契約に合わせ、今回の接続は登録トークン方式を使用する。
Firebase Messaging 25.1.3では`getToken`／`onNewToken`は非推奨だが提供されている。
推奨されるFirebase Installation ID方式への移行は、送信側の宛先指定とRelay契約を
合わせて変更する必要があるため、公開運用前の設計課題として残す。
FIDを登録トークンとして黙って送信する実装にはしていない。

[Workers＋D1試験版](../relay/workers/README.md)に実送信アダプターを追加した。
次に必要なのは[Web画面でのRelay配置とFCM送信権限の設定](relay-workers-setup.md)、
公開URLをAndroidへ設定して行う実配信確認。
`onDeletedMessages`からの欠落補完、背景での無効化・ログアウト解除の定期再試行、
Push有効時の簡易通知スケジュール調整、物理端末のDoze・強制停止は残課題。

## 検証結果（2026-09-21）

- 単体テスト133件成功、Debugビルド成功。
- Pixel_10a AVD / Android 17で端末テスト25件成功。
  既存のホーム2件は未ログインのため前提条件未充足で対象外。
- 配置されたFirebaseプロジェクトで実トークン取得と暗号化保存を確認。
  ローカル投入した暗号文エンベロープをWorkManagerで処理し、未登録宛先を表示しないことも確認。
- Windowsの単体保存テストはAndroid用FileStorageの置換で失敗したため、
  テストのみOkioStorageを明示。暗号化・復元・削除・改ざん検出の検証は維持した。
- 実FCMメッセージ配送、Mastodonからの通知、物理端末は未検証。
  公開Relayへの登録・解除は下記の追加確認で成功した。

## 公開RelayへのAndroid接続確認

`RelayLiveConnectionDeviceTest`は、Androidの実FCMトークンで公開Relayへの登録、
同じ登録の再送、不正な管理用秘密値の拒否、未知メッセージの取得、解除・墓標を確認する。
既存アカウントの購読は変更せず、検証用登録をfinallyで解除する。
1回の実行で1件の墓標を残す。トークン、管理用秘密値、配送URLをログへ出さない。
実際のFCM送信やMastodonからの通知は、このテストの確認範囲に含まれない。

通常のテストではスキップし、次の指定でのみ公開Relayへ接続する。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=io.github.ponpokoo.mastodonclient.RelayLiveConnectionDeviceTest' '-Pandroid.testInstrumentationRunnerArguments.liveRelay=true'
```

初回の接続では`/health`が`configured`でも登録・解除がHTTP 500になった。
D1の`daily_usage`テーブルが欠けていることを確認した。
このテーブルも[初期化SQL](../relay/workers/migrations/0001_initial.sql)に含まれる。
不足分の適用後、Pixel_10a AVD / Android 17から接続テスト1件が成功した（2026-09-21）。
実FCMトークンでの登録・再登録、403認証拒否、未知メッセージの404、解除と遅延PUTの410を確認。
検証用登録は解除済み。接続URLを含むDebug APKをエミュレーターへインストールした。
次はアプリの「リアルタイム通知」を有効化し、必要ならpush権限の再認証を行い、
Mastodonからの通知でFCM受信・復号・表示を確認する。

同日、利用者が再認証後に「有効」になったことと、Mastodonからの通知がNagisaに届いたことを確認した。
今回の試験では公式アプリと同程度の待ち時間だったとの報告。配信遅延を計測・保証したものではない。
実配信の初回確認は完了し、次は実機での継続試験を行う。
画面消灯・長時間放置、重複・欠落、通知タップ、WorkersのCPU時間・無料枠使用量は引き続き確認する。

## 参照

- [Firebase Android設定](https://firebase.google.com/docs/android/setup)
- [FCM受信とWorkManagerへの引き継ぎ](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)
- [登録トークンとFID](https://firebase.google.com/docs/cloud-messaging/android/get-started)
- [WorkManager](https://developer.android.com/jetpack/androidx/releases/work)
