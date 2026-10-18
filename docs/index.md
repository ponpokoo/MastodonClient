# ドキュメント一覧

目的に応じて以下の文書を参照する。現行仕様・設計判断・過去の調査や検証記録は、それぞれの文書で確認する。

## 開発・仕様・設計判断

| 文書 | 読むとき |
| --- | --- |
| [実装ルール](../AGENTS.md) | 実装の境界、変更時の確認、ADRの記録基準を確認する |
| [開発ガイド](project-setup.md) | 開発環境、セッション管理、保存方針、ビルド・テストの選び方を確認する |
| [変更範囲に応じたCI](project-setup.md#変更範囲に応じたci) | push／PRの自動確認、手動再実行、結果の見方と次回以降の見直し事項を確認する |
| [R8適用済みReleaseの自動確認](project-setup.md#r8適用済みreleaseの自動確認) | 公開前の独立したUIテスト、確認先サーバーと専用端末の指定、Debugテストとの役割分担を確認する |
| [UI・機能仕様](ui-guidelines.md) | 共通ルール、テーマ、共通操作と機能別仕様への入口を確認する |
| [閲覧・タイムライン](ui/timelines.md) | 探索・検索、タグ、保存済み投稿、ホームの取得・位置復元を確認する |
| [投稿表示・メディア](ui/status-media.md) | 投稿カード・詳細、画像・音声・動画、投稿操作を確認する |
| [アカウント・プロフィール](ui/accounts-profile.md) | ログイン、アカウント管理、プロフィール表示・編集、注目のハッシュタグによる絞り込みを確認する |
| [通知](ui/notifications.md) | 受信、一覧の取得、新着・既読判定と復元を確認する |
| [投稿・下書き](ui/compose-drafts.md) | 投稿入力、添付、返信・引用、下書きの保存と呼び出しを確認する |
| [設定・フィルター・ミュート](ui/settings-moderation.md) | 設定の初期値、同期、フィルター、ミュート・ブロック・報告を確認する |
| [リスト・ブックマーク・お気に入り](ui/timelines.md#リストブックマークお気に入り) | リスト管理の対象外範囲、投稿操作、更新・追加取得・失敗時の再試行と状態保持を確認する |
| [ホームの位置保持と未取得区間](ui/timelines.md#ホームの新着表示と位置復元) | 更新時の位置保持、Gapの手動補完、復帰時の表示を確認する |
| [ログインとアカウント追加](ui/accounts-profile.md#ログインとアカウント追加) | 一画面のサーバー確認・ブラウザー認証、入力変更と再試行、追加の戻る操作を確認する |
| [自アカウントの表示情報同期](ui/accounts-profile.md#登録済み自アカウントの表示情報) | 起動・切替・プロフィール取得／編集後の名前・アイコン更新と状態保持を確認する |
| [登録済みアカウントの管理](ui/accounts-profile.md#登録済みアカウントの管理) | Play公開整備のIssue #10・#11、削除確認、共通ドラッグ操作、保存順と追加・再認証・削除後の動作を確認する |
| [通知一覧の取得・新着判定](ui/notifications.md#通知の表示更新既読位置) | 種類別取得、40件のページ、対応判定、既読待機と再起動後の新着判定を確認する |
| [ミュート・ブロック・報告](ui/settings-moderation.md#ミュートブロック報告) | メニュー、設定・解除、操作後の非表示、報告、端末内ワードミュートと公式フィルターv2の管理・未対応時の扱いを確認する |
| [設計判断（ADR）](adr/README.md) | 重要な設計の背景、採用理由、制約、見直し条件を確認する |
| [ライセンスと第三者通知](third-party-licenses.md) | アプリ内のライセンス表示、Apache 2.0適用範囲、Release依存の収録内容と再生成・公開前確認を参照する |
| [更新・リリース計画](release-plan.md) | 2.5.4の対象範囲、配布前の確認、今後の候補、Play配布と記録の手順を確認する |
| [内部リリースノートの保管](release-plan.md#内部リリースノートの保管) | Git管理外のノートへ成果物・確認結果・公開状況をまとめ、バックアップする方法を確認する |
| [Google Play公開までのワークフロー](google-play-publication-workflow.md) | 公開準備、必要な文書・申告、Play配布版の確認、限定テストと一般公開の判断を確認する |
| [公開ポリシーと問い合わせ窓口](https://ponpokoo.github.io/nagisa-policy/) | プライバシー、利用規約、安全基準、削除案内と連絡先を確認する |
| [GitHub Issues](https://github.com/ponpokoo/MastodonClient/issues) | 移行済みの継続確認・改善候補と対応の進捗を確認する |

## Push・Relay

まず[ハイブリッド配送の採用仕様](../relay/workers/hybrid.md)で現在の本番方式を確認する。
小通知はinline、大通知はsync_requiredを使い、Relayに本文を保存せず、永続再送を行わない。
本番切替と対応APKの配布・運用性能の確認は区別する。確認済みの範囲と実運用での確認事項は[採用後の対応と確認](../relay/workers/hybrid.md#採用後の対応と確認)を参照する。

### 現行仕様

| 目的 | 文書 |
| --- | --- |
| 本番Relayの配送・保存・失敗時の動作を確認する | [ハイブリッド配送の採用仕様](../relay/workers/hybrid.md) |
| 登録API・FCMエンベロープ・各版の互換性を確認する | [共通通信契約](relay-protocol.md) |
| Androidの設定・購読・解除・再開を変更する | [通知設定と購読管理](push-settings.md) |
| AndroidのFirebase接続・受信・復号・表示・差分同期を変更する | [Android接続と受信処理](push-reception.md) |

### 開発・運用手順

| 目的 | 文書 |
| --- | --- |
| Workers版Relayを開発・起動する | [Workers版README](../relay/workers/README.md)（本番ハイブリッドと旧保存経路の入口・ビルドを区別する） |
| 旧v1契約のローカル模擬Relayを起動する | [ローカルv1模擬版](../relay/README.md)（登録v2・実FCM送信は未対応） |
| Workersを配置・更新・停止・復旧する | [Workers配置・運用・復旧](../relay/workers/setup.md)（旧入口の新規配置と本番ハイブリッド更新を区別する） |
| 公開条件・監視方針・購読移行を確認する | [本運用への移行手順](relay-production.md)（準備・移行の経緯と旧経路の手順を含む） |

### 検証手順・記録・設計判断

| 目的 | 文書 |
| --- | --- |
| 自動テストを実行する・過去の調査や測定を探す | [検証ガイド](../relay/workers/testing.md)（テスト配置・測定ツール・環境準備・各記録への入口） |
| エミュレーターで実FCMのハイブリッド配送を試す | [専用Worker・D1・FCM設定とAndroid購読の切替](../relay/workers/hybrid-live-setup.md) |
| 4KiBの合成Pushで実FCMの大通知経路を試す | [一時登録・通知音のミュート・隔離した同期処理](../relay/workers/hybrid-live-setup.md#合成大通知の実fcm確認) |
| 規模増加や問題発生時に再検証の条件を選ぶ | [100人を想定した再測定計画](relay-100-user-remeasurement-plan.md)（当初の条件・合格基準を保持した参照資料） |
| 本番ハイブリッド切替の結果・差し戻し先・旧APKへの影響を確認する | [2026-10-08切替記録](investigations/relay-production-hybrid-cutover-20261008.md)・[直接切替の判断](adr/0015-production-hybrid-cutover.md) |
| 実FCMの通信量・表示遅延の測定結果を読む | [2026-10-08 小通知5件の測定](investigations/relay-live-fcm-measurement-20261008.md)（匿名結果・時計のずれ・測定範囲） |
| 責務分担・鍵紐付け・ハイブリッド採用・Android同期の判断を確認する | [ADR一覧](adr/README.md)（Push・Relay関連の判断と置き換え履歴） |

実測値は各測定記録、設計の採用理由は[ADR一覧](adr/README.md)を正本とし、入口の文書には重複して転記しない。

## 調査・継続確認

未解決の問題、残る互換性・保持方針の確認、採用前の試作を参照する。継続対応の進捗はIssuesで管理する。

| 文書 | 読むとき |
| --- | --- |
| [UI試作アプリ（試作ブランチ）](https://github.com/ponpokoo/MastodonClient/blob/codex/ui-prototype/ui-prototype/README.md) | 開発先は`codex/ui-prototype`。本体UIを取り込んだ4画面の試作、再現範囲・ビルド手順を確認する。未適用の差分と移植手順は同ブランチの[UI変更履歴と本体への移植](https://github.com/ponpokoo/MastodonClient/blob/codex/ui-prototype/ui-prototype/ui-changes.md)を参照する |
| [サーバー保存設定の取得・反映](investigations/server-account-settings-sync.md) | 同期対象6項目と閲覧注意関連の端末設定3項目は実装済み。採用した取得・更新方針、公式フィルターと端末内ワードミュートの併用、未確認の実サーバーでの他クライアント変更・権限・API・Streaming互換性を確認する。現行仕様は[UI仕様](ui/settings-moderation.md#サーバー保存設定と公式フィルター)を参照する |
| [下書き経由の投稿遅延](investigations/draft-post-latency-report.md) | 原因未確定。サーバー側の仕様によるものかも未確定として、調査結果・再現条件・原因特定の限界を確認する |
| [アカウント削除後の残る保持条件](investigations/account-removal-data-retention.md#4-push解除待ちの保存期間に上限がない) | ローカルデータの回収・再試行は実装済み。未実装のPush解除待ちの終了条件・保存期限と、旧孤立添付・共有画像キャッシュ・OAuth失効・過去のバックアップに関する対象外の範囲を確認する |
| [misskey.ioのAPI互換性](investigations/misskey-io-api-compatibility.md) | 2026-10-06の公開API観測と互換性判断を参照する。Misskey直接対応は未実装。連合経由の投稿表示と直接ログインを区別する |

## 完了した修正・改善

実装・確認を終えた範囲と、当時の調査・判断を参照する。対象外の機能や未確認の実運用まで完了扱いにしない。

| 文書 | 読むとき |
| --- | --- |
| [段階的リファクタリング計画](refactoring-plan.md) | 9候補の実装・実機確認は完了。採用した実装順・変更範囲・過剰設計を避ける制約・検証条件を参照する |
| [通知一覧の取得・新着判定の修正](investigations/notification-mention-loading-report.md) | 種類別40件取得、対応不明時のリアクション探索停止、既読取得との分離、閲覧記録と新着判定は実装済み。修正前の原因候補と調査テストを参照し、実アカウントでの症状解消の確認とは区別する。現行動作は[通知仕様](ui/notifications.md#通知の表示更新既読位置)を参照する |
| [アカウント削除時のローカルデータ回収](investigations/account-removal-data-retention.md#実装後の扱い) | 改善対象1〜3の回収・遅延処理の拒否・削除失敗時の再試行は実装・検証済み。削除対象と統合前の検証範囲を確認する。Push解除待ちの期限などは上記の継続確認を参照する |
| [UI修正の要点](investigations/resolved-ui-fixes.md) | 音声・プロフィールの改行・固定投稿・惰性スクロール・検索復帰の原因、修正理由、最終確認の範囲を確認する |
| [自プロフィール画像の点滅](investigations/profile-image-refresh-flicker-report.md) | 解決済み。再取得・旧画像保持の修正に至った原因と、当時の調査記録を参照する |

UIの詳細な調整履歴の要点は[UI修正の要点](investigations/resolved-ui-fixes.md)へ統合した。再現用テスト、未解決の調査、設計判断のADRは別途保持する。
