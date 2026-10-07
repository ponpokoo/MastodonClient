# Relayの負荷測定

本番入口は`src/worker.mjs`のまま。本ディレクトリは専用Worker・空の専用D1で使う合成負荷試験用。
本番DB・実端末・実FCMの認証情報を接続しない。
ハイブリッド方式の測定環境を用意するときは[専用環境の準備](hybrid-setup.md)を参照する。

ハイブリッドは2026-10-07に方式採用済み。配送・未完了作業は[採用仕様](../hybrid.md)、
自動テストと測定記録の全体は[testing.md](../testing.md)を参照する。本書は合成測定の再現手順。

公開後100人・1人2アカウント／1端末を想定した次回測定は[再測定計画](../../../docs/relay-100-user-remeasurement-plan.md)を参照する。
現行の固定120登録・90分期限・毎分3件の追加流入では200購読・2時間停止・24時間保持を測れない。
ツールと容量・再送の準備を終えてから、新しい専用環境で実行する。以下の既存コマンドが100人条件に対応済みとは扱わない。

## 100人・1端末の測定ツール

`100-prepare.mjs`・`100-worker.mjs`・`100-run.mjs`は200購読向けの別入口。
本番の既定上限は変更せず、信頼された測定入口から登録300・仮50・保持8,000件を指定する。
各購読に別のP-256鍵を生成し、模擬Google以外への通信を拒否する。
生成物・秘密値はGit対象外の`.wrangler/bench-100-20261007/`へ保存する。
測定用Worker名は`nagisa-relay-bench-*`に限定し、HTTPのトークンと期限を確認する。
生成時の期限は30時間（`--duration-hours=1..48`で指定可）。測定途中に再生成しない。

```powershell
node bench/100-prepare.mjs --origin=https://専用Worker名.サブドメイン.workers.dev
node bench/100-run.mjs --local --smoke
# worker.jsを専用Workerに配置、空D1をDBに接続、Logs無効、毎分Cronを保存する
node bench/100-run.mjs --task=init
node bench/100-run.mjs --task=smoke
node bench/100-run.mjs --task=normal
node bench/100-run.mjs --task=fetch --count=1000 --pace=600
node bench/100-run.mjs --task=stats
```

- `normal`はサイズ分布どおり1,000件を600ms間隔で送り、次に500件を120ms間隔で送る。
  通常分布の処理コストを短時間で測る試験であり、実時間の日次流量・24時間保持試験ではない。
  最大16要求を同時実行し、実際の経過時間・受付結果を記録する。
- `fetch`は全件4KiBを送り、認証付き取得を1回行う。取得後も保持される。
  1〜6,500件を指定できるが、開始前にアカウント全体の使用量を確認する。
- `outage`は2時間の通常500件と集中500件を模擬FCM停止中に投入し、
  復旧後250件/時を継続する。自動Cronが確実に起動していることと使用量を確認してから実行する。
  `observe`は追加投入や手動Cronなしで最大60分観測する。
- `stops`は合成環境の停止フラグと既存更新の応答を記録する。停止・解除・復元全体の合格を示すものではない。
- HTTPとCronの本番処理のD1 metadataを、背景処理の完了後に集計する。
  制御読み取り・測定記録・集計の追加SQLは日次費用の外挿から除外するが、アカウント使用量には含まれる。
  `within60`はHTTPで開始したFCM受付の件数。Cron経由の復旧時間や実端末表示とは区別する。
  CPUは測定ラッパーも含むCloudflare Metricsを確認し、本番相当のCPU合格に直接置き換えない。
- 結果はタスクと開始時刻ごとの匿名JSONへ保存する。`failed`・`incomplete`を合格に数えない。
  期限後もCronの設定自体は残るため、試験終了時に削除して保存する。
  `close`は専用環境の受付・送信フラグをfalseにして匿名集計を残す。Cron設定はダッシュボードで別途外す。
  2026-10-07の[初回資源評価](../../../docs/investigations/relay-100-user-measurement-20261007.md)は公開条件未達。
  通常・集中・全fetchの[匿名集計JSON](results/20261007-100.json)を保存した。

