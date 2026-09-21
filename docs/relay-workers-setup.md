# Workers Free＋D1へのNagisa Relay配置手順

CloudflareとGoogleのWeb画面は利用者が操作する。
コード・SQL・ローカルテストは[Workers版Relay](../relay/workers/README.md)に用意した。
Queues、KV、独自ドメインの契約は不要。まず少数アカウントで試す。

## 1. D1を作る

1. Cloudflare DashboardでD1を開き、`nagisa-relay`というデータベースを作る。
2. 作成したD1のConsoleを開く。
3. [0001_initial.sql](../relay/workers/migrations/0001_initial.sql)のSQLを実行する。
   一括実行できない画面ではセミコロンごとに実行する。
4. `registrations`、`messages`、`daily_usage`テーブルが作られたことを確認する。

```sql
SELECT name FROM sqlite_master WHERE type='table';
```

`daily_usage`も必須。欠けていると`/health`は成功しても、登録・解除でHTTP 500になる。
不足があれば初期化SQLを最後まで適用する（すべて`IF NOT EXISTS`付き）。

このSQLには初期データや秘密値は含まれない。
既存のローカルRelayのstate.jsonやAndroidの鍵をD1へ取り込まない。

## 2. Workerを作ってコードを配置する

1. Workers & PagesでWorkerを作成し、名前を`nagisa-relay`にする。
2. 発行された`https://…workers.dev`のURLを控える。
3. コード編集画面で、ローカルの`relay/workers/dist/worker.js`の内容を貼り付ける。
   元の`src/worker.mjs`には相対importがあるため、単独で貼るのは生成済みの`dist/worker.js`。
4. 保存・デプロイする。この時点では設定がないためRelayは停止状態になる。
5. WorkerのBindingsからD1を追加する。
   **変数名を`DB`**にし、手順1の`nagisa-relay`を選択する。
6. Compatibility dateを`2026-09-21`にする。Node.js互換フラグは不要。

ローカルで作り直すときは`relay/workers`で`npm ci`、`npm run build`。
ビルドはローカル生成のみで、Cloudflareへは公開しない。
Webで設定する場合、`wrangler.jsonc`の仮DB IDを変更する必要はない。

## 3. Google側でFCM送信用アカウントを用意する

1. Google Cloud Consoleで、Androidへ配置したFirebase設定と同じプロジェクトを選ぶ。
2. APIとサービスで「Firebase Cloud Messaging API」が有効か確認し、無効なら有効にする。
3. IAMと管理 → サービスアカウントで`nagisa-relay`を作る。
4. 対象プロジェクトに対して`Firebase Cloud Messaging API Admin`
   （`roles/firebasecloudmessaging.admin`）を付与する。
5. このサービスアカウントの「鍵」からJSON鍵を作成し、安全な場所へ保存する。
   Workersでは今回この鍵を使ってGoogleの短期アクセストークンを取得する。

鍵JSONをリポジトリやAndroidプロジェクト内へ置かず、チャットに貼らない。
必要なのはJSON内の`client_email`と`private_key`。`project_id`も対象を確認する。
Firebaseの`google-services.json`とサービスアカウント鍵JSONは別物。
鍵発行が組織ポリシーで禁止されている場合は、ポリシーを回避せず別の認証方式を検討する。

## 4. Workerの変数・Secretsを設定する

WorkerのSettings → Variables and Secretsで以下を設定する。

| 名前 | 種類 | 入力する値 |
| --- | --- | --- |
| `RELAY_ENABLED` | Text | 最初は`false` |
| `PUBLIC_ORIGIN` | Text | 手順2のHTTPS URL。`/push`などを付けない |
| `FCM_PROJECT_ID` | Text | FirebaseのプロジェクトID（プロジェクト番号ではない） |
| `VAPID_PUBLIC_KEYS` | Text | 後述のJSON配列 |
| `FCM_CLIENT_EMAIL` | Secret | サービスアカウントJSONの`client_email` |
| `FCM_PRIVATE_KEY` | Secret | サービスアカウントJSONの`private_key` |

秘密鍵は`-----BEGIN PRIVATE KEY-----`から`-----END PRIVATE KEY-----`まで。
JSONの外側の引用符は含めない。実改行と文字列`\n`のどちらも使用できる。
保存後に秘密値を表示・ログ出力して確認しない。

