# 100人向けハイブリッドRelay測定環境の準備

- 記録日：2026-10-07
- 対象：100人×2アカウント×1端末＝200購読の合成負荷測定。
- 状態：専用環境で200購読の短時間負荷・清掃・停止確認を完了。受付・配送は停止済み。公開判定は保留。
- 実行記録：[100人条件の測定](../../../docs/investigations/relay-hybrid-100-user-measurement-20261007.md)。
- 仕様：[ハイブリッド採用仕様](../hybrid.md)、[再測定計画](../../../docs/relay-100-user-remeasurement-plan.md)。

測定後にハイブリッド方式を採用した。決定と検証の入口は[ADR 0013](../../../docs/adr/0013-adopt-hybrid-push-delivery.md)・[testing.md](../testing.md)。
本書は専用の合成測定環境を作る手順で、本番切替手順ではない。

## 今用意するもの

Cloudflare Dashboardで次を新規作成する。

| 項目 | 値 |
| --- | --- |
| Worker名 | `nagisa-relay-bench-20261007-hybrid` |
| D1名 | `nagisa-relay-hybrid-20261007` |
| D1バインドの変数名 | `DB` |
| Compatibility date | `2026-09-21` |
| Workers Logs | 無効 |
| 準備段階のCron | なし |
| 変数・Secrets | 追加不要。模擬FCM・試験資格・制御設定は後続の測定バンドルで用意する |

同じCloudflareアカウント内でも新しいWorkerと空D1を組み合わせる。
本番`nagisa-relay`や前回の測定D1は選択しない。

## 1. 空のD1を作成する

1. Cloudflare Dashboardの「D1 SQL database」を開く。
2. 「Create Database」で`nagisa-relay-hybrid-20261007`を作る。
3. データベース名とDatabase IDを控える。
4. 空の状態で準備完了。SQLやサンプルデータの投入は不要。

測定用の初期化APIで0001・0002と測定テーブルを一度だけ作る予定。
既存測定入口はregistrationsが存在すると初期化を409で拒否するため、
通常の[Relay配置手順](../setup.md)にある手動SQL適用はここでは行わない。
この初期化は大量通知の投入とは別の準備作業。

## 2. Workerを作成する

1. 「Workers & Pages」→「Create application」→「Start with Hello World!」からWorkerを作る。
2. 名前は`nagisa-relay-bench-20261007-hybrid`にする。
3. 作成時のHello Worldコードのまま一旦Deployし、発行されたHTTPSのworkers.dev URLを控える。
4. Compatibility dateを`2026-09-21`に設定する。Node.js互換フラグは不要。

測定側はoriginを署名・誤接続防止に使う。伝えるURLは末尾のパス・クエリなしのorigin。
カスタムドメイン・Git連携・Android設定の変更は、この合成測定には不要。

## 3. D1をバインドする

1. 新しいWorkerの「Bindings」→「Add binding」を開く。
2. 「D1 database」を選ぶ。
3. Variable nameを大文字の`DB`にする。
4. Databaseは`nagisa-relay-hybrid-20261007`を選び、保存する。

Worker／D1の作成とバインドの画面操作は[CloudflareのD1公式手順](https://developers.cloudflare.com/d1/get-started/)を参照。

## 4. ログを無効にする

新しいWorkerのSettings／ObservabilityでWorkers Logsを無効にして保存する。
新規Workerはログ収集が既定で有効で、呼び出しログには要求URLが含まれ得る。
測定ではCPU Metricsと匿名集計を使う。[Workers Logs公式仕様](https://developers.cloudflare.com/workers/observability/logs/workers-logs/)

準備段階ではCronを登録しない。測定バンドルの配置・DB初期化が済んでから日次清掃を追加する。
Google側のFirebaseプロジェクト・サービスアカウント鍵・実端末トークンは不要。

## 5. 準備完了として共有する情報

このチャットへ次を伝える。

- 新しいWorkerのHTTPS URL
- D1名（Database IDも分かれば併記）
- `DB`バインド・ログ無効・Cronなしの設定が完了したこと

これらは接続先と設定確認の情報。APIトークンや秘密鍵を貼る必要はない。

## その後の作業

`100-prepare.mjs`・`100-worker.mjs`・`100-run.mjs`は`--mode=hybrid`に対応した。
生成先は`.wrangler/bench-hybrid-20261007/`。詳細は[測定ツール](README.md#ハイブリッド方式の測定)を参照する。
`dist/hybrid/hybrid-worker.js`はRelay本体の候補バンドルで、模擬FCMや測定制御を内蔵していない。
実行可能な測定用バンドルを用意してから、以下の順で進める。

1. 新しいoriginと測定期限に固定した候補バンドルを生成し、指定ファイルをWorkerへ配置する。
2. 空D1を初期化し、200件の合成購読を登録する。
3. 少量でinline／sync_required、成功・失敗集計、本文保持0を確認する。
4. 初期化後、日次清掃のCron `17 0 * * *`を追加する（UTC 00:17／JST 09:17）。
   DashboardはWorkerのSettings → Triggers／Trigger Events → Cron Triggers。
   時刻はUTCで扱う。[Cron公式手順](https://developers.cloudflare.com/workers/configuration/cron-triggers/)
5. アカウント全体の使用量を確認して、通常・集中・全大通知のCPU／D1測定を開始する。
6. 結果保存後、専用環境の受付・送信を停止し、Cronを外す。

通常条件は日次6,000通知の資源量へ外挿する。短時間測定を24時間運用の合格とは扱わない。
合成試験のFCM受付とAndroid端末の表示・差分同期は別に確認する。

## 今回作成済みの環境

- origin：`https://nagisa-relay-bench-20261007-hybrid.ponta3921.workers.dev`
- D1：`nagisa-relay-hybrid-20261007`、ID `f7bb89e8-03e3-45f1-ba7d-979081862504`
- `DB`バインド、Logs／Traces無効、初期化前テーブル0、200合成購読と少量確認を確認済み。
- Dashboardの互換日は2026-10-07。ローカルの確認日は2026-09-21で、同一ランタイムの証明には使わない。
- 今回は短時間の資源評価のため、Cronを追加せず清掃本体を手動で測る。
  上記の日次Cron追加は長時間観測時の手順。実時刻起動は後続の確認事項。
- 2026-10-07 22:39:40 JSTに受付・配送の4フラグをfalseへ戻した。本文保持0、pending0。
  Worker／DBと匿名測定記録は保持している。再開は記録した期限・資格・使用量を確認してから行う。
