# ADR 0012：本文を保存しないハイブリッドRelayを隔離候補として実装

- 状態：superseded by [ADR 0013](0013-adopt-hybrid-push-delivery.md)（2026-10-07、測定後に方式採用）。
- 記録日：2026-10-07

以下は隔離候補として実装を許可した時点の判断。測定結果は[100人測定](../investigations/relay-hybrid-100-user-measurement-20261007.md)。
この判断の時点では本番切替・既存購読移行は未実施だった。後の直接切替は[ADR 0015](0015-production-hybrid-cutover.md)を参照する。

## 背景

100人・200購読の現行方式はD1資源条件に未達で、本文COUNT・保存・永続再送が主要な削減対象。
実端末の保持履歴62件はすべてinline／1KiB以下だが、全利用者の比率は不明。
全Push同期へ移す前に、小通知のAPI不要経路を残す候補を実測する。

## 決定と理由

別入口・別origin・空D1でハイブリッド候補を実装し、配送方式は信頼されたコードから固定する。
小通知は現行3,500 bytes判定でv1 inline、大通知は本文なしのv2 sync_requiredを送る。
本文保存・取得・永続retryを省き、FCM受付を待ってPushの成功応答を返す。
購読v2、VAPID、revision、解除墓標、受付制限を維持し、通信前の再照合を行う。
UNREGISTEREDは送信時のトークン・鍵・revisionと一致する対象購読だけを無効化する。
毎分の配送Cronを日次の登録・カウンター清掃へ置換する。
既定の本番入口は変更しない。既存スキーマと旧fetch経路は移行判断まで維持する。

## 代替案と受け入れる費用

- 全Push同期：Relayを単純化できるが、小通知でも端末API同期が必要になる。
- 現行方式：Relay内で再送できるが、全件の本文・索引・リースとCronの負荷が残る。
- 小通知だけ保存除去：既存fetchの負荷と再送構造が残り、今回の主要目的を満たしにくい。

Androidは復号と差分同期の2経路を保守する必要があり、同期対応は今回未実装。
Relay内の配送回復を失い、通信中断・FCM後の欠落は端末補完に依存する。上流再送は保証しない。
型付き無効トークンの他購読への反映は、その購読の送信結果まで遅れ得る。

## 見直し条件・関連

CPU・D1・端末API・欠落回復を実測し、不利が明確なら全同期も比較する。
本番採用時にはAndroid能力確認、旧APK／fetch終了条件、新方式の公開基準を別途決める。
[候補仕様](../../relay/workers/hybrid.md)、[調査](../investigations/relay-hybrid-delivery-assessment-20261007.md)、
[契約](../relay-protocol.md)、[ADR 0003](0003-push-relay-responsibilities.md)、[ADR 0010](0010-relay-subscription-key-binding.md)。
