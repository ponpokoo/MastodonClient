# UI試作アプリ

本体のコードを変更せず、デザイン・画面遷移・表示部品の操作感を確かめる独立した試作アプリ。
アプリ名は「UIの試作」、モジュール名は`ui-prototype`。
既存の試作APKを更新できるよう、namespace・application IDは`io.github.ponpokoo.transitionprototype`を維持する。
本体とは別にインストールできる。ローカルのダミーデータと同梱画像を使い、通信・ログイン・Firebase・本体の共有データには依存しない。

## 試作一覧と追加方針

起動時の一覧から次の試作を選ぶ。各試作の「試作一覧に戻る」またはAndroidの戻る操作で一覧へ戻る。
画像ビューアを開いている場合、Androidの戻る操作は先にビューアを閉じる。

| 試作 | 操作と確認対象 |
| --- | --- |
| 単一画像の遷移 | 一覧のサムネイルから全画面表示し、同じ位置へ戻る。スクロール位置、Crop→Fit、角丸、閉じるときの弱いバウンスを確認する |
| 複数画像・ズーム | 横長・縦長・正方形の3画像を開き、左右に切り替え、ピンチ／ダブルタップで拡大する。ズーム解除→角丸→現在の画像のサムネイルへ戻る順序を確認する |

新しい試作は独立した画面として追加し、一覧から開けるようにする。操作感の比較に必要な場合だけ、案やアニメーションを切り替える設定をその試作へ加える。
ローカルな表示状態はComposable内で管理し、試作のためだけにViewModel・Repositoryや本体との共通モジュールを導入しない。
通信・認証・保存を伴う試作を追加する場合は[実装ルール](../AGENTS.md)の境界に従う。
採用が決まった部品を本体へ移し、本体の画面・データ・戻る操作と組み合わせた動作を別途確認する。
本体の`app/src/androidTest`と`release-tests`は引き続き本体の回帰・公開前確認を担う。

画像遷移の試作は[Issue #3](https://github.com/ponpokoo/MastodonClient/issues/3)に対応する。2026-10-06に`transition-prototype`として作成したもので、本体への採用は未確定。

## 単一画像の遷移

- 端末内の静止画1枚を、一覧のサムネイルから全画面表示して同じ位置へ戻す。
- 同じ`SharedTransitionLayout`内の`LazyColumn`と重ねたビューアを`sharedElement`で結び、遷移中は対象側の画像1枚だけを描画する。
- 一覧を保持し、サムネイルの領域を残すことで開閉時のスクロール位置を維持する。
- サムネイルはCrop・8 dp角丸、全画面はFit。開く時間と背景・ボタンのフェードは350 ms。閉じるときは110 msで角丸を戻してから弱いバウンスで縮小し、画像同士はフェードしない。
- 閉じるボタンとAndroidの戻る操作を使用する。

この画面では同梱画像1枚のみを扱い、複数画像・ズームは別の試作で確認する。
原寸URLの取得、ネットワーク画像のCoilキャッシュ、画面外へのフォールバック、
下スワイプ、システムバーの表示切替、Navigation連携は未実装。
Crop→Fitはサムネイルとビューアの確定サイズから画像倍率を補間する。
変化中の枠サイズをもとに毎回Crop倍率を算出すると、途中で画像が一時的に拡大するため、
両端の倍率を使用する。背景と閉じるボタンだけをフェードする。

## ビルド・インストール

本体と同じJDK・Android SDKを使用する。Debug署名の試作APKを生成する。

```powershell
.\gradlew.bat :ui-prototype:assembleDebug --offline
adb -s emulator-5554 install -r ui-prototype/build/outputs/apk/debug/ui-prototype-debug.apk
adb -s emulator-5554 shell am start -n io.github.ponpokoo.transitionprototype/.MainActivity
```

出力は`ui-prototype/build/outputs/apk/debug/ui-prototype-debug.apk`。
実端末ではAPKをインストールし、一覧から試作を選んで操作する。
遷移途中の二重像・切り抜きの変化と、戻った画像の位置を確認する。

## 自動確認

```powershell
.\gradlew.bat :ui-prototype:connectedDebugAndroidTest --offline
```

`ImageTransitionDeviceTest`で開く途中・閉じる途中にキーの一致と共有遷移の実行を確認し、
スクロール後のAndroid戻る操作で画像の位置が保持され、再び開けることを確認する。
開く途中に戻る操作をしても元画像へ戻り、次の共有遷移を開始できることも確認する。
単なるフェードだけが動く場合を、Composeの共有遷移状態により区別する。
`ReferenceGalleryDeviceTest`は複数画像の共有遷移、切り替え・ズーム解除後の戻り先、開く途中の戻る操作と再表示を確認する。
`PrototypeMenuDeviceTest`は一覧から両方の試作を開き、ビューア→試作→一覧のAndroid戻る操作と、一覧へ戻って別の試作を選び直す操作を確認する。
画像遷移のテストは一覧・開閉途中・全画面・ズーム後などのPNGを端末のアプリ専用外部領域へ保存する。
これは滑らかさや低性能端末でのフレーム性能を保証するテストではない。

## 画像遷移の過去の検証記録

以下は旧`transition-prototype`で実施した確認の記録。現在のUI試作一覧や複数画像の確認結果を表すものではない。

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
