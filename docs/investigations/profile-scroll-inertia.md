# プロフィール先頭の投稿本文から始めたスクロールの慣性

2026-10-20調査・修正。先頭表示中、下部に見えている投稿本文から上へスワイプすると、
ホームより強い慣性が発生する条件をエミュレーターで再現し、縦操作の担当を外側の一覧へ統一した。
自己紹介から操作した従来の比較とは、操作を受け持つ一覧が異なる。

## 修正前に再現した差

実際の`TimelineContent`と`ProfileContent`へ同じ合成投稿を渡し、最初の投稿本文の表示範囲内から
240 pxを160 msでスワイプした。双方とも一覧の先頭から開始する。対照として、プロフィール情報を
隠し、投稿一覧は先頭のまま操作する場合も比較した。

| 開始位置 | 指を離した際の初速の絶対値（px/s） | 指を離した後の移動要求量の絶対値（px） |
| --- | ---: | ---: |
| ホーム先頭の投稿本文 | 1,500 | 193 |
| プロフィール先頭で下部に見えている投稿本文 | 3,000 | 643 |
| プロフィール情報を隠した状態の投稿本文 | 1,500 | 193 |

移動要求量は外側の観測用`NestedScrollConnection.onPreScroll`へ届いた`SideEffect`のY成分の合計。
画像の見た目やフレーム落ちを測った値ではない。初速は`onPreFling`のY成分。

80／160／320 msの入力、および指の各移動の間に画面更新を挟む入力で比較した。
後者の160 ms指定では、ホームが約1,412 px/s・174 px、プロフィール先頭が約2,824 px/s・579 px、
プロフィール情報を隠した対照が約1,412 px/s・174 pxだった。
画面更新を挟む入力はイベント間にも時計が進むため、指定時間を実時間と同一視しない。
同じ入力手順同士の比較として扱う。80 ms指定では初速の比が約2.36倍になり、倍率は一定ではない。

もう一つ、投稿本文から始めた慣性中、まだ見えている上のプロフィール情報へ触り直しても停止しなかった。
指を置いて待つ160 msの間に、ヘッダーのオフセットは376 pxから792 pxへ進んだ。
投稿本文へ触り直す対照では260 pxのまま停止した。

## 修正前の実装との対応と未確定点

[ProfileContent](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/profile/ProfileContent.kt)の
修正前の`headerScrollConnection.onPreScroll`は、投稿一覧が受け取った上向き移動を
`resolvedHeaderListState.dispatchRawDelta`へ先に渡していた。このとき投稿一覧を含むPagerの画面上の位置も移動する。
プロフィール情報が隠れた対照ではこの位置変化が起きず、初速・慣性移動要求量がホームと一致した。

使用中のCompose Foundation 1.10.5の`Draggable.kt`では、ドラッグを受けるノードの位置変化を
`nodeOffset`へ蓄積し、`VelocityTracker.addPointerInputChange`へ渡している。
Compose UI 1.11.2の`PlatformVelocityTracker.kt`では、位置補正を入力座標に加える経路がある。
投稿欄自身の移動とこの補正の組み合わせが初速過大の有力な原因と考えられる。
ライブラリ内部のどの経路・版の組み合わせが原因か、依存関係をそろえるだけで解消するかは未検証。
移動する部品の速度推定には[AndroidX側にも問題の記録](https://issuetracker.google.com/issues/216582726)があるが、
本件と同一の原因・修正範囲とは確定していない。

触り直しで止まらない間は、`posts.isScrollInProgress=true`、`header.isScrollInProgress=false`だった。
上の情報を動かすraw dispatchではヘッダー側のスクロール状態が立たず、ヘッダーだけに届くタッチでは
子の投稿一覧が持つ慣性を停止できていなかった。

## 修正後の扱い

投稿一覧のタッチによる縦スクロールを無効にし、画面上の位置が動かない外側の一覧で縦操作と
慣性の速度を扱う。外側がヘッダーを隠した後の移動量を選択中の投稿へ渡し、戻る方向では投稿を先に戻す。
どちらの領域へ触れても同じスクロール処理を停止できる。各タブの一覧状態と横Pagerは維持する。
読み上げ操作で投稿の続きを進められるよう、外側のスクロール範囲はヘッダーと投稿の両方を含める。
速度倍率やComposeの依存バージョンは変更していない。2026-10-20、利用者から修正版の実機確認済みとの報告を受けた。

修正後の上記160 msの入力では、ホーム・プロフィール先頭・プロフィール情報を隠した対照のすべてが
約1,500 px/s・193 pxで一致した。画面更新を挟む条件も約1,412 px/s・174 pxで一致し、
両領域への触り直しはヘッダーのオフセット189 pxのまま停止した。

通常の回帰テストは[ProfileScrollOwnershipDeviceTest](../../app/src/androidTest/java/io/github/ponpokoo/mastodonclient/ProfileScrollOwnershipDeviceTest.kt)。
実際のホームとの初速・慣性移動要求量の比較、両領域への触り直し、読み上げ用のスクロール操作、
投稿からヘッダーへ戻る逆フリックを対象にする。

## 再現用テスト

環境はQA用`NagisaMediaQa`、Android 17、1080×2424 px、420 dpi、Debug版。
Foundationは1.10.5、UIは1.11.2を解決していた。ReleaseのUIも依存確認では1.11.2だったが、
Release APKで同じ操作を実測した結果ではない。認証や実サーバーは使わない。

[調査テスト](profile-scroll-inertia/tests/ProfileTopPostScrollInvestigationTest.kt)は
[専用init script](profile-scroll-inertia/investigation.init.gradle)でだけ取り込み、通常のCI・端末テストへ加えない。
速度比較3件は測定用でログに結果を出す。投稿本文への触り直し1件は停止を検証する。
上の情報への触り直し1件も停止を検証する。修正前の調査では、この停止確認が上記の位置差で失敗した。
修正後は外側の一覧が慣性を持つことを確認する条件へ更新している。

```powershell
.\gradlew.bat -I docs/investigations/profile-scroll-inertia/investigation.init.gradle :app:assembleDebugAndroidTest
```

対象端末へ現行のDebug本体APKとテストAPKをインストールし、シリアルを指定して実行する。

```powershell
adb -s emulator-5562 shell am instrument -w -e class io.github.ponpokoo.mastodonclient.ProfileTopPostScrollInvestigationTest io.github.ponpokoo.mastodonclient.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5562 logcat -d -s 'TopPostInertia:I' '*:S'
```
