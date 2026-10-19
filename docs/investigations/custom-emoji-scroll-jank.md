# カスタム絵文字が多い投稿の往復スクロール調査

2026-10-19。対象は`1d3d452`を基点とする作業ツリー。既存のタイムライン・メディア改善の未コミット差分を含む2.5.5 Debugで測定した。探索の実投稿での観測を踏まえ、本文・リアクションの寸法保持と一括反映、本文画像の描画集約を実装した。一時診断コードは撤去済み。スクロール位置の維持を優先し、総合的な滑らかさは実機での確認を残す。

## 分かったこと

修正前の探索投稿では、リアクション20個の欄が再表示時に404→509pxへ変わった。修正後は、同じ20個の欄で最初から509pxを使うことを確認した。本文・リアクションの確定済み寸法を画像の読み込みから独立して保持し、再表示の画像待ちで仮寸法へ戻す経路を修正している。

本文の512 URL上限による寸法消失も修正した。最近のURLのキャッシュは512件のままとし、投稿データが保持されている間は別に確定寸法を保持する。520種類を含む本文の再表示と、本文・リアクションの画像再読み込み中のスクロール位置維持を回帰テストの対象とする。

以下はAndroid 17エミュレーター上のDebugでの観測で、配布版の実機性能や各要因の寄与率は未確認。

## 修正後の実装と確認範囲

- [`EmojiDimensionsCache`](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/EmojiDimensionsCache.kt)は、投稿データに対応する弱参照のキーでURLと縦横比だけを保持する。データが回収された後の項目は次のアクセスで除去する。画像・Painter・投稿そのものは保持しない。同じURLを持つ別の投稿への再利用用には、独立した上限512件の最近のURLキャッシュを使う。
- [`rememberEmojiImages`](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/EmojiImages.kt)で本文とリアクションの寸法処理を共有する。ImageLoader別に隔離し、Painterと要求はCompositionのライフサイクルに従う。読み込み状態はコルーチン側で監視し、全要求が成功または失敗したところで寸法をまとめて反映する。失敗した画像は既知の寸法、未知なら正方形を使う。
- 本文は通常のテキスト用placeholderで折り返しを計測し、その位置へ画像をまとめて描く。出現数分の`Image`・modifier・画像用semanticsの生成を省く。本文のshortcode、リンク注釈、文字拡大時の配置は維持する。
- 読んでいる位置の維持を優先する。本文・リアクションとも、行を画面外で破棄し、画像キャッシュを削除して戻った後も、保存した行と83pxのスクロールオフセット、確定済みの配置を画像待ちの前後で維持する端末テストを追加した。

同じ合成条件での初回を除く3回の範囲は次のとおり。`DRAW_DURATION`はWindowの描画時間、計測は本文のsubcompositionを含む。実行順・JIT・エミュレーター負荷を含むため厳密な改善倍率とは扱わない。

| 静止絵文字128個 | 修正前の本文計測 | 修正後の本文計測 | 修正前の最大描画時間 | 修正後の最大描画時間 |
| --- | --- | --- | --- | --- |
| 1 URLの反復 | 61.1〜74.5ms | 43.7〜54.7ms | 27.9〜39.5ms | 1.3〜2.9ms |
| 128 URL | 89.4〜115.6ms | 73.6〜94.7ms | 22.2〜24.7ms | 2.4〜2.9ms |

探索の実投稿では、修正後の20個の欄が509pxを維持した。一方、画面全体のフレーム統計は測定環境の状態で大きく変わり、修正版の温まった3往復で174フレーム中164件、同じ時点の環境でリアクション処理だけを修正前へ戻した比較版でも134フレーム中131件がjank判定だった。この値から総合的な滑らかさの改善率は確定しない。リアクションのPainter生成を計測箇所の外へ移したため、欄単体の計測時間の倍率も速度改善の根拠に使わない。

520種類の回帰テストはデコード済みの合成画像を使い、ネイティブデコーダーを数百件同時実行する負荷から寸法保持の検証を切り離した。実デコーダーを通す性能比較は128 URLまでであり、520種類の初回デコードを高速化した確認ではない。動く絵文字の再デコードと再生中の継続的な描画は残る。

