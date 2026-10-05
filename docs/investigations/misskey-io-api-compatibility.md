# misskey.ioのMastodon API互換性

調査日：2026-10-06 02時台（日本時間）

## 結論

現在のアプリへmisskey.ioのアカウントで直接ログインし、通知・絵文字リアクションを扱う互換性は確認できず、必要なMastodon形式のAPIが存在しないことを直接観測した。MisskeyネイティブAPIを使う実装が別途必要と判断する。

Misskey公式FAQも、原則としてMastodon API互換ではないと説明している。[公式FAQ](https://misskey-hub.net/ja/docs/for-users/resources/faq/)

ActivityPubによるサーバー間の連合と、アプリから使うクライアントAPIの互換性は別の範囲。Fedibird等のMastodon互換サーバーにログインしてmisskey.io由来の投稿を扱う場合は、ログイン先サーバーが返すAPIと変換結果を確認する必要がある。今回の結果を、連合経由でMisskey由来の投稿やリアクションを扱えないという意味には使わない。

## 公開エンドポイントの直接観測

認証なしのGETで確認した。404応答の本文はいずれもMisskey形式の`error.code = UNKNOWN_API_ENDPOINT`であり、認証不足による401や単なるHTMLページではなかった。

| URLのパス | HTTP | 結果 |
| --- | --- | --- |
| `/api/v2/instance` | 404 | Mastodon形式のインスタンス情報APIがない |
| `/api/v1/instance` | 404 | 旧版のインスタンス情報APIもない |
| `/api/v1/notifications` | 404 | このアプリの通知取得先がない |
| `/.well-known/nodeinfo` | 200 | NodeInfo 2.1・2.0の公開先を返す |
| `/nodeinfo/2.1` | 200 | softwareは`misskey`、自己申告版は`2025.4.1-io.12b-fb6fbea074`。protocolsは`activitypub` |
| `/.well-known/oauth-authorization-server` | 200 | OAuthエンドポイントとMisskey用スコープを返す |
| `/api-doc` | 200 | 公開API仕様ページ。仕様JSONとして`/api.json`を指定 |
| `/api.json` | 200 | MisskeyネイティブAPIのOpenAPI仕様 |

サーバー実装の公開参照先はNodeInfoに記載された`https://github.com/MisskeyIO/misskey`。実行中バイナリとソースの一致は検証していない。

## 現コードとの相違

公開仕様の`servers`は`https://misskey.io/api`。次のネイティブAPIとパラメーターを確認した。[misskey.io公開API仕様](https://misskey.io/api-doc)、[仕様JSON](https://misskey.io/api.json)

| 機能 | 現在のアプリ | misskey.ioの公開仕様 |
| --- | --- | --- |
| アプリ登録・認証 | Mastodonの`POST /api/v1/apps`、OAuth、`read write` | OpenAPIに`/api/v1/apps`なし。OAuthはクライアント紹介ページのディスカバリを使う方式。MiAuthも別方式として公式文書に記載 |
| 通知一覧 | `GET /api/v1/notifications`、`max_id`、`limit` | `POST /api/i/notifications`、JSONの`untilId`・`sinceId`・`includeTypes`・`excludeTypes` |
| リアクション追加 | `PUT /api/v1/statuses/{id}/emoji_reactions/{emoji}` | `POST /api/notes/reactions/create`、JSONの`noteId`・`reaction` |
| リアクション削除 | `POST /api/v1/statuses/{id}/emoji_unreaction` | `POST /api/notes/reactions/delete`、JSONの`noteId` |
| 通知モデル | `created_at`、`account`、`status` | `createdAt`、`user`、`note`。リアクション通知には`reaction`、集約形式には`reaction:grouped`もある |
| 投稿のリアクション | `emoji_reactions`配列の存在で操作ボタンを判定 | Noteの`reactions`は絵文字から件数へのオブジェクト、`myReaction`は別フィールド |

現コードの参照：

- [ログイン処理](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultAuthRepository.kt)
- [Mastodon API定義](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/MastodonApi.kt)
- [通知DTO](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/dto/SocialDto.kt)
- [投稿DTO](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/remote/dto/StatusDto.kt)

MisskeyにもOAuthとPKCEはあるが、Mastodon方式のアプリ登録と同一ではない。OAuthが共通していることだけで、データ取得・操作APIやDTOの互換性を推定できない。[Misskey公式OAuth仕様](https://misskey-hub.net/ja/docs/for-developers/api/token/oauth/)、[MiAuth仕様](https://misskey-hub.net/ja/docs/for-developers/api/token/miauth/)

## 対応を検討する場合

`fedibird_capabilities`の判定を追加するだけではmisskey.io対応にならない。認証、ネイティブAPI呼出し、DTOから既存domainモデルへの変換、通知の種類指定とページング、Streamingなどの対応範囲を別途設計する必要がある。現在はMastodonクライアントの実装であり、Misskey対応を実装済みとして扱わない。

今回のmstdn.jpにおける空リアクションタブの連続読込は、API互換性の問題とは別に、対応が分からない場合でも自動取得が続かない制御で改善できる。[通知取得の調査](notification-mention-loading-report.md)

## 検証範囲

公開GETの観測、公開OpenAPI・公式文書の確認、現コードとの比較のみ。アプリ登録・ログイン・トークン発行・通知の既読化・リアクション送信は行っていない。端末・エミュレーター・実アカウントも使用していない。本番コードは変更しておらず、文書とローカルリンク・差分の確認のみ行う。Gradle実行は不要。
