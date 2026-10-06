# Workers本運用の配置・復旧計画

- 記録日：2026-10-06
- 状態：手順2・4の準備に加え、手順6の本番互換更新と既存購読1件の自動移行・通知表示を確認済み。全購読の移行と配布は未完了。Freeでの本運用合格は未判定。

[本運用への移行手順](../../docs/relay-production.md)の手順2・4・6・7をCloudflareへ具体化する。
選定の理由は[ADR 0009](../../docs/adr/0009-relay-cloudflare-deployment.md)、
現行試験版の操作は[setup.md](setup.md)を参照する。
購読単位の鍵登録、部分停止、再送の変更は実装済み。判断は[ADR 0010](../../docs/adr/0010-relay-subscription-key-binding.md)。
ローカル確認とクラウド実測は別であり、本書だけで本運用を開始しない。

## 環境と公開origin

| 項目 | 準備方針 | 手順4で残す実際の値・確認 |
| --- | --- | --- |
| 本運用先 | 既存Workerを互換更新し、originを維持する方針 | Worker名、公開origin、D1名・bindingの対応。秘密値や配送endpointは記録しない |
| 検証先 | 別Worker・別D1・別Secretsを用意する | 実際のリソース名と本番と混在しないこと。復元試験用DBにも本番送信Workerを接続しない |
| DB binding | 環境ごとのD1を`DB`へ接続 | 配置対象と既存DBの一致、Time Travelに対応するDBであること |
| Firebase | 環境のAndroidビルドとFCMプロジェクトを一致させる | 対象プロジェクト・送信権限。負荷試験は模擬FCMを使う |
| 配置設定 | 検証・本番の設定を区別し、環境ごとの配置対象を確認 | 既存`wrangler.jsonc`はプレースホルダーを含むため、そのまま本番設定と扱わない |
| 保存地域 | 既存DBの配置を確認。新規検証DBのlocation hintは`apac`を候補にする | 実際のDB配置と、地域固定を保証しないこと |

