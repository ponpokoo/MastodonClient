# Relayのテスト・測定一覧

ハイブリッド方式の採用後、テストと調査記録をここから参照する。
採用決定は[ADR 0013](../../docs/adr/0013-adopt-hybrid-push-delivery.md)、配送仕様と実運用での確認事項は[hybrid.md](hybrid.md)。
今回のPush試験は2026-10-09で終了した。以下の手順は、仕様変更・不具合対応や必要な再検証に使う。残る確認は[実運用へ引き継ぐ](hybrid.md#採用後の対応と確認)。

## テストの配置

| 対象 | ファイル | 件数・確認する内容 |
| --- | --- | --- |
| ハイブリッド配送 | [test/hybrid.test.mjs](test/hybrid.test.mjs) | 8件。サイズ境界、inline／同期トリガー、FCM待機、失敗応答、TTL、鍵・トークン競合、本文非保存、制限、実バンドル |
| 既存配送・共通登録 | [test/relay.test.mjs](test/relay.test.mjs) | 29件。登録v2・VAPID・墓標・移行・停止・容量と、保存／fetch／リース／再送の互換経路 |
| FCM送信器 | [test/fcm.test.mjs](test/fcm.test.mjs) | 6件。OAuth共有・更新、認証失敗、TTL、エラー分類、Retry-After、本文読み取りのタイムアウト |
| 測定記録 | [bench/sample.test.mjs](bench/sample.test.mjs) | 2件。保存応答喪失時の上書き再試行、上限と秘密値を出さない固定エラー |
| 共通試験準備 | [test/support/relay-fixture.mjs](test/support/relay-fixture.mjs) | D1・時計・使い捨て鍵・模擬Google・HTTPヘルパー。各テストファイルで別runtimeを作る |
| ローカルv1模擬Relay | [../test/relay.test.mjs](../test/relay.test.mjs) | 16件。ファイル保存・模擬配送・HTTP契約。Workers／Android同期の検証ではない |

Workersの旧保存経路は差し戻し用に回帰テストを維持する。本番は[2026-10-08の記録](../../docs/investigations/relay-production-hybrid-cutover-20261008.md)のとおり切替済み。
採用決定だけで旧経路・スキーマ・テストを削除しない。重複したD1・署名・模擬Googleの準備は共通fixtureへ集約した。
テスト件数は2026-10-07の整理時点。実FCMや外部Mastodonにテスト通知を送るコマンドではない。

## 実行方法

push／PRでは[変更範囲に応じたCI](../../docs/project-setup.md#変更範囲に応じたci)が、Workers変更時にNode 24で`npm ci`・`npm test`を実行する。
ローカル模擬Relayは別ジョブで、対応する変更がある場合だけ実行する。CIの手動実行は両方を選ぶ。
クラウド配置・実FCM送信・合成負荷測定は実行せず、必要時には以下の既存手順を使用する。

`relay/workers`を作業ディレクトリとし、Node.js 22以降と導入済みの依存関係を使う。

```powershell
# 初回・lockfile変更時
npm ci
# バンドルを含む全45件。外部配置はしない
npm test
```

バンドル作成済みでソースだけを整理したときの対象確認は次のとおり。

```powershell
node --test test/hybrid.test.mjs test/relay.test.mjs
node --test test/fcm.test.mjs
node --test bench/sample.test.mjs
```

バンドルのテストを含むため、Workerソース変更後は`npm test`で再生成する。
ローカルv1版を変更した場合は`relay`で`npm test`。Relayのみの変更でGradleを実行しない。
文書のみの変更は内容・参照先・差分を確認する。

## Androidハイブリッド同期

実FCMを使う専用Worker・D1・送信資格・テスト購読は[少量実配信の準備](hybrid-live-setup.md)で設定する。
端末側の仕様・検証記録・実行方法は[Android受信](../../docs/push-reception.md#2026-10-07androidハイブリッド同期の確認)、
集約・初期化の判断は[ADR 0014](../../docs/adr/0014-android-hybrid-push-sync.md)を参照する。
Workersの45件はAndroid差分同期・実FCM・電池消費を検証するテストではない。
実FCMの小通知を背景で測った結果・匿名原票・再現手順は[2026-10-08の端末測定](../../docs/investigations/relay-live-fcm-measurement-20261008.md)を参照する。

## 調査とクラウド測定の順序

| 記録 | 用途・限界 |
| --- | --- |
| [2026-10-06 本運用準備](../../docs/investigations/relay-production-measurement-20261006.md) | 登録v2の本番互換更新、既存購読1件・実通知、旧方式の滞留確認。採用方式の合格記録へ流用しない |
| [2026-10-07 旧方式100人測定](../../docs/investigations/relay-100-user-measurement-20261007.md)・[匿名JSON](bench/results/20261007-100.json) | 保存・COUNT・fetch・再送のD1負荷。旧方式の比較根拠 |
| [方式比較・実端末サイズ調査](../../docs/investigations/relay-hybrid-delivery-assessment-20261007.md)・[匿名サイズ集計](../../docs/investigations/relay-push-size-statistics-20261007.json) | 保持履歴62件は小通知。全利用者のサイズ分布は未確定 |
| [ハイブリッド100人測定](../../docs/investigations/relay-hybrid-100-user-measurement-20261007.md)・[匿名JSON](bench/results/20261007-hybrid.json) | 200購読、通常・集中・全小・全大、模擬FCM障害、清掃、停止。実FCM・端末到着・24時間運用は未測定 |
| [2026-10-08 本番ハイブリッド切替](../../docs/investigations/relay-production-hybrid-cutover-20261008.md) | 配置版・DB継承・公開health／capabilities・日次Cron・45件の回帰。実FCM・端末表示の確認とは区別 |
| [2026-10-08 実FCM測定](../../docs/investigations/relay-live-fcm-measurement-20261008.md) | 背景エミュレーターで小通知5件の受信・表示・通信量と受信後遅延を測定。送信からの遅延は時計差を含む参考推定。大通知・欠落回復・電池は未測定 |

再現ツールは[bench/README.md](bench/README.md)、専用環境は[hybrid-setup.md](bench/hybrid-setup.md)。
既存測定環境は停止済み。資格・期限・本番からの隔離・アカウント使用量を確認して新しい測定を準備する。
再測定は[100人計画](../../docs/relay-100-user-remeasurement-plan.md)に従う。

## 結果の扱い

- 自動テスト、合成負荷、実通知、APK版・配置版の確認を区別する。
- HTTP失敗と集計未完了を成功へ含めない。初回inlineの未完了1件は匿名JSONの全試行に保持した。
- D1の本体費用と測定用SQL、HTTP応答時間とCPU、模擬FCM受付と端末表示を区別する。
- 配送URL・資格・本文・個別通知IDを共有結果へ含めない。`.wrangler`のfixture／バンドルは公開結果に含めない。
- 実運用での確認事項の正本は[hybrid.md](hybrid.md#採用後の対応と確認)、実測値の正本は各測定記録。件数や数値を各ガイドへ繰り返し転記しない。

## 2026-10-07の整理確認

ハイブリッド8件を別ファイルへ移し、既存経路29件と共有fixtureを分離した。振る舞い・期待値・件数を維持する。
対象の`node --test test/hybrid.test.mjs test/relay.test.mjs`は37件成功。
続く`npm test`は両バンドルのdry-run生成と45件すべて成功、失敗・スキップ0。
本整理でAndroid／実FCM／クラウド負荷を再試験した結果ではない。

## Workersの過去の検証記録

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
