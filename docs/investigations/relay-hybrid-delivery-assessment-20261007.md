# Relayのハイブリッド配送の調査・比較（2026-10-07）

- 状態：実装前の調査。ハイブリッドを第一候補として維持する。実端末の保持履歴62件を匿名集計済み。全利用者のサイズ分布は未確定。
- 前提：100人×2アカウント×1端末＝200購読、6,000通知/日、月額0円。
- 対象：現在の作業ツリーと[100人向け合成測定](relay-100-user-measurement-20261007.md)。
- Android・Relayの配送実装、購読、公開設定は変更していない。提案の採用・公開可の判定ではない。
- 利用者の許可後、端末内で匿名集計する計測テストだけを追加した。本体APKは更新していない。

後続作業：利用者の指示で[Relay測定用候補](../../relay/workers/hybrid.md)を実装し、
[専用Cloudflare環境で100人条件の資源評価](relay-hybrid-100-user-measurement-20261007.md)を完了した。
以下は実装前の比較記録。後続測定でもハイブリッドを第一候補として維持する。Androidの同期対応は未実施。

採用後の追記：2026-10-07、利用者の指示で[ハイブリッド方式を採用](../adr/0013-adopt-hybrid-push-delivery.md)した。
以下の「第一候補」は当時の比較判断。現行の方針・未完了作業は[採用仕様](../../relay/workers/hybrid.md)を参照する。

## 1. 調査で分かったこと

**小さい暗号文は保存せずinlineで直送し、大きい暗号文は同期トリガーへ置き換える案は、
現行の主要なD1負荷を除去しながら、inline通知のAPI取得なしの表示を維持できる。**
実端末に残る受信履歴62件はすべてinlineかつ暗号文1KiB以下だった。ただし全受信履歴ではなく、
全利用者のinline比率は未確定。前回の合成負荷の90%も実利用の値として採用しない。
割合が低くてもD1本文保存の削減は成立するが、全Push同期方式に対する端末側の利点は小さくなる。

| 調査項目 | 確認結果 |
| --- | --- |
| 受信する暗号文のサイズ | 実端末の保持履歴62件はすべて1KiB以下。個別バイト数は取り出していない。現行上限64KiB、合成投入は1,024／4,096／65,536バイト |
| inline／fetch比率 | 実端末の保持履歴は62／0（100%／0%）。全受信・全利用者の比率は未確定。合成投入の設計値は90%／10% |
| D1に保存する対象 | TTLが正で受付に成功する全Push。inlineも保存→claim→送信→成功時DELETE。fetch成功分はTTLまで保持 |
| 直近の実利用サイズ帯 | 実端末の保持履歴：≤1KiBが62、他4帯が0。本番D1の保持0件だけでは分布を判断できない |
| 消せるD1処理 | 全体／登録別COUNT、本文INSERT、claim、成功後DELETE／UPDATE、再試行UPDATE、本文GETと取得時の頻度記録 |
| 不要になる保存・再送・Cron | 新方式へ移行済みの購読ではmessages・due・リース・永続再試行・毎分の配送Cronを除去可能。登録／頻度記録の清掃は残る |
| Android API増分 | 大きい通知の割合q、同期の集約と取得ページ数に依存。集約なし・1ページなら6,000q要求/日。回復同期等は別途 |

### 実利用の確認範囲と不足

2026-10-07の19時台（JST）、Cloudflareの本番D1 `nagisa-relay`で読み取り専用の集計SQLを実行した。
本文・配送先・トークン・登録IDを結果へ出さず、保持件数、サイズ帯、inline適合数だけを集計した。
保持0、未送信0、期限内0。本文の最小／最大はnull、サイズ帯と方式別件数はすべて0だった。
本番への通知投入、設定変更、清掃、購読変更は行っていない。

inline成功後は削除され、fetchも失効後に清掃される。
仮に保持行が残っていても、D1残存行の分布は大きい通知や失敗通知に偏り、全受信Pushの比率を表さない。
過去のFedibirdでの少量実配送・表示の記録にも暗号文サイズや方式の内訳はない。

接続済みエミュレーターにWorkManager DBがあることは確認した。
DB／WAL全体のコピーは、通知や識別子など広いアプリ状態を含み得るとして自動承認レビューが却下した。
コピーや本文取り出しは実行していない。端末内でサイズ帯のみを集計する方法、または匿名の受信集計が追加で必要。
この不足を合成データやMastodon最新版の実装から推測した数値で埋めない。

