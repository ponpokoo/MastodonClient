# Nagisa Relay通信契約（登録v2・現行配送v1／採用方式v2）

Android、Node.jsローカル模擬Relay、Workers版Relayが使用する登録・解除・Push受付・暗号文取得・FCMエンベロープの契約を定義する。
Mastodon購読の接続順序と再開・ログアウト処理は[購読管理](push-settings.md)を参照する。
暗号文の中継と端末側の復号に責務を分ける判断は、
[ADR 0013：ハイブリッド配送と情報境界](adr/0013-adopt-hybrid-push-delivery.md)を参照する。

本番Relayは2026-10-08からハイブリッド（小通知v1 inline／大通知v2 sync_required）。
現在のAndroidソースはv1 inline／旧fetchとv2 sync_requiredに対応する。対応APKの配布は別途。
旧APKの大通知欠落を許容した[直接切替の判断](adr/0015-production-hybrid-cutover.md)と[配置記録](investigations/relay-production-hybrid-cutover-20261008.md)を参照する。
各版の契約を混同しない。テストの一覧は[Relay検証ガイド](../relay/workers/testing.md)。

VAPID検証、保存方式、配送・再送、件数上限は実装ごとに異なる。
[ローカルRelay](../relay/README.md)と[Workers版Relay](../relay/workers/README.md)の条件を混同しない。
Workersの配置は[配置・運用手順](../relay/workers/setup.md)、Android受信は[Android接続と受信処理](push-reception.md)を参照する。

## 購読ごとの鍵登録（v2）

現在のAndroidは`PUT /v2/registrations/{registrationId}`を使用する。Workersはv2対応、
ローカル模擬Relayはv1のみ。新Androidから旧Relayへの自動フォールバックは行わず、登録エラーとする。
設計判断は[ADR 0010](adr/0010-relay-subscription-key-binding.md)を参照する。

- Bearer管理用トークン・IDの形式・HTTPS・リダイレクト禁止はv1と同じ。
- 本文：`{"fcmToken":"端末トークン","serverKey":"サーバーのVAPID公開鍵","revision":1}`。
  `serverKey`の省略またはnullは仮登録。余分なフィールドは拒否する。
- 201（新規）／200（更新）：`{"endpoint":"https://relay.example/push/配送専用ID","revision":1,"state":"active"}`。
  鍵未確定なら`state`は`pending`。Androidはendpoint・revision・stateを確認する。
- 公開の`GET /v2/capabilities`は`{"registrationVersion":2,"keyBinding":true}`を返す。
  管理資格の証明ではない。Androidはv2の成功応答を要求し、旧契約へ秘密値を追加して送らない。

P-256の曲線上にある非圧縮65 bytesの公開鍵だけを受け付け、パディングなしBase64URLへ正規化する。
Pushは対象登録の鍵との一致、署名、audience、期限を検証してから保存する。
鍵の検証後に登録が変更された場合も保存時のrevision照合で409とし、古い検証結果を使用しない。
仮登録ではPushを403で拒否し、24時間で配送先・FCMトークン・鍵を消して墓標にする。
期限の延長は更新で認めない。失効後はオフ→解除完了→オンで新しいIDを作る。

revisionは正の安全なJSON整数。端末は更新前に増分を暗号化保存する。
同一revision・同一内容の再送は同じendpointを返し、異なる内容や古いrevisionは409。
鍵を確定後にnullへ戻せない。FCM更新は現在の鍵を維持する。
管理認証付きの鍵変更では新しい鍵へ即時切り替え、旧鍵の併用期間は設けない。
アプリ未起動中の鍵変更は検出できず、次の照合までは通知が欠落し得る。

既存v1の登録は同じID・endpoint・管理資格でv2へ移行できる。移行後のv1 PUTは426。
旧登録の受付・v1 PUTは運用者が明示した`LEGACY_V1_UNTIL`の期限内だけ全体鍵リストを使う。
未設定・期限切れではv1 PUTは426、旧登録へのPushは403。期限前に受付済みの暗号文はTTLまで再送し得る。
解除と暗号文取得、FCMエンベロープは引き続きv1。旧APKの解除も移行後に利用できる。
旧Relayを先に新スキーマ・新コードへ更新してからAndroidを展開する。

