# ドキュメント一覧

目的に応じて以下の文書を参照する。現行仕様・設計判断・過去の調査や検証記録は、それぞれの文書で確認する。

## 開発・仕様・設計判断

| 文書 | 読むとき |
| --- | --- |
| [実装ルール](../AGENTS.md) | 実装の境界、変更時の確認、ADRの記録基準を確認する |
| [開発ガイド](project-setup.md) | 開発環境、セッション管理、保存方針、ビルド・テストの選び方を確認する |
| [UI・機能仕様](ui-guidelines.md) | 画面、操作、テーマ、設定の現行動作を確認する |
| [ミュート・ブロック・報告](ui-guidelines.md#ミュートブロック報告) | メニュー、設定・解除、操作後の非表示、報告、設定での管理とワードミュートを確認する |
| [設計判断（ADR）](adr/README.md) | 重要な設計の背景、採用理由、制約、見直し条件を確認する |
| [更新・リリース計画](release-plan.md) | 対象版の変更・確認状況、今後の候補、リリース手順を確認する |

## Push・Relay

| 文書 | 読むとき |
| --- | --- |
| [通知設定と購読管理](push-settings.md) | Pushの登録・更新・解除、再認証、ログアウト後の再試行を確認する |
| [Android接続と受信処理](push-reception.md) | Firebaseの設定、FCM受信、復号、通知表示を確認する |
| [Relay共通通信契約](relay-protocol.md) | Androidと両Relay実装の登録・解除・配送形式を確認する |
| [ローカル模擬Relay](../relay/README.md) | ローカルの起動・保存・模擬配送・テストを確認する |
| [Workers版Relay](../relay/workers/README.md) | Workers版の開発・配送・保存・制限・テストを確認する |
| [Workers配置・運用手順](../relay/workers/setup.md) | 公開配置、変数・Secrets、監視、停止の手順を確認する |
| [Relay本運用への移行手順](relay-production.md) | 配置先未定の段階から、必要な実装変更、環境選定、検証、購読移行、監視・復旧、開始条件を確認する |

## 調査記録

| 文書 | 読むとき |
| --- | --- |
| [音声添付の調査・修正記録](audio-playback.md) | 音声表示・再生の修正経緯と当時の検証範囲を確認する |
| [下書き経由の投稿遅延](investigations/draft-post-latency-report.md) | 調査の結果、再現条件、原因特定の限界を確認する |
| [通知メンションの遅延・リアクションの連続読込](investigations/notification-mention-loading-report.md) | mstdn.jpでの空表示・遅延、絵文字リアクション非対応時の連続読込、共通カーソルと既読位置待機、端末なしの検証範囲を確認する |
| [misskey.ioのAPI互換性](investigations/misskey-io-api-compatibility.md) | 公開APIの観測、MastodonとMisskeyの認証・通知・リアクションAPIの違い、連合と直接ログインの範囲を確認する |
| [プロフィール文の改行](investigations/profile-note-line-breaks.md) | Unicodeの行区切りによる表示不具合、HTML変換の確認結果、U+2028の対応を確認する |
| [プロフィールの固定投稿](investigations/profile-pinned-status-position.md) | 固定投稿の追加時に先頭表示が外れる原因と、先頭表示・閲覧位置を維持する修正を確認する |
| [画像付き固定投稿のスクロール](investigations/profile-pinned-scroll-report.md) | 固定投稿4件の速度別テスト、ヘッダー境界での惰性停止の修正、実機での解決確認を確認する |