利用者から匿名計測の許可を受け、
[PushPayloadSizeStatisticsDeviceTest](relay-push-size-statistics/tests/PushPayloadSizeStatisticsDeviceTest.kt)
を追加した。SQLiteは端末内で読み取り専用で開き、外へ出す値はカウンターだけ。
保存済みPixel_10aのDebug 2.4.1／16で履歴集計は1件成功し、受信Workは0件だった。
初回の計測プロセスは起動失敗し、再実行で成功した。失敗原因は特定していない。
計測用に一時起動したエミュレーターは、利用者の実端末で確認する指示に従って終了した。
同日の実端末（Nagisa Release 2.4.1／16）では、同じRelease署名の計測APKのみを追加し、
送信前後の履歴集計が成功した。本体APK・保存データの置換や購読設定の変更はしていない。
最初は保持受信Work 57件、inline 57、fetch 0、未対応／解析不能0。
利用者が実通知を5件送った後は62件、inline 62、fetch 0、未対応／解析不能0。
暗号文のサイズ帯は≤1KiBが62、他の4帯が0。増分5件はいずれもinline／≤1KiBに収まる。
観測開始時の有効購読は3件。新規Workの180秒観測はADB通信終了で最終結果を回収できず、
この5件は送信前後の保持件数の差として記録した。通知IDを照合した個別追跡や表示遅延の測定ではない。
受信時刻・本文・識別子は取得していないため、履歴の期間やテスト通知混入の有無は確定できない。
この標本は小さい通知を直送する案を支持するが、全利用者の比率100%を示さない。
[匿名集計結果](relay-push-size-statistics-20261007.json)に送信前後のカウンターと確認範囲を保存した。

計測APKには`pushSizeStats=retained`（履歴集計）と`pushSizeStats=observe`（10〜180秒の新規Work観測）を用意した。
fetchの履歴だけでは本文サイズが分からない。新規観測では現在の購読・認証との紐付けを端末内で照合し、
通常の認証付きRelay GETで暗号文を読み、長さと配送JSON適合数のみを数える。
配送JSONはAndroidで再構成した参考値で、エスケープ差を含み得る。実際のinline／fetchを方式比率の基準にする。
Web Push本文は復号せず、通知を自作・送信せず、計測による取得回数を別カウンターで報告する。
古い履歴やテスト由来Workが混ざり得るため、履歴スナップショットを全受信の実利用分布と扱わない。
ADB通信終了への対策として、観測カウンターだけを端末の一時キャッシュに保存するようにした。
初回は計測APK側の保存先に書けず失敗し、対象アプリの外部キャッシュへ修正した。
修正後の10秒観測は匿名結果の`phase=complete`を回収でき、新規受信0、計測用GET 0だった。
この0件を利用者の5件の結果と混同しない。5件の確認には前述の57→62の履歴差を使う。

この計測器は旧v1のinline／fetchを対象とする過去調査用コードとして保管し、通常のAndroidテストから外す。
v2 `sync_required`は未対応扱いになるため、現在のハイブリッド方式のサイズ分布確認には使わない。
旧方式の調査に必要な場合だけ、[専用init script](relay-push-size-statistics/investigation.init.gradle)を指定してテストAPKを生成する。

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --offline --no-configuration-cache `
  --init-script docs/investigations/relay-push-size-statistics/investigation.init.gradle
```

計測を実行する際は、テストクラスと`pushSizeStats=retained`または`pushSizeStats=observe`を明示する。

## 2. サイズ判定と分布

本書ではKB帯をKiB（1,024バイト）で定義し、受信した**暗号文のバイナリ長**を分類する。
平文の文字数・Base64文字数・FCM配送データの長さを混同しない。

| 暗号文サイズ帯 | 実利用 | 前回の通常合成投入 | 現行のinline適合の目安 |
| --- | --- | --- | --- |
| ≤1KiB | 保持履歴62件（100%） | 90%（ちょうど1KiB） | 空の暗号ヘッダーなら適合。大きいヘッダーを含む場合は個別判定 |
| >1〜2KiB | 保持履歴0件 | 0% | 空の暗号ヘッダーなら適合。ID長やヘッダーも含めて判定 |
| >2〜3KiB | 保持履歴0件 | 0% | 帯の途中で切り替わる |
| >3〜4KiB | 保持履歴0件 | 9%（ちょうど4KiB） | 現行エンベロープではfetch |
| >4KiB | 保持履歴0件 | 1%（64KiB） | 現行エンベロープではfetch |

