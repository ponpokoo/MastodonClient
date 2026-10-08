# ドキュメント一覧

目的に応じて以下の文書を参照する。現行仕様・設計判断・過去の調査や検証記録は、それぞれの文書で確認する。

## 開発・仕様・設計判断

| 文書 | 読むとき |
| --- | --- |
| [実装ルール](../AGENTS.md) | 実装の境界、変更時の確認、ADRの記録基準を確認する |
| [開発ガイド](project-setup.md) | 開発環境、セッション管理、保存方針、ビルド・テストの選び方を確認する |
| [UI・機能仕様](ui-guidelines.md) | 画面、操作、テーマ、設定の現行動作を確認する |
| [ログインとアカウント追加](ui-guidelines.md#ログインとアカウント追加) | 一画面のサーバー確認・ブラウザー認証、入力変更と再試行、追加の戻る操作を確認する |
| [自アカウントの表示情報同期](ui-guidelines.md#登録済み自アカウントの表示情報) | 起動・切替・プロフィール取得／編集後の名前・アイコン更新と状態保持を確認する |
| [登録済みアカウントの管理](ui-guidelines.md#登録済みアカウントの管理) | Play公開整備のIssue #10・#11、削除確認、共通ドラッグ操作、保存順と追加・再認証・削除後の動作を確認する |
| [通知一覧の取得・新着判定](ui-guidelines.md#通知の表示更新既読位置) | 種類別取得、40件のページ、対応判定、既読待機と再起動後の新着判定を確認する |
| [ミュート・ブロック・報告](ui-guidelines.md#ミュートブロック報告) | メニュー、設定・解除、操作後の非表示、報告、設定での管理、投稿・通知欄のワードミュートを確認する |
| [設計判断（ADR）](adr/README.md) | 重要な設計の背景、採用理由、制約、見直し条件を確認する |
| [ライセンスと第三者通知](third-party-licenses.md) | アプリ内のライセンス表示、Apache 2.0適用範囲、Release依存の収録内容と再生成・公開前確認を参照する |
| [更新・リリース計画](release-plan.md) | 2.4.1の変更・確認状況、事前検証用成果物との区別、今後の候補、リリース手順を確認する |
| [Google Play公開までのワークフロー](google-play-publication-workflow.md) | 公開準備、必要な文書・申告、Play配布版の確認、限定テストと一般公開の判断を確認する |
| [GitHub Issues](https://github.com/ponpokoo/MastodonClient/issues) | 移行済みの継続確認・改善候補と対応の進捗を確認する |

## Push・Relay

まず[Workers版Relay](../relay/workers/README.md)で現在の方式を確認する。
本番は2026-10-08にハイブリッド方式へ切替済み。Android差分同期を実装し、実FCMの小通知5件を測定済み。大通知・欠落回復の実測と対応APKの配布は未完了。

| 目的 | 文書 |
| --- | --- |
| Androidの購読・受信を変更する | [通知設定と購読管理](push-settings.md)・[Android接続と受信処理](push-reception.md) |
| 通信形式・配送仕様を確認する | [共通通信契約](relay-protocol.md)・[ハイブリッド仕様と未完了作業](../relay/workers/hybrid.md) |
| Relayを開発・起動する | [Workers版](../relay/workers/README.md)・[ローカルv1模擬版](../relay/README.md) |
| 配置・更新・停止・復旧する | [Workers配置・運用・復旧](../relay/workers/setup.md) |
| 本番ハイブリッド切替の結果・旧APKへの影響を確認する | [2026-10-08切替記録](investigations/relay-production-hybrid-cutover-20261008.md)・[直接切替の判断](adr/0015-production-hybrid-cutover.md) |
| 実FCMの通信量・表示遅延を確認する | [2026-10-08 小通知5件の測定](investigations/relay-live-fcm-measurement-20261008.md)（匿名結果・時計のずれ・測定範囲） |
| エミュレーターで実FCMのハイブリッド配送を試す | [専用Worker・D1・FCM設定とAndroid購読の切替](../relay/workers/hybrid-live-setup.md) |
| 公開条件・購読移行を確認する | [本運用への移行手順](relay-production.md)・[100人向け再測定計画](relay-100-user-remeasurement-plan.md) |
| テスト・測定・過去の調査を読む | [検証ガイド](../relay/workers/testing.md)（測定ツール・専用環境の準備・各記録への入口） |

実測値は各測定記録、設計の採用理由は[ADR一覧](adr/README.md)を正本とし、入口の文書には重複して転記しない。

## 調査・継続確認

| 文書 | 読むとき |
| --- | --- |
| [段階的リファクタリング計画](refactoring-plan.md) | 調査で挙げた9候補の実装順、最小変更範囲、過剰設計を避ける制約、検証・完了条件を確認する（実装・実機確認完了） |
| [画像共有遷移の最小試作](../transition-prototype/README.md) | Issue #3の独立した試作アプリ、静止画1枚の開閉、ビルド手順、確認範囲を確認する |
| [下書き経由の投稿遅延](investigations/draft-post-latency-report.md) | 調査の結果、再現条件、原因特定の限界を確認する |
| [自プロフィール画像の点滅](investigations/profile-image-refresh-flicker-report.md) | アイコンとヘッダーの再取得・旧画像保持・キャッシュの調査結果、採用した最小修正案と確認条件を参照する |
| [通知メンションの遅延・リアクションの連続読込](investigations/notification-mention-loading-report.md) | mstdn.jpでの空表示・遅延、絵文字リアクション非対応時の連続読込、共通カーソルと既読位置待機、端末なしの検証範囲を確認する |
| [misskey.ioのAPI互換性](investigations/misskey-io-api-compatibility.md) | 公開APIの観測、MastodonとMisskeyの認証・通知・リアクションAPIの違い、連合と直接ログインの範囲を確認する |

## 完了した修正

| 文書 | 読むとき |
| --- | --- |
| [UI修正の要点](investigations/resolved-ui-fixes.md) | 音声・プロフィールの改行・固定投稿・惰性スクロール・検索復帰の原因、修正理由、最終確認の範囲を確認する |

詳細な調整履歴の要点は上記へ統合した。再現用テスト、未解決の調査、設計判断のADRは別途保持する。