`VAPID_PUBLIC_KEYS`には、試すMastodonサーバーの公開鍵を指定する。
Mastodon 4.3以降では、ブラウザーで**利用するサーバー**の`/api/v2/instance`を開き、
`configuration.vapid.public_key`を確認できる。例のホストへ固定しない。

入力形式（実際の公開鍵に置き換える）：

```json
["そのサーバーのVAPID公開鍵"]
```

複数のサーバーを試す場合は配列へ追加する。最大20鍵。
末尾の`=`がある公開鍵も受け付ける。
古いサーバー等でこの項目がない場合、Push購読API応答の`server_key`と同じ鍵を使う。
それも取得できない場合は、そのサーバーの対応確認が必要。検証を無効にして回避しない。
この鍵はMastodonサーバーの署名用公開鍵であり、Androidが生成した暗号化用公開鍵ではない。

## 5. Cronを追加して試験を開始する

1. WorkerのSettings → Trigger EventsでCron Triggerを追加し、`* * * * *`（毎分）を指定する。
   反映には時間がかかる場合がある。
2. ObservabilityでWorkers Logsの保存を無効にする。
   コードは固定エラーコードだけを出すが、プラットフォームの自動ログは配送URLを含む可能性がある。
3. `RELAY_ENABLED`を`true`へ変更し、設定をデプロイする。
4. ブラウザーで`https://…workers.dev/health`を開く。
   `{"status":"configured","mode":"workers-d1"}`なら設定形式を読み込めている。
   `relay_not_configured`なら変数名・公開鍵配列・プロジェクトID・秘密鍵の形式を確認する。

`configured`だけではDB接続、Googleの権限、実配信の成功までは確認できない。

ここまで終わったら**Workerの公開URLだけ**を共有する。
次にローカルの`local.properties`へ`nagisa.relayUrl=https://…workers.dev`を設定し、
Debug APKをビルドしてNagisa側の通知を有効化する。
Mastodonから通知を発生させ、背景のNagisaに届くことを確認する。
Firebase Consoleの「通知を作成」で送るnotificationメッセージは、この経路の検証には使わない。

2026-09-21の接続確認: `nagisa-relay.ponta3921.workers.dev`をAndroidのローカル設定へ反映。
初回に不足していた`daily_usage`を追加した後、エミュレーターからの登録・再登録・認証拒否・解除を確認した。
実FCM送信とMastodonからの通知表示は、この登録テストでは確認していない。

## 確認する数字と停止方法

- Workers MetricsでHTTPとCronのCPU時間、エラーを確認する。
  通常送信、初回／認証更新後、大きな暗号文、再送のそれぞれを確認する。
  ローカルの所要時間はWorkers FreeのCPU 10ms以内を証明しない。
- D1の読み取り・書き込み件数とWorkersの日次リクエスト数を確認する。
  無料枠は他のWorker／DBの利用と合わせて確認する。
- D1 Consoleでは次のSQLで件数だけを確認できる。
  期限はUnixミリ秒。`delivered=1`はFCM受付済みのfetch用暗号文で、端末で表示済みという意味ではない。

```sql
SELECT deleted, invalid, COUNT(*) AS count FROM registrations GROUP BY deleted, invalid;
SELECT delivered, COUNT(*) AS count, MIN(next_attempt) AS earliest_retry FROM messages GROUP BY delivered;
SELECT day, count FROM daily_usage ORDER BY day DESC;
```

試験を停止するときは`RELAY_ENABLED=false`へ変更する。
新規受付とCron送信は止まるが、すでにFCMが受け付けた通知は取り消せない。
停止中は削除処理も止まるので、D1の保存データは残る。
試験を終了してデータを消す場合は先にAndroidで通知を無効化して解除を完了する。
同じURLのDBだけを空にすると墓標が失われるため、DBの初期化を通常の復旧手段にしない。

参照: [Cloudflare DashboardでのWorker作成](https://developers.cloudflare.com/workers/get-started/dashboard/)、
[D1](https://developers.cloudflare.com/d1/get-started/)、
[Secrets](https://developers.cloudflare.com/workers/configuration/secrets/)、
[Cron](https://developers.cloudflare.com/workers/configuration/cron-triggers/)、
[Mastodon Instance](https://docs.joinmastodon.org/entities/Instance/)、
[FCM認証](https://firebase.google.com/docs/cloud-messaging/send/v1-api)、
[Workers Metrics](https://developers.cloudflare.com/workers/observability/metrics-and-analytics/)。
