# Nagisa Relay — Workers Free＋D1 試験版

Mastodon Web Pushを受け、暗号文をFCM HTTP v1のdataメッセージでNagisaへ中継する。
本文の復号はAndroid側で行う。実行時の外部npm依存、Queues、KV、常駐サーバーは不要。

```text
Mastodon → Worker → D1へ保存 → FCM → Nagisa
                      ↑        |
                      └ Cronで再送
```

登録・トークン更新・解除・暗号文取得は[既存のAndroid契約](../../docs/relay-protocol.md)を維持する。
従来の[Node.jsローカル模擬Relay](../README.md)とは別の実装で、state.jsonの移行はしない。
公開環境では新しいRelay URLで購読を作り直す。

## ローカルでの確認

Node.js 22以降（今回は24.19.0）を使う。npmもそのNode.jsと同じインストールのものを使う。

```powershell
Set-Location relay/workers
npm ci
npm test
npm run db:local
npm run dev
```

`npm test`は配布バンドルを作り、Node.js＋MiniflareのD1、workerdで検証する。
Googleへの送信はすべて模擬応答に置き換える。Cloudflareへのログイン・公開は不要。
Wrangler 4.135.0と、それが使用するMiniflare 5.20260918.0-alphaをlockfileで固定した。
開発ツールの版であり、デプロイ先でnpmパッケージを動かす構成ではない。

`dev`は初期状態では停止設定。`http://localhost:8787/health`が`disabled`を返す。
実際の秘密値を`.dev.vars`へ置く場合は`.dev.vars.example`を参照する。Git対象外。
`wrangler dev`のリクエストログには配送URLが出ることがあるため、ログをそのまま共有しない。
ローカルCronの呼び出しは`http://localhost:8787/cdn-cgi/handler/scheduled`。
ローカル起動だけではMastodonやAndroidからの実配信は試せない。

`npm run build`は**dry-runのみ**で、Webエディターへ貼る`dist/worker.js`を生成する。
`dist`は生成物としてGit対象外。ソースを修正した後は再生成する。

## Web画面での設定

操作は利用者が行う。詳しい順番は[Workers配置手順](../../docs/relay-workers-setup.md)。

| 項目 | 種類 | 値 |
| --- | --- | --- |
| `DB` | D1 binding | `0001_initial.sql`を適用したDB |
| `RELAY_ENABLED` | Text | 準備中は`false`、最後に`true` |
| `PUBLIC_ORIGIN` | Text | `https://nagisa-relay.自分のサブドメイン.workers.dev`（パスなし） |
| `VAPID_PUBLIC_KEYS` | Text | 試すMastodonサーバーの公開鍵のJSON配列 |
| `FCM_PROJECT_ID` | Text | Androidと同じFirebaseプロジェクトID |
| `FCM_CLIENT_EMAIL` | Secret | FCM送信用サービスアカウントの`client_email` |
| `FCM_PRIVATE_KEY` | Secret | 同アカウントの`private_key`（PEM） |
| Cron Trigger | 毎分 | `* * * * *` |

秘密鍵の改行は実改行と文字列`\n`の両方を受け付ける。JSON全体を秘密鍵欄へ貼らない。
`google-services.json`はAndroid用で、このサービスアカウント秘密鍵の代わりにはならない。
鍵や登録トークンはソース、APK、チャット、SQLへ貼り付けない。
設定不足のときは503。`/health`の`configured`は設定形式の確認であり、Google認証成功の証明ではない。

## 配送と保存

- POST受信時に署名、audience、有効期限、許可した公開鍵を検証する。
  標準`vapid t=..., k=...`と旧`WebPush`認証に対応する。
- 試験版は運用者が設定したサーバー鍵のみを許可する（最大20鍵）。
  個々の登録とサーバー鍵の紐付けをAndroidから受け取るAPIはまだない。
  許可サーバーのいずれかが署名できれば配送URLへ送信できる設計であり、
  一般公開用の購読単位のVAPID鍵制限を実装したものではない。
- D1にコミットしてから201を返す。201は受付であって、端末への到着確認ではない。
  応答後の`waitUntil`で初回送信し、処理中断時はD1の行から復旧する。
- Cronは期限切れを削除し、送信可能なメッセージを**毎回最大1件**再送する。
  初回送信が通常経路で、Cronは少量の失敗を補う試験用。大量滞留には向かない。
- 30秒のD1リースで多重送信を抑制。Google通信は各7秒で打ち切る。
  プロセス停止などによる重複はあり得るため、Android側の重複排除は必須。
- 再送は60秒から指数バックオフ、最大8回。FCMのRetry-Afterも尊重する。
  到来時刻が分単位になるため、短いTTLの通知は再送前に失効し得る。
- 本文64 KiBまで。TTLは最大24時間。TTL 0は保存・送信せず破棄する。
  inlineのJSONが3500 bytesを超えると、管理用Bearerで暗号文を取得するfetch方式にする。
  inline成功時は削除、fetch成功時はTTLまで保持する。
- 管理用秘密値はSHA-256ハッシュのみ保存。FCMトークン、配送ID、暗号文はD1に保存する。
  Web Push秘密鍵・auth secret・Mastodonアクセストークン・復号済み本文は保存しない。
- 解除の墓標は無期限保持する。遅延PUTによる復活を拒否し、同じIDを再利用しない。
- 無効トークンはFCMの型付き`UNREGISTERED`で判断する。一般的な400/404で端末を無効化しない。
  トークン更新と競合した古い送信結果は、新しい宛先に適用しない。

## 無料枠に対する試験上限

登録は墓標込み100件、保持メッセージは全体200件・登録別20件。
正常形式の管理要求／署名検証済みPushは全体でUTC日付あたり2000要求まで。
上限到達時は429を返す。日次上限は解除・取得も含むので、試験中は余裕を持って使う。
期限切れメッセージはCronで回収されるまで容量に含める。

これらはメモリ・DB使用を抑える上限であり、料金やDoS防御の保証ではない。
拒否する要求にもWorkers実行やD1の読み取りが生じる。
一般向け公開前に登録の濫用防止、購読ごとの鍵制限、管理・削除方針、負荷を再検討する。

CPU 10msはまだ公開環境で未計測。ネットワーク待ち時間ではなく、
VAPID検証、OAuth用RSA署名、最大本文のBase64化、Cron処理を含むCPU使用時間を確認する。
アクセストークンは有効期限内で同一isolateにキャッシュするが、cold startでは作り直す。
Cronを1件にしても10ms以内が保証されるわけではない。

アプリが出すログは固定エラーコードのみ。Workers Logsは初期設定で無効。
Web画面で作成した場合も、保存ログにリクエストURLを含めないよう確認する。
CloudflareのMetrics、D1の件数だけを返すSQL、端末の表示で動作を確認する。

## 検証範囲

2026-09-21: 23件のローカルテスト成功。配布用バンドルのローカル生成、登録の競合・認証・墓標、
署名拒否、旧暗号ヘッダー、TTL、暗号文取得、容量制限、D1リース、再送、
トークン更新競合、FCMの認証・エラー分類を検証。
Workers FreeのCPU上限、クラウド上のD1、実Mastodon→FCM→端末の配信は未検証。
Androidのコード・設定URLはこの追加では変更していない。

参照: [D1 batch](https://developers.cloudflare.com/d1/worker-api/d1-database/)、
[Cron](https://developers.cloudflare.com/workers/configuration/cron-triggers/)、
[VAPID](https://www.rfc-editor.org/rfc/rfc8292.html)、
[FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api)、
[FCMエラー](https://firebase.google.com/docs/cloud-messaging/error-codes)。
