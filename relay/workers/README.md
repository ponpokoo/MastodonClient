# Nagisa Relay — Workers＋D1（本運用準備版）

Mastodon Web Pushを受け、暗号文をFCM HTTP v1のdataメッセージでNagisaへ中継する。
本文の復号はAndroid側で行う。実行時の外部npm依存、Queues、KV、常駐サーバーは不要。

本運用の準備・実装変更・検証・移行は[本運用への移行手順](../../docs/relay-production.md)、
選定したWorkers＋D1の環境準備は[配置・復旧計画](production.md)、選定理由は[ADR 0009](../../docs/adr/0009-relay-cloudflare-deployment.md)を参照する。
登録v2・購読ごとの鍵検証・部分停止・再送の変更は実装済み。本番配置・Free適合性は未確認。
契約と移行判断は[ADR 0010](../../docs/adr/0010-relay-subscription-key-binding.md)を参照する。

```text
Mastodon → Worker → D1へ保存 → FCM → Nagisa
                      ↑        |
                      └ Cronで再送
```

登録・トークン更新・解除・暗号文取得は[共通通信契約](../../docs/relay-protocol.md)を維持する。
従来の[Node.jsローカル模擬Relay](../README.md)とは別の実装で、state.jsonの移行はしない。
同じWorkers originの既存登録は追加マイグレーションで移行する。URLを変更するときは購読を作り直す。

## ローカルでの確認

Node.js 22以降を使う。2026-09-21の検証では24.19.0を使用した。npmもそのNode.jsと同じインストールのものを使う。

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

配置、D1初期化、変数・Secrets、Cron、運用確認と停止の手順は[配置・運用手順](setup.md)にまとめる。
CloudflareとGoogleのWeb画面は利用者が操作する。

秘密鍵の改行は実改行と文字列`\n`の両方を受け付ける。JSON全体を秘密鍵欄へ貼らない。
`google-services.json`はAndroid用で、このサービスアカウント秘密鍵の代わりにはならない。
鍵や登録トークンはソース、APK、チャット、SQLへ貼り付けない。
設定不足のときは503。`/health`の`configured`は設定形式の確認であり、Google認証成功の証明ではない。

## 配送と保存

- POST受信時に署名、audience、有効期限、許可した公開鍵を検証する。
  標準`vapid t=..., k=...`と旧`WebPush`認証に対応する。
- 登録v2はAndroidが取得した公開鍵を購読へ紐付け、対象の鍵だけを許可する。
  鍵未確定の仮登録はPushを拒否し、24時間後に墓標化する。v1は明示した期限内だけ全体鍵リストを使う。
- D1にコミットしてから201を返す。201は受付であって、端末への到着確認ではない。
  応答後の`waitUntil`で初回送信し、処理中断時はD1の行から復旧する。
- Cronは期限切れを削除し、購読ごとの順番を考慮して最大20件を逐次再送する。
  1実行50 DB文の上限に余裕を確保して打ち切るため、無効トークン等が多い場合は20件未満になる。
- 60秒のD1リースで多重送信を抑制。Google通信は各10秒で打ち切る。
  プロセス停止などによる重複はあり得るため、Android側の重複排除は必須。
- 再送は60秒から指数バックオフ（基準上限3600秒＋最大25%ジッター）、最大8回。Retry-Afterも尊重する。
  到来時刻が分単位になるため、短いTTLの通知は再送前に失効し得る。
- 本文64 KiBまで。TTLは最大24時間。TTL 0は保存・送信せず破棄する。
  inlineのJSONが3500 bytesを超えると、管理用Bearerで暗号文を取得するfetch方式にする。
  inline成功時は削除、fetch成功時はTTLまで保持する。
- 管理用秘密値はSHA-256ハッシュのみ保存。FCMトークン、配送ID、暗号文はD1に保存する。
  Web Push秘密鍵・auth secret・Mastodonアクセストークン・復号済み本文は保存しない。
- 解除の墓標は無期限保持する。遅延PUTによる復活を拒否し、同じIDを再利用しない。
- 無効トークンはFCMの型付き`UNREGISTERED`で判断する。一般的な400/404で端末を無効化しない。
  トークン更新と競合した古い送信結果は、新しい宛先に適用しない。
- 通信失敗・429・5xxは再送する。その他の一般HTTPエラーや不正な認証応答は
  固定コード`relay_delivery_configuration_error`を報告して該当通知を破棄する。端末は無効化しない。
  修正まで`DELIVERY_ENABLED=false`で送信を止め、必要ならPush受付も停止する。
