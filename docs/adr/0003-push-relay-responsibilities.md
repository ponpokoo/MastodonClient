# ADR 0003：Relayでの暗号文中継とAndroidでの復号

- 状態：accepted（既存設計の追記）
- 記録日：2026-10-05
- 当初の採用日・比較経緯：不明。以下の理由・代替案の評価・見直し条件は現行仕様と実装を基に今回整理した。

## 背景

Mastodon Web PushをAndroidへ届けるため、Relayで受け付けた暗号文をFCM経由で配送する。
アカウントの認証情報、Web Pushの秘密鍵・auth secret、復号した本文を扱う範囲を定める必要がある。
Android、ローカル模擬Relay、Workers版Relayは共通の通信契約を持ち、配送の実装と運用条件は異なる。

## 決定と理由

Relayは暗号文を中継し、Androidが購読・セッションを照合して復号と通知表示を行う。
Mastodonアクセストークン・OAuth client secret・Web Push秘密鍵・auth secretをRelayへ送信しない。
Relay専用HTTPクライアントではMastodon認証Interceptorを共有せず、別の管理用トークンを使用する。
FCMはdataメッセージで暗号文情報を送り、容量に応じてinline配送と暗号文のfetch取得を使う。
Relayが秘密鍵や復号済み本文を保持する必要をなくし、端末側で現在の購読に合う通知だけを表示する。

## 代替案の評価（今回の整理）

- Relayで復号：端末側の復号処理は減るが、Web Pushの秘密情報と平文をRelayで扱うため現行の情報境界に反する。
- FCMのnotificationメッセージで自動表示：SDKの表示経路に乗るため、端末側で復号・購読照合して表示する経路を維持できない。
- 全暗号文をinlineで配送：追加取得を省けるが、FCMの容量上限を超える本文を扱えない。

## 受け入れる制約

Android側に復号、鍵の保存、購読照合、重複排除、再試行の責務が残る。
fetch方式では追加通信が必要。OSの処理制限やFCM・Relayの再送条件により表示の即時性は保証しない。
RelayはFCMトークン・配送情報・暗号文を扱うため、その保存・ログ・運用条件も引き続き管理する。

## 見直し条件

配送方式、FCMの宛先指定、Relayの保存・取得契約を変える場合は、Androidと各Relayへの互換性を確認する。
一般公開や配送負荷の増加に向けては、登録の濫用防止・購読単位の鍵制限・保存と削除の方針を再検討する。
拡張時も秘密情報を端末に保持する境界を維持する。境界自体を変更する提案は別ADRとして評価する。

## 関連文書・実装

- [Relay共通通信契約](../relay-protocol.md)・[Android接続と受信処理](../push-reception.md)
- [購読管理](../push-settings.md)・[ローカルRelay](../../relay/README.md)・[Workers版Relay](../../relay/workers/README.md)
- [DefaultPushMessageRepository](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultPushMessageRepository.kt)
