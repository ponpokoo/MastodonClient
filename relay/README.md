# Nagisa Relay（ローカル開発版）

Node.jsの標準機能のみで動く、Nagisa 2.0.0向けの通知中継実装。
登録・解除・暗号文受付・永続キュー・模擬FCM送信を検証する。
実際のFCM送信とVAPID検証は未実装。
Workers Free＋D1での実FCM送信用コードは、別実装の[Workers試験版](workers/README.md)を参照する。
Android側の[受信・復号・表示](../docs/push-reception.md)は固定暗号文とエミュレーターで検証済み。
**この版は127.0.0.1での開発用。本番公開できる完成版ではない。**

## 起動

Node.js 20以降が必要。外部パッケージのインストールは不要。
このリポジトリではNode.js 20.9.0で検証した。公開運用時にはサポート中のランタイムを選定する。
PowerShellでリポジトリのルートから実行する。

```powershell
Set-Location relay
node src/server.mjs
```

待受は `http://127.0.0.1:8787`。別のターミナルで同じrelayフォルダーに移動し、次を実行する。

```powershell
node demo.mjs
```

デモは登録 → 合成バイト列のPush受付 → 模擬送信 → 解除を行い、件数だけを表示する。
合成バイト列は正しいWeb Push暗号文ではないので、復号の検証にはならない。
健康状態は `/health` で確認できる。秘密値、本文、配送先一覧を返す管理APIはない。
サーバーはCtrl+Cで終了する。

Androidの実クライアントはHTTPSを要求するため、このHTTPデモへそのまま接続できない。
MastodonからもPCのlocalhostには届かない。ここではHTTPテストクライアントと模擬送信で検証する。

## 設定

| 環境変数 | 初期値・意味 |
| --- | --- |
| `PORT` | `8787`。待受IPは常に127.0.0.1 |
| `RELAY_DATA_DIR` | 起動ディレクトリ下の`data` |
| `MOCK_FCM_MODE` | `success` / `fail-once` / `transient` / `invalid` |
| `RELAY_PUBLIC_ORIGIN` | 任意のHTTPS origin。返すendpointの組み立て用。TLSや外部公開を有効化する設定ではない |

例えば、一度失敗してから再送に成功する挙動を確認する場合：

```powershell
$env:MOCK_FCM_MODE = 'fail-once'
node src/server.mjs
```

`fail-once`は起動後の最初の送信だけ失敗する。デモの件数は起動中のプロセス全体の累積。
設定を戻すには `Remove-Item Env:MOCK_FCM_MODE` を実行する。

## HTTP契約

登録APIは [共通契約](../docs/relay-protocol.md) に従う。

- `PUT /v1/registrations/{id}`: 管理用Bearerと`{"fcmToken":"..."}`で登録・宛先更新。
  初回201、同一登録200。endpointは更新しても変えない。
- `DELETE /v1/registrations/{id}`: 管理用Bearerで解除。未登録も墓標を作って204。
  遅れて届いたPUTは410。別の秘密値は403。
- `POST /push/{deliveryId}`: `TTL`、`Content-Encoding`とバイナリ本文を受け付け、保存後201。
  未知・無効・解除済みendpointは410。受付は端末への配信完了を意味しない。
- `GET /v1/registrations/{id}/messages/{messageId}`: 管理用Bearerで暗号文を取得。
  他の登録・期限切れは404。何度取得しても期限までは同じ内容を返す。

受け付けるContent-Encodingは`aes128gcm`と旧形式`aesgcm`。
旧形式には`Encryption`と`Crypto-Key`が必要。Authorizationなど不要なヘッダーは配送しない。
VAPID署名・暗号文の完全性は検証しない。配送URLを知るローカルテストクライアントからの受付であり、
標準Web Pushサービス全体の実装ではない。Locationは受付IDを示すだけで、配送レシート取得APIは未実装。

## 配送・制限

- 本文最大64 KiB、暗号化関連ヘッダーは各2 KiB。TTLは最大24時間に短縮。
- TTL 0は保存せず破棄する。期限切れは配送せず、取得も拒否する。
- 登録は墓標込み1000件、保持メッセージ全体1000件・登録別100件を上限とする。
- HTTPリクエストはプロセス全体で毎分300件。超過は429とRetry-Afterを返す。
- 保存キューは250msごとに確認。失敗時は1秒から指数的に間隔を増やし最大5分、最大8回まで試行する。
- 無効FCMトークンは、そのトークンを使う登録と待機メッセージを停止する。
  同じ登録IDへの新しいトークン登録で再び有効にできる。
- 送信中にトークンが更新された場合、古い応答で新しい宛先を無効化しない。
- 送信成功後の保存前に停止すると重複配送し得る。受信側の重複排除が必要。
  解除前にすでに送信開始した通知は取り消せないので、端末側でも解除状態を確認する。

模擬送信先は `send(token, data, {priority, ttlSeconds})` の境界を持つ。
実FCM用アダプターは今後追加する。dataは全値Stringの独自エンベロープ：

- 共通: `version=1`, `registrationId`, `messageId`, `transport`
- 小さい通知: `transport=inline`, `encoding`, `headers`（JSON文字列）, `body`（Base64URL）
- 大きい通知: `transport=fetch`。端末が登録済みRelayと管理用認証を使い上記GETで取得する。

inlineのJSONが3500 bytesを超えるとfetchへ切り替える。FCMの4096 bytes上限に余裕を設ける設計で、
実FCMのサイズ検証は未実施。fetch通知の送信成功後も暗号文をTTLまで保持する。
Androidのfetch・復号処理と[FCM受信Service・WorkManager](../docs/firebase-android.md)は実装済み。
このRelayは模擬FCM送信のため、実配信との接続はまだ行っていない。

## 保存と運用上の境界

`data/state.json`に登録と暗号文を保存する。管理用秘密値はSHA-256ハッシュのみ保存するが、
FCMトークンと配送IDは保存されるため、データディレクトリは開発者だけが読める場所を使用する。
Web Push秘密鍵やMastodonアクセストークンは受け取らない。

書き込みは一時ファイルへのfsync後にrenameし、成功後だけメモリ状態を更新する。
壊れたデータは起動エラーとし、新規データで上書きしない。単一プロセス専用で、
二重起動を`process.lock`により拒否する。強制終了後は該当プロセスが停止済みか確認してから、
指定したデータディレクトリの`process.lock`だけを手動で削除する。`state.json`は保持する。
電源断時の完全な耐久性・複数ホストでの運用は保証しない。

本番化ではVAPID・登録の濫用対策、HTTPS、実FCM認証、トランザクション対応DB、
複数ワーカーの排他、送信タイムアウト、監視、墓標の保持・削除方針を追加・確認する。
ローカル版の墓標は無期限保持なので、開発を繰り返して上限へ達した場合は新しいデータディレクトリを指定する。

## テスト

```powershell
npm test
```

登録の冪等性、再起動、応答競合、認証、解除と遅延登録、TTL、サイズ制限、
再試行、無効トークン、暗号文取得、実ループバックHTTPを検証する。Androidビルドは不要。

参照: [Web PushのTTL](https://www.rfc-editor.org/rfc/rfc8030.html#section-5.2)、
[FCMメッセージ仕様](https://firebase.google.com/docs/cloud-messaging/customize-messages/set-message-type)。
