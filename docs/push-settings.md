# リアルタイム通知の設定と購読管理

2026-09-21時点の実装仕様。[受信・復号・表示](push-reception.md)と
[Firebase Android接続](firebase-android.md)を参照。

## 利用者向けの動作

- 設定 → 通知の「通知を受け取る方法」に、アカウントごとの「バックグラウンドの通知（Push）」スイッチを表示。
  準備中・オフ・再認証待ち・登録中・有効・解除待ち・エラーを表示する。
- Relayの接続設定がないビルドでは「準備中」と表示し、有効化を禁止する。
  起動中のAndroid通知・定期確認通知は引き続き利用できる。
- 有効化時はAndroid 13以上の通知許可を確認する。
  `push`権限がない既存アカウントには再認証ボタンを表示する。
- 再認証では`read write push`を要求する。インスタンスとアカウントIDを照合し、
  別アカウントや権限不足の結果でログイン情報を置き換えない。
  正常終了時はセッションIDを維持して認証情報を更新し、タイムラインを再作成する。
- 通常のログインは従来どおり`read write`を要求する。

## 保存・解除・再開

`DefaultPushControlRepository`が希望する設定と購読状態を調整する。

Fedibirdの絵文字リアクションは、インスタンス情報の`fedibird_capabilities`に
`emoji_reaction`がある場合だけ`data[alerts][emoji_reaction]=true`で購読する。
ホスト名やバージョン文字列では判定せず、通常のMastodonへ独自の通知種類を送らない。
機能情報はv2を優先し、404の場合だけv1へフォールバックする。
新規購読と既存購読の再照合の両方に適用し、更新後のアプリ起動時のトークン同期で反映する。
購読のendpoint・暗号鍵・Relay登録IDは維持する。再認証やRelayの更新は不要。
受信側は通知種類による除外をせず、復号したタイトル・本文を既存の共通通知処理へ渡す。

根拠: Fedibird公式の[Push購読パラメーター](https://github.com/fedibird/mastodon/blob/main/app/controllers/api/v1/push/subscriptions_controller.rb)と
[公開機能情報](https://github.com/fedibird/mastodon/blob/main/app/serializers/rest/instance_serializer.rb)。

修正後、Push・Instance関連の単体テストとDebugビルドが成功。
既存購読への追加・応答消失後の再開、通常サーバーとの分離、暗号化されたリアクション通知の
復号から表示処理への引き渡しを確認した。実際の絵文字リアクション通知の到着も利用者が確認済み。

`secure_push_control` DataStoreに設定、FCMトークン、解除待ち情報をAndroid Keystoreで
暗号化保存する。このファイルはバックアップと端末移行の対象外。

無効化・ログアウト・再認証の前に解除待ちを保存し、受信側の購読状態を解除中にする。
ログアウト時に通信が失敗してもローカルのログアウトは完了する。
解除に必要な旧セッションの認証情報だけは暗号化した解除待ち情報に残し、成功後に削除する。
旧Relayの識別子を用いてRelayの配送停止とMastodon購読解除を再試行する。
保存に失敗した場合はログアウトを中止し、エラーを表示する。

再認証は旧購読の解除完了後に開始する。ブラウザーで認証中、または中断後に
アプリへ戻った際、古い認証情報で再登録しない。再認証をやり直すかスイッチをオフにできる。

アプリ起動・前景復帰時に保存した処理を再開する。前景中は登録中・エラー・解除待ちが
ある場合だけ30秒ごとに再試行する。画面の再試行ボタンでも実行できる。
購読解除専用の背景での定期再試行は未実装。後段で追加したFCMトークン同期Workerも
起動時・トークン変更時に解除待ちを再確認する。

## FCMとの接続境界

`PushRuntime.configureTransport(relay, token)`でHTTPSのRelayとトークンを渡し、
`PushRuntime.onTokenChanged(token)`で更新する。トークン変更は有効化済みの全アカウントに反映する。
接続設定はプロセスごとに初期化する必要がある。保存済みRelayは解除・既存購読の更新に使用する。

その後、[Firebase SDK・トークン更新・受信Service](firebase-android.md)を接続した。
Relay URLは未設定で、現ビルドだけでバックグラウンド即時通知を受け取れる状態ではない。
Push表示は前景通知設定に従う。Push有効時も定期確認通知は補助として動作する。起動時の即時確認を含むスケジュール調整は残課題。

## 検証

- 購読管理の単体テスト8件：未設定、権限不足、複数アカウントのトークン更新、
  オフライン解除の復元、無効化、再認証中の再登録抑止、キャンセルからの再開。
- 単体テスト全体130件成功、失敗・エラーなし。Debugビルド成功。
- Pixel_10a AVD / Android 17で再認証テスト4件成功。
  HTTP応答は端末内で差し替え、AndroidのURI処理と認証フローを検証した。
- 設定操作2件と、Keystore・復号・通知表示・重複排除の回帰1件も成功。
- Android 17で古いEspressoの入力API呼び出しが失敗したため、テスト用の
  Espressoを3.7.0、AndroidX JUnitを1.3.0へ更新した。
  アプリを起動する既存テストには通知許可の事前付与を追加した。
- 更新後の端末テスト全体はGradle成功、25件中23件成功。
  ホームの既存2件はログイン済みセッションがないため`AssumptionViolatedException`で対象外。
  XMLはこの2件をfailureとして出力するが、Gradleは前提条件未充足として扱う。
- 実Mastodonのブラウザー認証、実FCM送信、物理端末での配送条件は未確認。

## 参照

- [Mastodon OAuth](https://docs.joinmastodon.org/methods/oauth/)
- [Mastodon Push API](https://docs.joinmastodon.org/methods/push/)
- [AndroidX Testの変更履歴](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)
