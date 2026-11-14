# パフォーマンス改善の変更指示書

2026-11-12作成。[調査記録](android-performance-audit-20261112.md)の候補番号を維持する。対象は1・2・3・5・6・7。未実装の作業指示であり、実装済み仕様ではない。4・8・9は本指示の対象外。

## 共通条件

- 着手時に対象コード・既存テストを再確認する。調査基点は`5d1fe84`。
- 既存の表示、操作、取得鮮度、セッション分離、保存形式を維持する。不要な依存・抽象化・大規模なRepository統合を追加しない。
- 実装と測定を候補ごとに分け、成功した確認を理由なく繰り返さない。単なる短縮や再Composition回数だけで高速化を宣言しない。
- 計測用ログにトークン・投稿本文・アカウント情報を出さない。測定用変更は本番経路から除くか既存の診断方針に従う。
- 変更後もアカウント切替、再認証、ログアウト、遅延応答、キャンセルを確認する。UI→ViewModel→Repository→data sourceの境界を保つ。
- 推奨順序は1→2→3、その後5・6・7。5の改善を評価するときは3による削減と混同しない。

## 1. 設定JSONの不要な解析を止める

対象：UserPreferencesStore.preferences / decodeAppPreferences、購読元MainActivity・AppNavigation・MainSessionViewModel・DefaultWordMuteRepository。アバター検証情報も同じDataStoreに保存される点を再確認する。

### 変更手順

1. DataStoreのPreferences全体から、APP_PREFERENCESと旧設定フォールバックに必要な値だけを抽出する。
2. 抽出値へdistinctUntilChangedを適用してからJSON解析・正規化を行う。解析後だけの比較では目的を達成しない。
3. 初期値、破損JSON、旧設定、削除済みenum値の扱いを維持する。
4. 残る購読者間の解析重複を測定する。必要と判明した場合だけ、明確な所有スコープで結果を共有する。Storeごとの無期限scope追加はしない。

### 受入条件・検証

- 無関係なキーの連続更新では、購読開始時を除いて設定JSONを再解析しない。設定変更は即時反映される。
- 旧設定からの復元、既定値、破損入力の回復、Store再生成が従来どおり。
- ReadingPreferencesTest、TimelineDisplayPreferencesTest、AccountDataRemovalTest等の影響する既存テストを選択する。無関係なキー更新時の解析抑制と必要な設定変更の伝達を追加検証する。
- アバター未検証の連続スクロールで解析回数・Main時間・割り当てを比較する。共有化前後の比較は別に行う。

## 2. 認証StoreをMainから安全に呼べるようにする

対象：SecureAuthStoreのgetSession/getSessions/observeSessions/read/writeとKeystoreCipher初期化、呼び出し元の起動・詳細セッション照合。

### 変更手順

1. 暗号化・復号・JSON処理の呼び出し元スレッドを整理し、Storeにテスト可能なDispatcherを渡す。
2. 同期Keystore処理をMain外へ移す。withContextだけでなく、コンストラクターでのloadも対象にする。Flowの上流変換にも適切な実行先を設定する。
3. DataStoreのedit内で行っている最新資格情報の照合・原子的更新を外へ移さない。
4. 初回は認証結果の独自キャッシュを導入しない。複数呼び出しが並行化するため、Cipher/KeyStoreの共有と鍵初回生成の競合を確認する。必要な同期だけを設ける。
5. キャンセル伝播、旧形式復元、暗号化失敗時の既存挙動を維持する。

### 受入条件・検証

- 起動・セッション照合・observeSessionsの復号とKeystore初期化がMainで実行されない。
- 切替・削除・再認証後に古いセッションや遅延書き込みを採用しない。単なる時間短縮のため照合回数を削らない。
- AccountDisplayStoreTest、AccountOrderStoreTest、ReauthorizationStoreTest、AccountDisplaySynchronizerTest、関連する詳細/アカウント切替テストを先行する。認証共有基盤の変更として開発ガイドに従い単体テスト全体・Debugビルドへ広げる。
- テストDispatcherで実行先とキャンセルを検証し、端末の実Keystoreで初回鍵作成・復元・詳細往復を確認する。Main時間と総遅延を分けて測る。

## 3. ワードミュートの本文変換を再利用する

対象：ModerationFilters.matchesWordMute/moderationPlainText、通知・プロフィール・検索・詳細等のwithModeration。TimelineModerationCacheは既存対策として保持する。

### 変更手順

1. 最初はHTML文字列→プレーンテキスト変換だけを、上限付きで再利用する。画面/セッションの所有関係を明確にし、投稿・Activity・Painterをキャッシュしない。
2. 本文が同じなら反応件数やloadingフラグ変更でHTML解析し直さない。異なる本文には必ず新しい変換を使う。
3. ワードの一致判定、CW、サーバーフィルターの期限・変更、アカウント単位の条件は毎回正しく反映する。
4. 判定結果全体のキャッシュは変換回数の削減後も問題が残る場合のみ。採用時は投稿一覧・本文・CW・ワード・フィルター・期限・セッションを失効条件として検証する。
5. moderated.valueの即時性やミュート解除時の元データ復元を維持する。Eagerlyを一律に停止する変更は含めない。

### 受入条件・検証

- 同じ本文の状態変更では変換を再利用し、編集本文は即座に再解析する。上限超過で安全に退避できる。
- ワード追加/削除、大小文字、HTMLエンティティ、CW、ブースト、投稿なし通知、セッション切替、フィルター期限切れの表示が従来どおり。
- TimelineModerationCacheTest、WordMuteIntegrationTest、ServerFilterIntegrationTest、AccountModerationIntegrationTestと対象画面のテストを使う。
- 通知200件・会話100件でワード0/20件を比較し、変換回数、Main時間、GC、フレーム時間を記録する。

