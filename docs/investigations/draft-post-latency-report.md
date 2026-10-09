# 下書き経由の投稿遅延：調査報告

調査日：2026-10-03（日本時間）

状態：遅延原因は未確定。サーバー側の仕様によるものかどうかも未確定として扱う。

## 結論

ユーザー確認による再現条件は「添付なし」「投稿画面が閉じたあと、相手側に表示されるまでが遅い」。この条件に対して、現在の作業ツリーには下書き経由だけ投稿を予約したり、YouTubeプレビューを待ってから送信したりする経路はない。

通常投稿と下書き投稿は同じ `ComposePostViewModel.post()` → `DefaultTimelineRepository.createStatus()` → `MastodonApi.createStatus()` を通る。ローカルのMockWebServerで実際に生成したHTTPリクエストを比較し、同じ入力では本文が完全一致し、ヘッダーも `Idempotency-Key` 以外は一致することを確認した。下書き保存日時から `scheduled_at` を生成する処理は存在しない。

今回の観測と最も整合する候補は、API成功後の送信元サーバーの配信処理、受信先サーバーの取り込み、または受信側クライアントの一覧更新である。どの区間かはまだ確定できない。実サーバーの要求・応答時刻、配信ログ、受信時刻を取得しておらず、「下書きが原因で連合配送が遅くなる」と証明できたわけではない。YouTubeのOGP処理だけを原因とする根拠もない。

別件として、送信失敗後に同じViewModelで別の下書きを復元すると古い `Idempotency-Key` を引き継ぐことを再現した。ただし、通常の「下書きを保存→復元→送信」ではキーが更新されるため、これだけでは今回の遅延を説明できない。

## 調査範囲と限界

- 対象：現在の作業ツリー。HEADは `49cb96fb6e8314fb943a08d836d0fcfec306f044`。投稿・下書き関連を含む多数の未コミット変更があるため、HEADそのものや配布済みAPKと同一ではない。
- `app/build.gradle.kts` の版番号は `2.2.1` / versionCode `13`。再現端末のインストール版、送信元・受信先サーバー、サーバー実装・バージョンは未確認。
- 画面、ナビゲーション、ViewModel、ドメインモデルとRepository契約、data Repository、Retrofit・OkHttp、DataStore、メディア処理、既存テストを追跡した。
- アプリの本番コードは変更していない。報告書と、通常のテスト対象には入らない明示実行用の調査テストだけを追加した。
- テストは合成データ、メモリ上のDataStore、ローカルHTTPサーバーを使用する。端末の実DataStore書き込み時間、HTTPS接続時間、実サーバーのAPI処理時間、連合配送時間は測定していない。

## 1. コードパスの比較

以下の行番号は調査時点の作業ツリーを基準とする。

| 段階 | 通常投稿 | 下書き経由 | コード根拠 |
| --- | --- | --- | --- |
| 入力 | `onTextChanged()` が本文をUI状態へ格納 | 同じ入力から `toDraft()` で保存モデルを作成 | [ComposePostViewModel.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/compose/ComposePostViewModel.kt) 322、912行 |
| 明示保存 | なし | `saveDraft()` → `UserPreferencesStore.saveDraft()`。保存成功後に入力をクリア、キーを更新 | 同ViewModel 765–808行、[UserPreferencesStore.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/preferences/UserPreferencesStore.kt) 222行 |
| 一覧 | なし | 初期化時にセッション別の下書きを読み、保存日時の降順で表示 | ViewModel 291行、[ComposePostScreen.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/compose/ComposePostScreen.kt) 624–658行 |
| 復元 | なし | `restoreDraft()` → `withDraft()`。返信・引用指定を復元し、保存済み下書きを一覧から削除。添付を再確認 | ViewModel 582–620、998–1015行 |
| 送信ボタン | `viewModel::post` | 同じボタン・関数 | ComposePostScreen 354–357行 |
| 送信モデル | `post()` が `CreateStatusRequest` を構築 | 同じ構築処理。下書き由来を示すフラグをAPIに渡さない | ViewModel 683–740行、[ComposerModels.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/model/ComposerModels.kt) `CreateStatusRequest` |
| ドメイン→data | `TimelineRepository.createStatus(session, request, key)` | 同じ関数 | [TimelineRepository.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/repository/TimelineRepository.kt) 159行、[DefaultTimelineRepository.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultTimelineRepository.kt) 552行 |
| HTTP | 選択セッションのURL・トークンで `POST /api/v1/statuses` | 同じAPI・HTTPクライアント | [MastodonApi.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonApi.kt) 263–278行、[ApiClientFactory.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/network/ApiClientFactory.kt) 36–63行 |
| API成功後 | DTO→domain変換、メモリキャッシュ、入力バッファ削除、添付ファイル削除、`posted=true` | これらに加えて復元元キーの下書き削除を再実行 | DefaultTimelineRepository 676–681行、ViewModel 753–759行 |
| 画面を閉じる | `posted=true` を受けて `onPosted()` → `popBackStack()` | 同じ経路 | ComposePostScreen 196行、[AppNavigation.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/navigation/AppNavigation.kt) 550行 |

