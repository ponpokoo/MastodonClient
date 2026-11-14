# Android処理・パフォーマンス調査

2026-11-12。対象はNagisa 3.0.3、コミット`5d1fe84`の作業ツリー。Android本体を横断し、主要操作の実装・関連テスト・既存調査を静的に確認した。実機計測、Gradleビルド、テスト実行は未実施。処理構造の存在と、体感性能への影響を区別する。本記録の候補は未実装であり、改善率やANR・jankの原因を確定したものではない。

候補1・2・3・5・6・7の実装条件は[変更指示書](android-performance-change-instructions-20261112.md)を参照する。進捗はIssuesで管理し、本記録を進捗表として更新しない。

## A. 全体評価

優先候補は、無関係なDataStore更新に伴う設定JSONの重複解析、認証情報のMain上での復号、ホーム以外の画面のワードミュート再計算。次いでホームDBの全件置換と、会話取得後の投稿単位のイベント配信が負荷を増やす。

| 操作 | 追跡結果 |
| --- | --- |
| 起動→ホーム | MainSessionViewModelで復元、TimelineViewModelで保存済み表示後に通信更新。設定解析・認証復号に改善余地 |
| スクロール・更新・追加取得 | 安定key、取得中ガード、カーソル管理あり。保存費用と長時間の一覧保持が候補 |
| 投稿詳細へ遷移 | seed・会話キャッシュを先行表示し、投稿とcontextを並列取得。取得後のイベント配信に改善余地 |
| 詳細から復帰 | ホームViewModelを保持し、復帰だけでホーム全体を再取得しない。通知の復帰経路は別途要確認 |
| タブ切替 | ViewModelを保持。プロフィール・探索は取得済み判定あり。非表示画面の派生計算は継続 |
| お気に入り等 | StatusActionManagerを共有。楽観更新・復元・セッション照合あり。一覧への適用は全体走査 |
| 画像・メディア | 共有ImageLoader、アバター期限管理、プレビュー選択あり。動画準備の開始条件は計測候補 |
| 通知・背景処理 | 先読み・既読管理・単一Streaming接続・Push世代管理あり。復帰トリガーの統合余地 |

## B. 改善候補（優先順）

工数は回帰テスト調整込みの概算。小＝半日〜1.5人日、中＝2〜4人日、大＝5人日以上。全候補の体感効果は未計測。

### 1. 設定JSONの重複解析 — 高／小

[UserPreferencesStore.preferences](../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/preferences/UserPreferencesStore.kt)はcold Flowで、DataStore発行ごとに各購読者が設定JSONを解析する。[AvatarValidationStore](../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/image/AvatarValidationStore.kt)も同じDataStoreへ書く。設定自体が不変でもアバター検証時刻・絵文字履歴等の更新で解析される構造を確認した。

設定に必要な保存値を抽出し、解析前にdistinctUntilChangedを適用する。共有化は残る重複が問題になった場合の代替案。CPU・割り当て削減を期待する。旧設定のフォールバックを漏らすリスクがある。多数のアバター初回表示時の解析回数・Main時間・割り当てを比較する。

### 2. 認証情報のMain上での復号 — 高／小〜中

[SecureAuthStore](../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/security/SecureAuthStore.kt)のgetSessions/getSessionはDataStore取得後に呼び出し元で復号し、observeSessionsのmapも実行先を変更しない。[KeystoreCipher](../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/security/KeystoreCipher.kt)の初期化も同期処理。MainSessionViewModelの復元、詳細のセッション照合からMainで呼ばれる経路を確認した。

Store側で暗号処理と初期化をMain外へ移す。まずキャッシュを追加せずスレッド配置だけを変える。起動・遷移時のMain占有削減を期待するが、所要時間は要実測。削除・再認証との順序を維持する必要がある。実Keystoreで初回起動と詳細往復を測る。既存Store単体テストの暗号処理は差し替えられており性能保証ではない。

### 3. ホーム以外のワードミュート再計算 — 高／中

