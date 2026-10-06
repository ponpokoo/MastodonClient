# Relayの負荷測定

本番入口は`src/worker.mjs`のまま。本ディレクトリは専用Worker・空の専用D1で使う合成負荷試験用。
本番DB・実端末・実FCMの認証情報を接続しない。

## 測定方法

- `prepare.mjs`は使い捨てのP-256鍵、模擬OAuth用RSA鍵、120登録、測定用トークンを生成する。
  端末側秘密鍵と管理用トークンはサーバーのバンドルに含めない。
  生成物はGit対象外の`.wrangler/bench/`へ保存する。値やバンドル全文をログに出さない。
- `worker.mjs`は本番`createWorker`を呼び、Google通信だけを模擬応答に置換する。
  想定外の送信先も拒否する。専用origin・測定用トークン・90分の期限を全HTTP経路で確認する。
  Cron処理も同じ期限で停止する。期限後もCloudflareのCron設定自体は残るため、測定後に外す。
- 初期化APIは空DBに0001→0002を一度適用する。既存テーブルがあれば409で止まり、削除や初期化のやり直しは行わない。
  120登録の準備はHTTP新規登録10件/分を迂回する合成データ投入であり、登録性能の測定には使わない。
- `run.mjs`は100件の通常inline、300件のburst、模擬FCM停止中のinline 300件・fetch 300件を送る。
  クラウドのburstは200ms間隔を目標にし、実際の経過時間も記録する。
  バックオフを改変せず復旧後80秒待ち、実Cronによる滞留を最大60分観測する。
  `--live-only`はこの観測中に毎分3件のinline通知を追加する。
- ローカルでは`--local`を使用する。Cronを手動で連続実行するため、クラウドの復旧時間やCPU合格の根拠にはしない。
  `--smoke`は2登録・20通知の動作確認。DBはMiniflareの使い捨てDB。

Node.js 22以降で`relay/workers`を作業ディレクトリにする。依存関係は既存のdevDependenciesを使う。

```powershell
node bench/prepare.mjs
node bench/run.mjs --local --smoke
node bench/run.mjs --local
```

クラウドでは生成した`.wrangler/bench/worker.mjs`を専用Workerへ配置し、空の専用D1を`DB`で接続する。
Workers Logsの保存を無効にし、毎分Cronを設定してから以下を実行する。既存環境では再実行しない。

```powershell
node bench/run.mjs
# 別プロセスで、上の測定が動いている間だけ実行
node bench/run.mjs --live-only
```

現在のoriginは2026-10-06の検証Worker用に固定している。再測定は新しい専用環境とoriginを用意してから生成する。
`prepare`を同じ測定中に再実行するとトークン・鍵が変わり、配置版との接続が失われる。

## 数値の読み方

- `latencyMs`はクライアントから受付応答までの経過時間。FCM受付・端末表示・Workers CPUとは別。
- HTTP応答ヘッダーのD1値は応答生成時までの途中集計。`waitUntil`の完了時期に左右されるため、総費用の外挿には使わない。
- `cronSamples`は本番再送処理の文数・D1 metadataの行数・経過時間。
  測定制御の読み取り1文・開始記録1文・完了更新1文はこの文数から除外するが、実際にはCloudflareの使用量に含まれる。
  本番処理が最大46文の場合、測定側を含め49文になる。
  開始した回を先に保存し、`elapsed=-1`は進行中または未完了、`-2`は捕捉した失敗を示す。
  完了前の記録を成功回数へ含めない。D1への往復が遅い場合は正常な実行中でも一時的に`-1`になる。
  D1の`first()`を`all()`の先頭行で代用してmetadataを取得するため、CPUには測定用ラッパーの負荷も含まれる。
- CPUはCloudflare Metrics、DB全体の行数と容量はD1 Metricsで別に観測する。
  初期化・合成データ投入を含む集計とPush負荷の時間帯を区別する。
- 結果JSONは秘密値や配送URLを含めず`.wrangler/bench/result-*.json`へ保存する。
  復旧の観測プロセスはPCのスリープ・終了で止まる。クラウドCronはPCと独立して動く。
- 合成暗号文は復号可能な実通知ではない。fetch経路はFCM受付後に暗号文を保持するため、
  `pending=0`でも`messages=300`等が正常。端末による取得・復号・表示は別試験が必要。

既存測定の復旧観測だけを続ける場合は`node bench/observe.mjs`を使う。初期化や通知の投入は行わず、
元の復旧開始時刻から60分以内に収束したかを記録し、生成時の90分期限で停止する。
手動の切り分け実行があれば`--manual-sample=<bench_samples.atのミリ秒値>`を回数分渡す。
その回を自動Cronの成功回数から除外し、`result-cron-investigation.json`に分けて保存する。
元の`result-cloud.json`は上書きしない。旧プロセスはコード更新前の集計を続けるため、この追加観測を参照する。

測定後は専用WorkerのCronを外す。合成データのDB・使い捨て認証情報・ローカル生成物の削除は対象を確認して行う。
結果と未検証項目は[測定記録](../../../docs/investigations/relay-production-measurement-20261006.md)へ残す。
