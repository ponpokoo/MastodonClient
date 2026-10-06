# 画像遷移の最小試作

[Issue #3](https://github.com/ponpokoo/MastodonClient/issues/3)の調査用アプリ。2026-10-06作成。
アプリ名は「画像遷移の試作」、application IDは`io.github.ponpokoo.transitionprototype`。
本体とは別にインストールできる。通信、ログイン、Firebase、共有データへの依存はない。

## 今回確かめること

- 端末内の静止画1枚を、一覧のサムネイルから全画面表示して同じ位置へ戻す。
- 同じ`SharedTransitionLayout`内の`LazyColumn`と重ねたビューアを`sharedElement`で結び、遷移中は対象側の画像1枚だけを描画する。
- 一覧を保持し、サムネイルの領域を残すことで開閉時のスクロール位置を維持する。
- サムネイルはCrop・8 dp角丸、全画面はFit。開閉・画像倍率・角丸・背景とボタンのフェードを250 msで揃え、画像同士はフェードしない。
- 閉じるボタンとAndroidの戻る操作を使用する。

原寸URLの取得、Coilキャッシュ、複数画像、画面外へのフォールバック、ズーム・パン・
下スワイプ、システムバーの表示切替、Navigation連携は未実装。本体への採用判断は未確定。
Crop→Fitはサムネイルとビューアの確定サイズから画像倍率を補間する。
変化中の枠サイズをもとに毎回Crop倍率を算出すると、途中で画像が一時的に拡大するため、
両端の倍率を使用する。背景と閉じるボタンだけをフェードする。
このローカルな表示状態のみの試作はComposable内に状態を持ち、Repositoryは導入しない。

## ビルド・インストール

本体と同じJDK・Android SDKを使用する。Debug署名の試作APKを生成する。

```powershell
.\gradlew.bat :transition-prototype:assembleDebug --offline
adb -s emulator-5554 install -r transition-prototype/build/outputs/apk/debug/transition-prototype-debug.apk
adb -s emulator-5554 shell am start -n io.github.ponpokoo.transitionprototype/.MainActivity
```

出力は`transition-prototype/build/outputs/apk/debug/transition-prototype-debug.apk`。
実端末ではAPKをインストールし、画像をタップして開閉する。
遷移途中の二重像・切り抜きの変化と、戻った画像の位置を確認する。

## 自動確認

```powershell
.\gradlew.bat :transition-prototype:connectedDebugAndroidTest --offline
```

`ImageTransitionDeviceTest`で開く途中・閉じる途中にキーの一致と共有遷移の実行を確認し、
スクロール後のAndroid戻る操作で画像の位置が保持され、再び開けることを確認する。
開く途中に戻る操作をしても元画像へ戻り、次の共有遷移を開始できることも確認する。
単なるフェードだけが動く場合を、Composeの共有遷移状態により区別する。
テストは一覧・開く途中2枚・全画面・閉じる途中2枚のPNGを端末のアプリ専用外部領域へ保存する。
これは滑らかさや低性能端末でのフレーム性能を保証するテストではない。

## 検証記録

- `:transition-prototype:assembleDebug :transition-prototype:assembleDebugAndroidTest --offline`が成功。初回のみキャッシュ不足の依存関係をオンラインで取得した。
- Pixel_10a（Android 17／API 37、`emulator-5554`）で上記2件が成功。開く途中と閉じる途中の共有キーの一致・遷移実行、戻る操作によるスクロール位置保持・再表示を確認した。
- 初回の端末テストではEspressoの古い推移依存がAndroid 17で失敗した。本体と同じEspresso 3.7.0を明示して解消した。
- 初版の`sharedBounds`ではPNG4枚を目視確認。全画面では画像全体を表示する一方、CropとFitをフェードする途中、特に閉じる途中に二重像が見えた。
- 暗色背景の文字色を修正後、Debug APK生成とキャプチャ付き開閉テスト1件を再確認して成功。最新版をエミュレーターへインストールし、起動した。
- 実端末での確認、フレーム時間の計測、本体アプリのビルド・テストは未実施。本体のコード・依存関係・アプリIDは変更していない。

画像は`build/transition-captures/`、テスト出力は`build/device-test-result.txt`と
`build/visual-test-result.txt`に保存する（いずれもGit管理外）。

### 中間に画像が挟まる見え方の修正（2026-10-06）

利用者の指摘を受け、`sharedBounds`の2画像を重ねるフェードを廃止し、`sharedElement`へ変更。
画像倍率と角丸を開閉の状態に沿って補間し、同じ絵が2枚重なる描画を避ける。
途中の戻る操作の回帰テストを追加した。Debugアプリ・テストAPKのビルドが成功し、
Pixel_10a（Android 17／API 37）で3件の端末テストが成功。
開く途中・閉じる途中の各160 msと320 msのPNGを目視確認し、初版で見えた太陽や山の
二重像がないことを確認した。フレーム性能の測定と実端末での見え方の確認は未実施。

### モーション時間の短縮（2026-10-06）

利用者の指定により、開閉・画像倍率・角丸・背景と閉じるボタンのフェードを600 msから
250 msに短縮した。途中確認のキャプチャは80 msと160 msに変更した。
Debugアプリ・テストAPKのビルドと、Pixel_10a（Android 17）の既存3件の端末テストが成功。
最新版をエミュレーターへインストールして起動した。実端末での速さの確認は未実施。

## 調査の根拠

- [Compose共有遷移・制約](https://developer.android.com/develop/ui/compose/animation/shared-elements)：Dialogとの相互運用、ContentScaleと形状の自動補間に制約がある。
- [境界のリサイズ](https://developer.android.com/develop/ui/compose/animation/shared-elements/customize)：異なる縦横比とリサイズ方式の説明。
- 本体の`AppNavigation.kt`は`dialog<Route.MediaViewer>`、`MediaViewerScreen.kt`は別Windowの制御を使用する。この試作は同一ツリーへの移行案を試すもので、現行動作の変更ではない。
- [文書一覧](../docs/index.md)、[現行UI仕様](../docs/ui-guidelines.md)、[開発ガイド](../docs/project-setup.md)。
