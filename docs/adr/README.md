# 設計判断の記録（ADR）

重要な設計判断の背景、採用理由、受け入れる制約、見直し条件を残す。
記録基準と運用は[AGENTS.md](../../AGENTS.md#architecture-decision-records)に従う。
現行仕様と実装ルールは[開発ガイド](../project-setup.md)や各機能の仕様書を参照する。

## 一覧

| 番号 | 判断 | 状態 |
| --- | --- | --- |
| 0001 | [キャンセルと世代番号の照合によるセッション隔離](0001-session-isolation.md) | accepted |
| 0002 | [通知・ホームに限定した永続キャッシュ](0002-persistent-browsing-cache.md) | accepted |
| 0003 | [Relayでの暗号文中継とAndroidでの復号](0003-push-relay-responsibilities.md) | accepted |
| 0004 | [ミュート・ブロック成功後の共有非表示状態とキャッシュ除去](0004-account-moderation-invalidation.md) | accepted |
| 0005 | [管理アカウントの隔離とローカルワードミュート](0005-moderation-management-word-mutes.md) | accepted |

## 記録方法

1. 次の番号で`NNNN-short-name.md`を作り、1件の判断を20〜40行程度にまとめる。
2. 状態・記録日、背景、決定と理由、代替案、制約、見直し条件、関連文書を記載する。
3. 未採用の案は`proposed`、採用した判断は`accepted`とし、関連文書と相互リンクして一覧に追加する。
4. 判断を置き換える場合は新しいADRを作り、旧ADRを`superseded`にして双方をリンクする。番号を再利用しない。

初期3件は2026-10-05に既存文書と実装を確認して追記した記録。
当初の採用日と比較経緯は不明。理由・代替案の評価・見直し条件は今回の整理であることを各ADRに明記する。
この記録日は、設計の初回採用日やアプリ・Relayの再検証日を示さない。
