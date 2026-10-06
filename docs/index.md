# ドキュメント一覧

目的に応じて以下の文書を参照する。現行仕様・設計判断・過去の調査や検証記録は、それぞれの文書で確認する。

## 開発・仕様・設計判断

| 文書 | 読むとき |
| --- | --- |
| [実装ルール](../AGENTS.md) | 実装の境界、変更時の確認、ADRの記録基準を確認する |
| [開発ガイド](project-setup.md) | 開発環境、セッション管理、保存方針、ビルド・テストの選び方を確認する |
| [UI・機能仕様](ui-guidelines.md) | 画面、操作、テーマ、設定の現行動作を確認する |
| [通知一覧の取得・新着判定](ui-guidelines.md#通知の表示更新既読位置) | 種類別取得、40件のページ、対応判定、既読待機と再起動後の新着判定を確認する |
| [ミュート・ブロック・報告](ui-guidelines.md#ミュートブロック報告) | メニュー、設定・解除、操作後の非表示、報告、設定での管理、投稿・通知欄のワードミュートを確認する |
| [設計判断（ADR）](adr/README.md) | 重要な設計の背景、採用理由、制約、見直し条件を確認する |
| [更新・リリース計画](release-plan.md) | 2.4.1の変更・確認状況、事前検証用成果物との区別、今後の候補、リリース手順を確認する |
| [GitHub Issues](https://github.com/ponpokoo/MastodonClient/issues) | 移行済みの継続確認・改善候補と対応の進捗を確認する |

## Push・Relay

| 文書 | 読むとき |
| --- | --- |
| [通知設定と購読管理](push-settings.md) | Pushの登録・更新・解除、再認証、ログアウト後の再試行を確認する |
| [Android接続と受信処理](push-reception.md) | Firebaseの設定、FCM受信、復号、通知表示を確認する |
| [Relay通信契約](relay-protocol.md) | 登録v2・配送v1、旧Relay・ローカルv1との互換性を確認する |
| [ローカル模擬Relay](../relay/README.md) | ローカルの起動・保存・模擬配送・テストを確認する |
| [Workers版Relay](../relay/workers/README.md) | Workers版の開発・配送・保存・制限・テストを確認する |
| [Workers配置・運用手順](../relay/workers/setup.md) | 公開配置、変数・Secrets、監視、停止の手順を確認する |
| [Workers本運用の配置・復旧計画](../relay/workers/production.md) | 選定したWorkers＋D1の環境分離・DB移行・監視・隔離復元・停止の準備と未実施項目を確認する |
| [Relay本運用への移行手順](relay-production.md) | 最大30人・無料運用の前提、Workers＋D1の選定、必要な実装変更、検証、購読移行、監視・復旧、開始条件を確認する |

## 調査・継続確認

| 文書 | 読むとき |
| --- | --- |
| [画像共有遷移の最小試作](../transition-prototype/README.md) | Issue #3の独立した試作アプリ、静止画1枚の開閉、ビルド手順、確認範囲を確認する |
| [Relay本運用準備の測定](investigations/relay-production-measurement-20261006.md) | 合成負荷・滞留解消、本番RelayとDBの互換更新、既存購読の自動移行と少量実通知の確認記録を参照する |
| [下書き経由の投稿遅延](investigations/draft-post-latency-report.md) | 調査の結果、再現条件、原因特定の限界を確認する |
| [通知メンションの遅延・リアクションの連続読込](investigations/notification-mention-loading-report.md) | mstdn.jpでの空表示・遅延、絵文字リアクション非対応時の連続読込、共通カーソルと既読位置待機、端末なしの検証範囲を確認する |
| [misskey.ioのAPI互換性](investigations/misskey-io-api-compatibility.md) | 公開APIの観測、MastodonとMisskeyの認証・通知・リアクションAPIの違い、連合と直接ログインの範囲を確認する |

## 完了した修正

| 文書 | 読むとき |
| --- | --- |
| [UI修正の要点](investigations/resolved-ui-fixes.md) | 音声・プロフィールの改行・固定投稿・惰性スクロール・検索復帰の原因、修正理由、最終確認の範囲を確認する |

詳細な調整履歴の要点は上記へ統合した。再現用テスト、未解決の調査、設計判断のADRは別途保持する。
