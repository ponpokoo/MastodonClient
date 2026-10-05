# 画像付き固定投稿のスクロール調査

調査・修正日: 2026-10-05。
参考動画の確認後、ヘッダー上からの惰性スクロールが投稿一覧へ引き継がれない挙動を再現し、移動量の引き継ぎを修正した。
修正版APKの提示後、同日のユーザー報告で実機でも本件が完全に解決したことを確認した。
動画中のすべての引っかかりの原因を特定したわけではない。

## 報告された条件

複数の画像付き固定投稿があるプロフィールで、下へスワイプして先頭へ戻るとき、
固定投稿どうし、または通常投稿と固定投稿の境目で引っかかる。
追加の依頼に合わせ、固定投稿4件で低速・中速・高速のスクロールを確認した。

以下の速度別・長いヘッダーの調査結果は修正前の記録。修正後の結果は「修正後の確認」に分ける。

## 固定投稿4件の確認

Pixel_10a（Android 17）、Debug版の共通`ProfileContent`に合成データを表示した。
固定投稿4件にはそれぞれ異なる画像を1枚添付した。画像寸法は800×200、600×800、
1000×600、400×400で、API由来の比率情報がない条件を使った。通常投稿は20件。
画像は端末内で生成し、外部アカウントやネットワークは使っていない。

指を画面高さの70%動かすスワイプを、下へ進む方向に4回、先頭へ戻る方向に6回行った。
スワイプ間はComposeの時計を4フレーム進め、惰性移動の途中で次のスワイプを始めた。
最後の惰性移動もフレームごとの投稿位置として記録した。

| 速度 | 1回のスワイプ時間 | 結果 | 保存した結果 |
| --- | --- | --- | --- |
| 低速 | 1200ms | 固定4件を越えて通常投稿へ進み、固定投稿とヘッダーの先頭へ戻った | [XML](profile-pinned-scroll/results-slow-four.xml)、[位置ログ抜粋](profile-pinned-scroll/trace-slow-four.txt) |
| 中速 | 500ms | 同上 | [XML](profile-pinned-scroll/results-medium-four.xml)、[位置ログ抜粋](profile-pinned-scroll/trace-medium-four.txt) |
| 高速 | 120ms | 同上 | [XML](profile-pinned-scroll/results-fast-four.xml) |

各速度の端末テスト1件が成功し、スクロールが止まって次の投稿へ進めない現象は再現しなかった。
低速は一覧インデックス6、中速は7まで進み、戻った後は投稿・ヘッダーともインデックス0・オフセット0だった。
これは位置と操作完了の確認であり、実時間のフレーム落ちがないことを保証する結果ではない。
Composeのテスト時計を使った入力とローカル画像なので、実端末の描画時間や画像通信の遅延は別途確認が必要。

## 添付画像＋URLサムネイルの追加確認

同じ端末・共通プロフィールで、固定投稿4件それぞれに添付画像1枚と、サムネイル画像付きのURLカードを表示した。
本文にもURLリンクを含め、添付画像とURLサムネイルの双方がComposeの表示ツリーにあることを確認してから操作した。
URLカードの画像は640×360、300×450、640×400、512×512で、比率情報なしの条件を使った。
カードの画像も端末内で生成した。`example.test`の合成URLを表示し、リンク先へ通信する操作は行っていない。

投稿が高くなるため、各速度とも下へ進むスワイプを5回、先頭へ戻るスワイプを7回にした。
移動距離・速度・スワイプ間の4フレーム・惰性移動の観測方法は前の確認と同じ。

| 速度 | 1回のスワイプ時間 | 到達した最大インデックス | 結果 |
| --- | --- | --- | --- |
| 低速 | 1200ms | 6 | 成功。[XML](profile-pinned-scroll/results-slow-four-image-url.xml)、[位置ログ抜粋](profile-pinned-scroll/trace-slow-four-image-url.txt) |
| 中速 | 500ms | 8 | 成功。[XML](profile-pinned-scroll/results-medium-four-image-url.xml)、[位置ログ抜粋](profile-pinned-scroll/trace-medium-four-image-url.txt) |
| 高速 | 120ms | 19 | 成功。[XML](profile-pinned-scroll/results-fast-four-image-url.xml)、[位置ログ抜粋](profile-pinned-scroll/trace-fast-four-image-url.txt) |

3件とも固定投稿4件を越えて通常投稿まで進み、最後は投稿・ヘッダーともインデックス0・オフセット0に戻った。
この合成データでは、画像＋URLサムネイルの構成でも移動停止を再現していない。
前の確認と同様に、位置の確認結果から実端末の一瞬の描画遅延や通信時の引っかかりを否定しない。

