# ADR 0013：本文を保存しないハイブリッドPush配送を採用する

- 状態：accepted（APK移行を切替条件とする部分は[ADR 0015](0015-production-hybrid-cutover.md)で置換。配送・情報境界は継続）
- 決定日・記録日：2026-10-07。利用者の採用指示による。
- 置換：[ADR 0003](0003-push-relay-responsibilities.md)の配送方式、[ADR 0012](0012-isolated-hybrid-relay-candidate.md)の候補限定。
  [ADR 0009](0009-relay-cloudflare-deployment.md)のWorkers＋D1選定は維持し、D1を配送キューの正本とする部分を置換する。

## 背景

100人×2アカウント×1端末、6,000通知/日の旧方式はD1本文保存・COUNT・再送の負荷が大きかった。
専用環境のハイブリッド測定では通常1,000件のD1読取約92%・書込約70%減、本文保持0を確認した。
実端末の保持履歴62件は小通知だったが、全利用者のサイズ比率は未確定。

## 決定と理由

小通知は既存の配送JSON 3,500 bytes判定でv1 inlineを直送し、Androidが復号・表示する。
大通知は本文を保持せずv2 sync_requiredを送り、Androidがアカウント単位で差分同期する。
FCM受付成功を待って201を返す。本文GET、永続retry、毎分の配送Cronを新経路から除き、日次清掃を残す。
D1は購読・鍵・revision・墓標・受付制限を保持する。共有トークンの無効化は対象購読の照合で防ぐ。
Mastodon認証情報・Web Push秘密鍵・auth secret・復号済み本文をRelayへ送らない境界を継承する。

## 代替案・受け入れる制約

- 全Push同期：小通知もAPI取得が必要。ハイブリッドは小通知の追加取得を省けるため選ぶ。
- 保存・fetch・再送の継続：端末同期の追加は少ないが、主要なD1負荷が残る。
- Androidには復号と差分同期の2経路、通知ID重複排除、失敗・FCM欠落の補完が必要。
  Relayの永続再送を失う。上流再送・即時表示・欠落しない配送を保証しない。

## 移行・見直し条件

当初は旧Androidの能力確認と旧fetch終了条件を決めてから切り替える方針だった。
2026-10-08に利用者が旧APKの大通知欠落を許容し、直接切替を指示した。[ADR 0015](0015-production-hybrid-cutover.md)を参照する。
HTTP 500の原因、CPU上位値、実FCM、Android同期・欠落回復、24時間運用・監視・復元を確認して公開判断する。
API・通信量・電池・遅延が明確に不利、または規模・予算が変わる場合は方式を再評価する。
仕様と未完了作業は[ハイブリッド仕様](../../relay/workers/hybrid.md)、検証の入口は[テスト・測定一覧](../../relay/workers/testing.md)。
根拠は[方式比較](../investigations/relay-hybrid-delivery-assessment-20261007.md)と[100人測定](../investigations/relay-hybrid-100-user-measurement-20261007.md)。