## 登録・FCMトークン更新

`PUT /v1/registrations/{registrationId}`

以下はローカル模擬RelayとWorkersの期限付き旧契約。現在のAndroidの有効化には上記v2を使う。

- Authorization: `Bearer {managementToken}`
- Content-Type: `application/json`
- 本文: `{"fcmToken":"端末で取得したFCMトークン"}`
- 200または201: `{"endpoint":"https://relay.example/push/配送専用ID"}`

registrationIdは端末が安全な乱数から生成する128 bit以上のID、managementTokenは
別途生成する256 bit以上の秘密値とする。いずれもパディングなしBase64URLを使用する。
クライアントの形式検証は乱数品質を保証しないため、生成処理で確保する。
アカウント購読ごとに別の組を作り、通信前に暗号化保存する。

サーバーは初回登録を原子的に作成し、管理用秘密値は検証用ハッシュで保存する。
既存IDへのPUTは同じmanagementTokenの場合だけ許可する。
同じ要求の再送は同じ配送endpointを返し、FCMトークン更新でもendpointを維持する。
別の管理用秘密値で既存登録を上書きできてはいけない。
配送専用IDはregistrationId・managementTokenと独立して生成する。

この要求にMastodonのURL・アクセストークン、Web Push秘密鍵・auth secretを含めない。
AndroidのRelay専用HTTPクライアントはMastodon認証Interceptorを共有せず、リダイレクトを追わない。
実設定は認証情報を埋め込まないHTTPS URLのみ許可する。

## 解除

`DELETE /v1/registrations/{registrationId}`。同じ管理用Authorizationを使用する。

- 204: 解除完了。ローカルRelayでは未登録IDも墓標を作って204を返す。
- 404: 存在しないため、解除済みとして扱う。
- 401／403: 認証・権限エラー。解除成功として扱わない。
- 429／5xx／通信失敗: 未完了。上位の購読管理で再試行する。

削除時には配送endpointを無効化し、保持中の配送も停止する。
IDは解除後に再利用せず、再有効化では新しい組を発行する。
サーバー側では削除済み登録の墓標を保持するなど、遅延したPUTによる復活を防ぐ。
墓標の保持と登録件数の上限は各Relay実装のREADMEを参照する。一般公開時の保持期間と管理情報の削除方針は今後確定する。

## Push受付と暗号文取得

- `POST /push/{deliveryId}`: `TTL`、`Content-Encoding`とバイナリ本文を受け付け、保存後201。
  未知・無効・解除済みendpointは410。受付は端末への配信完了を意味しない。
- `GET /v1/registrations/{id}/messages/{messageId}`: 管理用Bearerで暗号文を取得。
  他の登録・期限切れは404。何度取得しても期限までは同じ内容を返す。

受け付けるContent-Encodingは`aes128gcm`と旧形式`aesgcm`。
旧形式には`Encryption`と`Crypto-Key`が必要。Authorizationなど不要なヘッダーは配送しない。
Locationは受付IDを示すだけで、配送レシート取得APIは未実装。
本文は最大64 KiB、暗号化関連ヘッダーは各2 KiB。TTLは最大24時間に短縮する。
TTL 0は保存・送信せず破棄し、期限切れは配送せず取得も拒否する。
VAPIDの認証条件と保存・再送の挙動は各Relay実装のREADMEを参照する。

## FCMエンベロープ

dataは全値Stringの独自エンベロープ：

- 共通: `version=1`, `registrationId`, `messageId`, `transport`
- 小さい通知: `transport=inline`, `encoding`, `headers`（JSON文字列）, `body`（Base64URL）
- 大きい通知: `transport=fetch`。端末が登録済みRelayと管理用認証を使い上記GETで取得する。

inlineのJSONが3500 bytesを超えるとfetchへ切り替える。FCMの4096 bytes上限に余裕を設ける設計で、
fetch通知の送信成功後も暗号文をTTLまで保持する。実FCMの確認範囲は各文書の過去の検証記録を参照する。