## 参考動画から追加した長いヘッダーの確認

ユーザー提供の`Screenrecorder-2026-10-05-19-55-21-986.mp4`（約8.94秒）をローカルで確認した。
動画では長い自己紹介とプロフィール項目の下に投稿欄があり、最初は投稿欄が画面外にある。
画像の固定投稿とURLカードの固定投稿が別々の行に並ぶ。先行テストの短いプロフィールと、
同じ投稿に画像とURLカードを入れる構成では、この条件を十分に確認できていなかった。

約3秒、6.25〜6.75秒、8〜8.75秒の付近では、ヘッダーが隠れた後に最初の画像付き固定投稿が表示されている。
指の接触位置・操作イベントは動画に記録されていないため、停止した各場面の入力を断定しない。
個人のプロフィール内容や動画フレームはリポジトリへコピーしていない。

合成データに22行の自己紹介とプロフィール項目2件を追加し、最初は投稿欄が画面外になる条件を作った。
固定投稿は4件の画像＋URLカード構成。画面高さの80%から20%へ120msで動かすスワイプを行い、
惰性が終わった位置を確認した。続けて、同じ操作を表示済みの投稿欄から始めた。

| 操作 | ヘッダーの位置 | 投稿一覧の位置 |
| --- | --- | --- |
| 最初の表示 | インデックス0・オフセット0 | インデックス0・オフセット0。投稿欄は未表示 |
| ヘッダーから高速スワイプ | インデックス1・オフセット0（タブ行が上端） | インデックス0・オフセット0。固定投稿の先頭で止まった |
| 投稿欄から同じ高速スワイプ | インデックス1・オフセット0 | インデックス7・オフセット450px。通常投稿まで進んだ |

「ヘッダーからの惰性も投稿へ続く」という期待を検証する調査用端末テスト1件は、
投稿一覧の位置が0のままであることを理由に失敗した。入力自体が動かない条件ではなく、
対照の投稿欄からのスワイプは成功している。[結果XML](profile-pinned-scroll/results-long-header.xml)、
[位置ログ](profile-pinned-scroll/trace-long-header.txt)を保存した。

共通プロフィールは外側のヘッダー用`LazyColumn`の中に、Pagerと投稿用`LazyColumn`を置く。
投稿側から始まるスクロールは`headerScrollConnection`でヘッダーを先に動かせる。
一方、ヘッダー側から始まるスクロールの残りを投稿側へ渡す処理はない。
外側の一覧が末尾に達すると、まだ下に投稿が続いていてもその惰性が止まる。
長いプロフィールでは最初の操作がヘッダー側で始まるため、この境界を踏みやすい。

修正では、ヘッダー側の操作中に消費されなかった上方向の移動量を、外側の`onPostScroll`から
選択中の投稿一覧へ渡す。ヘッダー側の既存の惰性処理がその消費量を受け取るため、
別の惰性処理を起動せず、境界の前後で同じ操作が継続する。
投稿側の操作中や横方向のタブ移動中には転送しない。タブごとの一覧状態と共通ヘッダーを保持する。
縦方向を単一の一覧へ組み直す案は、タブごとの閲覧位置・ヘッダー共有への変更が大きいため採用していない。
固定投稿どうしの境界や戻り方向での引っかかりについては、今回の再現結果だけで原因を断定しない。

## 調査時点で確認できた実装と原因候補

- [共通プロフィール](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/timeline/SocialScreens.kt)は固定投稿と通常投稿を同じ`LazyColumn`に並べる。固定投稿を別の縦スクロール領域にはしていない。ヘッダーは別の`LazyColumn`だが、今回報告された境目は投稿一覧の中にある。
- 固定IDが変わったときの先頭位置指定は、同じID・順序のままスクロールするだけでは繰り返されない。
- [MediaGrid](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/timeline/HomeTimelineScreen.kt)は画像取得後の寸法で比率を更新し、画像欄を再計測する。[比率情報がないときは1:1](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/timeline/ThumbnailLayout.kt)で計算する。
- 先行する3件の画像テストでは、800×200画像の読み込みで投稿高が1371pxから711pxへ変わった。ただし、先頭へ戻る途中の移動停止は、画像キャッシュを残す場合も消す場合も再現しなかった。高さ変化は候補であり、報告された現象の原因と断定しない。