[ModerationFilters.matchesWordMute](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/ModerationFilters.kt)はワード設定時にHtmlCompat.fromHtmlで本文変換する。[ModeratedState](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/ModeratedState.kt)はEagerlyで派生状態を計算し、value読み取りでも再評価する。通知・プロフィール・検索・詳細等で本文が不変でも再解析する。ホームにはTimelineModerationCacheが既にある。

上限付き本文変換キャッシュから始め、必要なら一覧・条件が同じ場合の判定結果も再利用する。CPU・GC負荷削減を期待する。編集・CW・ワード変更・フィルター期限・セッション変更の失効漏れがリスク。変換回数とフレーム時間を比較する。購読停止だけで即時の非表示保証を壊さない。

### 4. ホームDBの全件変換・置換 — 中〜高／中

[RoomHomeTimelineLocalDataSource.mutate](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/HomeTimelineLocalDataSource.kt)はStreaming追加・編集・削除やページ保存で最大500行をデコードし、全削除・再エンコード・挿入する。applyStatusUpdatesは変更行だけ書くが対象判定は全行デコード。処理はIO上でありMainブロックとは断定しない。

変更なしの保存省略、payload再利用、差分書き込みを先に検討する。元投稿ID列による局所検索は必要なら次段階。CPU・書き込み量・保存待ちの削減が候補。ブースト、Gap、閲覧位置、通信中の操作、削除後の書き込み拒否が回帰リスク。500行に連続イベントを投入し、JSON件数・更新行数・トランザクション時間を測る。

### 5. 会話取得後の投稿単位の更新配信 — 中／中

[StatusDetailViewModel.loadConversation](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/detail/StatusDetailViewModel.kt)は取得会話の各投稿をStatusUpdatedとして配信する。TimelineViewModel等は都度一覧を走査し、詳細自身も会話全体へ再適用する。会話C件・一覧N件なら概ねC×N、詳細自身ではC²の走査になり得る。StateFlowの同値抑制があっても走査・割り当ては残るが、配信数と再Composition数は同一ではない。

変更をまとめIDで参照して各一覧へ一巡で適用する。小さい代替案は変更なし・無関係な一覧への適用省略。イベント順序、楽観更新、ブースト反映がリスク。20/100/300件の会話でイベント数・走査・Main時間を比較する。StoredイベントはホームDBの再保存を既に避けている。

### 6. 通知画面再生成時の重複更新要求 — 中／小〜中

[NotificationsLifecycleEffect](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/notifications/NotificationsLifecycleEffect.kt)の二つのobserverは再生成時にwasVisible=false、stopped=trueから開始する。通知タブ選択中、既存ViewModelを再利用しhasObservedStart=trueなら、同じRESUMEから可視化更新と復帰更新が要求される。通信中なら追加更新が予約される。条件付き経路の確認であり、通常の背景復帰すべてで二重になるという意味ではない。

同じライフサイクル復帰の要求を入口で統合する。後着通知・手動更新の追加取得は維持する。該当時のAPI・保存回数削減が候補。未読ハイライト・後着通知の取りこぼしがリスク。通知→詳細→通知、Activity再生成、遅延応答中の通知タップで要求数を検証する。

### 7. StreamingのMain上でのJSON解析 — 中／小〜中

[DefaultTimelineRepository.observeUserStream](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultTimelineRepository.kt)のmapNotNullはJSON解析・モデル変換を収集側のMainで実行する。SSE読み取り自体はIO。バースト時のMain占有が懸念されるが時間は未計測。

解析・変換を上流のDefault Dispatcherへ移し、UI反映はMainに保つ。順序・キャンセル・キャッシュ同期を維持し、conflateや間引きで削除・編集を捨てない。合成SSEで解析スレッド、イベント順、入力応答・フレーム時間を比較する。

### 8. インライン動画の準備開始 — 低〜中／中、計測先行

[InlineVideo](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/media/InlineVideo.kt)はfactoryで無条件にsetVideoURIし、可視範囲・RESUMED判定はstart/pauseに使用する。Surface等の条件が揃えばprepareへ進む構造。画面外の無駄な通信・デコーダー確保の規模は未確認。

表示許可または狭い先読み範囲に入ってから準備する案を比較する。通信・リソース削減の可能性と再生開始遅延のトレードオフがある。高速スクロールのprepare数・受信量・初回動画フレーム時間を測り、先読みが有効なら維持する。