- 仮登録の期限切れ・無効FCM登録の30日経過時に識別子・トークン・鍵を消して墓標にする。

## 無料枠に対する試験上限

有効・仮・無効登録を合わせて120件、うち仮登録30件。墓標を含む総数は10000件まで。
保持通知は全体1000件・登録別100件。PushはUTC日付あたり20000要求まで（拒否した署名も消費）。
新規登録は全体10回/分、既存更新30回/分/購読、Push360回/分/購読、fetch60回/分/購読。
未知IDの解除は10回/分。既存IDの解除は通知・新規登録上限で止めない。
上限到達は429＋Retry-After。日次Push上限も既存更新・取得・解除には適用しない。
期限切れメッセージはCronで回収されるまで容量に含める。

これらはメモリ・DB使用を抑える上限であり、料金やDoS防御の保証ではない。
拒否する要求にもWorkers実行やD1の読み取りが生じる。
Google Playの限定テスターへの配布を前提とするが、Relay APIはテスター資格を検証しない。
120件は購読数であり30人を識別する上限ではない。登録濫用や一般公開時は資格確認を再検討する。

過去の利用者報告では公開WorkersのCPU時間と無料枠使用量を確認済みだが、具体的な計測値は記録していない。
ネットワーク待ち時間ではなく、
VAPID検証、OAuth用RSA署名、最大本文のBase64化、Cron処理を含むCPU使用時間を確認する。
アクセストークンは有効期限内で同一isolateにキャッシュするが、cold startでは作り直す。
20件のCronでも10ms以内は保証されない。負荷・障害復旧・D1使用量をクラウドで測定してから公開する。

アプリが出すログは固定エラーコードのみ。Workers Logsは初期設定で無効。
Web画面で作成した場合も、保存ログにリクエストURLを含めないよう確認する。
CloudflareのMetrics、D1の件数だけを返すSQL、端末の表示で動作を確認する。

## 過去の検証記録

### 2026-10-06：Cloudflare Freeでの測定開始

専用Worker＋空の専用D1、模擬FCMで120登録・1,000件の通知受付を測定した。
burstは300件を59.98秒で受け付け、受付応答p99は279.06ms。600件の滞留から実Cronで復旧を観測中。
初期CPU表示には10msを超える分位値があるため、無料本運用に合格したとは判断していない。
条件・版・未検証項目は[測定記録](../../docs/investigations/relay-production-measurement-20261006.md)、
再現手順は[bench/README](bench/README.md)を参照。

### 2026-10-06：本運用準備のローカル確認

Node.js 24.19.0で`npm ci`・`npm test`成功、34件成功・失敗なし。
登録v2、仮登録・応答再送、2サーバーの鍵隔離・鍵更新、旧契約の期限、既存データの追加移行、
部分停止・容量・リース競合・Cronの公平性と50 DB文以内、FCMのエラー分類・本文タイムアウト、
復元登録の墓標化を模擬FCM／Miniflare／workerdで確認した。
Androidは関連テスト後に単体テスト全体334件・Debugビルド成功。
クラウド配置、Free CPU・D1消費量、600件の滞留復旧、実機配信、監視通知、Time Travel復元は未実施。

以下は当時のコード・設定・確認範囲の記録。今回の文書整理ではテストを再実行していない。

### 2026-09-21：Workersローカルテスト

2026-09-21: 23件のローカルテスト成功。配布用バンドルのローカル生成、登録の競合・認証・墓標、
署名拒否、旧暗号ヘッダー、TTL、暗号文取得、容量制限、D1リース、再送、
トークン更新競合、FCMの認証・エラー分類を検証。
このローカルテストではWorkers FreeのCPU上限、クラウド上のD1、実Mastodon→FCM→端末の配信は未検証。
Androidのコード・設定URLはこの追加では変更していない。

### 2026-09-21～2026-09-23：公開環境への接続・実配信

公開Relayの登録・解除、利用者による実配信と継続試験の記録は[Android側の過去の検証記録](../../docs/push-reception.md#過去の検証記録)を参照する。
ローカルテストの結果とは区別し、公開環境の性能保証には用いない。

参照: [D1 batch](https://developers.cloudflare.com/d1/worker-api/d1-database/)、
[Cron](https://developers.cloudflare.com/workers/configuration/cron-triggers/)、
[VAPID](https://www.rfc-editor.org/rfc/rfc8292.html)、
[FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api)、
[FCMエラー](https://firebase.google.com/docs/cloud-messaging/error-codes)。