固定0・1・3件、長文・短文、上下の移動、惰性移動、画像付きの先行8ケースも成功した。
[先行8件のXML](profile-pinned-scroll/results-warm.xml)を保存した。さらに画像のメモリキャッシュを消した3件の戻り操作1件も成功した。
画像取得・描画遅延の原因を特定する場合は、現象が出る投稿の添付枚数・比率情報と、実際の画像取得・描画時間を合わせて確認する必要がある。
今回修正したのはヘッダーから投稿一覧への惰性の継続。下記の実機報告で本件の解決を確認したため、追加調査は行っていない。

## 修正後の確認

Pixel_10a（Android 17）のDebug版で以下を確認した。各対象を個別に実行し、結果XMLの件数も確認した。

| 対象 | 件数 | 結果 |
| --- | --- | --- |
| `ProfileScrollDeviceTest` | 2 | 成功。[XML](profile-pinned-scroll/results-fix-regression.xml)。指を離した時点ではヘッダー内にあり、惰性だけで投稿へ進むこと、継続中の投稿欄への触り直しで停止し、ヘッダーを先に戻さず逆方向へドラッグできることを確認 |
| `ProfilePinnedStatusesDeviceTest` | 3 | 成功。[XML](profile-pinned-scroll/results-fix-pinned.xml)。固定投稿の遅延追加で先頭表示・閲覧中の投稿と位置を維持 |
| `ProfileTabSwipeDeviceTest` | 4 | 成功。[XML](profile-pinned-scroll/results-fix-tabs.xml)。タップ・左右スワイプによる選択と投稿タブへ戻った際の閲覧位置を維持 |
| 調査用`ProfilePinnedScrollInvestigationTest` | 16 | 成功。[XML](profile-pinned-scroll/results-fixed-investigation.xml)。長いヘッダー、固定4件の画像／画像＋URLサムネイルの低速1200ms・中速500ms・高速120msでの往復、画像キャッシュ消去後の戻りを含む |

修正前と同じ長いプロフィールからの高速スワイプでは、投稿一覧がインデックス3・オフセット1292pxまで進み、
最初の固定投稿で停止しなかった。通常投稿も表示領域へ入った。[修正後の位置ログ](profile-pinned-scroll/trace-long-header-fixed.txt)を保存した。

`:app:testDebugUnitTest :app:assembleRelease --offline`は成功。単体313件の失敗・エラー・スキップは0件。
署名付きRelease APK（2.4.0／15）を生成し、版番号と従来の配布鍵によるv2署名を確認した。
APKの所在とハッシュは[生成記録](../release-plan.md#プロフィールの惰性継続を含むapk再生成2026-10-05)を参照する。
端末テストはDebug版の合成データによる確認。

2026-10-05、修正版Release APK（2.4.0／15）の提示後、ユーザーから「完全に解決していることを確認しました」と実機確認の報告を受けた。
元のプロフィールで報告された固定投稿間・通常投稿と固定投稿の境目の引っかかりを含め、本件は解決確認済みとする。
この報告は実機での操作結果であり、個々の画像取得時間や描画時間を測定した結果とは区別する。

## 再実行

[調査用ソース](profile-pinned-scroll/tests/ProfilePinnedScrollInvestigationTest.kt)は通常のテストに含めず、init scriptで必要なときだけ追加する。
速度ごとに以下の`<method>`を置き換えて実行する。複数の同一クラスのメソッドをカンマで並べる指定は使わない。

```powershell
.\gradlew.bat -I docs/investigations/profile-pinned-scroll/investigation.init.gradle :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=io.github.ponpokoo.mastodonclient.ProfilePinnedScrollInvestigationTest#<method>'
```

- 低速: `slowScrollDownAndBackWithFourImagePins`
- 中速: `mediumScrollDownAndBackWithFourImagePins`
- 高速: `rapidScrollDownAndBackWithFourImagePins`

添付画像＋URLサムネイルの構成では、次のメソッドを使う。

- 低速: `slowScrollDownAndBackWithFourImageAndUrlPins`
- 中速: `mediumScrollDownAndBackWithFourImageAndUrlPins`
- 高速: `rapidScrollDownAndBackWithFourImageAndUrlPins`

長いヘッダーからの惰性移動の診断は`headerOriginFlingContinuesIntoPostsWithLongProfile`を指定する。
修正前には失敗し、上記の境界で止まる位置を出力した。診断用ソースは通常のテストには含めていない。
惰性の継続と触り直しは通常の`ProfileScrollDeviceTest`にも回帰テストを追加した。

関連: [プロフィールの仕様](../ui-guidelines.md#プロフィール)、[固定投稿の表示位置の調査](profile-pinned-status-position.md)、[検証範囲](../project-setup.md#ビルドと確認)。
