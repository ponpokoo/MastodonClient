# Relay通知の受信・復号・表示

2026-09-21時点の実装。設定画面・OAuth再認証・[FCM SDK受信](firebase-android.md)を接続済み。
Firebaseなしで受信ロジックを呼び出し、固定の暗号文による検証ができる。
利用者がプッシュ通知を有効化して使える状態ではない。

## 呼び出し口と境界

`PushMessageHandler(context).receive(data)`が受信の呼び出し口。
dataは [Relay契約](../relay/README.md#配送制限) のString値のMapで、DTOを画面へ渡さない。
`DefaultPushMessageRepository`が購読・セッションを確認し、暗号文を取得・復号して
ドメインの`PushNotification`へ変換し、既存の`SystemNotificationDataSource`へ渡す。
HTTP取得と復号はIOコンテキストで処理する。

- `PROCESSED`: 通知表示処理に渡した。重複・OS許可拒否では表示されない場合もある。
- `IGNORED`: 対象購読なし・解除中・未ログイン・期限切れなどで処理対象外。
- `REJECTED`: 未対応形式、不正データ、暗号認証失敗など。
- 通信エラー・キャンセル・保存情報の破損は呼び出し側へ伝える。
  FCM受信Workerが期限内に時間を置いて再試行する。

FCMアダプターは長い取得を受信コールバック内に抱えず、実行制限に対応するWorkerへ渡す。
アプリ独自の公開BroadcastReceiverや外部から通知表示を起動するIntentは追加していない。

## 暗号文の取得と復号

`inline`では受信本文を直接使用し、`fetch`では端末に保存したRelay識別子を接続先にする。
受信MapのURLは接続先として採用しない。管理用トークンだけを送信し、Mastodonトークンを送らない。
HTTPの本番設定とリダイレクトを拒否し、取得JSONは100,000 bytes、暗号文は64 KiBまでに制限する。
取得応答の登録ID・メッセージIDも要求と照合する。404／410は表示しない。

標準の`aes128gcm`（RFC 8291）と旧`aesgcm`（draft-04）の単一レコードに対応。
P-256の公開点、ECDH、HKDF-SHA256、AES-GCM認証、各形式のパディングを検証する。
未対応の符号化・複数レコード・曖昧な暗号ヘッダー・改ざん・鍵違いは表示しない。
独自の暗号アルゴリズムを作らず、JCAの暗号プリミティブを使用する。

Mastodon Push本文はREST通知とは別DTOで解析する。
`notification_id`は数値表現の場合もStringとして桁を保ち、未知の通知種類・追加項目を許容する。
本文内の`access_token`は取り込まず、保存・ログ出力しない。
タイトル・本文は平文として表示し、HTMLの解釈や追加の通知詳細API取得は行わない。

## 表示と重複防止

登録IDに対応するログイン済みアカウントを選び、閲覧中アカウントには依存しない。
ACTIVEかつ認証情報のフィンガープリントが一致する購読だけを扱う。
取得後と表示直前にも状態を再確認するため、取得中のログアウト・再認証・解除は表示を抑止する。
最終表示は購読管理と共通Mutexで直列化する。[ログアウト時の購読解除](push-settings.md)も接続済み。

Streaming・簡易通知・Pushは同じ配信コーディネーターと
`delivered_notifications`のセッション別履歴を使用する。
通知IDで直近500件を重複排除し、同時受信でも同じアカウントの同一通知を重ねて表示しない。
通知権限がない場合や表示に失敗した場合は配信済みにしない。
履歴の保存前にプロセスが停止した場合や500件を超える古い再送の完全な重複排除は保証しない。

既存チャンネル・アイコン・通知グループ・アプリを開くタップ動作を共用する。
PushにもOSの通知許可とチャンネル設定が適用される。
Push表示も前景では「起動中の通知」に従う。現在の[設定画面](push-settings.md)ではRelay未設定のため有効化できない。

## 検証結果

- 復号・受信・取得・重複排除の単体テスト20件追加。
- 関連テスト30件成功後、単体テスト全体122件成功（失敗・エラー・スキップなし）。
- Debugビルド成功。
- Pixel_10a AVD / Android 17で`PushNotificationDeviceTest`の1件成功。
  実際のAndroid Keystoreで保存・復元し、固定暗号文を復号してOS通知を表示。
  平文表示、Streamingと共通の重複排除、解除中の受信抑止を確認した。
- 実FCM配信、実Mastodonからの送信、物理端末のDoze・通常終了・強制停止は未確認。

公開RFC・draftの既知ベクトルに加え、Node.jsの独立したHKDF/AES実装による固定暗号文を使用する。
`relay/tools/encryption-fixtures.mjs`を実行すると単体・Androidテスト用の資材を再生成できる。
資材の鍵は公開仕様の例で、利用者の鍵ではない。

## 参照

- [RFC 8291](https://www.rfc-editor.org/rfc/rfc8291.html)
- [旧Web Push暗号化draft-04](https://datatracker.ietf.org/doc/html/draft-ietf-webpush-encryption-04)
- [Mastodon Push本文の公式実装](https://github.com/mastodon/mastodon/blob/main/app/serializers/web/notification_serializer.rb)