キャッシュ・shortcodeの単体テスト、文字拡大・実描画・読み上げ・リンク・リアクション操作の既存端末テストと、新規の寸法保持・スクロール位置の回帰テストで確認する。CIは既存の`app/`と`app/src/androidTest/`の選択範囲に含まれるため、パス・コマンド・ツール版の更新は不要と判断した。修正後のCSV・実投稿のログ・トレースと比較版の結果は、Git管理外の`app/build/outputs/emoji-reentry-measurements/fixed/`に保存した。

以下は原因と比較条件を残すための修正前の記録。ソース中の変数・関数名も当時の実装を指す。

## 修正前：探索タブの実投稿

ログイン済みの探索→投稿を使い、表示済み範囲を縦に3往復した。実投稿には本文よりもリアクション欄に多数の横長・動く絵文字があった。本文・投稿者名・URL・認証情報を診断ログへ記録せず、同じCompositionを識別する一時番号、リアクション数、寸法、時間、画像キャッシュ種別だけを取得した。

一時的に[`StatusCard.kt`](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/status/StatusCard.kt)のリアクション用`BoxWithConstraints`の`measure()`前後と、画像の`onSuccess`を計測した。`DisposableEffect`で生成・破棄も記録し、`atrace`の`gfx view dalvik`と`dumpsys gfxinfo ... framestats`を併用した。計測時間には子のsubcompositionと診断ログの費用も含むため、通常版の正確な時間とは扱わない。

| 実投稿のリアクション数・再表示 | リアクション欄の高さ | 最大計測時間 | 画像のメモリ / ディスク / 通信 |
| --- | --- | --- | --- |
| 20個・2往復目の復帰 | 404 → 509px | 71.9ms | 15 / 5 / 0 |
| 同じ20個・3往復目の復帰 | 404 → 509px | 81.5ms | 15 / 5 / 0 |
| 13個・2往復目の表示 | 299 → 404px | 72.2ms | 7 / 6 / 0 |
| 同じ13個・3往復目の表示 | 299 → 404px | 47.4ms | 7 / 6 / 0 |

20個の欄は各復帰で4回計測され、高さが約40dp増えた。メモリから返る静止画の寸法は初回計測中にも反映されるが、遅れて完了する画像の寸法でさらに`FlowRow`の折り返しが変わった。3往復目では同じ一時番号の欄の破棄→再生成も記録されている。通信取得を伴わずに症状が繰り返される経路を確認できた。

診断ありの約10秒間の往復区間では、383フレーム中170フレーム（44.39%）が端末のjank判定となり、p95は65ms、p99は150msだった。停止中だけの約3.1秒間でも139フレームを生成し、46フレームがjank判定となった。動く絵文字を表示する画面の継続的な描画負荷とも整合する。ただし、これらは画面全体の値で、個々の絵文字のGPU負荷や再デコード時間を分離した測定ではない。

### 修正前のコード

リアクション画像の`imageRatio`は`remember(reaction.imageUrl) { mutableStateOf(1f) }`にだけ保持される。行が画面外で破棄されると失われ、再表示では正方形から始まる。本文用の`emojiRatioCaches`は参照されない。

`AsyncImage.onSuccess`が各画像の縦横比を個別に反映し、`Modifier.width((24f * imageRatio).dp...)`の幅が変わる。縦長の場合は名前のテキストも追加される。外側は`FlowRow`なので、幅や子要素の変更が折り返しと投稿高の変化につながる。実測の404→509pxはこの経路と一致する。

改善は、リアクション画像の確定寸法を再表示まで保持すること、画像完了ごとの寸法変更をまとめること、同じ欄の再生成・計測を減らすことを先に検討する。本文のキャッシュ上限だけを広げても、この経路は変わらない。

一時診断コードは撤去し、診断なしのDebug APKを再ビルドした。ログ・集計CSV・トレース・診断コードの控えはGit管理外の`app/build/outputs/emoji-reentry-measurements/live-explore/`に保存した。再現操作は、探索→投稿でリアクションの多い行を表示し、行が破棄される距離まで画面外へ出してから戻る。読み込み後の幅・高さとフレーム時間を別々に確認する。

## 修正前：本文の合成テストの条件と測定方法

