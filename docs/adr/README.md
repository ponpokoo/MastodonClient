# 設計判断の記録（ADR）

重要な設計判断の背景、採用理由、受け入れる制約、見直し条件を残す。
記録基準と運用は[AGENTS.md](../../AGENTS.md#architecture-decision-records)に従う。
現行仕様と実装ルールは[開発ガイド](../project-setup.md)や各機能の仕様書を参照する。

## 一覧

| 番号 | 判断 | 状態 |
| --- | --- | --- |
| 0001 | [キャンセルと世代番号の照合によるセッション隔離](0001-session-isolation.md) | accepted |
| 0002 | [通知・ホームに限定した永続キャッシュ](0002-persistent-browsing-cache.md) | accepted |
| 0003 | [Relayでの暗号文中継とAndroidでの復号](0003-push-relay-responsibilities.md) | superseded by 0013（情報境界は継承） |
| 0004 | [ミュート・ブロック成功後の共有非表示状態とキャッシュ除去](0004-account-moderation-invalidation.md) | accepted |
| 0005 | [管理アカウントの隔離とローカルワードミュート](0005-moderation-management-word-mutes.md) | superseded by 0008 |
| 0006 | [スワイプの選択表示とデータ取得の分離](0006-shared-swipe-tabs.md) | accepted |
| 0007 | [種類別通知ページと独立した閲覧記録](0007-notification-category-pages-and-read-state.md) | accepted |
| 0008 | [通知欄のワードミュートとAndroid通知の適用範囲](0008-word-mutes-in-notification-lists.md) | accepted |
| 0009 | [小規模Relayの実装・検証先としてWorkersとD1を継続利用](0009-relay-cloudflare-deployment.md) | accepted（配送保存方針は0013で置換、Free公開可否は未判定） |
| 0010 | [Relay登録v2で購読ごとのVAPID鍵を確定](0010-relay-subscription-key-binding.md) | accepted（本番互換更新済み、全購読移行は未完了） |
| 0012 | [本文を保存しないハイブリッドRelayを隔離候補として実装](0012-isolated-hybrid-relay-candidate.md) | superseded by 0013 |
| 0013 | [本文を保存しないハイブリッドPush配送を採用](0013-adopt-hybrid-push-delivery.md) | accepted（移行条件は0015で置換、本番切替済み） |
| 0014 | [AndroidのハイブリッドPushを永続世代と差分位置で補完](0014-android-hybrid-push-sync.md) | accepted（小通知の実FCM測定済み、大通知・欠落回復・対応APK配布は未完了） |
| 0015 | [旧APKの大通知欠落を許容して本番Relayを直接切替](0015-production-hybrid-cutover.md) | accepted（0013の移行条件を置換） |

## 記録方法

通常の機能追加・不具合修正・文書運用の変更ではADRを増やさない。
共有境界・保存／移行・互換性・本番基盤について、後から判断を見直すために理由が必要な重要な選択を対象とする。
既存の仕様やADRで理由を説明できる場合は、その更新・参照で足りる。

1. 必要な場合だけ次の番号で`NNNN-short-name.md`を作り、1件の判断を簡潔にまとめる。行数・見出しの固定形式は設けない。
2. 状態・記録日、背景、決定と理由、関連する代替案と制約を記載する。見直し条件・参照は役立つ場合だけ加える。
3. 未採用の案は`proposed`、採用した判断は`accepted`として一覧に追加する。規則の理解に必要な場合は該当ガイドからリンクする。
4. 判断を置き換える場合は新しいADRを作り、旧ADRを`superseded`にして双方をリンクする。番号を再利用しない。

初期3件は2026-10-05に既存文書と実装を確認して追記した記録。
当初の採用日と比較経緯は不明。理由・代替案の評価・見直し条件は今回の整理であることを各ADRに明記する。
この記録日は、設計の初回採用日やアプリ・Relayの再検証日を示さない。