[deliveryData](../../relay/workers/src/protocol.mjs)はFCMのdataにするJSONをUTF-8化して3,500バイト以内ならinlineにする。
暗号文はBase64URLで約4/3へ増え、登録ID・メッセージID・encoding・暗号ヘッダー・transportも加わる。
FCMの通常の上限4,096バイトはキーと値を含む。JSON全体3,500バイトは現行の保守的な判定であり、
受信暗号文を「4KB以下なら直接配送できる」と分類するのは誤り。
[FCMサイズ仕様](https://firebase.google.com/docs/cloud-messaging/error-codes)

現行の関数を使ったサイズ計算では、aes128gcm、暗号ヘッダー`{}`、43文字の登録IDとメッセージIDで、
暗号文1,024バイト→配送JSON1,571バイト、2,048→2,936、2,560→3,619、4,096→5,667。
inlineとなる最大暗号文長は2,471バイト。登録IDが128文字なら2,407バイト。
実際の暗号ヘッダーが増えると閾値は下がる。これは実通知のサイズ測定ではなく、現行形式の計算。
前回の合成本文はサイズ負荷用であり、Androidで復号・表示できる実通知の標本ではない。

ハイブリッド候補でも最初はこの3,500バイト判定を維持し、境界拡大は別の実FCM確認後に判断する。

## 3. 現行経路と削減できるD1処理

主要ファイル：

- [worker.mjs](../../relay/workers/src/worker.mjs)：Push受付、背景送信、本文GET、自動Cron。
- [store.mjs](../../relay/workers/src/store.mjs)：購読照合、受付制限、本文保存、リース、再試行、清掃。
- [protocol.mjs](../../relay/workers/src/protocol.mjs)：VAPID検証、本文形式、サイズ判定。
- [fcm.mjs](../../relay/workers/src/fcm.mjs)：OAuthキャッシュ、FCM送信、エラー分類。
- [0001](../../relay/workers/migrations/0001_initial.sql)・[0002](../../relay/workers/migrations/0002_bound_registrations.sql)：messages・索引・登録・頻度記録。
- [wrangler.jsonc](../../relay/workers/wrangler.jsonc)：毎分Cron。Cloudflare Queuesのbinding／consumerはない。

正常なTTL正のPushは、D1で通常6文を実行する。
destination SELECT、throttle UPSERT、admit UPSERT、enqueue INSERT、claim UPDATE、complete DELETE／UPDATE。
新方式ではenqueue・claim・completeの通常3文が不要になる。
ただし鍵の検証中の解除・revision変更・トークン更新を確認するSELECT等が必要で、単純に3文へ減ると確定しない。
安全確認を1文加える候補なら、通常6→4文、6,000件で36,000→24,000文/日。
これはSQL文数の概算で、課金対象のrows_read／rows_writtenの削減率ではない。

| 現行処理 | ハイブリッド候補での扱い |
| --- | --- |
| enqueue内の全体COUNT・登録別COUNT | 本文の保存容量制限が不要になり除去。登録容量の確認は残る |
| 本文INSERTとmessagesの索引更新 | 全Push分を除去。通知1件ごとの保存バイトも0にできる |
| claim UPDATE・lease_id／lease_until | 永続配送ジョブがなくなれば除去 |
| 成功時inline DELETE／fetch delivered UPDATE | 除去。FCM結果を成功ごとにD1記録しない |
| attempts・next_attemptと再送検索due | 永続再送を廃止するなら除去 |
| fetchのGET処理 | 新方式分では除去。現行は正常取得1回につきSELECT3文＋頻度UPSERT1文 |
| pruneの本文期限・登録別本文削除 | 新方式分では除去 |
| 毎分Cron | 配送用途は除去。現行は滞留0でも清掃5文＋due1文、計8,640文/日 |
| 登録・仮登録失効・無効トークン・墓標 | 維持。本文清掃と登録清掃を分け、後者は日次等へ変更を検討 |
| 日次上限・購読別頻度制限・古い頻度記録の削除 | 維持。毎分Cronを止めるだけでは古いカウンターが残る |

前回測定では通常Pushの書込10,093行/1,000件、全fetchのPush＋取得11,809行/1,000件。
新方式で本文・索引・リース・完了・取得時頻度記録を除去できるが、既存記録はSQLごとの行数を分解していない。
したがって「何行／何%減るか」の実測値はまだない。
特にインデックス更新やカウンター行の新規作成・削除も書込行に含まれるため、文数から行数を置き換えない。
[D1使用量の数え方](https://developers.cloudflare.com/d1/platform/pricing/)

全fetchでの`0 + ... + 5,999 = 17,997,000`行の全体COUNTモデルは、本文を保存しない2候補とも除去できる。
これは旧方式の日次外挿モデルであり、改修候補の読取行数が0になるという意味ではない。
UNREGISTEREDの無効化にはmessagesのリースガードを使えなくなるため、登録・トークン・revision照合で置き換える。

## 4. Android APIリクエスト増分の概算

N=6,000通知/日、q=現行判定でinlineに入らない割合。
Gはアカウント／セッションごとに集約後の同期回数、pは1同期の平均API要求数
（ページ取得・空の終端確認・再試行を含む）、Rは起動・画面更新・FCM欠落補完等の追加要求数とする。

- 現行：Push起因のMastodon API要求は0。代わりにRelay本文GETは約Nq回、取得再試行は別。
- 全Push同期：Push起因は約G_all×p。集約なし・各1ページならN回。
- ハイブリッド：Push起因は約G_large×p。集約なし・各1ページならNq回。
- 合計には各方式の補完Rを加える。現在すでに行う画面更新や別設定の簡易通知pollingを二重加算しない。

| q（仮定） | 現行Relay本文GET/日 | ハイブリッドのPush起因API/日 | 全Push同期のPush起因API/日 |
| --- | --- | --- | --- |
| 1% | 60 | 60 | 6,000 |
| 10% | 600 | 600 | 6,000 |
| 30% | 1,800 | 1,800 | 6,000 |
| 50% | 3,000 | 3,000 | 6,000 |
| 100% | 6,000 | 6,000 | 6,000 |

表は集約なし・各1ページ・失敗なし・Rを除く。実利用qは不明。
前回の合成比率q=10%を仮に使うと、ハイブリッドは600要求/日＝利用者平均6要求/日。
全Push同期は6,000要求/日＝利用者平均60要求/日。実測のAPI件数ではない。
ハイブリッドの600要求は既存のRelay GET600回を置き換えるため、追加HTTP回数は単純な+600ではない。
通知REST応答は投稿・投稿者等を含み、1要求あたりのバイト数は暗号文GETと異なる。

同一アカウントの大きいPushが平均3件まとまるなら、G_largeは概ね600/3=200回/日。
ただしアカウントをまたいで集約せず、疎な通知では集約効果がない。
通常の仮定では1購読30件/日なので、バースト測定で得た集約率を通常日へ流用しない。
平均p=2なら要求数も2倍。差分にinline表示済みの通知や欠落通知も含まれるので、
qは同期の起動数を決めても、取得する通知数やページ数を直接決めない。

起動等で200購読を1日2回・各1要求で補完する別の仮定ではR=400要求/日。
これは画面表示やPush同期と重複しない追加分だけのモデルで、必須の同期頻度として採用した値ではない。
通信量は「FCM配送バイト＋Relay GET／Mastodon API応答バイト＋TLS等」で比較し、
画像等の追加取得を別計上する。実応答サイズ・電池消費・表示遅延は未測定。

## 5. 3方式の比較

負荷・遅延・電池の候補評価はコード構造からの推定。候補版の実測値ではない。

| 比較軸 | 現行 | 全Push同期トリガー | ハイブリッド（第一候補） |
| --- | --- | --- | --- |
| Workers負荷 | 全Pushの保存・送信、fetchのGET、毎分清掃・再送 | 保存／GET／配送Cronなし。VAPIDとFCM送信は残る | 同様に保存／GET／配送Cronなし。小さい暗号文のBase64配送・サイズ判定は残る |
| D1 read/write | 全件COUNT、本文・索引・リース・完了・再送・取得 | 主に登録照合と受付制限。本文関係を除去 | 全同期方式とほぼ同じ削減。inlineも保存しないことが条件 |
| Mastodon API | Push受信そのものでは0。画面更新・簡易通知等は別 | 全Pushの集約後にAPI同期＋補完 | 大きいPushの集約後だけAPI同期＋補完 |
| Android通信量 | 小はFCMのみ。大はFCM＋Relay GET | 小さいFCM＋API応答を毎同期。応答量次第で増える | 小は現行並み。大はRelay GETをAPIへ置換。全同期より抑えやすいが実量は未測定 |
| Android電池 | 小は復号・表示、大は取得。全通知でWork実行 | API通信と同期処理が増えやすい。集約で緩和 | 小は復号・表示を維持、大だけ同期。全同期より抑えやすいという仮説 |
| 通知表示遅延 | 小はAPI不要、大はRelay GET待ち。FCM障害は再送待ち | 同期待ち・API通信・Work実行待ちが全通知に入る | 小のAPI不要経路を維持。大は集約待ち＋API取得。即時表示の保証はしない |
| Push欠落耐性 | Relay→FCM失敗をTTL内で再送。FCM後の欠落補完onDeletedMessagesは未実装 | Relay再送なし。後続Push・起動等の差分同期で回復 | Relay再送なし。大Push／補完同期で回復。小Pushだけ続いても同期は起動しないため共通補完が必須 |
| 実装・保守 | Androidは復号＋fetch、Relayはリース・再試行・清掃を保守 | Relayは簡単。Androidに同期・ページ・カーソル管理。移行後は復号を省ける | Relayはサイズ分岐。Androidは復号＋同期を保守。ただし既存inline経路を再利用できる |

いずれの方式もFCM障害・Doze・ネットワーク・強制停止等による即時OS通知欠落を保証付きで回復するものではない。
「通知一覧の回復」と「元の時刻でのOS通知表示」は別。
FCMのHIGHは利用者に見える通知を想定しており、同期だけを繰り返して表示しない運用では優先度低下の可能性がある。
同期にWorkManagerを使い、実際に届いた優先度と表示結果を確認する。
[Firebaseの優先度・処理方針](https://firebase.google.com/docs/cloud-messaging/android-message-priority)

## 6. Android側で必要な補完と注意点

- [FcmEnvelope](../../app/src/main/java/io/github/ponpokoo/mastodonclient/notification/FcmEnvelope.kt)と
  [受信Repository](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultPushMessageRepository.kt)は
  現行inline／fetchのみを許可する。未知のsync_requiredは拒否されるため、配送契約を拡張・版管理する。
- [FcmWorkScheduler](../../app/src/main/java/io/github/ponpokoo/mastodonclient/notification/FcmWorkScheduler.kt)は
  登録ID＋メッセージID単位。新同期はaccount/session単位とし、認証世代・Relay購読照合を維持する。
- Unique Workだけで集約を完了としない。KEEPだけでは実行終端付近の新Pushを取りこぼし、
  REPLACEだけでは連続Pushで進行中取得を中断し続ける可能性がある。
  永続の同期要求世代／dirty状態と完了時の再確認で、実行中の追加要求を次の差分取得へ反映する。
- [MastodonApi](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonApi.kt)は現在max_id・limit・typesのみ。
  since_id／min_id等を追加し、HTTP Linkや終端確認で対象範囲の全ページを処理する。
  サーバーの件数上限・版・拡張差を考慮し、要求件数未満だけで取得完了としない。
  IDはStringのまま使い、数値比較を追加しない。
  [Mastodon通知API](https://docs.joinmastodon.org/methods/notifications/)
- **inlineで受信した新しい通知IDだけで同期カーソルを進めない。**
  その前に大きい通知や欠落があると飛ばす。全ページ処理済みの同期位置、表示済みID、閲覧既読位置を分ける。
  全ページ取得・保存の完了後に同期位置を進め、途中失敗・ログアウト・世代変更時は進めない。
- [NotificationDeliveryCoordinator](../../app/src/main/java/io/github/ponpokoo/mastodonclient/notification/NotificationDeliveryCoordinator.kt)の
  session＋notification IDによる重複防止は再利用できるが、現行履歴は500件。
  長い未同期期間の再取得や大量欠落を同じ500件だけで保証せず、キャッシュ・同期記録との整合を評価する。
- [NagisaMessagingService](../../app/src/main/java/io/github/ponpokoo/mastodonclient/notification/NagisaMessagingService.kt)には
  onDeletedMessagesがない。追加時は欠落した購読をコールバックから特定できないため、対象の有効なローカル購読へ同期要求を出す。
  起動・通知画面更新・必要な前景復帰にも差分補完を接続し、直近同期との二重取得を避ける。
  [Firebaseの欠落時処理](https://firebase.google.com/docs/cloud-messaging/android/receive-messages)
- 簡易通知pollingは最新40件、表示最大5件の別設定。これだけを全ページの欠落回復と扱わない。
  Push同期とpolling・画面更新・Streamingの表示済みIDを共用する。
- 通知種類、購読のalerts／policy、前景表示設定、セッション削除を維持する。
  API同期でPush対象外の通知も取得し得るため、取得した全項目を無条件にOS通知へ出さない。
- 同期失敗はカーソルを維持して次回回復する。Relayへの本文再送要求は新設しない。
  Relayは小の暗号文にも大の同期トリガーにも購読VAPID検証を行い、Mastodon認証・Web Push秘密鍵を受け取らない。

## 7. 第一候補と次に確定すること

**ハイブリッドを第一候補として維持する。現時点で明確に不利とする実測根拠はない。**
全同期方式と同等に本文関係のD1負荷を除去でき、inline比率が高ければ大部分の追加API通信を避けられる。
実端末の保持履歴62件はいずれも直送対象なので、この標本では大通知による同期起動が0件になる。
起動・欠落回復の同期は別途必要。62件から公開後のq=0やAPI要求0/日を断定しない。
qがほぼ100%ならAPI面の差は小さいが、それだけで即座に全同期へ変更せず、復号分岐の保守負担・実機結果も比較する。

実装前に不足する実測を次の方法で確定する。

1. **サイズ帯とinline適合比率**：受信暗号文のバイト長と実際の配送JSON長を区別する。
   小規模な通常利用を数日観測し、2つ以上の利用対象サーバー、通知種別、長文／日本語も含める。
   全受信数、TTL正の対象数、5帯の件数、inline適合／非適合数、拒否数を匿名集計する。
   本文・トークン・鍵・配送先・アカウントIDを保存しない。標本数と観測期間を明記し、偏りを示す。
   通知ごとにD1へ測定行を追加せず、測定用の集計負荷を本番処理と分ける。
2. **同期コスト**：端末内でAPI要求数、取得項目／ページ数、応答バイト、集約率、失敗・重複表示数を匿名計測する。
   40件を超える未同期・ページ途中失敗・inlineとsync_requiredの順不同・実行中追加Pushを含める。
3. **候補負荷**：採用時は旧配送の購読／APKと分離した候補を用意し、200購読・6,000通知/日と集中負荷を再測定する。
   SQLごとのrows_read／rows_written、日次清掃、残るカウンターの新規／再利用／削除も測る。
   CPU、実FCM、実機の表示遅延・電池・通信も確認する。
4. **移行と運用条件**：旧fetchを使うAPK／購読が残る間は本文GET・保持・再送を先に削除しない。
   新契約の能力交渉または購読別版管理で移行し、旧経路の終了条件を決める。
   実装採用時は[ADR 0003](../adr/0003-push-relay-responsibilities.md)等との関係を新ADRに記録し、
   [既存の再測定計画](../relay-100-user-remeasurement-plan.md)の本文保持・滞留再送要件を新方式向けに見直す。
   旧方式の2時間滞留解消を、新方式で未実施のまま合格扱いにしない。

### 今回行った確認

ソース・既存記録・本番D1の匿名集計・端末内DBの存在を読み取り確認し、現行関数でサイズ計算を行った。
今回の調査で候補配送実装、負荷試験、電池測定は実行していない。
許可後の匿名計測テストAPKのビルドは成功し、エミュレーターの履歴集計1件が成功した。
実端末ではRelease署名の計測APKだけを追加し、送信前後の履歴集計が成功した（57→62件）。
修正した観測カウンターの一時保存も10秒で完了し、結果の回収後に一時JSONと計測APKを端末から削除した。
調査文書・索引・匿名計測テストを追加し、ローカルリンク・差分・計算内容を確認する。

関連：[通知購読管理](../push-settings.md)、[受信仕様](../push-reception.md)、
[配送契約](../relay-protocol.md)、[Workers版](../../relay/workers/README.md)、
[前回測定の匿名結果](../../relay/workers/bench/results/20261007-100.json)。