通常投稿用に `createStatus(session, text, replyToId, key)` という別のRepositoryオーバーロードもあるが、実際の投稿画面は通常・下書きとも `CreateStatusRequest` を受け取るオーバーロードを使う。通常画面だけ単純な旧オーバーロードを使うという差はない。

「明示保存した下書き」と「画面を閉じた際の編集中バッファ」も区別した。後者は `retainInput()` → `retainComposeBuffer()` により `UserPreferencesStore` 内の `MutableStateFlow<Map<String, ComposeDraft>>` へ保存されるメモリ上のデータで、永続DataStoreやRoomではない。再表示時は `loadAccountData()` 847行以降で復元され、最終的には同じ `post()` を通る。

## 2. 最終HTTPリクエスト

`MastodonApi.createStatus()` は `@FormUrlEncoded` を使用する。JSON本文を送る経路ではない。保存データのJSON形式とHTTPの形式は別である。

| 項目 | 両経路での値・省略条件 | 下書きだけの追加・残存 |
| --- | --- | --- |
| `status` | `state.text` → `request.text`。本文をそのまま渡す | 復元した本文そのもの。変換なし |
| `visibility` | `state.visibility.apiValue`。必ず送る | 保存時の公開範囲を復元する。通常の新規入力が現在の既定値を使う場合とは異なり得る |
| `language` | nullまたは空文字なら省略 | 保存した言語を復元する。保存後の選択と違えばpayloadも違う |
| `spoiler_text` | 空白のみなら省略、それ以外は原文 | 保存したCWを復元する |
| `sensitive` | `state.sensitive || state.spoilerText.isNotBlank()`。必ず送る | 保存した設定とCWに従う |
| `media_ids[]` | 準備済み添付のmedia ID。空なら省略 | 保存したIDを再確認し、404なら再アップロードでIDが変わる可能性。今回の添付なし条件では省略 |
| `in_reply_to_id` | `activeReplyToId`。nullなら省略 | 保存した返信先を復元。通常の新規投稿と返信下書きを比べれば異なる |
| `quoted_status_id` | native引用時の引用元ID。nullなら省略 | 保存した引用指定を復元 |
| `scheduled_at` | API引数自体が存在しない。常に未送信 | 追加されない |
| `poll[options][]` | 選択肢をtrimして空を除外。空配列なら省略 | 保存した投票選択肢を復元 |
| `poll[expires_in]` | 投票が有効な場合の期間（秒）。無効なら省略 | 保存した期間を復元。保存日時から計算しない |
| `poll[multiple]` | 投票が有効な場合のbool。無効なら省略 | 保存した設定を復元 |
| その他 | `poll[hide_totals]`、`quote_approval_policy`、下書きkey・日時・状態・retry情報は送らない | 下書き経由の追加フィールドなし |
| `Content-Type` | `application/x-www-form-urlencoded` | 同じ |
| `Authorization` | 選択セッションのBearer認証。下書きにトークンを保存しない | 同じセッションなら同じ。実トークンは記録しない |
| `Idempotency-Key` | ViewModel内のUUID | ライフサイクルは次節参照 |
| HTTP自動ヘッダー | OkHttpが構成する接続先、本文長など | ローカル比較でキー以外の全ヘッダーが一致 |

