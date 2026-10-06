# mstdn.jpの通知一覧：メンション遅延・リアクションの連続読込の調査

調査日：2026-10-06（日本時間）

同日後続の実装修正では、種類別40件取得、対応不明のリアクション探索停止、一覧と既読取得の分離、
閲覧記録の保存と境界不明時の全件新着判定の抑止を追加した。
現行動作は[通知仕様](../ui-guidelines.md#通知の表示更新既読位置)、判断は[ADR 0007](../adr/0007-notification-category-pages-and-read-state.md)を参照。
以下のコード位置・未実装候補・検証結果は調査時点の記録であり、修正後の結果とは区別する。

## 結論と対象

利用者が確認した症状は、アプリ内の「通知 → メンション」が空になる、または表示が遅いこと。再ログイン後も発生する。端末が接続されていない前提で、現在の作業ツリー、公開API、端末不要の単体テストを調べた。

アプリ側には、症状を説明できる次の条件がある。実アカウントの応答を取得していないため、今回の発生原因を一つに確定したものではない。

1. メンション専用取得がなく、全種類の通知を最大80件ずつ取得してから表示を絞り込む。他の通知が多いと、メンションまで何ページも順に遡る必要がある。
2. 初回更新では保存済み履歴を最新の全通知ページで置き換える。このページにメンションがなければ、保存済みメンションが表示から外れる。
3. 初回は通知本体と既読位置を並行取得するが、画面への通知本体の反映は既読位置の完了を待つ。
4. 通知タブへ戻るだけでは最新情報を再取得しない。Streamingで取り逃した新着は、手動更新やアプリの前景復帰まで反映されない場合がある。

追加申告の「絵文字リアクション非対応のmstdn.jpで、リアクション通知を延々と読み込む」についても調査した。空の絞り込み一覧が末尾付近として扱われ、ページ更新ごとに全通知の自動追加取得を再開する構造が原因候補として強く一致する。詳細は後述の追加調査を参照。

対象はHEAD `b457b63bbc7a46210492a54461277f969d1174f3`、`2.4.0` / versionCode `15`の作業ツリー。調査開始時の差分は未追跡の`.idea/`のみ。利用者のインストールAPKと一致するかは未確認。アプリの本番コードは変更していない。調査用テストは通常のテスト対象から除外し、明示指定時だけ追加する。

## サーバーについて確認できたこと

2026-10-06 01時台（JST）の直接取得で、[mstdn.jpの公開インスタンスAPI](https://mstdn.jp/api/v2/instance)はHTTP 200、応答は以下だった。

| 項目 | 値 |
| --- | --- |
| `version` | `4.7.3` |
| `source_url` | `https://github.com/DimensionDev/maskodon/tree/stable-4.7-oauth` |
| `configuration.urls.streaming` | `wss://mstdn.jp` |
| 公開インスタンスAPIの1回の取得時間 | 約0.32秒 |

これは公開情報の観測であり、認証付き通知APIの速度、アカウント別の通知設定、Streamingの配信状態を検証した結果ではない。検索・ブラウジング側のキャッシュは`4.6.6`を返したため、上の値には直接取得した応答を使った。インスタンスが独自派生版を使っていることも考慮する。

公式通知APIでは`types[]`による種類指定が3.5.0から利用できる。`mention`が正式なメンション種別で、現コードの種類判定と一致する。したがって、公開された版情報から単純な旧バージョン非対応や`mention`の綴り違いを優先する根拠はない。[Mastodon公式通知API](https://docs.joinmastodon.org/methods/notifications/#get-all-notifications)

## 1. 全通知を取得してからメンションを探している

[MastodonApi.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonApi.kt) 211行の通知取得には`max_id`と`limit`しかなく、`types[]`はない。[DefaultTimelineRepository.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultTimelineRepository.kt) 369行から全件を変換し、[SocialScreens.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/timeline/SocialScreens.kt) 118・204行で`mention`または`reply`を表示対象にする。メンションタブの選択によってHTTP要求は変わらない。

例えば、直近のメンションより新しいお気に入り・ブースト等が400件あると、80件ずつ返る場合でもメンション到達には6ページ必要になる。これは説明用の条件であり、利用者の実件数ではない。サーバーがページ件数を制限すればさらに増える。各ページは直前ページのカーソルが必要なので逐次通信になる。

画面は絞り込み後に0件なら、履歴取得が完了していなくても「該当する通知はありません」を出す（SocialScreens 206行）。末尾付近で自動追加取得する処理と「さらに読み込む」はあるため、永続的に取得しない実装ではない。ただし空表示は「サーバーの全履歴にメンションがない」ことを意味しない。Composeの自動追加取得が利用者の画面で実際に何回発火するかは、端末なしでは検証していない。

## 2. 初回更新で保存済みメンションが表示から外れる

[NotificationsViewModel.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/notifications/NotificationsViewModel.kt) 98行でDBを先に表示し、130行で初回の通信結果へ置き換える。取得中のStreaming新着以外は、保存済み履歴をそのまま残さない。

「DBに古いメンションがある → 最初に見える → 最新全通知80件がすべてお気に入り → メンション一覧が空になる → 追加ページで再び現れる」という条件が成立する。履歴の未取得区間を飛ばさないための現行仕様であり、単純に古いキャッシュを結合すると順序・履歴の連続性を壊す。修正するなら種類別の取得位置を管理する必要がある。[通知の保存・更新仕様](../ui-guidelines.md#通知の表示更新既読位置)

ログアウトは対象アカウントの通知キャッシュと既読位置を削除する（[DefaultAuthRepository.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultAuthRepository.kt) 125行）。再ログインしても全通知から探す方式は変わらず、保存済み表示による補助も失われる。このため再ログインで改善しない症状と整合する。

## 3. 通知APIが終わっていても既読位置を待つ

NotificationsViewModel 93・96行では`GET /api/v1/markers`と`GET /api/v1/notifications`を並行開始する。しかし107行の`markerRequest.await()`が108行の通知応答反映より前にある。キャッシュがない場合、通知APIが成功していても既読位置の要求が終了するまで読み込み表示が残る。通知APIが失敗した場合のエラー表示も同じ待機の後になる。

既読位置の失敗は保存値へフォールバックするが、終了を待つ点は変わらない。両APIとDB読取りが独立に進む条件では、最初の通知反映は最も遅い処理に依存する。[ApiClientFactory.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/network/ApiClientFactory.kt)の通常クライアントには、アプリ独自の通信全体の`callTimeout`や既読位置専用の期限設定がない。実際の待ち時間は未計測。

この待機は初回・セッション切替後・再ログイン後に発生し、初回成功後の通常の手動更新では既読位置を再取得しない。すべての更新が遅い場合には、この条件だけでは説明できない。既読位置は一覧取得の必須データではなく、未読判定用の別APIである。[Mastodon公式Markers API](https://docs.joinmastodon.org/methods/markers/)

## 4. Streaming受信がないとタブへ戻っても新着を補わない

NotificationsViewModel 78行の`hasLoaded`判定により、一度成功した後の`onNotificationsVisible()`では再取得を省く。手動更新、アプリの前景復帰、Android通知タップでは強制取得する。通常のタブ切替では一覧と閲覧位置を保持する仕様。[通知の更新仕様](../ui-guidelines.md#設定と初期値)

[MainSessionViewModel.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/main/MainSessionViewModel.kt)はStreamingオフ・Wi-Fi条件不成立・設定に従った背景移行時に接続しない。切断時は2秒から最大30秒の待機で再接続するが、再接続成功時のREST取得はない。再接続の待機時間と欠落した通知の補完時間は別問題である。[MastodonStreamingDataSource.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonStreamingDataSource.kt)はSSEのみを使い、受信後のDTO変換失敗はRepositoryで`getOrNull()`により破棄される。

公式APIはSSEとWebSocketを記載しているため、公開された`wss://mstdn.jp`をHTTPSへ変換するコードだけで不具合と断定できない。認証付き接続の成立・実イベント受信は未確認。[Mastodon公式Streaming API](https://docs.joinmastodon.org/methods/streaming/)

## 5. 追加調査：非対応サーバーでリアクションを連続読込

### 結論

利用者が確認した「mstdn.jpは絵文字リアクション非対応」という条件では、現コードの自動追加取得が、存在しないリアクションを探して全通知履歴を遡り続ける。専用のリアクションAPIを繰り返し呼ぶのではなく、`GET /api/v1/notifications?max_id=...&limit=80`を繰り返す。再ログインでもこの処理は変わらない。

### 読込を繰り返す経路

1. SocialScreens 188行の`NotificationFilter.entries.forEach`は、全サーバーでリアクションタブを出す。対応可否を受け取る引数もない。
2. 同118–120行のフィルターは、リアクションを`type`に`reaction`を含む通知に限定する。`favourite`や`reblog`は対象外であり、お気に入りをこのタブに表示する設計ではない。[公式の通知種別](https://docs.joinmastodon.org/entities/Notification/#type)でも、お気に入りは`favourite`、ブーストは`reblog`として別種別である。
3. リアクションが0件でも、LazyColumnには空表示の1行と「さらに読み込む」またはローディングの1行がある（SocialScreens 206・257・273行）。この2行が表示されると、末尾付近の条件`lastVisible >= totalItemsCount - 4`を満たす。
4. 自動取得のLaunchedEffectは、絞り込み前の`state.notifications.size`と共通カーソルをキーに持つ（同158行）。全通知の次ページを追加すると、リアクション0件のままでもキーが変わり、Effectと`distinctUntilChanged()`の収集が作り直される。表示行のindexが前回と同じでも、新しい収集の初回値として追加取得が再発火し得る。
5. NotificationsViewModelの`loadNextNotifications()`は選択フィルターとサーバー対応可否を知らないため、全通知を追記して共通カーソルを進める。リアクション0件を理由には止めない。

これにより「空表示 → 次の全通知ページ → リアクションは依然0件 → Effect再起動 → 次の全通知ページ」という循環になる。空行・末尾判定・Effectのキーはコードで確認した。実Compose画面での再発火回数は端末なしでは測定していない。

### 停止条件と負担

完全な無限ループと断定するものではない。全通知APIが空を返す、次のカーソルが前回と同じになる、取得に失敗する、または画面・選択変更で自動取得のEffectが終了すると連続取得は止まる。選択変更だけでは、既に進行中のViewModelの追加要求はキャンセルされず、そのページの結果は共通一覧へ反映される。

ただし、有限でも数千件の通知履歴があれば長く続く。Repositoryの終了判定は全通知ページが空かどうかであり、種類別の0件ではない。これはサーバーによるページ件数制限を履歴末尾と誤認しないための判定で、単に「80件未満で終了」へ変更すれば別の履歴欠落を起こす。

追加取得ごとに全通知のdomain変換、投稿のメモリキャッシュ更新、共通一覧への追記、通知DBの保存を行う。通知DBの最新200件制限は、通信やViewModelの一覧件数を止める上限ではない。このため空のリアクションタブでも通信・処理・メモリの消費が続く。具体的な消費量やメンション表示への遅延量は未計測。

### 既存の対応判定は通知タブに届いていない

投稿モデルには`supportsEmojiReactions`があり、Repositoryは投稿の`emoji_reactions`フィールドの有無で設定し、投稿のリアクション操作ボタンはこの値で出し分ける（DefaultTimelineRepository 879行、HomeTimelineScreen 1928行）。Push購読にも`fedibird_capabilities`から追加通知種別を判定する別経路がある。しかし、どちらも通知タブの表示や自動追加取得の制御には使用していない。

今回のテストでは、すべての投稿が`supportsEmojiReactions=false`、リアクション通知が0件の状態でも、25ページ・2,000件の全通知を追加でき、26回目の空応答で初めて終了した。各`loadNextNotifications()`はテストから明示的に呼び出したため、これはViewModelの停止条件の確認であり、Composeの自動呼出しそのものの実行テストではない。

### 修正方針（実装候補・未実装）

- 2026-10-06の利用者指定により、当面のリアクション通知のサポート範囲は、Instance APIの`fedibird_capabilities`に`emoji_reaction`が含まれるサーバーとする。ドメイン名でFedibird系を判定したり、mstdn.jpを特別扱いしたりしない。この条件はNagisaのサポート範囲を決めるもので、サーバー機能そのものの有無を断定するものではない。
- `emoji_reaction`の対応情報を取得できない場合は、リアクションタブを残して「現在サポートしていません」と表示する。フィールドの欠落、一覧に`emoji_reaction`がない場合、Instance APIの取得失敗を含め、対応を確認できない場合の実装候補として記録する。
- この表示中は、リアクションタブからの自動追加取得と「さらに読み込む」による取得を行わず、全通知履歴からリアクションを探し続ける処理を止める。対応情報の確認中も、そのタブからの履歴探索は開始しない。「すべて」「メンション」の取得や共通の全通知一覧の終了判定には、この制限を適用しない。
- 対応情報はdomainモデル・Repository経由で通知ViewModelとUIへ渡す。通信失敗を永続的なサーバー非対応判定として保存せず、対応情報を再確認できるようにする。アカウント切替時は対象インスタンスとセッションを照合し、以前の判定や遅延応答を引き継がない。
- 対応サーバーでは種類別の取得・カーソルを管理する。サーバーごとの拡張通知種別と種類指定APIの挙動を確認し、対応外の種類名を一律に送らない。
- `emoji_reaction`を確認できたサーバーで通知が0件の場合は、通常の空表示として扱い、「現在サポートしていません」とは表示しない。種類別の取得ができず全通知から探索する場合も、自動遡及の上限を検討する。他のサーバー実装は互換性を確認してからサポート範囲を広げる。[misskey.ioのAPI互換性調査](misskey-io-api-compatibility.md)

将来実装する際は、対応情報の取得成功・欠落・通信失敗・再確認、アカウント切替、対応サーバーでの0件を検証する。サポート対象外のリアクションタブから追加要求が発行されず、メンションや全通知の取得を妨げないことも確認する。この補足は実装候補の記録のみで、アプリコード・現行仕様・テストには適用していない。

この問題とメンション遅延は、種類別タブで全通知の取得状態を共用する同じ構造から生じる。今回のリアクション連続読込を修正しても、初回の既読位置待機やサーバー側の通知保留は別途対応が必要。ここでは調査と修正候補の記録までを行い、本番コードは変更していない。

## 6. 未確認だが切り分けが必要な候補

| 候補 | コード・仕様の根拠 | 今回の確度 |
| --- | --- | --- |
| サーバー側の通知フィルタリング | 公式APIの`include_filtered`は既定でfalse。現コードは指定せず、通知リクエストの確認・承認機能もない | 対象アカウントの設定と、保留中のメンションの有無は未確認 |
| ミュート・ブロックによる非表示 | RepositoryとUIが通知抑制付きミュート・ブロックを除外する | 対象通知元の状態は未確認。端末ワードミュートは通知一覧には適用していない |
| 応答内の別通知のDTO不整合 | `List<NotificationDto>`の一括変換なので、別種別の通知1件が壊れてもページ全体が失敗する | 合成した不正データで確認する条件。mstdn.jpが実際にこの形式を返している根拠はない |
| 権限・失効 | 通知APIには`read:notifications`が必要。現ログイン要求は`read write`で、401/403は再ログイン案内になる | 実トークンのスコープ・HTTPコード未確認。再ログイン済みなので優先度を下げる |
| サーバー負荷・連合配送待ち | クライアントが取得する前に通知が生成されていない可能性 | 公開インスタンスAPIの速度だけでは判断不可 |

通知ポリシーと`include_filtered`は[公式通知API](https://docs.joinmastodon.org/methods/notifications/#get-all-notifications)に基づく。再ログインはサーバー側の通知フィルタやミュート設定を初期化しない。Push・Firebase・RelayやAndroid通知許可は、今回対象のRESTによるアプリ内一覧取得の必須条件ではない。

## 修正候補と追加観測

最優先候補は、メンションタブで対応サーバーへ`types[]=mention`を指定し、種類別に取得済み一覧・カーソル・読込状態を持つこと。種類指定が未対応・無視されるインスタンスへの対応、既存の全通知・リアクション・Streaming・未読判定との統合が必要。全通知用の共有カーソルだけを流用する修正は避ける。この変更を実施する場合、キャッシュと種類別取得の扱いが設計判断に該当するか確認し、必要なADRと現行仕様を更新する。

次に、一覧の表示を既読位置から独立させ、既読判定が未確定な間は既読送信・新着数の確定を待つ案がある。セッション切替、待機中のStreaming受信、利用者の閲覧による既読化を回帰テストで確認する。「該当する通知はありません」も、履歴探索中と取得完了を区別する余地がある。Streaming再接続時の欠落補完は別の改善候補。

実症状を確定するには、同じアカウントでWeb版に対象メンションが見えるかを最初に比較する。Webにもなければ通知保留・通知抑制・サーバー生成を優先し、Webにあるならアプリの全通知ページ・追加取得・エラー・Streamingを優先する。既にログアウトでキャッシュを失っているため、再ログインを繰り返すことよりこの比較の方が切り分けに有効。

認証付き観測を後日行う場合は、全通知と種類指定取得の件数・メンション件数・HTTPコード・所要時間、既読位置の所要時間、追加ページ到達を区別する。URL全体、本文、アクセストークン、Authorizationはログへ出さない。利用者へトークン提供を求める必要はない。

## 検証

初回は調査用4件、既存のNotificationsViewModelTest 22件、DefaultTimelineRepositoryTest 36件、計62件が成功。アプリと単体テストのコンパイルも成功した。追加調査では、新しく追加したリアクションの履歴取得テスト1件のみを選択実行して成功し、単体テストのコンパイルも成功した。本番コードの変更はなく、初回に成功したテストは再実行していない。2回の実行で計63件を確認した。全単体テスト・APK生成・端末テストは実施していない。端末・エミュレーター・実アカウントの通知API・実メンション送信は使用していない。

| 調査テスト | 確認結果 |
| --- | --- |
| 通知応答を完了させ、既読位置だけ保留 | 通知一覧は空・読み込み中のまま。既読位置を失敗で完了すると通知が表示される |
| 保存済みメンション＋最新80件がお気に入り | 保存済みメンションは初回応答後に表示から外れ、追加ページで戻る |
| Streamingイベントなしで通知タブへ戻る | 再取得されない。前景復帰を通知すると取得され新着が追加される |
| 正常メンション＋別種別の不正な通知 | ページ全体が失敗する。正常メンションだけの応答は成功する |
| リアクション非対応の投稿を持つお気に入り通知2,000件 | リアクション0件でも25ページまで取得を継続し、26回目の空応答で終了する。終了後の追加取得は発行されない |

上記は合成データによる条件の確認であり、実アカウントで同じ条件が発生した証明ではない。調査テストは現挙動の記録なので、将来の修正後に同じ期待値を維持するための回帰テストとしては使わない。

- [調査テスト](notification-mention-loading/tests/NotificationMentionLoadingInvestigationTest.kt)
- [明示実行用Gradle init script](notification-mention-loading/investigation.init.gradle)
- [JUnit結果（62件）](notification-mention-loading/results.xml)
- [追加リアクション調査のJUnit結果（1件）](notification-mention-loading/results-reactions.xml)

macOSのこの環境での実行コマンド：

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest --no-configuration-cache \
  --init-script docs/investigations/notification-mention-loading/investigation.init.gradle \
  --tests '*NotificationMentionLoadingInvestigationTest' \
  --tests '*NotificationsViewModelTest' \
  --tests '*DefaultTimelineRepositoryTest'
```

依存未取得のため、最初の`--offline`実行はKSP 2.3.6の解決段階で終了した。これはアプリの不具合やテスト失敗を示す結果ではない。

追加調査では上のコマンドに`--offline`を付け、`--tests`を次の1指定に置き換えて実行した。

```sh
--tests '*NotificationMentionLoadingInvestigationTest.nonReactionHistoryKeepsPagingUntilTheEntireHistoryReturnsEmpty'
```
