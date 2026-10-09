# 少量の実FCMを使うハイブリッドRelayの準備

対象は管理したテストアカウント・端末での少量実配信。大量の合成負荷は[bench/hybrid-setup.md](bench/hybrid-setup.md)を使う。
2026-10-08の[小通知測定](../../docs/investigations/relay-live-fcm-measurement-20261008.md)は利用者の指示により切替済み本番を使用した。
本書は今後専用環境を作る場合の手順で、専用環境を実際に配置した記録ではない。

## Worker・D1・FCM

1. 新しいWorkerと空のD1を作り、D1を`DB`へバインドする。本番DBや合成測定DBを流用しない。
2. [setup.mdのD1作成](setup.md#1-d1を作る)に従い0001・0002を一度ずつ適用する。
3. `relay/workers`で`npm run build:hybrid`を実行し、`dist/hybrid/hybrid-worker.js`を配置する。
   合成測定用バンドルや旧方式の`dist/worker.js`と取り違えない。
4. [FCM送信用アカウント](setup.md#3-google側でfcm送信用アカウントを用意する)と
   [変数・Secrets](setup.md#4-workerの変数secretsを設定する)を設定する。
   AndroidのFirebase設定と同じprojectを使い、専用originを`PUBLIC_ORIGIN`にする。
   サービスアカウント秘密鍵はSecretへ保存し、チャット・Git・Androidへ渡さない。
5. 準備中は4つの停止フラグをfalseにする。稼働確認時に
   `RELAY_ENABLED`・`REGISTRATION_ENABLED`・`PUSH_ENABLED`・`DELIVERY_ENABLED`をtrueにする。
   登録v2を使い、旧登録の移行資格を追加しない。
6. Cronを`17 0 * * *`（日次09:17 JST）にする。Dashboardでは最後にSaveして保存状態を確認する。
   毎分配送Cronは設定しない。
7. `/health`がconfigured、`/v2/capabilities`が登録v2・hybrid・配送v2・syncRequired対応を示すことを確認する。

本文・FCMトークン・管理資格・配送endpointをログや測定原票に記録しない。
端末が受信できたことはhealth／FCM受付成功とは別に確認する。

## Androidとテスト購読

1. Firebase設定と専用Relay URLを[Androidビルド設定](../../docs/push-reception.md#ビルド設定)へ指定する。
2. sync_required対応のDebug APKを作る。既存購読の接続先を変える場合は、旧APKでPushをオフにし、
   解除完了を確認してから新APKを導入する。未解除のまま接続先だけを変更すると旧Relay登録が残る。
3. 対象アカウントでPushとOS通知を有効にし、背景で別の管理下アカウントから少量のメンションを送る。
   [購読管理](../../docs/push-settings.md)に従い、Mastodonの秘密情報をRelayへ渡さない。
4. 通信量・遅延を測る場合は[実FCM測定の再現手順](../../docs/investigations/relay-live-fcm-measurement-20261008.md#再現と確認)を使う。
   小通知だけでは大通知のAPI同期・欠落回復を検証したことにならない。
5. 終了後はPushをオフにして解除完了を確認し、専用Workerの受付・配送を停止する。
   必要な匿名結果を保存し、計測APK・試験資格・不要な専用環境を片付ける。

既存本番の直接切替・差し戻しは[本番記録](../../docs/investigations/relay-production-hybrid-cutover-20261008.md)を参照する。

## 合成大通知の実FCM確認

[LargePushLiveDeviceTest](../../app/src/androidTest/java/io/github/ponpokoo/mastodonclient/LargePushLiveDeviceTest.kt)は、APKに設定したハイブリッドRelayへ一時登録を作り、4KiBの合成本文を1件送る。実FCMでv2 `sync_required`を受信した後、受信したエンベロープを試験用のリポジトリへ渡し、実Mastodon APIから取得した既存通知1件のOS表示と重複抑止を確認する。
元のアカウント・Push購読は書き換えず、試験用のローカル登録・表示履歴とRelay登録を終了時に削除する。Relayには削除墓標が残る。

Firebase設定済みのDebug APKとandroidTest APK、ログイン済みアカウント、APIから取得できる通知1件以上、OS通知許可が必要。通知音量を0にしてから明示的に実行する。端末IDは対象に合わせる。

```powershell
adb -s emulator-5554 shell cmd media_session volume --stream 5 --set 0 --get
adb -s emulator-5554 shell am instrument -w -e class io.github.ponpokoo.mastodonclient.LargePushLiveDeviceTest -e largePushLive true io.github.ponpokoo.mastodonclient.test/androidx.test.runner.AndroidJUnitRunner
```

追加・変更したテストAPKは事前に`:app:assembleDebugAndroidTest --offline`で生成してインストールする。`largePushLive=true`を指定しなければ外部試験は実行しない。
件数・サイズ・固定ラベルだけを端末の外部cacheの`large-push-live.json`とinstrumentation結果へ保存する。本文・個別通知ID・認証情報・配送endpointは出力しない。

本文はサイズ分岐用の合成バイト列で、Web Push暗号文の生成・復号は対象外。この試験では受信後の同期を隔離した処理で実行する。通常購読のワーカーによる自動同期、Mastodonが生成する大通知、送信からの遅延・電池・長時間運用の実測結果としては扱わない。