任意設定なし・添付なしのフォームをデコードすると、両経路とも `status=<本文>&sensitive=false&visibility=public` の3フィールドだけになる。

下書きの公開範囲・返信先・引用先・言語を引き継ぐのは保存内容の復元であり、遅延状態が混入したものではない。ただし通常投稿との比較ではこれらも同じにする必要がある。現在の既定公開範囲を変えても、過去の下書きの公開範囲は変わらない。

## 3. `scheduled_at` と日時

投稿状態、`CreateStatusRequest`、`ComposeDraft`、Repository→APIのマッピング、Retrofit定義を追跡し、`scheduled_at` / `scheduledAt` の投稿実装がないことを確認した。

保存する日時は `ComposeDraft.updatedAtEpochMillis` の1種類。`toDraft()` が `System.currentTimeMillis()` で設定し、一覧の並び順と保存日時表示に使う。投稿日・予約日時に変換する処理はない。API応答の `StatusDto.createdAt` は送信後の投稿表示モデルへ写される値で、下書きから要求へ入る値ではない。

公式APIでは予約日時を付けると応答も通常のStatusではなくScheduledStatusになる。現在のクライアントは予約日時を送らず、Statusを受け取る定義である。[公式statuses API](https://docs.joinmastodon.org/methods/statuses/#create)

したがって、現在のコードにおける「下書き保存日時を投稿日と取り違えて予約する」という候補は除外できる。

## 4. `Idempotency-Key`

| タイミング | 処理 | 根拠 |
| --- | --- | --- |
| ViewModel生成 | `UUID.randomUUID().toString()` | ComposePostViewModel 98行 |
| 送信 | 現在のキーをRepository→HTTPヘッダーへ渡す | 740行、MastodonApi 266行 |
| 送信失敗→同一内容の再送 | キーを保持する。独自の自動再試行ループはなく、利用者の再送でも同じキー | 1018–1022行 |
| 投稿成功 | キーを更新 | 755行 |
| 明示下書き保存成功 | キーを更新 | 790行 |
| 下書き復元 | **キーを更新しない** | 582–620行 |
| DataStoreへの保存 | キー項目なし。`ComposeDraft.key` は下書き識別子であり、HTTPのキーとは別 | ComposeDraft、`toDraft()`、UserPreferencesStore.saveDraft() |

一般的な「保存成功→同じ画面で復元」と「別の画面／新しいViewModelで復元」では、それぞれ保存時・ViewModel生成時に新しいキーになる。下書きJSONから古いキーを読み出す経路はない。通常投稿も再送ごとに新しいキーを発行するわけではなく、失敗後は保持する。

一方、次の別条件は調査テストで再現した。

1. 同じ投稿画面で投稿Aを送信し、通信失敗として扱う。
2. 保存済みの別の下書きBを復元する。
3. Bを送信すると、本文がAと異なるのに同じキーを使う。

公式APIではキーは重複投稿の抑止に使われ、最大1時間保持される。Aが実際には受理され、応答だけ失われた場合、Bが既存のAとして扱われるなどの危険がある。これは新規投稿の欠落・取り違えの問題であり、今回の「しばらくして新しい投稿が届く」を直接説明する証拠ではない。[公式statuses API](https://docs.joinmastodon.org/methods/statuses/#create)

## 5. 下書きに保存する全項目

永続保存先はPreferences DataStore `user_preferences` の文字列キー `compose_drafts`。値は `List<ComposeDraft>` のJSON。保存・削除時にはリスト全体をdecodeし、更新後にencodeする。Room、WorkManager、サーバーの予約投稿機能は使用しない。

| 分類 | 保存項目 |
| --- | --- |
| 識別・アカウント | `key`, `sessionId` |
| 返信・引用 | `replyToId`, `quotedStatusId`, `quotedStatusUrl`, `nativeQuote` |
| 投稿内容 | `text`, `spoilerText`, `visibility`, `sensitive`, `language` |
| 添付 | `attachmentUris`, `attachmentFileNames`, `attachmentMimeTypes`, `attachmentDescriptions`, `attachmentMediaIds` |
| 投票 | `pollOptions`, `pollExpiresInSeconds`, `pollMultiple` |
| 保存日時 | `updatedAtEpochMillis` |

YouTube URLは通常は `text` の一部として保存する。独立したpreviewデータは保存しない。引用元URLだけは `quotedStatusUrl` として別に保存する。

保存しない項目は、投稿日、作成日時、予約投稿情報、HTTPのIdempotency-Key、投稿完了状態、retry回数・次回実行日時、転送進捗、`transferState`、エラー、`uploadedDescription`、認証情報である。添付本体は、取り込み時にアプリ専用の `filesDir/draft_media` へコピーしたファイルをURIで参照する。[DraftMediaDataSource.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/DraftMediaDataSource.kt)

復元は `withDraft()` 998行以降が本文・設定・投票をUI状態へ写し、添付URIごとに `DraftAttachment` を再構築する。`mediaId` は復元するが、転送状態は既定値 `Waiting` になる。[DraftAttachment.kt](../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/model/DraftAttachment.kt)

## 6. URL文字列

変換経路は `onTextChanged(text)` → `state.text` → `toDraft(text=text)` → JSON encode/decode → `withDraft(text=draft.text)` → `CreateStatusRequest(text=state.text)` → `@Field("status")`。

この経路に本文のtrim、URL encode/decode、Unicode正規化、Markdown化、HTML化、URL previewの混入はない。JSON上の改行エスケープはdecodeで元に戻る。最後のフォームエンコードは通常投稿にも下書き投稿にも同じように適用され、URL内の `%2B` なども本文の文字列として保持される。

調査テストでは長いYouTube URLと `youtu.be` URLを同じ本文へ入れ、クエリ、フラグメント、`%2B`、literal `+`、先頭・末尾の空白、タブ、CRLF、LF、日本語、結合Unicodeを保存・復元した。保存JSONを再decodeした本文、復元UIの本文、HTTPフォームをdecodeした `status` はすべて元の本文と一致した。

共有Intentの入力では `IncomingShareBus.kt` の `parseIncomingShare()` が最初にtrimと長さ制限を行う。ただしこれは共有入力を受け取る段階の共通処理で、下書き復元専用の変換ではない。手入力と共有入力を比較する場合はこの入口差を区別する必要がある。AppNavigationのYouTube専用処理は閲覧中のリンクを外部アプリで開くためのもので、投稿本文の変換や送信待機ではない。

## 7. 待機する処理と、今回の条件への関係

| 処理 | タイミング・内容 | 今回の「添付なし、画面が閉じたあと」への評価 |
| --- | --- | --- |
| 復元時のDataStore更新 | `restoreDraft()` が `isLoading=true` にし、`deleteDraft()` 完了まで送信を禁止。NonCancellableで実行 | 実在する待機。ただし送信前に完了する |
| 投稿成功後の下書き削除 | 復元元キーが残るため `post().onSuccess` で再び `deleteDraft()`。既に消えたキーでも保存リストを更新 | 実在する追加待機。画面が閉じる前に完了するため、そのあとの到着待ちを直接説明しない |
| 保存データの量 | 保存・削除で下書き一覧全体のJSONを変換する。変換を明示的に別Dispatcherへ移すコードはない | 多数の下書きがある場合の端末負荷候補。実時間は未測定。画面閉鎖後の配送待ちとは別 |
| 添付の再確認 | `Waiting`へ復元し、保存IDがあれば `GET /api/v1/media/{id}`。404なら再アップロード | 添付なしでは呼ばれない |
| media processing | 1秒間隔で準備状態を確認、処理確認の期限は約60秒。通信タイムアウトは別に存在 | 添付なしでは実行されない。投稿全体の上限が60秒という意味ではない |
| Mutex・upload完了待ち | `mediaMutex` で添付を直列処理。`post()` はmedia jobをjoinしてからstatuses APIへ進む | 添付なしでは処理対象もjoin対象もゼロ |
| 添付開始の待機 | 最大1秒または `isPosting=true` まで。投稿開始により解除される | 添付なしではゼロ。下書き専用でもない |
| 返信先・引用元の取得 | 復元時に必要ならキャッシュ／GETから取得。返信先未取得なら送信を拒否 | 保存した返信・引用があれば要確認。単なるYouTube URLに対するOGPではない。送信前の条件 |
| 設定・絵文字取得 | 画面の初期化／再表示時に `loadAccountData()` が順に取得 | 通常画面でも共通。`restoreDraft()` 自体からは再実行しない |
| Room更新 | statuses成功経路ではdomain変換とメモリキャッシュ更新だけ | 投稿送信のクリティカルパスにはない |
| WorkManager・通信状態確認 | 投稿を遅延配送するWorkerや投稿前の独自ネットワーク待機なし | 下書き経由の送信キューなし |
| URL preview・OGP | 投稿画面の送信経路に取得処理なし | クライアント内で待っていない。サーバー内部処理は未確認 |
| UIのdelay | `delay(16)` は絵文字後のフォーカス、`delay(1_500)` はSnackbar表示終了 | 別のLaunchedEffect。`post()` はjoinしない |
| リトライ | statuses送信の独自delay・debounce・自動retryループなし。共通OkHttpの接続回復はあり得る | 通常・下書きとも同じ設定 |

DataStore更新を人工的に停止するテストにより、復元中はAPIに到達せず、投稿成功後の再削除が停止すると `isPosting=true / posted=false` のままになることも確認した。これは待機箇所の存在を証明するテストで、実端末で同じ待ち時間が発生した証明ではない。

## 8. API応答と配送・表示を分ける確認手順

現在の `posted=true` はRepositoryが成功し、端末側の後始末も終わったことを表す。相手のサーバーが受信したことを示す配送確認ではない。通常の成功経路で画面が閉じているなら、statuses APIの成功応答は既に受け取っている。

Mastodon本家の公式説明では、HTTP処理とは別にSidekiqが投稿配送などを行い、`push` は他サーバーへの配送、`ingress` は受信Activityの処理を担当する。これがサーバー側待機を優先候補とする根拠である。ただし対象インスタンスが本家と同じ構成とは限らず、キュー名やOGPとの依存関係を対象環境で確認する必要がある。[公式サーバー運用資料](https://docs.joinmastodon.org/admin/scaling/#background-processing-sidekiq)

原因を確定するには次の区間を分けて記録する。以下は計測案であり、今回アプリへ実装した機能ではない。

| 時点 | 記録する場所・内容 |
| --- | --- |
| T0 | `post()` 開始。ローカル調査ID、通常／復元の区分 |
| T1 | API直前。添付待機終了、選択セッションとの対応 |
| T2 | HTTP応答受信。ステータスコード、可能ならサーバーの相関ID |
| T3 | Retrofit decode・domain変換・Repository成功 |
| T4 | ローカル後始末終了、`posted=true`、画面閉鎖 |
| T5 | 受信先サーバーが同じ投稿URIを取り込んだ時刻 |
| T6 | 相手側のクライアント一覧に表示された時刻 |

T1−T0が大きければ端末の準備処理、T2−T1が大きければHTTP通信・API処理、T4−T2が大きければdecode・後始末、T5がT4より大幅に遅ければ配送・取り込み、T6がT5より大幅に遅ければ受信側の更新処理を調べる。配送が画面閉鎖より先に済むこともあるため、順序を前提にせず実測値を使う。端末内の経過時間には単調増加時計、別サーバーとの比較には時刻同期済みのUTC時刻を使う。

再現比較は同じアカウント・送信先・公開範囲・言語・CW・返信／引用指定・添付数で、通常／下書きを交互に複数回行う。短縮URL／長いURLとURLなし本文も分ける。同じ動画のプレビューキャッシュが結果へ影響し得るため、投稿順を固定しない。API応答の投稿ID・投稿URI・created_atを確認し、古い投稿が返っていないかも調べる。インスタンス間ではローカルIDが異なるため、投稿の同一性はURIで照合する。

受信先のホーム／通知／Streamingとサーバーログを照合する。受信前にURL検索・resolveを行うと投稿を取り寄せることで配送待ちの観測が変わり得るため、比較中はそれらを混ぜない。送信元が本家Mastodonなら配信ジョブの開始・失敗・retry、受信先では取り込み完了と一覧更新を確認する。

計測時に本文・URL・Authorization・アクセストークン・Idempotency-Keyそのものを通常ログへ出す必要はない。キーの同一性はローカル調査IDにひも付けた比較結果として扱える。本文の比較は今回の合成テスト、または限定した調査データで行う。HTTP body loggingをReleaseで有効にする案は採らない。

## 9. 修正案と優先度

### 今回の配送遅延に対する判断

現在の根拠から、`scheduled_at` の削除、本文の追加trim、OGP待機の削除などを実装する理由はない。該当する処理がないか、検証で同一性を確認できている。まず実際に再現したAPKの版を合わせ、T2／T4／T5／T6を取得して遅延区間を確定する。

### 別件：下書き切替時のキー再利用

小規模な修正候補は、`restoreDraft()` のアカウント・編集中状態のチェックを通り、別の投稿内容を採用するタイミングでキーを更新すること。具体的なdiff方針は次の1行追加。今回は適用していない。

```diff
         stopMedia()
         ReferenceKind.entries.forEach(::cancelTargetLoad)
+        idempotencyKey = UUID.randomUUID().toString()
         restoredDraftKey = draft.key
```

同じ内容の通信リトライではキーを保持する必要があるため、毎回 `post()` の先頭で生成する変更は避ける。修正する際は、失敗後の同一投稿の再送では同じキー、別下書きへの切替では新しいキーとなる回帰テストを通常のテスト群へ追加する。本文の編集やアカウント切替も同じキーに残るため、送信済み要求と再送を識別する設計が必要かは別途評価する。

### 別件：投稿成功後の重複DataStore更新

復元時の削除成功を別の状態で記録し、既に削除した下書きに対する投稿成功後の `deleteDraft()` を省く案がある。復元時に削除失敗した場合は成功後の削除を維持し、再保存時の下書き識別子も保持する。単純にキーを捨てる修正では、再保存の扱いが変わるため注意が必要。

これは画面が閉じるまでの待機・書き込みを減らす改善候補であり、画面が閉じたあとの相手への配送遅延を直す変更とは位置付けない。添付のサーバー再確認を省く修正も、期限切れ・処理中のmedia IDを見逃すため今回の対策としては行わない。

## 検証結果と再実行

既存テスト79件成功：`ApiClientFactoryTest` 2件、`DefaultTimelineRepositoryTest` 36件、`ComposeLanguageTest` 5件、`ComposeMediaFlowTest` 20件、`ComposeQuoteTest` 9件、`ComposeReplyTest` 7件。対象テストを実行し、アプリ／テストのコンパイルタスクも成功。全単体テスト、APK生成、端末テスト、実投稿・連合配送の検証は実施していない。

調査用テスト4件成功。HTTP比較の1件内で「任意設定なし」「CW・言語・公開範囲指定」「投票あり」の3条件を検証した。他の3件ではキー再利用、保存によるキー更新、復元前後のDataStore待機を確認した。

- [調査テストソース](draft-post-latency/tests/ComposeDraftLatencyInvestigationTest.kt)
- [明示実行用Gradle init script](draft-post-latency/investigation.init.gradle)
- [最終実行のJUnit XML](draft-post-latency/results.xml)

PowerShellでリポジトリルートから実行する。JAVA_HOMEは今回使用した既存JDKの場所であり、別環境では環境に合わせる。

```powershell
$env:JAVA_HOME = 'C:\Users\ponta\AppData\Local\Programs\android-studio\jbr'
$env:GRADLE_USER_HOME = 'C:\Users\ponta\.gradle'
.\gradlew.bat :app:testDebugUnitTest --offline `
  --tests '*ComposeLanguageTest' --tests '*ComposeMediaFlowTest' `
  --tests '*ComposeReplyTest' --tests '*ComposeQuoteTest' `
  --tests '*DefaultTimelineRepositoryTest' --tests '*ApiClientFactoryTest'

.\gradlew.bat :app:testDebugUnitTest --offline --no-configuration-cache `
  --init-script docs/investigations/draft-post-latency/investigation.init.gradle `
  --tests '*ComposeDraftLatencyInvestigationTest'
```

初回の調査テスト実行ではソース登録をjavaからkotlinへ修正し、HTTP非同期完了をテストが待つよう修正した。最終版は4件とも成功しており、途中のテスト準備上の失敗をアプリ不具合とは扱っていない。Gradle初期実行のJDK／キャッシュの環境問題も解消してから検証した。