### 9. 表示用リストの長時間保持 — 低〜中／大、計測先行

[TimelineViewModel](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/timeline/TimelineViewModel.kt)と[NotificationsViewModel](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/notifications/NotificationsViewModel.kt)の一覧は追加取得で増える。DB上限はViewModelに適用されない。通知mergeでは既存一覧も日時解析・ソートする。直ちに参照リークを意味しない。

500/2,000/5,000件でHeap・GC・更新時間を測り、問題がある場合だけ閲覧位置と取得境界を守る範囲管理を検討する。単純な件数切り捨ては戻りスクロールを壊すため不可。日時解析の再利用は小工数の代替案。

## C. 最優先案

1→2→3を先行候補とする。設定値抽出後の変更判定、Storeのmain-safe化、本文変換の上限付き再利用は、保存形式・鮮度・表示仕様を変えず着手できる。4のDB差分保存は整合性の確認範囲が広いため後段とする。詳細は変更指示書を参照。

## D. 計測・検証

同一端末・投稿データ・画像・通信条件で変更前後を交互に測定する。初回とキャッシュ済みを分け、端末/OS/リフレッシュレート/ビルド条件/反復回数を記録する。R8有効Releaseの体感評価とDebugの原因分析を混同しない。

Perfetto、Android Studio CPU/Memory Profiler、`adb shell dumpsys gfxinfo io.github.ponpokoo.mastodonclient framestats`、`adb shell dumpsys meminfo io.github.ponpokoo.mastodonclient`を使用する。関数別の回数は実装作業時の診断カウンター等で測る。初回Activity描画とホーム投稿表示完了を別々に記録する。継続測定が必要ならMacrobenchmarkを検討するが、導入を前提条件にしない。

| 既存テスト | 内容を確認した保証対象（今回未実行） |
| --- | --- |
| AccountDisplayStoreTest / ReauthorizationStoreTest | 選択・認証情報の保持、削除・再認証後の古い書き込み拒否 |
| TimelineModerationCacheTest / WordMuteIntegrationTest | 本文/CW/ブースト/通知、解除時の通信なし復元、期限・アカウント分離 |
| HomeTimelineCacheDeviceTest | ブースト識別、Gap・位置、キャンセルロールバック、通信と操作の順序 |
| StatusDetailActionsTest | 詳細とホームの反応一致、失敗時復元、詳細終了後の操作完了 |
| NotificationPrefetchRefreshTest / NotificationRefreshLifecycleDeviceTest | 後着通知、追加更新集約、Predictive Backプレビューでの不要更新防止 |
| PushSyncRepositoryTest | バースト集約、短いページ、失敗再開、古いセッション応答排除 |

## E. 現状維持が適切な箇所

- ホームのキャッシュ先行表示と通信再検証、詳細の投稿/context並列再検証は鮮度のため維持する。
- タブViewModel保持、セッション切替時のキャンセル・世代照合、投稿操作管理の共有は維持する。
- 主要LazyColumnの安定key、snapshotFlow/derivedStateOfは実装済み。一律の安定性アノテーション追加やUI分割の根拠はない。
- アバターのURL単位集約・同時実行制限・24時間期限・旧画像保持、絵文字の寸法保持・描画集約は維持する。[既存絵文字調査](custom-emoji-scroll-jank.md)の修正前の問題を未修正として扱わない。
- Room DAOは主にsuspendによる明示取得。Room Flow無効化による再取得増幅は確認していない。位置インデックスは計測なしに追加せず、JSON・全件置換より優先する根拠は不足。
- PushのUnique Workと世代管理は維持する。キュー数だけを理由にKEEPへ変更すると後着Pushを失う可能性がある。
- 確認した動画・音声経路には停止・解放がある。明確なActivityリークは特定していない。Heap測定による不存在の証明はしていない。

## 参考

- [Android Coroutine推奨設計](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [Compose性能の推奨事項](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [Macrobenchmark測定指標](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [AOSP VideoView](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/widget/VideoView.java)
- [開発・検証方針](../project-setup.md)
