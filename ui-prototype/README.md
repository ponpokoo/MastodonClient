# UI試作アプリ

本体のコードを変更せず、デザイン・画面遷移・表示部品の操作感を確かめる独立した試作アプリ。
アプリ名は「UIの試作」、モジュール名は`ui-prototype`。
既存の試作APKを更新できるよう、namespace・application IDは`io.github.ponpokoo.transitionprototype`を維持する。
本体とは別にインストールできる。ローカルのダミーデータと同梱画像を使い、通信・ログイン・Firebase・本体の共有データには依存しない。

**現在のUI変更はすべて本体へ未適用**。変更履歴、現在の値、確認範囲、本体への移植手順は[UI変更履歴と本体への移植](ui-changes.md)にまとめる。
本体への移植と動作確認が完了した項目は、変更履歴とこのREADMEから該当箇所を削除する。詳細は[移植後の記載整理](ui-changes.md#移植後の記載整理)を参照する。

## 本アプリと同じ動作を基準にするルール

- 試作対象の基準は、本アプリの現在の表示・操作・画面遷移とする。実装前に[UI仕様](../docs/ui-guidelines.md)と、対応する本体のコード・テストを確認し、一般的なUIの想定だけで動作を決めない。投稿表示は[StatusCard.kt](../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/status/StatusCard.kt)、画像操作は[MediaViewerScreen.kt](../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/media/MediaViewerScreen.kt)を参照する。
- 対象範囲のタップ・ダブルタップ・ピンチ・ドラッグの役割と優先順位、戻る順序、状態保持、操作UIとシステムバーの切り替え、表示設定・倍率・アニメーション時間を合わせる。試作を簡単にするための省略や独自変更を加えない。ライブラリや内部構成が異なる場合も、同じ入力で同じ表示・状態になることを確認する。
- 本アプリと異なる案は、ユーザーから依頼された比較・変更の範囲だけで試す。基準動作、試す差分、未実装・未確認の範囲をこのREADMEに明記し、現在の試作に残る差分を採用済みの仕様として扱わない。ジェスチャーの競合を調べる場合は、単独操作だけでなく、2本指から1本指への切り替え、操作中断、戻る操作なども比較する。
- 本体のコードを変更せず、データ取得・認証・送信などはダミーデータや代替処理で分離する。対象外の機能は再現範囲から明示的に外し、対象の見た目や操作の省略と混同しない。本体への採用時は本体のデータ・画面構成と組み合わせて別途確認する。

## メイン画面（4画面とタイムライン）

起動時は本体からコピーした[HomeTimelineScreen.kt](src/main/java/io/github/ponpokoo/mastodonclient/feature/timeline/HomeTimelineScreen.kt)で、ホーム・探索・通知・プロフィールの4画面と下部ナビゲーションを表示する。この構成をUI確認の入口として使う。投稿カード、検索・通知・プロフィールの表示、ViewModel、セッション状態、テーマも本体のコードをコピーして使用する。
メイン画面間の横スワイプは無効で、下部アイコンで切り替える。タブ切替でホームのスクロール位置・お気に入り・ブースト、探索の入力と各画面のタブ選択を保持する。ホームの再選択で先頭へ戻る。

ホームには固定の投稿2件を並べる。1件目は画像1枚、2件目は画像3枚。標準サムネイル表示に合わせ、3枚は上段2枚・下段1枚で、画像の比率に応じて行の幅を分ける。間隔8 dp、角丸8 dp、行の高さは最大400 dp。
投稿者のドメインは架空の`example.test`。画像は同梱素材を使う。

添付画像はタップした画像から全画面で開き、3枚の投稿では左右に切り替えられる。ビューアは本体の画像操作を基準に、単一タップで操作UIとシステムバーを切り替え、ダブルタップは1倍／2.5倍、ピンチは0.5〜5倍、1倍未満で離すと1倍へ戻す。拡大中は画像移動を優先し、全体表示で画面高の12%以上を下へドラッグして離すと閉じる。
閉じるボタン・Androidの戻る操作でも元の投稿と閲覧位置へ戻る。投稿者のアバターはコピーした本体のプロフィール画面へ進む。

### 復元した画像遷移

削除した試作の画像遷移を、メイン画面の添付画像へ統合している。サムネイルから350 msで全画面へ広がり、切り抜き表示から画像全体の表示へ変わる。閉じるときは拡大・移動・下ドラッグを150 msで戻し、角丸を110 msで付けてから、弱いバウンドのあるスプリングでサムネイルへ戻る。画像を左右に切り替えた場合は、最後に表示した画像の位置へ戻る。開く途中の戻る操作にも対応する。

この遷移はユーザーから依頼された復元であり、本体のDialogによる全画面表示との差分として扱う。ジェスチャーの値と操作UI・システムバーの切替は本体を基準にする。横スワイプと拡大中の画像移動を優先し、下ドラッグの途中で2本目の指が触れた場合は閉じる操作を中止してピンチへ引き継ぐ。同じ操作で1本指へ戻っても閉じない。別の画像へ切り替えると、前の画像の倍率と移動量を戻す。

### 再現範囲

現在の再現範囲は4画面の切替、ホームの投稿、検索入力と結果タブ、通知のフィルター、自分と投稿者のプロフィール（ヘッダーと投稿タブのスクロール連携を含む）、静止画像操作。探索・通知・検索結果は空の一覧、プロフィールの投稿・メディアタブにはその投稿者の固定投稿を表示する。
通信・認証・投稿送信・画像保存は対象外。お気に入り／ブースト／ブックマークとフォローはローカルで切り替える。フィード切替は同じ固定2件、アカウント一覧は固定1件、お知らせは空。設定・投稿詳細・投稿作成・引用・外部リンク・一覧画面など未接続の入口は対象外と表示する。プロフィール編集や管理操作など、Repositoryの代替処理を用意していない操作は未対応の結果を返す。
本体との差分は下記の確認中のUI調整に加え、画像遷移と競合回避、遷移中の背面画面のInsets固定、固定ダークテーマ、外部処理の代替である。投稿2件だけの短い一覧でも位置を保持できるよう、非表示のタブは最後の表示領域を保持し、他のタブの上部バーによる一覧の再配置を防ぐ。プロフィールのアバター・ヘッダー画像は全画面で開くが、添付画像のようなサムネイル位置への遷移は付けていない。動画・音声部品はコピーしているが、今回の固定データと端末確認の対象は静止画像のみ。
端末テストは試作内の動作確認であり、本体との一致やフレーム性能を保証するものではない。

確認中のUI調整は試作だけに保持する。投稿の公開範囲アイコンは14.4 dp（本体は12 dp）で、時刻・三点メニューとの中央揃えを維持する。プロフィールの投稿タブは最小44 dp（本体の標準は48 dp）とし、文字サイズ・太さは維持し、文字拡大時は必要な高さへ広げる。これらと画像遷移などの試作差分はすべて本体へ未適用とし、項目別の状態は[変更履歴](ui-changes.md#現在の差分と適用状況)で確認する。

## 本体UIの取り込みと調整

`src/main/java/io/github/ponpokoo/mastodonclient/`に本体と同じパッケージ・配置でコピーを置く。本体の`app`に依存させず、UI → 本体のViewModel → domain Repositoryの接続を保ち、Repositoryの実装だけを[固定データ](src/main/java/io/github/ponpokoo/transitionprototype/PrototypeFixtures.kt)に差し替える。元ファイルと取り込み時のハッシュは[production-sources.json](production-sources.json)に記録する。

| ファイル | 調整する内容 |
| --- | --- |
| `PrototypeApp.kt` | 固定Repositoryと本体ViewModelの接続、投稿者画面・画像ビューアの開閉 |
| `PrototypeFixtures.kt` | 固定の投稿者・本文・画像・件数 |
| `mastodonclient/feature/timeline/HomeTimelineScreen.kt` | 4画面の構成、下部ナビゲーション |
| `mastodonclient/feature/status/StatusCard.kt` | 投稿、アバター、操作アイコン、画像の配置 |
| `mastodonclient/feature/profile/ProfileContent.kt` | プロフィール表示・スクロール連携 |
| `PrototypeImageTransition.kt` | サムネイルと全画面の画像遷移、角丸、戻り先 |
| `mastodonclient/feature/media/MediaViewerScreen.kt` | 本体の画像読込・ズーム処理と、試作の閉じる遷移・ジェスチャー競合回避 |
| `mastodonclient/ui/theme/` | 本体の配色・文字・形状 |

別の部品を試すときは、その画面・ViewModel・必要なモデル／Repositoryインターフェースを本体から取り込み、固定Repositoryへ必要な代替処理を追加する。表示やジェスチャーを一から書き直さない。共通モジュールへ切り出す必要はなく、本体のコードは変更しない。

Python 3で以下を実行する（リポジトリ直下から）。

```powershell
python ui-prototype/import-production-ui.py --check
python ui-prototype/import-production-ui.py --diff
python ui-prototype/import-production-ui.py --add app/src/main/java/io/github/ponpokoo/mastodonclient/feature/対象/対象.kt
python ui-prototype/import-production-ui.py --refresh
```

`--check`は本体側の更新（UPSTREAM）と試作側の変更（ADAPTED）を表示し、本体の更新があれば終了コード1を返す。`--diff`は現在の本体と試作の差分を表示する。`--add`は新しいファイルだけをコピーする。依存ファイルも同じ方法で明示的に追加する。`--refresh`は取り込み後に試作側を変更していないファイルだけを更新し、実験を加えたファイルは残す（要手動反映が残れば終了コード1）。残った差分を読んで手動で反映し、該当する端末テストを実行する。反映を確認したファイルは`--record app/src/main/…/対象.kt`で本体の新しい基準を記録する（コピーの内容は変更しない）。必要なリソースは別途コピーし、`R`参照は試作namespaceに合わせる（スクリプトはKotlinの`R`参照のみ変換する）。

固定データ・接続コード・画像遷移実験は`transitionprototype/`に置き、取り込み元との違いを追えるようにする。コピーした画像ビューアの変更も`--diff`で確認できる。通信・認証・保存を追加する場合は[実装ルール](../AGENTS.md)に従う。
採用が決まった変更を本体へ移し、本体の画面・データ・戻る操作と組み合わせた動作を別途確認する。
本体の`app/src/androidTest`と`release-tests`は引き続き本体の回帰・公開前確認を担う。

## ビルド・インストール

本体と同じJDK・Android SDKを使用する。Debug署名の試作APKを生成する。初回または依存更新時は`--offline`を外して依存を取得する。

```powershell
.\gradlew.bat :ui-prototype:assembleDebug --offline
adb -s emulator-5554 install -r ui-prototype/build/outputs/apk/debug/ui-prototype-debug.apk
adb -s emulator-5554 shell am start -n io.github.ponpokoo.transitionprototype/.MainActivity
```

出力は`ui-prototype/build/outputs/apk/debug/ui-prototype-debug.apk`。
実端末へ入れる場合は、接続端末のシリアル番号を指定してインストールする。起動時にホームの投稿2件を表示する。

## 自動確認

```powershell
python -m unittest discover -s ui-prototype/tests
.\gradlew.bat :ui-prototype:connectedDebugAndroidTest --offline
```

`PrototypeAppDeviceTest`で4画面の切替と状態保持、ホーム再選択で先頭へ戻る操作、投稿者のプロフィールへの遷移、タップした添付画像からの切替と戻り先を確認する。
同梱画像が本体の画像ローダーで読み込まれ、空のサムネイルにならないことも確認する。
投稿者画面でお気に入りを切り替えた結果がホームへ反映されること、同じ投稿が複数の画面にあってもタップした画面のサムネイル位置から遷移することも確認する。
画像操作は操作UIの表示切替、拡大中の下ドラッグとの競合、ピンチの倍率上限と縮小後に全体表示へ戻る動作を確認する。
`PrototypeImageTransitionDeviceTest`でスクロール済みのサムネイルからの開閉、最後に表示した画像への戻り、拡大からのリセット順、開く途中の戻る、下ドラッグ中の2本指への切替、システムバーを隠した後のスクロール位置を確認する。
