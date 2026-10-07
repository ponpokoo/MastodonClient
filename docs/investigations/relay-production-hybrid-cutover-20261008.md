# 本番Relayのハイブリッド切替記録（2026-10-08）

## 決定と範囲

利用者がほとんどいない時間帯で、旧APKのsync_required非対応を許容する明示指示により直接切り替えた。
専用実FCM環境の先行確認・全APKの更新待ちを省略する判断は[ADR 0015](../adr/0015-production-hybrid-cutover.md)に記録した。
配送仕様は[hybrid.md](../../relay/workers/hybrid.md)、Android側は[push-reception.md](../push-reception.md)を参照する。

## 配置結果

| 項目 | 確認した状態 |
| --- | --- |
| Worker・公開origin | `nagisa-relay`・`https://nagisa-relay.ponta3921.workers.dev` |
| 配置コード | `npm run build:hybrid`で生成した`dist/hybrid/hybrid-worker.js` |
| 本番版ID | `4ae6343d-0d8e-401d-884d-e44f0daf665c`、Active・100% traffic |
| 切替前の版ID | `aaaa74d6-fe9c-4e6e-b220-532a8f935c2f` |
| SHA-256 | `8FD39C36EB93410D9126DDF41D96709613CFC4D0C19130169D5426047CC7AFDB` |
| D1 | 既存`nagisa-relay`、`a4e086ef-26d2-469b-a50f-e859285e752f`を継承 |
| DB変更 | スキーマ変更、購読行の書換え、テーブル削除・再作成なし |
| FCM | 既存project・2つの暗号化Secretを継承。秘密値は取得せず存在だけを確認 |
| 停止フラグ | RELAY・REGISTRATION・PUSH・DELIVERYの4つはすべてtrueを継承 |
| 上限 | 登録300、仮登録50。その他は既定値 |
| Cron | 毎分から`17 0 * * *`へ変更。日次00:17 UTC／09:17 JSTの清掃のみ |
| 公開health | HTTP 200、`status=configured`・`mode=workers-d1` |
| 公開capabilities | HTTP 200、登録v2・鍵紐付け・`deliveryMode=hybrid`・配送v2・`syncRequired=true` |

配置記録とDashboardの証跡はGit対象外の`relay/workers/.wrangler/production-hybrid-20261008/`に保存した。
この記録の配置確認は2026-10-08 JST（CloudflareのUTC表示は2026-10-07）。

## 検証と限界

- 最終コードの`relay/workers`の`npm test`は45件すべて成功。Google応答は模擬である。
- 初回はsandbox内でworkerdのSQLite起動がアクセス拒否。許可された実行環境で成功した。
- 初回の配置送信は自動承認レビューがエディターの型エラー3件を理由に拒否した。
  FCM Error属性の作成方法、容量定数の型推論、ハイブリッド入口の全容量指定を修正した。
  実行時の値・配送判断は維持し、コード一致・エディターのエラー0件・再テスト成功後に配置した。
- 実通知の表示、実FCMでの大通知・欠落回復、通信量・表示遅延・電池、日次Cronの実時刻起動は未確認。
- 100人・200購読での一般提供の運用合格を示す記録ではない。

切替後の[実FCM測定](relay-live-fcm-measurement-20261008.md)で背景エミュレーターへ小通知5件の受信・表示・通信量と受信後遅延を確認した。
上記は配置時点の確認範囲。大通知・欠落回復・電池・日次Cronの実時刻起動は引き続き未確認。

## 既存利用者への影響と差し戻し

購読のendpoint・鍵・FCMトークンを引き継ぐため、登録v2の既存購読を作り直す必要はない。
旧APKはinlineの小通知を引き続き扱えるが、大通知のsync_requiredは表示できない。
登録v1は配送対象外。切替前のfetch通知・配送待ちジョブも新経路では取得・再送しない。
既存messages行は削除しない。対応APKの配布と大通知・欠落回復の実FCM確認は別途行う。

小通知・登録・解除に障害がある場合は、必要に応じてPUSH／DELIVERYを停止し、
DashboardのDeploymentsで切替前の版IDを復元する。旧配送を再開する際はCronも毎分へ戻す。
DBを以前の時点へ巻き戻さず、切替後の解除・墓標・購読更新を保持する。
復元後はhealth・capabilitiesと実通知を確認する。差し戻し自体は今回実行していない。

運用手順は[relay-production.md](../relay-production.md)、配置・停止手順は[setup.md](../../relay/workers/setup.md)を参照する。