- Android 17、`emulator-5554`（`sdk_gphone16k_x86_64`）、画面密度2.625、Coil 3.2.0。
- 幅300dp、高さ500dpの`LazyColumn`に本番の`StatusContentText`を配置。絵文字高は本番と同じ24dp。投稿カードの他の要素は含めない。
- 正方形の静止PNGまたは2フレーム・20fps・無限ループのGIFを128個表示。同じURLの反復と128 URLを分ける。対照は`text `を128回繰り返した通常本文。
- 初回表示後、25番目の行へ移動して対象を画面外へ出し、先頭へ戻る操作を3回行う。移動は`scrollToItem`で、フリングの性能測定ではない。
- 独自Fetcherが合成画像の符号化済みバイトを`DataSource.DISK`として返す。通常のデコーダーとメモリキャッシュを通すが、実ネットワーク・HTTPキャッシュ・ディスクI/Oは使わない。
- Coilの要求・メモリヒット・デコード回数、本文の`Modifier.layout`内の`measure()`所要時間と高さ、Windowの`FrameMetrics`を記録する。本文の計測時間には`BoxWithConstraints`内部のsubcompositionも含まれる。Windowの`LAYOUT_MEASURE_DURATION`と同じ範囲の値ではない。
- 表示が落ち着いた後の500msのWindowフレーム数も記録する。フレーム数は絵文字ごとの描画回数やjank率ではない。

## 修正前：本文の同じ位置へ戻ったときの結果

初回を除く3回の範囲。要求・ヒット・デコード回数は1回の再表示あたりの値。

| 本文 | 要求 / メモリヒット / デコード | 本文の再生成・計測 | 再表示期間の最大フレーム時間 | 停止中500msのフレーム数 |
| --- | --- | --- | --- | --- |
| 通常文字 | 0 / 0 / 0 | 1.5〜2.4ms | 17.5〜19.8ms | 0 |
| 静止絵文字128個・1 URL | 1 / 1 / 0 | 61.1〜74.5ms | 98.6〜124.8ms | 0 |
| 静止絵文字128個・128 URL | 128 / 128 / 0 | 89.4〜115.6ms | 119.0〜147.5ms | 0 |
| 動く絵文字128個・1 URL | 1 / 0 / 1 | 45.1〜45.2ms | 82.2〜84.2ms | 10 |
| 動く絵文字128個・128 URL | 128 / 0 / 128 | 93.0〜115.0ms | 158.9〜161.2ms | 11〜23 |

絵文字本文は再表示ごとに計測1回、高さ693pxで変化しなかった。静止画の全メモリヒット・再デコードなしでも重いため、画像の先読みや寸法キャッシュの拡大だけでは負荷が残る。同じ絵文字1種類を繰り返す場合にも発生しており、種類数だけでなく出現数にも影響される。

静止画の再表示フレームではWindowの`DRAW_DURATION`最大値も約22〜39msだった。ただしGPUの処理時間を個別に測っていないため、GPUが主因とは断定しない。静止画とGIFの時間の大小も、Debug実行・JIT・実行順の影響を含むため、形式自体の速度比較には使わない。

## 修正前：本文の再表示時のサイズ再確定

横長5:1の絵文字8個で測定。正方形の仮寸法は63×63px、確定寸法は315×63px。別の520 URLを本番の表示経路で読み込んで縦横比キャッシュを追い出した。

| 条件 | 計測で観測した本文高 | 結果 |
| --- | --- | --- |
| 初回読み込み | 63 → 252px | 読み込み完了時に再確定 |
| 縦横比あり、画像キャッシュのみ削除して読み込みを保留 | 252px | 画像待ちでも確定寸法を維持 |
| 縦横比を追い出し、画像キャッシュも削除 | 63 → 252px | 仮寸法から再確定 |
| 縦横比だけ追い出し、画像は8件すべてメモリヒット | 63 → 252px | デコード0件でも再確定 |

「画像をキャッシュしていればレイアウトも安定する」とは限らない。一方、512 URL以内で縦横比が保持される通常の再表示では、今回のテストでサイズの揺れは発生しなかった。単一投稿に512種類を超える絵文字がある場合も、全種類の寸法を同時に保持できないことがコードから分かるが、この条件の往復測定は未実施。

## 修正前の本文の実装との対応

[`CustomEmojiText.kt`](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/CustomEmojiText.kt)の`rememberSizedEmojiInlineContent`が本文用の経路。