既存の試験originは[Androidの過去記録](../../docs/push-reception.md#過去の検証記録)にある。
手順4で現在の公開originを確認し、Worker名・アカウントサブドメインを不要に変更しない。
URL変更が必要なら移行手順6.2に従う。URLを維持してもDBの作り直しで既存配送先を失わないようにする。

## 変数・Secrets・背景処理

- 現行の`PUBLIC_ORIGIN`、`FCM_PROJECT_ID`、`DB`と、Secretsの`FCM_CLIENT_EMAIL`・`FCM_PRIVATE_KEY`を環境ごとに設定する。
  値はチャット・Git・操作記録・ログへ貼らず、必要な運用権限だけに限定する。
- `RELAY_ENABLED=false`を配置・復元時の初期状態とする。現行では更新・解除・取得も止まる全体停止であり、部分停止と区別する。
- `REGISTRATION_ENABLED`・`PUSH_ENABLED`・`DELIVERY_ENABLED`を配置時はすべて`false`にし、検証後に個別に有効化する。
  全体停止を解除してもこれらが明示的に`true`になるまでは新規登録・受付・送信を開始しない。
- `VAPID_PUBLIC_KEYS`はv2では`[]`。旧登録を維持する移行期間だけUTCの`LEGACY_V1_UNTIL`と全体リストを明示する。
  今回は運用者の判断により猶予を設けず、2026-10-06の配置時に`[]`へ変更した。`LEGACY_V1_UNTIL`は未設定。
  未設定・期限切れでは旧登録の新しいPushを拒否する。
- Cronは毎分`* * * * *`を出発点とする。UTCで動作し、設定反映と実際の起動を確認する。
  処理件数・リース・試行回数・タイムアウトは手順3の確定仕様を反映し、HTTP・CronともFree CPU 10ms内か測定する。
- Workers Logsの保存を無効にし、Metrics・Custom Alertsの対象データに配送URLや秘密値が入らないことを確認する。
  通知先・しきい値・評価間隔とテスト通知の到達を記録する。

## 配置とDB移行

1. 対象コミット・成果物・環境・戻せる互換版を記録し、[現行README](README.md)に従いNode.js 22以降でローカル確認する。
   `npm ci`、`npm test`、`npm run build`を使う。`build`はdry-runでありクラウド配置ではない。
2. 新しい空の検証DBは[0001](migrations/0001_initial.sql)→[0002](migrations/0002_bound_registrations.sql)の順に適用する。
   既存DBは0002だけを一度適用する。`wrangler d1 migrations apply DB`等で適用履歴を管理し、ALTERを再実行しない。
3. 本番の既存DBには初期化や削除を行わず、変更前のTime Travel bookmarkとSQL退避を確保する。
   検証DBで既存登録・配送ID・墓標・キューの保持を確認してから、互換マイグレーションを適用する。
4. [setup.mdの配置操作](setup.md#2-workerを作ってコードを配置する)を基に、確認した対象Workerへ生成バンドルを配置する。
   部分停止の変数とマイグレーションは上記に従う。新鍵制限を無視する旧バンドルへ差し戻さない。
5. healthだけで合格にせず、DB接続・登録・鍵隔離・解除・実配送・Cron・監視・負荷・復元を移行手順4・5で確認する。
6. 旧URLの登録・配送IDを保持し、Relayを先に、対応Androidを後に展開する。
   新Android→旧Relay・ローカルv1は登録エラー。旧Androidは猶予期限内のv1と解除・取得だけを使用できる。

## バックアップと隔離復元

2026-10-06の本番移行では、Time Travel bookmarkとアプリ3テーブルの暗号化したJSON・SQL退避を確保し、
ローカルのメモリ内SQLiteで復元・追加マイグレーション・元の全列の保持を確認した。
本番は既存の`nagisa-relay` Workerと同名D1を維持して0002だけを追加適用した。
退避はGit管理外の`.wrangler/production-migration-20261006/`にDPAPI CurrentUserで保管し、保持期限は2026-10-13。
クラウド隔離DBでの復元・別端末からの退避復号は未検証。
配置・版・フラグ・自動移行・実通知の詳細は[手順6の実施記録](../../docs/investigations/relay-production-measurement-20261006.md#手順6既存公開relayの互換更新2026-10-06)を参照する。

[D1 Time Travel](https://developers.cloudflare.com/d1/reference/time-travel/)は自動で履歴を保持し、Freeでは過去7日まで復元できる。
初期の復元機能として利用し、登録状態のRPO目標24時間と復旧目標を実機能・復元試験で確認する。
Time Travelは対象DBをその場で上書きし、別DBへの履歴の複製には使えない。

- 変更前にはbookmarkとSQL退避を確保する。bookmarkの照会は対象DBを確認して`npx --no-install wrangler d1 time-travel info <DB名>`を使う。
  SQL退避は[Wranglerのexport](https://developers.cloudflare.com/d1/wrangler-commands/#d1-export)の
  `d1 export <DB名> --remote --output <退避先>`を使い、Git管理外の安全な場所へ保存・暗号化する。
  山括弧の部分は実際の値へ置き換える。これらは手動操作の例であり、今回実行していない。
- SQLはFCMトークン・配送ID・管理用ハッシュ・暗号文を含む秘密情報として扱い、閲覧・転送を限定し7日で削除する。
  長期保存する退避ファイルを作ってTTL・削除方針を迂回しない。
- 隔離復元では、送信WorkerやFCM Secretsに接続していない空のDBへSQLを取り込み、登録状態・墓標・スキーマを確認する。
  Time Travelの動作自体は別の検証DBに模擬データで確認し、通常の復元試験で本番DBを巻き戻さない。
- 実障害で本番をTime Travel復元する場合は、先に受付・送信を止める。復元後も送信を止めたまま、
  バックアップ後の解除を反映する。解除情報を確認できない購読は配送停止とし、端末で再登録する。
  再構成できない場合は全体・部分停止を維持したまま、隔離環境で確認済みの
  [復元登録の墓標化SQL](operations/retire-restored-registrations.sql)を対象DBへ一度適用する。
  すべての復元登録を墓標化し、FCMトークン・配送ID・鍵・キューを消す。墓標は削除しない。
  件数だけの照会で全登録`deleted=1`・通知0件を確認してから段階再開する。
  利用者はPushオフ→解除完了→オンで新ID・鍵を作る。保存済みIDのPUTだけでは再開しない。
  全体停止のままSQL適用を完了して確認するため、途中まで適用された状態で受付・送信を再開しない。
- 復元試験のSQL・履歴にも秘密情報が残るため、隔離DB・退避ファイルを保持方針に従って片付ける。

## 停止と差し戻し

本運用では新規登録停止、Push受付停止、送信停止を分け、更新・解除・取得を可能な限り維持する。
新規登録は`REGISTRATION_ENABLED=false`、受付は`PUSH_ENABLED=false`、送信は`DELIVERY_ENABLED=false`。
`RELAY_ENABLED=false`は緊急・配置・復元時の全体停止として使用する。
差し戻し先は新契約・新スキーマ・購読の鍵制限を理解する版に限定する。
FCM受付済みの通知は取り消せず、DBを空にする操作や解除済み登録の復活を通常の復旧手段にしない。

## 公開前に埋める記録

対象Worker・origin・DB、配置版・SQL適用順序、CPU・DB・再送の測定、Custom Alerts利用可否・通知到達、
隔離復元・部分停止・秘密情報更新の結果を記録する。
2026-10-06に専用Worker＋D1で[測定](../../docs/investigations/relay-production-measurement-20261006.md)した。
14:17 JSTに未送信0件へ収束し、検証Cronは解除した。手動切り分け40件＋実Cron 560件の復旧で、
総経過はCron設定変更・起動待ち込みで約66分、自動Cron開始後の560件は約28分で解消した。
60分目標に対する性能評価は未確定。手動試験なしの再測定が残る。
運用者は起動後の再送性能を初期試験運用には十分と暫定判断し、再測定を手順5の開始条件にせず実配送確認へ進む。
本運用向けのCPU・復旧・監視・実配送・隔離復元の合格判断はまだ完了していない。

参照：[Workers制限](https://developers.cloudflare.com/workers/platform/limits/)、
[D1制限](https://developers.cloudflare.com/d1/platform/limits/)、[保存地域](https://developers.cloudflare.com/d1/configuration/data-location/)、
[Secrets](https://developers.cloudflare.com/workers/configuration/secrets/)、[Cron](https://developers.cloudflare.com/workers/configuration/cron-triggers/)。