## 5. 会話取得後の更新をまとめて適用する

対象：StatusDetailViewModel.loadConversation/applyStatusChange、BrowsingSession、イベント受信側Timeline/Notifications/Profile/Search/SavedTimelines/HashtagのViewModel。

### 変更手順

1. 全イベント受信箇所と、通信中変更のバッファ処理を列挙する。Stored/Confirmed/Optimistic/RolledBackとfieldの意味を保持する。
2. 会話取得で得たStored更新を一つのバッチとして配信し、受信側はstatusIdで参照して一覧を一巡する案を第一候補とする。規模が過大なら、変更なしの更新・無関係な一覧の走査省略から段階的に進める。
3. バッチ内の同じ投稿に対する順序とフィールド単位の更新を保つ。単純なassociateByで異なるフィールドの更新を消さない。
4. ブーストのtimelineId/boostedBy/時刻を保持する。詳細自身への適用も一巡とし、同時操作を消さない。
5. Stored更新をホームDBへ再保存しない。通信中バッファへの追加・再適用もバッチを扱えるようにする。全受信側の対応を確認する。

### 受入条件・検証

- 一つの会話取得の適用は各一覧で概ね一巡となり、無関係な投稿の値は不変。
- 遅い詳細応答で新しいお気に入り/ブースト/リアクションを戻さない。失敗時復元、詳細を閉じた後の操作完了、別アカウントへの混入防止を維持する。
- StatusDetailActionsTest、StatusActionUpdateTest、TimelineViewModelTest、NotificationCacheUpdatesTest、関連画面の投稿更新テストを先行。共有イベント変更として単体テスト全体とDebugビルドも確認する。
- 会話20/100/300件でイベント数・走査回数・割り当て・Main時間を測る。同値抑制と再Composition回数は別々に扱う。

## 6. 通知の同一復帰トリガーを統合する

対象：NotificationsLifecycleEffectの二つのobserver、NotificationsViewModel.onNotificationsVisible/onLifecycleStarted/onAppForeground/loadCategory。

### 変更手順

1. まず通知タブ選択・既存ViewModel・Composition再生成・hasObservedStart=trueの条件をテストで再現し、同一RESUMEからの要求を数える。
2. 可視化と復帰を同じライフサイクルイベントに対して一度だけ処理する。入口で復帰理由を判別する等、最小の変更を選ぶ。単純な時間TTLによる抑制はしない。
3. 非表示タブでも従来必要な前景復帰更新を維持する。通知表示時のkeepHighlightsと、復帰時の他カテゴリ更新の意味を整理して保持する。
4. 進行中通信より後の通知タップ・手動更新・別の復帰には、既存のpendingRefreshesによる追加一回を残す。
5. STARTEDだけのPredictive Backプレビューでは更新・既読化・ハイライト消去を起こさない。

### 受入条件・検証

- 同じRESUMEの重複要求だけを除去し、後着通知や利用者の更新要求を捨てない。
- NotificationPrefetchRefreshTest、NotificationRefreshLifecycleDeviceTest、NotificationPaginationAndReadTest、NotificationCacheRestorationTestを確認する。
- 通知→詳細→戻る、Activity再生成、通常背景復帰、タブ往復、通信中の手動更新/通知タップ、キャンセルしたPredictive Backを網羅する。
- API回数と表示内容を併せて比較する。要求数が減っても後着通知が出なければ不合格。

## 7. Streamingの解析・変換をMain外へ移す

対象：DefaultTimelineRepository.observeUserStream、MastodonStreamingDataSource、MainSessionViewModel.startStreaming。

### 変更手順

1. SSE読み取り、JSON解析、DTO→domain、キャッシュ更新、UIイベント配信の境界を整理する。
2. 解析・変換を適切なDispatcherへ移す。flowOn等を使う場合は、その上流範囲・バッファ・キャンセル時の動作を明示する。UI側のmutable state操作はMainに保つ。
3. 逐次順序を維持する。イベントごとの無制限launch、conflate、debounce、DROP方式で編集・削除・通知を捨てない。
4. statusCache等の共有データのロックを確認し、Mainからの参照と競合しないことを確認する。
5. 既存callbackFlowのtrySend失敗も高負荷時に観測する。今回の変更でバッファ溢れが見つかった場合は、損失を隠さず別の必要修正として扱う。

### 受入条件・検証

- JSON解析・モデル変換がMain外で動き、UI反映は順序を保つ。切替/背景停止後に古いイベントを採用しない。
- MastodonStreamingDataSourceTest、MainTabsIntegrationTest、ServerSettingsSessionTest、TimelineViewModelTest等の影響箇所を確認し、共有通信経路として単体テスト全体・Debugビルドへ広げる。
- 合成SSEで追加→編集→削除、通知、filters_changed、切断・再接続、バースト中キャンセルを検証する。投入数/受信数と最終状態を確認する。
- スクロール中の合成バーストでMain時間・フレーム時間・イベント反映遅延を比較する。総CPU削減とMainからの移動を区別して報告する。

## 完了判定と提出内容

各変更について、変更前の再現条件、変更後の同じ条件での結果、実行したテスト、未実行の端末確認、リスクをPR/コミット説明に記載する。既存CIの変更パスに含まれるか、コマンド・ツール版の変更が必要かを確認して判断を短く記載する。CIの新しい性能閾値や依存導入は本作業の前提にしない。

性能差が測定ノイズの範囲なら、その旨を記載する。機能回帰がなく不要処理の削減を確認できても、体感高速化を測定していなければ断定しない。仕様・セットアップが変わらない内部改善は、通常のガイドやリリース計画へ履歴を追記しない。
