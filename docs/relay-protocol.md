# Nagisa Relay登録API v1（ローカル検証用）

Android側の通信クライアントとMockWebServerテストを実装済み。
Android側の鍵生成・購読状態の暗号化保存・再開処理も実装済み。
[ローカルRelay](../relay/README.md)の登録・受付・永続キュー・模擬送信も実装済み。
[設定・認証フロー](push-settings.md)にも接続済み。
[Workers＋D1試験版](../relay/workers/README.md)に実FCM送信アダプターを追加した。
公開環境の設定と実配信の確認は未実施。手順は[Workers配置](relay-workers-setup.md)を参照する。
[0.2.0計画](push-notifications-0.2.0.md)の第1段階として登録APIを定義する。
配送・暗号文取得のローカル契約は [Relay README](../relay/README.md#http契約) を参照する。

## 登録・FCMトークン更新

`PUT /v1/registrations/{registrationId}`

- Authorization: `Bearer {managementToken}`
- Content-Type: `application/json`
- 本文: `{"fcmToken":"端末で取得したFCMトークン"}`
- 200または201: `{"endpoint":"https://relay.example/push/配送専用ID"}`

registrationIdは端末が安全な乱数から生成する128 bit以上のID、managementTokenは
別途生成する256 bit以上の秘密値とする。いずれもパディングなしBase64URLを使用する。
クライアントの形式検証は乱数品質を保証しないため、生成処理で確保する。
アカウント購読ごとに別の組を作り、通信前に暗号化保存する。

サーバーは初回登録を原子的に作成し、管理用秘密値は検証用ハッシュで保存する。
既存IDへのPUTは同じmanagementTokenの場合だけ許可する。
同じ要求の再送は同じ配送endpointを返し、FCMトークン更新でもendpointを維持する。
別の管理用秘密値で既存登録を上書きできてはいけない。
配送専用IDはregistrationId・managementTokenと独立して生成する。

この要求にMastodonのURL・アクセストークン、Web Push秘密鍵・auth secretを含めない。
AndroidのRelay専用HTTPクライアントはMastodon認証Interceptorを共有せず、リダイレクトを追わない。
実設定は認証情報を埋め込まないHTTPS URLのみ許可する。

## 解除

`DELETE /v1/registrations/{registrationId}`。同じ管理用Authorizationを使用する。

- 204: 解除完了。ローカルRelayでは未登録IDも墓標を作って204を返す。
- 404: 存在しないため、解除済みとして扱う。
- 401／403: 認証・権限エラー。解除成功として扱わない。
- 429／5xx／通信失敗: 未完了。上位の購読管理で再試行する。

削除時には配送endpointを無効化し、保持中の配送も停止する。
IDは解除後に再利用せず、再有効化では新しい組を発行する。
サーバー側では削除済み登録の墓標を保持するなど、遅延したPUTによる復活を防ぐ。
ローカル版は墓標を無期限保持し、登録と合わせて1000件を上限とする。
公開運用の保持期間と管理情報の削除方針は今後確定する。

## Mastodonとの接続順序

1. Web Push鍵とRelay登録用秘密値を端末に保存する。
2. Relayへ登録し、配送endpointを保存する。
3. 対象アカウントのインスタンスへWeb Push購読を登録する。
4. 応答を保存して有効状態へ移行する。

通信前に進捗を保存し、途中でプロセスが終了しても同じキーで再開できるようにする。
MastodonのGETが404なら現在の購読なしとして扱うが、API非対応かどうかは別の判定が必要。
401／403を購読なしと誤認して再登録を繰り返さない。
POSTは既存購読を置き換えるため、取得・照合と明示的な有効化操作の上で使用する。
`DefaultPushRegistrationRepository`は同じ要求の再呼び出しで保存済みの進捗から再開する。
自動起動・時間を置いた再試行は未接続。別endpointの既存購読はエラーとし、無断で置換しない。
同じendpointで通知設定を変更する場合のみ再登録する。
保存済みのセッション認証情報のフィンガープリント・Relay識別子を照合し、不一致では通信しない。
再認証時の旧購読の解除と移行は[設定・認証フロー](push-settings.md)で実装した。

解除は端末の状態を解除中に保存し、Relay → Mastodon → ローカル保存情報の順で行う。
Mastodon側は保存済みendpointと一致する購読だけを削除する。
解除に必要な認証情報を破棄する前に処理する必要があり、ログアウトへの接続は未実装。
GET照合とPOST／DELETEの間の外部クライアントによる変更には、Mastodon APIに条件付き更新がないため
原子的な保護を提供しない。アプリ内の処理は共通Mutexで直列化する。

## 検証

2026-09-21: PushSubscriptionProtocolTest 6件、RelayRegistrationProtocolTest 4件成功。
Debug APKビルド成功。外部サービスへの通信、Relayサーバー実行、実機通知は未実施。

同日、鍵生成・暗号化保存・購読状態管理の13件を追加し、関連23件成功。
単体テスト全体102件とDebugビルドも成功。Android Keystoreの実機検証は未実施。

さらにローカルRelayのNode.jsテスト16件成功。localhostでデモを実行し、
登録・受付・模擬送信の一時失敗からの再送成功・解除を確認した。
AndroidコードはこのRelay追加では変更せず、Gradleは再実行していない。

検証対象はフォームエンコード、旧応答・未知フィールド、数値表現IDの文字列保持、
インスタンスとトークンの分離、404と認証・サーバーエラーの区別、解除再送、
FCMトークン更新要求、不正なID・HTTP設定の拒否。