## ハイブリッド方式の測定

同じ100人用ツールに`--mode=hybrid`を指定する。既定のqueued方式とは生成先・originを分ける。
秘密値とバンドルは`.wrangler/bench-hybrid-20261007/`へ保存し、既存fixtureがある場合は再生成を拒否する。
`worker.js`だけを指定した新しいWorkerへ配置する。D1は空の専用DB、変数名は`DB`。

```powershell
node bench/100-prepare.mjs --mode=hybrid --origin=https://専用Worker名.サブドメイン.workers.dev
node bench/100-run.mjs --mode=hybrid --local --smoke
node bench/100-run.mjs --mode=hybrid --local --task=outage
# worker.jsを専用Workerに配置、空D1をDBへ接続、Logs無効
node bench/100-run.mjs --mode=hybrid --task=init
node bench/100-run.mjs --mode=hybrid --smoke
node bench/100-run.mjs --mode=hybrid --task=normal
node bench/100-run.mjs --mode=hybrid --task=inline --count=1000 --pace=120
node bench/100-run.mjs --mode=hybrid --task=fetch --count=1000 --pace=120
node bench/100-run.mjs --mode=hybrid --task=outage
node bench/100-run.mjs --mode=hybrid --task=maintenance
node bench/100-run.mjs --mode=hybrid --task=stops
node bench/100-run.mjs --mode=hybrid --task=close
node bench/100-export.mjs --mode=hybrid
```

- `fetch`というタスク名は従来との対比のため残す。hybridでは全4KiBの`sync_required`で、本文GETを実行しない。
- `inline`は全1KiB。実端末62件のサイズ傾向を模擬する別条件で、全利用者の分布の証明には使わない。
- `outage`は模擬FCM 503を10件、その後の新しい通知の成功を10件確認する。
  本文・ジョブを残さないため、障害中の通知の自動再送・2時間滞留解消は測らない。端末補完は別工程。
- `maintenance`はscheduledの清掃本体を1回呼ぶ。日次Cronの実時刻起動の証明とは区別する。
  短時間試験ではCronなしでも実行できる。長時間試験は初期化後に日次Cronを追加し、終了時に外す。
- ビルド時にdirect sendの1箇所へ要求単位の観測を加える。FCM送信器の共有OAuthキャッシュを維持し、
  同時実行でも別要求の配送件数を混ぜない。本番ソースに観測フックは追加しない。
  模擬OAuthは必ず成功し、期限内は1回の模擬FCMを数える。`inline_count`／`sync_count`は送信試行の件数。
- `measurementComplete`と`hybridChecks`で、記録完了・本文0・成功要求とFCM受付の対応を確認する。
  503は失敗として保存する。`within60`と応答時間は模擬FCM受付までで、実FCMやAndroid表示の値ではない。
- hybridの測定記録はHTTP応答前に保存し、同じ集計値を最大3回まで上書き再試行する。
  測定用の保存待ちがHTTP応答時間に加わる。本番処理のSQL4文には含めない。
  既存fixtureのまま測定コードだけ直す場合は`100-build.mjs --mode=hybrid`で再ビルドし、
  manifestのハッシュを確認して再配置する。資格を再生成しない。各新結果にバンドルのハッシュを保存する。
- `100-export.mjs`は匿名結果だけを`results/20261007-hybrid.json`へ出力する。
  同じタスクの再試行は最新結果と全試行の両方を残し、未完了記録があった試行を消さない。
  清掃の実行有無と本番処理のD1行数も残す。初期結果のハッシュ補完は推定と明記する。

2026-10-07の[ハイブリッド資源評価](../../../docs/investigations/relay-hybrid-100-user-measurement-20261007.md)と
[匿名集計JSON](results/20261007-hybrid.json)を保存した。受付・配送は停止済み、Cronなし。
通常条件の内部エラー1件、CPU上位値と実FCM、Android同期、24時間運用は後続確認とする。

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