GETの取得応答は、`version`、`registrationId`、`messageId`、`encoding`、`headers`、`body`を持つJSON。
各値はStringで、FCMのinline形式と同じ暗号文情報を返す。取得応答には`transport`を含めない。
Androidは要求した登録ID・メッセージIDと取得応答を照合する。
FCMへはdataメッセージだけを送り、notificationメッセージによる自動表示を使用しない。

## 採用したハイブリッド方式の配送契約

2026-10-07に[ADR 0013](adr/0013-adopt-hybrid-push-delivery.md)で採用した[配送仕様](../relay/workers/hybrid.md)。
隔離したRelay入口で実装・測定後、2026-10-08に本番へ配置した。現在のAndroidと本番ハイブリッドは配送v2も扱い、ローカル模擬Relayは配送v1。
採用決定と実配置は区別し、直接切替の条件は[ADR 0015](adr/0015-production-hybrid-cutover.md)に記録する。

登録・更新はv2だけ、解除は既存の管理認証と墓標を維持する。
ハイブリッド入口の`GET /v2/capabilities`は次を返す。

```json
{"registrationVersion":2,"keyBinding":true,"deliveryMode":"hybrid","deliveryVersion":2,"syncRequired":true}
```

deliveryVersionはその入口が使う最新配送版。小通知には既存v1 inlineも使う。
FCM dataはすべてStringで、JSON全体3,500 bytes以内の小通知は現在と同じv1 inline形式。
超過時は次の形式で、暗号文・暗号ヘッダー・本文取得先を含めない。

```json
{"version":"2","registrationId":"登録ID","messageId":"配送識別子","transport":"sync_required"}
```

registrationIdとmessageIdの形式はv1と同じ。messageIdはRelay内の配送識別子であり、
Mastodon通知IDやsince_idとして使わない。残りTTLはFCMのandroid.ttlで伝える。
Androidが対応する場合は現在のセッション・購読を照合してNotifications APIを差分取得する。
現在のAndroidの同期・初期化・回復は[受信仕様](push-reception.md#ハイブリッドの差分同期と欠落回復)に従う。
対応前の旧APKはv2 sync_requiredを拒否する。今回の本番切替は[ADR 0015](adr/0015-production-hybrid-cutover.md)に従い、その大通知欠落を許容して実施した。

正のTTLのPushはFCM受付成功後に201。本文を保存せず、本文GETは404、永続retryはない。
一時的な送信失敗は503＋Retry-After、恒久的な送信設定エラーは502で返す。
TTL 0は保存・送信せず破棄する。応答喪失時の重複・欠落を、Relayで回復する保証はない。
上流再送へ依存した保証を設けず、端末の同期補完・通知ID重複排除を別途実装する。

## 過去の検証記録

以下は当時のコード・設定・確認範囲の記録。今回の文書整理ではテストを再実行していない。公開Relayへの接続・実配信は[Android側の過去の検証記録](push-reception.md#過去の検証記録)を参照する。

### 2026-09-21：登録・購読契約とローカルRelay

2026-09-21: PushSubscriptionProtocolTest 6件、RelayRegistrationProtocolTest 4件成功。
Debug APKビルド成功。外部サービスへの通信、Relayサーバー実行、実機通知は未実施。

同日、鍵生成・暗号化保存・購読状態管理の13件を追加し、関連23件成功。
単体テスト全体102件とDebugビルドも成功。Android Keystoreの実機検証は未実施。

さらにローカルRelayのNode.jsテスト16件成功。localhostでデモを実行し、
登録・受付・模擬送信の一時失敗からの再送成功・解除を確認した。
AndroidコードはこのRelay追加では変更せず、Gradleは再実行していない。

検証対象はフォームエンコード、旧応答・未知フィールド、数値表現IDの文字列保持、
インスタンスとトークンの分離、404と認証・サーバーエラーの区別、解除再送、
FCMトークン更新要求、不正なID・HTTP設定の拒否。
