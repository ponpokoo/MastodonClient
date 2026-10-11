# サーバー保存設定の取得・反映と更新方針

調査日：2026-10-11（日本時間）。コードの確認対象は `ab01b5cf4cee70826a24f2dfa924219f898b2872`。
本書は調査開始時の差分と、利用者と合意した取得・更新・互換性の判断を記録する。当初の6項目への対応は作業ツリーに実装済みで、配布版への採用は未定。起動・切り替えでの取得、ミュート・ブロック／フィルターの各設定内手動更新、初期値を新規入力時だけ使う制約、端末内ワードミュートの廃止、通知欄を含む共通フィルター判定を採用した。Streaming接続成功時はフィルターだけ再取得し、切断中の変更を補う。
2026-10-12（日本時間）に、閲覧注意メディアの表示・CW本文の展開・投稿の初期閲覧注意フラグの3項目も起動・切り替え時の取得対象へ加える方針を合意した。この3項目の保存・反映は未実装であり、以下では追加予定として区別する。
現在の利用者向け仕様は[UI・機能仕様](../ui-guidelines.md#サーバー保存設定と公式フィルター)、同期・保存境界は[開発ガイド](../project-setup.md#サーバー保存設定の同期)を参照する。

## 対象と合意した取得契機

| 対象 | 取得API・項目 | アプリ起動 | アカウント切り替え | 設定内の更新ボタン | Streaming通知 |
| --- | --- | --- | --- | --- | --- |
| 投稿の初期公開範囲 | `GET /api/v1/preferences` の `posting:default:visibility` | 取得 | 取得 | 対象外 | 対象外 |
| 投稿の初期引用許可 | 同APIの `posting:default:quote_policy` | 取得 | 取得 | 対象外 | 対象外 |
| 投稿の初期言語 | 同APIの `posting:default:language` | 取得 | 取得 | 対象外 | 対象外 |
| メディアの表示設定（追加予定） | 同APIの `reading:expand:media` | 取得 | 取得 | 対象外 | 対象外 |
| CW本文の展開設定（追加予定） | 同APIの `reading:expand:spoilers` | 取得 | 取得 | 対象外 | 対象外 |
| 投稿の初期閲覧注意フラグ（追加予定） | 同APIの `posting:default:sensitive` | 取得 | 取得 | 対象外 | 対象外 |
| ミュートアカウント | `GET /api/v1/mutes` | 取得 | 取得 | 取得 | 対象外 |
| ブロックアカウント | `GET /api/v1/blocks` | 取得 | 取得 | 取得 | 対象外 |
| 公式フィルター | `GET /api/v2/filters` | 取得 | 取得 | 取得 | `filters_changed` で再取得 |

対象は実装済み6項目と追加予定3項目の計9項目とする。preferencesに属する6項目は同じ応答で取得できるため、追加予定3項目のために通信を増やさず、起動・切り替えは引き続き4系統とする。選択画面の共通更新ボタンは設けず、ミュート・ブロック・フィルターの各設定画面に同じ更新アイコンを設ける。公式フィルターは変更通知による自動再取得に加え、設定画面から手動で再取得できる。
端末内ワードミュートは廃止し、公式フィルターに集約する。旧ワードのサーバー送信・移行・変換は行わない。

- アプリ起動はプロセス起動後、セッション復元と保存済み設定の読み込みを終えた初回を指す。前景復帰・画面再生成をアプリ起動として扱わない。
- 初回ログイン後の未選択から選択済みへの変化は、初回アカウント有効化として同じ同期処理を使う。起動と初回選択で二重取得しない。
- アカウント切り替えでは切り替え先だけを取得し、未使用の登録アカウント全員を取得しない。A→B→Aは再取得し、同じアカウントの再選択は取得契機にしない。
- 投稿元・管理対象の選択は閲覧セッションから独立した現行仕様を維持する。これらの対象が実際に切り替わった場合も、切り替え先を明示して同じ同期窓口へ渡す。
- ミュート・ブロックの手動更新は、それぞれの設定画面内の更新ボタンを押して実行する。その画面で指定した管理アカウントの2系統だけを取得し、preferences・公式フィルターは呼ばない。フィルター画面の更新ボタンは、その管理アカウントの公式フィルターだけを取得する。アカウントの選択そのものは切り替え契機として扱う。
- 公式フィルターだけは `filters_changed` を契機に再取得する。切断中の変更を補うため、Streaming接続成功時にフィルターだけ再取得する。具体的な抑止・復旧方針は後述する。
- 投稿画面・管理一覧を開くこと自体や前景復帰そのものは設定同期の契機にしない。ただしStreamingの再接続による公式フィルターだけの取得は上記の例外となる。定期取得・5分／15分等のTTL再取得は設けない。
- 成功した設定は次の同期まで使用する。別クライアントの変更がその間反映されないことを、この取得方針の制約とする。

## 閲覧注意に関する3項目の追加調査

取得契機は上表のとおり、アプリ起動と実際のアカウント切り替えとする。初回アカウント有効化・A→B→A・同じアカウントの再選択の扱いも既存の同期方針を継承する。画面を開くだけの取得、前景復帰による取得、定期取得、専用の手動更新ボタン、Streamingでの再取得は追加しない。

| 項目 | 型・値と意味 | 現在の実装と追加対象 |
| --- | --- | --- |
| `reading:expand:media` | 文字列。`default`は閲覧注意のメディアだけ隠す、`show_all`は閲覧注意かどうかにかかわらず表示、`hide_all`はすべて隠す | [StatusCard][status-card]は投稿の`sensitive`を基準に初期表示を決めている。サーバー設定の保存・反映は未実装 |
| `reading:expand:spoilers` | Boolean。`true`はCW付き投稿の本文を最初から展開、`false`は折りたたむ | [StatusCard][status-card]はCWがある本文を初期状態で折りたたむ。サーバー設定の保存・反映は未実装 |
| `posting:default:sensitive` | Boolean。新規投稿のメディアを閲覧注意にする初期値。閲覧時の表示設定とは別 | 投稿・下書き・一時入力には`sensitive`の保持経路があるが、サーバー初期値を取得して新規入力へ適用する処理は未実装 |

3項目はすべて`GET /api/v1/preferences`の同じ応答に含まれる。公式Mastodonでは2.8.0以降で、ユーザートークンと`read:accounts`権限が必要。[公式API](https://docs.joinmastodon.org/methods/preferences/)、[各設定の意味](https://docs.joinmastodon.org/entities/preferences/)

### 追加時に守る境界と確認事項

- 既存の[設定Repository][server-data]のpreferences要求を再利用し、domainモデルとアカウント別キャッシュへ追加する。通信・セッション隔離・登録削除時の回収は既存の同期方針に従う。
- Booleanの`false`は取得済みの有効値とし、欠落・null・型不一致と区別する。メディア表示の未知値を`default`と決めつけない。欠落・未知値・取得失敗では最後の成功値を保持し、保存済み値がなければ現行表示・投稿初期値を維持する。
- 投稿の初期閲覧注意フラグを反映する際は、既存の初期公開範囲・引用許可・言語と同じく、新規入力作成時にキャッシュを一度読む。同期完了で編集中の投稿・復元した下書き・一時入力・既存投稿編集の値を書き換えない。
- メディア表示とCW本文展開は独立した設定として扱う。公式フィルターによるhide／warn／blurと投稿者の閲覧注意・CWも区別する。取得値を画面へ反映するタイミング、利用者が手動で展開・再折りたたみした状態の保持、通知内プレビューを含む適用範囲は実装前に確認する。
- 実装時は追加通信なしの起動・切り替え取得、3つのメディア表示値、Booleanのtrue／false、欠落・未知値・失敗時の保持、A→B→Aの遅延応答、アカウント別保存・回収、編集中と下書きの保護を確認する。

今回の合意対象は3項目を起動・切り替え時の取得対象に加えること。取得・保存・画面反映の実装とサーバー設定の編集UIは、この文書更新では行っていない。以下の実装済み差分・実装順・確認済み範囲は当初の6項目を対象とする。

## 調査開始時の実装との差分

| 対象 | 調査開始時の実装 | 追加した差分 |
| --- | --- | --- |
| 初期公開範囲 | [設定Store][preferences]の `AccountPreferences.defaultVisibility` をアカウント別に保存し、[投稿ViewModel][composer]の初期化・投稿元切り替え等で使用。[設定画面][settings]の変更も端末保存のみ | preferences API・DTO・domainモデル・取得結果の保存・初期化時の適用を追加 |
| 初期引用許可 | 投稿・下書き・作成要求に選択値がない。`Status.quote_approval.current_user` は引用元を引用できるかの判定に使用 | 自分の投稿の引用許可と引用元の引用可否を区別し、初期値・下書き・送信項目を追加。投稿画面に引用許可の選択UIは設けない |
| 初期言語 | [投稿ViewModel][composer]の新規初期値は `language = null`（デフォルト）。[言語ダイアログ][language-dialog]で選択し、下書き・一時入力・投稿／編集要求に保持。既存投稿の編集は元の言語を継承。サーバー初期言語の取得・保存はない | preferencesの初期言語をアカウント別に保存し、新規投稿の初期化に接続。既存の選択・保存・送信経路を再利用 |
| ミュート／ブロック | [API][api]に一覧・追加・解除あり。[Repository][timeline]の `getModerationAccounts` は一覧と関係情報を取得し、Linkカーソルを返す。[管理ViewModel][management]は項目初回表示・更新・追加取得に対応 | 起動・切り替え・手動更新の同期へ接続。管理画面を開くだけの取得をやめ、取得済みスナップショットを表示する |
| 共有の関係状態 | `getModerationAccounts` の一覧取得結果は管理画面へ返すだけ。共有状態は主に操作成功・個別の関係確認で更新し、[AccountModerationState][moderation]はメモリのみ | 同期結果を共有状態へ反映し、再起動後も利用できるアカウント別キャッシュを追加 |
| 公式フィルター | API・DTO・管理・表示処理がない。[StatusDto][status-dto]・[TimelineStatus][status]に `filtered` がなく、Streamingの `filters_changed` も未処理 | Filter／FilterResultをdomainへ変換し、投稿・通知の表示経路と設定管理へ追加。変更イベントの受信をフィルター一覧の再取得へ接続 |
| 端末内ワードミュート | `WordMuteRepository`からアカウント別DataStoreへ保存。本文HTMLを表示文字列に変換し、本文・CWの大小無視の部分一致で判定していた | 管理画面・Repository・保存モデル・表示判定を削除。旧設定は読み飛ばし、通常の設定保存時に除去。公式フィルターへの自動移行は行わない |

ミュート／ブロック操作は成功応答を共有状態へ反映し、投稿者・ブースト元、通知者・通知内投稿を非表示にする。
Repositoryの投稿メモリキャッシュと[ホーム][home-cache]・[通知][notification-cache]の永続キャッシュにも反映する。解除では削除済み投稿を復活させず、次の投稿取得で表示を補充する。
公式フィルターで隠した元の投稿・通知は保持し、ルール削除時に再表示できる。
現行動作の説明は[UI・機能仕様](../ui-guidelines.md#ミュートブロック報告)を参照する。

## キャッシュとセッション隔離

設定取得はViewModel → domain Repository → data Repository → remote／local data sourceの境界を守る。
preferencesと公式フィルターには小さなdomain契約とdata実装を追加し、既存ミュート／ブロックのAPI・操作経路を再利用する。
Composeからサーバー設定の通信・複数段階の同期を直接実行しない。

- 保存キーはセッションID・正規化したインスタンスURL・自アカウントIDで区別する。認証情報は設定キャッシュへ複製しない。
- 項目別に成功日時・取得状態・API対応状況を保持し、「未取得」「取得済み空」「取得失敗」「非対応」を区別する。実装済みの3つの初期設定と追加予定の閲覧注意関連3項目は同じpreferences要求で取得し、欠落項目等の適用可否は項目別に保持する。
- 保存済み設定を読み込んでからキャッシュ内の投稿・通知を表示し、起動直後に既知のミュート／ブロックを取りこぼさない。ネットワーク同期は背景で進める。
- 通信失敗で保存済み値を消さず、成功した空一覧だけを空として採用する。項目ごとの成功を確定し、一部の失敗で他の成功結果まで破棄しない。
- 成功値にTTLは設けず、設定取得失敗後のタイマー再試行も設けない。次の起動・切り替え、ミュート／ブロック・フィルターの手動更新、公式フィルターの変更通知・接続確立で再試行する。
- アカウント×項目ごとに進行中要求を共有し、重複送信を抑止する。起動・切り替え同期中の手動更新は、押した画面に対応する要求へ合流する。フィルターの連続通知・取得中の通知は後述の処理でまとめる。
- キャンセル・アカウント有効化世代・要求順序を照合する。A→B→Aでも最初のAの遅延応答を新しいAの同期結果へ混ぜない。
- 永続保存時にも登録と認証情報を照合し、登録削除・再認証後に旧応答で値が復活しないようにする。新しいキャッシュも[登録削除の回収経路][account-cleanup]へ追加する。
- 同期開始後に操作成功があれば、項目の更新世代を比較して古い取得結果を拒否する。成功した操作が一覧応答で巻き戻らないようにする。

既存の[SessionScopedViewModel][session-vm]・[BrowsingSession][session]と、[開発ガイドのセッション方針](../project-setup.md#メイン画面の責務とライフサイクル)を継承する。
操作がサーバーで成功した後は、登録・認証が有効な元のアカウントのローカル反映を完了できるようにする。画面切り替えだけを理由に成功を失わず、切り替え先のUIへ成功通知を出さない。

## 投稿初期値の適用と下書き

編集中の保護や保存に関する分岐を増やさないため、初期設定は新規入力を作る時に一度だけ適用する。通信結果を見て編集中の値を補正する方式は採用しない。

1. 新規入力を作る時に、対象アカウントの取得済みキャッシュを一度だけ読む。取得済み値がなければ公開範囲に既存の端末初期値、引用許可に「サーバーに従う」、言語に現行の「デフォルト」を使う。preferencesの通信完了は待たない。
2. 下書き・一時入力がある場合は、既存の復元経路でその投稿の値を読み戻す。投稿編集は元投稿の値を使う。初期値によって復元値を埋め直さない。
3. 入力状態を作成した後は、その投稿の選択値を維持する。同期完了は設定キャッシュだけを更新し、次の新規入力から使う。投稿画面から初期設定のFlowを継続購読して入力値へ適用しない。
4. 初期値の解決を共通処理にまとめ、投稿画面の初期化・投稿元切り替え時の新しい入力・下書き保存後の空入力へのリセットで使う。ネットワーク通信や追加の保存処理をこの共通処理へ入れない。
5. 公開範囲・言語の保存は既存の `toDraft`／`withDraft` と一時入力の経路を再利用し、引用許可だけnullableの項目を追加する。旧下書きは引用許可が未記録なので、過去の値を推測せず「サーバーに従う」として読み込む。

利用者が了承した制約として、初回取得が新規投稿開始に間に合わない場合、その投稿には従来の初期値を使い、取得結果は次の新規投稿から反映する。起動・切り替えで通信する契機は維持し、投稿画面を開くことによる再取得・再試行は追加しない。

### 複雑化を抑える実装境界

現行の[投稿ViewModel][composer]には、新規状態の生成、`loadAccountData` による一時入力復元、`restoreDraft`／`withDraft` による下書き復元、`toDraft` による保存がある。公開範囲・言語はすでにこれらを通るため、サーバー初期値の取得を理由に保存・復元の仕組みを作り直す必要はない。

- 設定同期の応答先はアカウント別キャッシュに限定し、ComposePostUiStateや下書き・一時入力を直接書き換えない。
- 同期完了を理由に `saveDraft`／`retainInput` を呼ばない。自動保存の契機、空入力の扱い、添付コピーの管理は現行仕様を維持する。
- 項目ごとの編集済みフラグ、空本文を根拠にした再適用、下書き保護専用の世代管理を追加しない。初期設定を適用する入口を新規状態生成に限定することで保護する。
- 復元値のnullも有効な選択として扱う。言語・引用許可を `draft.value ?: serverDefault` のように補完すると「デフォルト」の選択を失うため、既存の復元値をそのまま使う。
- 通信キャンセル・アカウント世代・保存時の登録照合は同期側に必要であり、既存のセッション方針に従う。この必要な隔離処理と、投稿入力の保護を分ける。

サーバー同期・公式フィルター全体には新たな処理が必要であり、変更全体が小さいとは扱わない。ここで抑えるのは投稿初期値の追加が既存の編集中保存・下書き復元へ持ち込む複雑さである。保存・復元と遅延応答を既存・追加テストで確認する。

引用許可は `public`／`followers`／`nobody` と未指定を区別する。具体値は新規投稿の `quote_approval_policy` に送信する。
投稿画面の引用許可ボタン・選択ダイアログ・手動変更処理は設けず、新規入力時に取得済み初期値を採用する。下書き・一時入力では保存した値を引き継ぐ。
未指定の場合はサーバーの投稿時の既定値に依存するため、旧下書きの過去の引用許可を保証できない。
`private`／`direct` の投稿では、具体値を送信する際に引用許可を `nobody` に制限する。[公式投稿API](https://docs.joinmastodon.org/methods/statuses/#post-a-new-status)

既存の端末初期公開範囲は、サーバー非対応・初回取得失敗時の代替値として保持する。設定の「投稿」は項目名を「公開範囲」とし、取得済みのサーバー値を表示する。通常時の補足説明文は置かない。
成功したサーバー値を優先しても、既存の端末設定をサーバーへ自動送信しない。
初期公開範囲・引用許可・言語の手動再取得UIは今回設けず、別クライアントの変更は再起動・アカウント切り替えで取得する。

### 投稿言語の調査開始時の動作と追加方針

調査開始時の新規投稿は「デフォルト」（`language = null`）で開始し、投稿APIのlanguage項目を省略してサーバーに委ねる。アプリが日本語の `ja` を固定送信する実装ではない。
[言語ダイアログ][language-dialog]は先頭に「デフォルト」を置き、`Locale.getISOLanguages()` の言語コードを日本語名とコードで表示する。日本語名で表示することと投稿言語を日本語に設定することは別である。
選択した言語は[作成要求][composer-models]と[Repository][timeline]を通じて `language` に送信し、[下書き][preferences]とアカウントごとの一時入力にも保存する。
投稿元を初めて別アカウントに切り替えるとデフォルトから開始し、元に戻すとそのアカウントの入力と言語選択を復元する。投稿編集では取得した元投稿のlanguageを初期値にする。
現行仕様は[投稿と下書き](../ui-guidelines.md#投稿と下書き)、既存の確認は[ComposeLanguageTest][language-test]も参照する。

追加する `posting:default:language` は新規投稿の初期言語で、ISO 639-1の2文字コードまたはnull。公式Mastodonでは2.8以降の項目である。[公式Preferencesモデル](https://docs.joinmastodon.org/entities/Preferences/#postingdefaultlanguage)

- 起動・アカウント切り替えで取得し、取得済み言語と投稿画面の言語選択を初期化時に連動させる。例えば `ja` なら新規投稿を日本語選択で開始し、その投稿の選択値を通常のlanguage送信経路へ渡す。
- 成功応答の明示的なnullは「デフォルト」として採用し、投稿時はlanguageを省略する。項目欠落・取得失敗とは区別し、これらで最後の成功値を消さない。DTOのnullable値だけで欠落とnullを同一視しない。
- 言語も新規入力を作る時だけスナップショットとして採用する。同期完了後に投稿入力へ再適用する経路を作らず、利用者が「デフォルト」を選び直した場合も維持する。
- 復元した下書き・一時入力の言語を優先し、既存のnullも「デフォルト」として復元する。旧下書きの項目欠落を新しいサーバー初期言語で埋めず、現行と同じnullとして扱う。
- 既存投稿編集では元投稿の言語を引き継ぐ。投稿言語の選択はその投稿だけに適用し、サーバーの初期言語を更新しない。
- 有効な言語コードが端末の選択肢にない場合もコードを表示できるようにし、黙って日本語やnullへ置換しない。アプリの表示言語や端末の言語設定から投稿言語を推測しない。

### 取得言語・投稿画面・送信値の連動

取得した `posting:default:language` をdomainへ変換して対象アカウントの設定キャッシュへ保存する。新規入力の初期値解決でその言語を `ComposePostUiState.language` へ一度だけコピーし、既存の `PostLanguageDialog.selected` と `CreateStatusRequest.language` はこの投稿状態の同じ値を参照する。UIへDTOを渡さず、表示専用の言語設定や送信直前だけの別の補完値を追加しない。

| 取得済み初期言語 | 新規投稿の初期選択 | 投稿時の言語選択 | APIのlanguage |
| --- | --- | --- | --- |
| `ja` | 日本語 (`ja`) | 変更なし | `ja` を送信 |
| `en` | 英語 (`en`) | 変更なし | `en` を送信 |
| 明示的なnull | デフォルト | 変更なし | 省略 |
| `ja` | 日本語 (`ja`) | 英語へ変更 | `en` を送信 |
| `ja` | 日本語 (`ja`) | デフォルトへ変更 | 省略 |

投稿内で言語を変えた場合は、下書き・一時入力・送信にもその選択値を使う。サーバーに保存された初期言語を変更する操作にはしない。次の新規入力は、その時点の取得済みサーバー初期言語で開始する。
下書き・一時入力の復元や既存投稿編集には、前節のとおり保存済みの投稿言語を使う。同期中に取得値が変わっても現在の投稿には再適用せず、新規入力を作る時だけ連動させる。

## ミュート／ブロックの一覧・操作状態・投稿表示

3契機でLinkヘッダーをたどり、全ページ取得成功後に一覧スナップショットを確定する。カーソルはアカウントIDから推測せずStringのまま扱う。
ページ取得途中で存在しないことを解除の根拠にしない。完全取得でもページ間の外部変更はあり得るため、消えた既知アカウントは必要に応じて関係APIで解除を確認してから共有状態を更新する。
関係情報の欠落・取得失敗も「ミュート／ブロックなし」に変換しない。[ミュート一覧](https://docs.joinmastodon.org/methods/mutes/)、[ブロック一覧](https://docs.joinmastodon.org/methods/blocks/)

取得結果を[共有状態][moderation]へ反映し、[既存の表示処理][moderation-filters]に接続する。
ミュートとブロックは独立した集合として扱い、ミュート一覧の更新でブロック・フォロー等の無関係な状態を消さない。
通知抑制と取得できたミュート期限を保持する。期限だけを根拠にサーバーでの解除成功を通知せず、外部の延長等は次の同期で確認する。

管理一覧の初回表示は保存済みスナップショットを使い、追加表示も取得済み一覧内のページングとする。
管理対象アカウントの切替ボタンは項目選択画面だけに表示する。ミュート中・ブロック中・サーバーのフィルターの各個別設定は、その選択済みアカウントを引き継ぎ、切替ボタンを表示しない。
未取得・取得失敗は空一覧と区別し、対象アカウントを指定した設定画面内の更新ボタンへ案内する。
既存の操作メニューが行う `GET /api/v1/accounts/relationships` は対象1件の操作状態確認であり、6項目の設定同期とは別扱いで維持する。メニュー表示時に設定の一括取得を追加しない。

### 更新と成功・失敗

ミュート／解除は `POST /api/v1/accounts/:id/mute`／`unmute`、ブロック／解除は `block`／`unblock` を使う。
成功応答のRelationshipで確定し、共有状態・メニュー・管理一覧を更新して既存の投稿／通知キャッシュ処理を実行する。設定一覧の再取得は行わない。
解除後の表示補充は通常の投稿・通知取得を使い、これを設定同期の契機にしない。

明確な失敗では直前の確定状態を維持し、成功表示を出さない。タイムアウト等で結果が不明な場合は自動再送せず、設定の手動更新で確認できるようにする。
サーバー成功後の保存失敗は、操作失敗と区別して再保存・手動更新へ案内する。
既存の解除取り消しは維持し、変更した側だけを再設定する。ミュートの通知抑制・残り期間を復元し、ブロックによって失われたフォロー関係を復元する操作にはしない。
通知抑制・期間を変更する場合もmute APIを使えるが、追加の編集UIは後続候補とする。[公式アカウント操作](https://docs.joinmastodon.org/methods/accounts/#mute-account)

## 公式フィルターと端末内ワードミュートの廃止

公式フィルターはID・タイトル・context・expires_at・filter_action・キーワードID／whole_word・投稿指定を保持し、「サーバーのフィルター」で管理する。
端末内ワードミュートの管理画面・追加／削除・保存モデル・Repository・共有状態・本文/CWの部分一致判定は削除する。

旧JSONの `wordMutes` は未知項目として読み飛ばし、他の設定を維持する。通常の設定保存時に旧項目を除去するため、保存前には旧JSON内に残り得るが、表示へは適用しない。
旧ワードのサーバー送信・移行・変換は行わず、公式フィルターと下書きは保持する。v2未対応のサーバーには非対応を表示し、端末内ワード判定を代替として残さない。
理由と受け入れる制約は[ADR 0017](../adr/0017-retire-local-word-mutes.md)を参照する。

公式フィルターのcontextと適用画面は次の対応とする。

| context | 基本の適用画面 |
| --- | --- |
| `home` | ホーム・リスト |
| `notifications` | アプリ内通知欄 |
| `public` | ローカル・連合の公開タイムライン |
| `thread` | 投稿詳細のスレッド |
| `account` | プロフィールの投稿 |

最小構成は上表の5つのcontextに対応する画面を対象とし、検索・探索・タグ・お気に入り／ブックマークには公式フィルターを適用しない。端末内ワードミュートの廃止に伴い、これらの画面での端末内ワード判定も終了する。専用contextがない画面の追加対応は後続の判断対象とする。
Android通知への公式フィルター適用も本書の最小構成には含めず、アプリ内通知欄と区別する。
expires_atが過ぎたフィルターは端末内で適用を停止し、設定一覧から自動削除しない。[公式Filterモデル](https://docs.joinmastodon.org/entities/Filter/)

投稿の `filtered` をDTOからdomainへ保持し、context・期限を確認して適用する。
`hide` は非表示、`warn` はフィルター名の警告と展開、`blur` はメディアを隠す処理とする。複数一致ではhideを優先し、未知の動作はwarn相当とする。
keyword_matches・status_matchesを保持し、単純な部分一致だけに置き換えない。[公式適用指針](https://docs.joinmastodon.org/api/guidelines/#filters)、[FilterResult](https://docs.joinmastodon.org/entities/FilterResult/)

ブースト行と元投稿の判定情報を落とさず、共通StatusCardを使わない通知内プレビューにも適用する。
公式フィルターによる非表示は元の投稿・通知を保持する表示処理とし、アカウントミュート／ブロックの削除経路へ混ぜない。

### 通知欄での警告（CW）と非表示

アプリ内通知欄では、フィルターの `warn` を「通知の種類・アカウント情報を残し、投稿内容を警告で折りたたむ」表示にする。
調査開始時の[NotificationsContent][notification-ui]は返信／メンションに[StatusCard][status-card]を使い、お気に入り・ブースト・リアクション等には `NotificationStatusQuote` の簡易プレビューを使う。前者には投稿者のCW表示があるが、後者は本文をそのまま表示する。今回、両経路へ共通の判定と警告部品を接続した。

- 通知内投稿の `filtered` から `notifications` に適用される有効な一致だけを判定し、「すべて」「メンション」「リアクション」で同じ扱いにする。投稿を含まないフォロー等の通知は対象外。
- `warn` では本文・投稿者のCW文・メディア・リンクカード・投票を展開前に表示せず、「フィルター：ネタバレ」等のフィルター名と「内容を表示」ボタンを出す。複数一致では該当するフィルター名を示し、理由として一致キーワードや本文の抜粋を表示しない。[公式適用指針](https://docs.joinmastodon.org/api/guidelines/#filters)
- 通常のCWと共通の背景付き警告枠・CWバッジ・開閉ボタンを使い、警告枠のタップまたは「内容を表示」でその通知の投稿部分だけを展開し、「内容を隠す」で再び折りたたむ。警告枠のタップや展開操作で投稿詳細へ遷移しない。展開後の本文・簡易プレビューから詳細を開く既存操作は維持し、詳細画面では `thread` の条件で改めて判定する。
- フィルターの警告と投稿者自身のCW（`spoilerText`）は別に扱い、投稿データのCW文へフィルター名を書き込まない。公式警告を展開しても投稿者CWや閲覧注意のメディアを自動展開しない。簡易プレビューにも投稿者CWの折りたたみを追加し、公式警告の展開後に本文が無条件で露出しないようにする。
- `hide` が有効な一致に含まれる場合は通知行ごと表示せず、展開ボタンを設けない。新着件数・強調表示からも除き、元の通知と取得カーソルは保持する。

フィルターのcontext・期限・動作を判定する共通処理と、フィルター・投稿者CWに共通の警告表示部品を、通常カードと簡易プレビューで再利用する。フィルターを開いてから投稿者のCWを開く段階的な表示を許容し、二つの警告を一つにまとめない。UIにはdomainの判定結果を渡し、DTOの解析や設定取得を入れない。
展開状態はアカウント・通知ID・適用警告の組み合わせに属する一時的なUI状態とし、設定や投稿キャッシュへ保存しない。アカウントや適用警告が変われば折りたたみに戻し、同じ設定の再取得だけで状態をリセットしない。フィルター設定の解除・サーバー更新は展開操作から行わない。

### 更新と既存投稿のキャッシュ

追加・編集は専用画面で名前・キーワードを先に入力し、その下に適用画面・表示方法・期限を配置する。保存操作は上部に固定し、期限はプルダウンで編集時の「変更しない」を維持する。入力・選択肢の仕様は[UI・機能仕様](../ui-guidelines.md#サーバー保存設定と公式フィルター)に従い、一覧の編集・削除ボタンは維持する。

作成・変更・削除は `POST /api/v2/filters`、`PUT /api/v2/filters/:id`、`DELETE /api/v2/filters/:id` を使う。
作成・変更の応答Filterを設定キャッシュへ保存し、削除成功時は対象IDを除く。操作成功自体を契機にfilters一覧を取り直さない。ただし同じ操作でも `filters_changed` が届き得るため、その通知による再取得は通常どおり処理する。通知に操作元の識別情報がないので、直後の通知を自分の操作だと決めつけて無視しない。
適用画面は重複キーの `context[]` 配列で送信し、番号付きcontextのオブジェクトへ変換しない。キーワードは既存IDで変更し、削除対象だけ `_destroy` を指定する。未編集のcontext・期限・動作・投稿指定を失わず、期限の再送で意図せず延長しない。[公式フィルターAPI](https://docs.joinmastodon.org/methods/filters/)

失敗時は編集入力・直前の確定状態を保持する。作成の結果が不明な場合は、次の変更通知・接続確立・起動・切り替え・設定内の手動更新で一覧と照合できるまで作成を再送せず、確認待ちとして表示する。結果不明の作成を自動再送しない。
外部で削除されたIDへの編集等はエラーを保持し、次の同期またはフィルター画面の更新ボタンで一覧を再確認する。

設定キャッシュの更新だけでは、取得済み投稿のfilteredは更新されない。
最小構成ではフィルター変更・同期差分の確定後に表示中の関連投稿・通知を通常の一覧取得で更新し、他の投稿キャッシュを再確認対象とする。
これは投稿の再取得であり、設定APIの取得契機を増やす処理ではない。既存の投稿操作・Streamingとの競合も扱う。
取得失敗時は「設定保存済み・表示反映待ち」を区別する。画面ごとの更新結果を保持し、一方の成功で別画面の失敗を解除しない。古い設定で開始した更新結果では新しい設定の待ち状態を解除しない。これは開いている画面の通常取得を対象とし、保存済みの全ページや過去投稿へ新規キーワードを再照合済みとは扱わない。
オフラインでも新規・変更キーワードを既存投稿へ即時反映するには、公式相当の照合処理が別途必要になる。

### Streamingによる変更通知と取りこぼし対策

`filters_changed` はフィルターが変更されたことを知らせるイベントであり、キーワードやcontext等の設定内容を含まない。受信後に対象アカウントの `GET /api/v2/filters` を呼んで最新一覧を確定する。[Streaming仕様](https://docs.joinmastodon.org/methods/streaming/#event-types-and-payloads)

公式MastodonのCustomFilterモデルは保存・削除のコミット後に、対象アカウント向けの `filters_changed` を発行する。定期通知や投稿受信ごとの通知として扱われるイベントではない。フィルターの変更が少なければ通常は通知も少ないと判断できるが、再保存・一括編集等で複数届く可能性はある。実サーバーでの発生回数は未測定であり、サーバー種類・バージョンによる差も確認対象とする。[公式実装（調査時のmain）](https://github.com/mastodon/mastodon/blob/main/app/models/custom_filter.rb)

調査開始時の[Repository][timeline]の `observeUserStream` は投稿追加・編集・削除と通知だけを扱い、`filters_changed` を含む他のイベントは無視する。[SSE処理][stream-source]も空payloadを送出しないため、イベント名だけ・空data・undefinedのいずれも変更通知として受けられるようにする。変更通知のpayloadを投稿JSONとして解析しない。[domainイベント][stream-model]へフィルター変更と接続確立を通知する経路を追加し、[MainSessionViewModel][main]から同期Repositoryへ対象セッションを渡す。API通信を待って通常の投稿・通知イベント処理を止めない。

- 短時間の連続通知をまとめ、アカウント×公式フィルターで同時取得を1件にする。取得中に新しい通知が届いた場合は未反映の通知を記録し、終了後に1回まとめて再取得する。進行中要求へ合流するだけで変更通知を失わないようにする。
- Streaming切断中の通知は現行クライアントに再生・履歴取得の経路がない。手動更新に依存せず切断中の変更を補うため、接続が成功した時にフィルターだけ1回取得する。起動・切り替えの取得と、同じセッション世代・接続確立後の取得を共有し、接続前に開始した古い取得だけで取りこぼしが補えたと判断しない。
- 前景復帰だけで全設定を取得しない。現行の `startStreaming` は前景／背景の変化で接続を作り直すため、維持できる既存接続を再利用する調整を加え、不要な再接続でフィルター取得を増やさない。
- 接続不能の再試行ごとにフィルターAPIを呼ばず、実際の接続成功時だけ取得する。イベント通知・接続確立・起動取得が重なっても同時要求を増やさず、取得失敗時は最後の成功一覧を保持して反映待ちを表示する。
- 既存のStreamingは閲覧アカウントに1本だけ接続する。別の管理対象・投稿元アカウントのために追加接続しない。そのアカウントは起動・対象切り替えで取得し、閲覧アカウントとして接続した時に変更通知で更新する。
- Streaming無効・Wi-Fi限定で条件外・非対応サーバーでは変更通知による同期を行えない。起動・アカウント切り替えと設定内の手動更新で取得できるようにし、定期取得は追加しない。
- セッション・接続世代・要求順序を照合し、旧接続のイベントや遅延取得を別アカウントへ適用しない。取得後の差分を前節の投稿キャッシュ再確認へ接続し、設定一覧だけ更新して既存投稿への反映済みとしない。

## 初期設定の取得とサーバーへの保存の区別

新規投稿画面の公開範囲・引用許可・言語は、その投稿の `visibility`・`quote_approval_policy`・`language` として送信し、サーバーの初期設定を変更しない。引用許可・言語の未指定は送信を省略する。
投稿済みの引用許可を変更するinteraction_policy APIも、初期設定とは別の機能として今回の最小構成に含めない。[引用投稿の公式仕様](https://docs.joinmastodon.org/client/quotes/)

設定の「公開範囲」の変更は、明示的な操作として `PATCH /api/v1/accounts/update_credentials` の `source[privacy]` だけを送信する。OAuthの `write:accounts` が必要で、既存の `write` 権限に含まれる。初期引用許可・初期言語のサーバー編集UIは後続候補とし、これらやプロフィールを同時に送信しない。
preferences API自体は取得用。初期公開範囲の更新値はpublic／unlisted／privateであり、投稿単位のdirectと区別する。[初期設定の更新仕様](https://docs.joinmastodon.org/methods/accounts/#update-account-credentials)
保存応答の自アカウントIDと `source.privacy` を確認した時だけ対象アカウントの設定キャッシュを更新する。引用許可・言語などのキャッシュは維持する。先行するpreferences取得の世代を無効化し、古い取得結果で保存値を上書きしない。
失敗・未対応・権限不足・保存結果の確認不能では最後の値を維持し、画面にエラーを表示する。成功後の端末保存失敗はサーバー反映済みと区別して表示する。アカウント切り替えでは処理をキャンセルし、旧世代の応答で別アカウントの選択・保存中表示・エラーを変更しない。
初期公開範囲の保存も現在の編集中の投稿や復元した下書きを変更せず、次の新規入力から反映する。既存の端末初期設定や廃止したワードミュートをサーバーへ自動送信する動作は追加しない。

## 互換性と未確認事項

- 公式Mastodonのpreferencesは2.8以降、初期引用許可は4.5以降。引用許可の応答キーは `posting:default:quote_policy`。公式ページの履歴欄の `quoted_policy` 表記と応答例を混同しない。[preferences API](https://docs.joinmastodon.org/methods/preferences/)
- フィルターv2は4.0以降、blurは4.4以降。最小構成はv2を対象とする。v1のみのサーバーは非対応を表示し、黙ってフィルターなしとして扱わない。端末内ワード判定へのフォールバックは設けない。[v2／v1 API](https://docs.joinmastodon.org/methods/filters/)
- `filters_changed` は公式Mastodonで2.4.3以降のuser streamイベント。ただしイベント対応だけでフィルターv2対応を推定しない。通知が来ないサーバー・Streaming無効時も、起動・切り替え取得とフィルター画面の更新ボタンを使う。[Streaming仕様](https://docs.joinmastodon.org/methods/streaming/#event-types-and-payloads)
- v1対応が必要なら専用のクライアント照合を追加し、phrase・whole_word・context・expires_at・irreversibleを保持する。v2の編集失敗からv1更新へ自動退避しない。[V1::Filter](https://docs.joinmastodon.org/entities/V1_Filter/)
- サーバー種類・version文字列は判断材料に留め、api_versionsと実際のAPI応答・項目の有無で対応を判断する。InstanceDtoにapi_versionsをnullableで追加した。[Instance](https://docs.joinmastodon.org/entities/Instance/#api_versions)
- 読み取りはread:accounts・read:mutes・read:blocks・read:filters、一覧に付随する関係取得はread:follows。更新は対応するwrite:*が必要。現行の要求read writeは通常これらを包含するが、保存済みトークンの実効権限も確認する。[OAuth権限](https://docs.joinmastodon.org/api/oauth-scopes/)、[関係API](https://docs.joinmastodon.org/methods/accounts/#check-relationships-to-other-accounts)
- 401／403をAPI非対応と決めつけず、認証・権限・アカウント制限を区別する。404／501等も設定なしへ変換しない。欠落項目・未知値はnullable／未確定として扱い、最後の成功値を保持して非対応パラメーターを送信しない。
- 旧投稿キャッシュにfilteredがない場合は「フィルター確認済み」としない。次の通常取得で補充する。設定キャッシュは既存のUserPreferencesStoreへ保存し、認証情報を含めない。アカウント削除時に回収する。バックアップ／端末移行は既存の通常設定の方針を維持し、復元されたキャッシュも登録済みアカウントとの一致を確認する。

## 対象ファイルと採用した実装順

| 範囲 | 主な対象ファイル |
| --- | --- |
| API・DTO | [MastodonApi.kt][api]、[StatusDto.kt][status-dto]、[InstanceDto.kt][instance-dto]、[AccountDto.kt][account-dto]。preferencesは欠落とnullを区別するJsonObject、filtersは[FilterDto][filter-dto]を追加 |
| domain・同期・保存 | [ServerAccountSettings][server-model]・[ServerAccountSettingsRepository][server-contract]・[DefaultServerAccountSettingsRepository][server-data]を追加し、UserPreferencesStoreのアカウント別保存を再利用。[TimelineRepository][timeline-contract]・[DefaultTimelineRepository][timeline]・[AccountModerationState][moderation]の既存経路を再利用 |
| 起動・切り替え | [MainSessionViewModel][main]、[AppNavigation][navigation]。同期項目の指定を起動／切り替えと手動更新で分ける |
| Streaming | [MastodonStreamingDataSource][stream-source]、[TimelineStreamEvent][stream-model]、[DefaultTimelineRepository][timeline]、[MainSessionViewModel][main]。payloadなしの変更通知・接続確立、取得の集約、接続維持とセッション照合を追加 |
| 投稿・下書き | [ComposePostViewModel][composer]、[ComposePostScreen][composer-screen]、[PostLanguageDialog][language-dialog]、[ComposerModels][composer-models]、[UserPreferencesStore][preferences]、[SettingsScreen][settings] |
| 設定からの初期公開範囲保存 | [PostingSettingsViewModel](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/settings/PostingSettingsViewModel.kt)、[SettingsNavigation](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/settings/SettingsNavigation.kt)、[SettingsScreen][settings]、[ServerAccountSettingsRepository](../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/repository/ServerAccountSettingsRepository.kt)、[DefaultServerAccountSettingsRepository](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultServerAccountSettingsRepository.kt)、[MastodonApi][api]、[AccountDto](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/dto/AccountDto.kt) |
| 管理UI | [ModerationManagementViewModel][management]、[ModerationManagementScreen][management-screen]、[AccountModerationMenu][moderation-menu]。既存の管理ViewModelにフィルター操作を接続し、[ServerFilterEditor][filter-editor]を追加 |
| 投稿・通知表示 | [TimelineStatus][status]、[ModerationFilters][moderation-filters]、[ModeratedState][moderated-state]、[StatusCard][status-card]、[NotificationsContent][notification-ui]。表示中一覧の更新経路にも接続 |
| キャッシュ・登録削除 | [HomeTimelineLocalDataSource][home-cache]、[NotificationLocalDataSource][notification-cache]、[AccountDataLocalDataSource][account-cleanup] |

1. アカウント別キャッシュ・取得状態・進行中要求の共有・保存時照合を追加し、起動／切り替えは4系統、各設定内の更新ボタンはミュート・ブロックの2系統またはフィルターの1系統とする。
2. preferences取得、公開範囲・引用許可・言語の新規投稿初期化、引用許可の送信と下書き保持を追加する。キャッシュを一度読む初期値解決を共通化し、既存の選択・保存・復元・送信経路を再利用する。
3. ミュート／ブロックの完全一覧を共有状態へ接続し、管理画面の自動取得をキャッシュ表示へ変更する。外部解除と同期中の操作競合を扱う。
4. 公式フィルターの取得・domain変換・filtered保持、context・期限・hide／warn／blurの表示を追加する。`filters_changed`・接続確立を公式フィルターだけの再取得へ接続し、連続通知・切断中の変更・取得中の通知を扱う。フィルター画面にも同じ更新ボタンを置き、手動取得を同じ窓口へ接続する。
5. 公式フィルター管理・作成／変更／削除、成功応答の保存、投稿キャッシュの再確認を追加する。

サーバー初期設定編集、フィルターv1、Android通知への適用、オフラインでの完全な再照合は後続候補とする。

## 実装時の確認対象

| 対象 | 確認する振る舞い |
| --- | --- |
| 取得契機 | 起動・実際の切り替えで4系統。選択画面には更新ボタンを置かず、ミュート・ブロック画面では指定した管理アカウントの2系統、フィルター画面では公式フィルターの1系統だけを取得する。手動更新でpreferencesを呼ばない。画面表示自体・定期取得で設定APIを呼ばず、変更通知・接続確立では公式フィルターだけ取得する |
| 外部変更 | 別クライアントの初期設定変更は起動／切り替えで反映。ミュート・ブロックの追加／解除は手動更新でも反映。フィルターの追加／解除／変更は受信した変更通知または設定内の手動更新、切断中の変更は接続確立後の取得で反映 |
| Streaming同期 | イベント名のみ・空data・undefinedの変更通知、連続通知・取得中の通知、起動／接続確立の重複、旧アカウントの通知、自己操作に伴う通知、通信失敗、切断・復帰、無効／Wi-Fi条件外／通知非対応、別の管理対象。フィルター取得が投稿・通知処理を止めず、不要な再接続で要求を増やさない |
| 失敗・非対応 | 初回失敗と成功済み空を区別。部分失敗・権限不足・未知項目・v2非対応で最後の成功設定を保持 |
| 一覧・操作競合 | 複数ページ・途中失敗・不透明カーソル・取得中の追加／解除。古い応答で操作成功が戻らず、片方の解除で他方を失わない |
| セッション | 同一サーバーの複数アカウント、異なるサーバーの同一ID、A→B→A、再認証・登録削除後の遅延応答、管理対象と閲覧対象の独立 |
| 投稿・下書き | 同期前の新規入力は代替値、同期後に作る新規入力は取得済み値。遅延応答が現在の入力・保存中の下書きを変えない。旧／新下書き・一時入力の復元、投稿元切り替え、保存後の空入力リセット、既存投稿編集、非公開時の引用制約 |
| 投稿言語 | 取得済みja／en／nullが新規投稿の言語選択とAPI送信値へ一致して反映される。手動で英語／デフォルトへ変えればその選択を送信する。項目欠落、端末一覧にない有効コード、遅延応答、nullを含む下書き・一時入力の復元、元投稿の言語継承。投稿単位の変更でサーバー初期設定を更新しない |
| 端末内ワードミュートの廃止 | 旧設定を含むJSONでも他の設定・公式フィルター・下書きを保持し、設定保存で旧項目を除去。管理項目を表示せず、本文・CWの文字列だけでは投稿や通知を隠さない |
| フィルター表示 | context・期限・複数一致・未知動作・status_matches、ブースト、通常カードと通知内プレビュー、アカウントのミュート・ブロックとの併用 |
| 通知欄の警告 | 返信／メンションと簡易プレビューの両経路・全通知タブ。展開前は本文・投稿者CW・メディア等が見えず、理由はフィルター名だけ。展開／再折りたたみと詳細遷移が競合せず、投稿者CWを自動展開しない。hide優先・非表示通知の新着除外、投稿なし通知、アカウント／適用警告変更時の状態リセット、同じ設定の再取得時の状態維持 |
| フィルター更新 | 成功応答だけで設定を更新。作成結果不明時の重複抑止、保存失敗、投稿取得失敗時の反映待ち、削除・期限切れ後の再表示 |
| 回収・旧保存形式 | 新しい設定キャッシュの登録削除、旧下書き・旧投稿キャッシュの読み込み、他アカウントと共通設定の保持 |

既存のModerationManagementRepositoryTest・AccountModerationRepositoryTest・ModerationManagementViewModelTest・AccountModerationMenuTest・AccountModerationIntegrationTest・ServerFilterIntegrationTest・AccountDataRemovalTest・ComposeReplyTest・ComposeQuoteTest・[ComposeLanguageTest][language-test]・[MastodonStreamingDataSourceTest][stream-test]・[NotificationContentDeviceTest][notification-content-test]等を拡張し、未カバーの振る舞いだけを追加する。通知の集約・接続確立時の再確認・取得中の追加通知は同期処理側でも検証する。
検証範囲は[開発ガイド](../project-setup.md#ビルドと確認)に従う。共有状態・セッション境界への変更を含むため、影響するテストを先行し、統合時は影響に応じて単体全体とDebugビルドへ広げる。

## 確認範囲と残る互換性確認

調査開始時のコード・既存テスト・プロジェクト文書と公式API文書を照合し、採用方針を実装へ反映した。MockWebServerと単体テストで取得・保持・操作競合・セッション隔離・作成結果不明・投稿初期値を、エミュレーターで通知警告と管理操作を確認した。認証済み実サーバーでの他クライアントからの変更、サーバーごとの権限・API・Streaming互換性は未確認。
フィルターv1、初期引用許可・初期言語のサーバー編集、Android通知への公式フィルター適用、オフラインでの完全な再照合は含めない。既存のAndroid変更パスがCIの共通チェックを対象にするため、CIコマンド・使用ツールの変更は不要。リリース範囲は変更していない。

[api]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonApi.kt
[status-dto]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/dto/StatusDto.kt
[instance-dto]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/dto/InstanceDto.kt
[account-dto]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/dto/AccountDto.kt
[timeline]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultTimelineRepository.kt
[timeline-contract]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/repository/TimelineRepository.kt
[stream-source]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonStreamingDataSource.kt
[stream-model]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/model/SocialModels.kt
[stream-test]: ../../app/src/test/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonStreamingDataSourceTest.kt
[preferences]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/preferences/UserPreferencesStore.kt
[composer]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/compose/ComposePostViewModel.kt
[composer-screen]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/compose/ComposePostScreen.kt
[language-dialog]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/compose/PostLanguageDialog.kt
[language-test]: ../../app/src/test/java/io/github/ponpokoo/mastodonclient/feature/compose/ComposeLanguageTest.kt
[composer-models]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/model/ComposerModels.kt
[settings]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/settings/SettingsScreen.kt
[management]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/settings/ModerationManagementViewModel.kt
[management-screen]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/settings/ModerationManagementScreen.kt
[moderation]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/model/AccountModeration.kt
[moderation-menu]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/AccountModerationMenu.kt
[status]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/model/TimelineStatus.kt
[moderation-filters]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/ModerationFilters.kt
[moderated-state]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/ModeratedState.kt
[status-card]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/status/StatusCard.kt
[notification-ui]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/notifications/NotificationsContent.kt
[notification-content-test]: ../../app/src/androidTest/java/io/github/ponpokoo/mastodonclient/NotificationContentDeviceTest.kt
[home-cache]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/HomeTimelineLocalDataSource.kt
[notification-cache]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/NotificationLocalDataSource.kt
[account-cleanup]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/local/AccountDataLocalDataSource.kt
[session-vm]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/SessionScopedViewModel.kt
[session]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/session/BrowsingSession.kt
[main]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/main/MainSessionViewModel.kt
[navigation]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/navigation/AppNavigation.kt

[server-model]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/model/ServerAccountSettings.kt
[server-contract]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/domain/repository/ServerAccountSettingsRepository.kt
[server-data]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultServerAccountSettingsRepository.kt
[filter-dto]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/dto/FilterDto.kt
[filter-editor]: ../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/settings/ServerFilterEditor.kt