- URL単位でPainterを共有しているため、同じ絵文字128個から128要求は発生しない。ただし、`InlineTextContent`の`Image`は本文中の出現数だけ生成・配置される。LazyColumnが遅延生成する単位は投稿で、投稿内の絵文字ごとには遅延生成しない。
- PainterとFlow購読はComposition内の`remember`に留まる。行が破棄された後の再表示では再作成・再要求される。HTML変換には別のプロセス内キャッシュがあるが、shortcode置換・inline要素・テキスト計測結果まで保持する仕組みではない。
- 各Painterの状態を親のCompositionで読み、状態が変わるたびに`states`とinline contentのMapを組み立てる。寸法の反映は全URLが成功または失敗するまで待つため、新規の寸法確定はまとめられるが、途中の状態更新自体は残る。遅い要求が1件あると他の新規寸法の反映も待つ。
- 縦横比は`LruCache(512)`、初期値は`1f`。Painterの成功から`LaunchedEffect`で実寸を反映するため、縦横比が失われていればメモリヒットでも仮寸法での計測が入り得る。
- デコード要求は常に`size(1024, 128)`。24dpの表示に対して過大な画像になる可能性はあるが、今回の32px合成画像では影響を測っていない。

動く絵文字の再デコードはCoil 3.2.0の実装とも一致する。`AnimatedImageDecoder`が返すDrawableは`asImage()`により共有不可となり、`MemoryCacheService`がメモリキャッシュへの保存を拒否する。ディスクに符号化済み画像が残ることと、デコード済み画像を再利用できることは別である。[デコーダー](https://github.com/coil-kt/coil/blob/3.2.0/coil-gif/src/main/java/coil3/gif/AnimatedImageDecoder.kt)、[Drawableの画像変換](https://github.com/coil-kt/coil/blob/3.2.0/coil-core/src/androidMain/kotlin/coil3/Image.android.kt)、[メモリキャッシュ条件](https://github.com/coil-kt/coil/blob/3.2.0/coil-core/src/commonMain/kotlin/coil3/memory/MemoryCacheService.kt)を参照。

本文の絵文字はRepositoryで`shortcode → url`に変換され、静止画用の`static_url`を保持していない。`gifAutoplay`は添付の`gifv`に使われ、本文の絵文字には渡らない。このため添付GIFの自動再生を止めても、本文の動く絵文字の再生負荷は残る。

## 当初の本文の改善候補

1. 絵文字の出現数に比例するinline `Image`の再生成・計測・描画を減らす。Mapやplaceholderの再利用を試し、十分でなければ描画要素を集約する方式を比較する。リンク操作、読み上げ、折り返し、文字拡大時の配置を維持する必要がある。
2. 動く絵文字の静止表示やスクロール中の再生制御を検討する。`static_url`をドメインまで保持する変更と、利用者向け設定の対象範囲の決定が必要。共有不可のDrawableを無理にキャッシュする方法は採らない。
3. 縦横比の保持を画像キャッシュから独立して見直す。表示中・再表示予定の投稿の寸法を守る方法を比較し、上限を増やすだけで解決扱いにしない。

修正の効果は同じ合成条件で比較したうえで、R8有効のReleaseと問題の実投稿を使い、実機のフレームトレースで確認する。

## 本文の合成テストの再実行

再現コードは[`StatusEmojiReentryMeasurementDeviceTest`](../../app/src/androidTest/java/io/github/ponpokoo/mastodonclient/StatusEmojiReentryMeasurementDeviceTest.kt)。Android 28以上の専用エミュレーターを使う。生成GIFのデコーダーを固定しているため、Android 26〜27の`GifDecoder`経路は対象外。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class io.github.ponpokoo.mastodonclient.StatusEmojiReentryMeasurementDeviceTest io.github.ponpokoo.mastodonclient.test/androidx.test.runner.AndroidJUnitRunner
```

端末内の`files/emoji-reentry-measurements/mounts.csv`と`geometry.csv`に測定値を出力する。`adb exec-out run-as io.github.ponpokoo.mastodonclient cat files/emoji-reentry-measurements/mounts.csv`などで取り出せる。今回のCSVはGit管理外の`app/build/outputs/emoji-reentry-measurements/`にも回収した。

調査テスト2件と既存の`StatusEmojiLayoutDeviceTest`2件が成功。寸法条件の拡張後は対象の調査テスト1件のみ再実行して成功した。調査ハーネスの初回実行ではLazyColumnの位置保持により対象が画面外になる問題があり、対象位置を明示してから測定した。表は修正後に成功した実行の値。
