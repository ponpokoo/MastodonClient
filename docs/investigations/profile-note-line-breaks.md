# プロフィール文の改行調査

調査・修正日: 2026-10-05。調査後、ユーザーの指定により`U+2028`の表示だけを修正した。

## 結果

ユーザーが指定した公開プロフィール`@amami_poke@mastodon.social`では、本文の行区切りに
Unicodeの`U+2028`（LINE SEPARATOR）が使われていた。修正前の表示部品では、この文字を
含む短い本文が1行に表示されることをAndroidエミュレーターで再現した。

公開APIを認証なしで読み取った結果、`note`は1つの`<p>`要素で、文章の途中に実際の
`U+2028`が1文字あった。文字列としての`\u2028`や`<br>`ではない。
これは確認時点のプロフィールの状態であり、他のプロフィールや今後の状態を保証しない。

## 調査時の処理

- [Accountの公式仕様](https://docs.joinmastodon.org/entities/Account/#note)では、表示用の`note`はHTML。
- `DefaultTimelineRepository`は`account.note`を変更せず`UserProfile.noteHtml`へ渡す。
- `ProfileContent`は自己紹介を共通の`StatusContentText`で描画する。
- `htmlToAnnotatedString`は`HtmlCompat.fromHtml`のLEGACYモードで変換し、前後を`trim`する。URLの注釈とカスタム絵文字を引き継ぐ。
- `U+2028`はHTML変換後も本文中に残るが、今回のCompose `Text`の描画では改行にならなかった。

## 合成データによる確認

Pixel_10a（Android 17）で、一時的な`ProfileNoteInvestigationDeviceTest`を対象指定して実行した。
調査後に一時テストを削除した。ネットワーク上のプロフィール本文や認証情報をテストへ入れていない。

| 入力 | 修正前の結果 |
| --- | --- |
| `<p>first<br>second</p>` | `first\nsecond`。画面の行数は2 |
| `<p>first</p><p>second</p>` | `first\n\nsecond` |
| `first`と`second`の間にLFまたはCRLF | 空白1文字へ変換 |
| `<p>`内に直接LF | 空白1文字へ変換 |
| `white-space: pre-wrap`を指定した`<div>`内にLF | 空白1文字へ変換。CSSによる改行保持には対応しない |
| `first<br><br>second` | `first\n\nsecond` |
| `<p>first`と`second</p>`の間に`U+2028` | 文字は保持されるが、画面の行数は1 |

7種類のHTML変換確認と、`U+2028`／`<br>`の描画比較は成功した。
実行は`connectedDebugAndroidTest`にクラスを指定し、描画比較は追加後にそのメソッドだけを指定した。
通常のLFがHTML中で空白になることは[AndroidのHTML変換実装](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/text/Html.java)とも整合する。

## 修正内容

HTML変換後の表示テキストで`U+2028`だけをLFへ置換する。1文字を1文字へ置換するため、
この変換によってリンクの位置は変わらない。`U+2029`や編集用の本文取得は今回の変更対象に含めない。

共通部品の変更なので、プロフィール以外の投稿・通知・プロフィール項目でも`U+2028`を改行として表示する。
既存のHTML変換、リンクの注釈、カスタム絵文字の置換処理は維持する。
HTMLに直接含まれるLFを一律に`<br>`へ変換すると、HTML整形用の改行も画面へ出るため、今回の対策と混同しない。

編集画面にも別の注意点がある。現在は表示用HTMLから編集用の自己紹介を復元しており、DTOにある
`source.note`をドメインへ渡していない。[公式仕様の`source.note`](https://docs.joinmastodon.org/entities/Account/#sourcenote)は編集用の平文なので、編集時の改行・末尾の空行を保持する改善は別途検討する。

関連: [UI・機能仕様のプロフィール](../ui-guidelines.md#プロフィール)、[開発ガイド](../project-setup.md#ビルドと確認)。

## 修正後の確認（2026-10-05）

Pixel_10a（Android 17）で`connectedDebugAndroidTest`に`io.github.ponpokoo.mastodonclient.StatusContentTextDeviceTest`を指定し、2件が成功した。`U+2028`が画面で2行になること、前後のリンクをタップしてそれぞれのURLを開けること、`<br>`・段落による改行、HTML中のLFと`U+2029`の従来の変換結果を確認した。同コマンドでアプリとテストのDebugビルドも成功した。
