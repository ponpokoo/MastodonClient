# Nagisa Relay（ローカル開発版）

Node.jsの標準機能のみで動く、共通通信契約v1のローカル通知中継実装。
現在のAndroidが有効化に使用する登録v2には未対応。v1のHTTP契約試験用として維持し、
新Androidとの登録・鍵確定の検証は[Workers版のローカル環境](workers/README.md)で行う。
登録・解除・暗号文受付・永続キュー・模擬FCM送信を検証する。
実際のFCM送信とVAPID検証は未実装。
Workers Free＋D1での実FCM送信用コードは、別実装の[Workers試験版](workers/README.md)を参照する。
Android側の受信・復号・表示と過去の検証記録は[Android接続と受信処理](../docs/push-reception.md)を参照する。
**この版は127.0.0.1での開発用。本番公開できる完成版ではない。**

2026-10-07に採用した[ハイブリッド配送](workers/hybrid.md)はWorkersの別入口に実装する。
このv1模擬実装と16件の回帰テストは旧契約の確認用に維持する。検証の入口は[Relayテスト一覧](workers/testing.md)。

## 起動

Node.js 20以降が必要。外部パッケージのインストールは不要。
2026-09-21の検証ではNode.js 20.9.0を使用した。公開運用時にはサポート中のランタイムを選定する。
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

登録・解除・Push受付・暗号文取得の形式は[共通契約](../docs/relay-protocol.md)に従う。
未登録IDのDELETEも墓標を作って204を返す。遅れて届いたPUTは410、別の管理用秘密値は403を返す。

VAPID署名・暗号文の完全性は検証しない。配送URLを知るローカルテストクライアントからの受付であり、
標準Web Pushサービス全体の実装ではない。

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
dataの形式、inline／fetchの切り替えと暗号文取得は[共通契約](../docs/relay-protocol.md#fcmエンベロープ)を参照する。
このRelayは模擬FCM送信専用であり、実FCM用アダプターは未実装。
実FCMのサイズ検証・実配信との接続は、このローカル版では行っていない。
Androidのfetch・復号処理とFCM受信Service・WorkManagerは[Android接続と受信処理](../docs/push-reception.md)を参照する。

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

## 過去の検証記録

### 2026-09-21：ローカル模擬送信

Node.jsテスト16件とlocalhostでのデモ実行の記録は[共通契約の過去の検証記録](../docs/relay-protocol.md#過去の検証記録)を参照する。
模擬FCM送信の確認であり、実FCM配信の結果ではない。今回の文書整理ではテストを再実行していない。
