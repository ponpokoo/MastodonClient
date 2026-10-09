# 自プロフィール画像の再取得と点滅の調査

2026-10-07の作業ツリーを対象とした、実装変更前の原因と修正案の調査記録。
依頼の対象は自アカウントのアイコンとヘッダー。添付文書の実装手順には着手せず、原因調査と最小修正案までを扱った。

状態：解決済み。2026-10-08に修正を実装し、2026-10-09の利用者報告により点滅の問題は解決済みとして扱う。現行動作は[UI・機能仕様](../ui-guidelines.md#登録済み自アカウントの表示情報)を参照する。以下の原因・経路・確認案は調査時点の記録として残す。

## 結論

アイコンはプロフィール情報の取得成功ごとに更新番号が増え、新しいCoil要求へ切り替わる。
現在のAsyncImageはLoading・Errorで旧画像を保持しないため、キャッシュ未命中の再取得開始時に旧画像が消え、背景だけの表示になる。
これはURLが同じ場合にも起こる。再取得を止める修正では、同じURLの画像変更を検出できなくなる。

ヘッダーもURLが変わると旧画像を保持せず新要求へ移る。一方、URLが同じ場合は再取得の契機がなく、古い画像が残り得る。
アイコンの「必ず再取得するが旧表示を保持しない」と、ヘッダーの「同じURLでは再取得しない」を分けて修正する必要がある。

通常のPull-to-RefreshはUserProfileをnullへ戻さない。Composable全体の破棄が毎回の点滅を起こすという根拠は見つからなかった。
実機での点滅の再現・時間計測は未実施であり、原因はコードと利用中のCoil実装から確認したもの。

## 調査時点の経路

| 対象 | 呼出しと表示 | 確認した動作 |
| --- | --- | --- |
| メインの自プロフィール | HomeTimelineScreen → OwnProfileViewModel.refreshProfile → loadTab(refresh=true) → getProfileHeader | Account情報取得後に既存profileを置換。取得中は旧profileを保持 |
| 別画面の自プロフィール | AccountProfileScreen → AccountProfileViewModel.refresh → loadTab(refresh=true) → getProfileHeader | メインと同じProfileContentで表示 |
| 自プロフィール取得・編集 | DefaultTimelineRepository.getProfile/getProfileHeader/updateProfile → AccountDisplaySynchronizer.commit → SecureAuthStore.updateAccountDisplay | API応答を再利用し、登録情報とアイコン更新番号を更新 |
| 起動・アカウント切替 | MainSessionViewModel.refreshAccountDisplay → DefaultAuthRepository.refreshAccountDisplay → verifyCredentials → 同じ同期処理 | 背景の表示情報更新でもアイコン再取得の契機になる |
| アイコン表示 | SocialScreens.ktのProfileContent → accountAvatarModel → AsyncImage | 更新番号とURLをmemory/diskキーへ含める |
| ヘッダー表示 | 同じProfileContent → AsyncImage(model=profile.headerUrl) | URLだけをモデルに使用。更新番号・再検証要求なし |

主なコード位置：

- [AccountAvatar.kt:19](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/common/AccountAvatar.kt#L19)：URL・更新番号をキーとしてImageRequestを生成。
- [SecureAuthStore.kt:81](../../app/src/main/java/io/github/ponpokoo/mastodonclient/core/security/SecureAuthStore.kt#L81)：表示情報が同じでもavatarRevisionを加算。
- [修正前のSocialScreens.kt:407](https://github.com/ponpokoo/MastodonClient/blob/cd75da317a7f3d7bdd82dc51f45df1095ddd888b/app/src/main/java/io/github/ponpokoo/mastodonclient/feature/timeline/SocialScreens.kt#L407)：削除された同名ファイルの参照用。修正前コミットの固定キーprofile_header内のヘッダーとアイコンを示し、調査時の未コミット変更を含む作業ツリー全体を示すものではない。
- [OwnProfileViewModel.kt:174](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/profile/OwnProfileViewModel.kt#L174)：再取得したprofileへの置換。
- [AccountProfileViewModel.kt:156](../../app/src/main/java/io/github/ponpokoo/mastodonclient/feature/profile/AccountProfileViewModel.kt#L156)：別画面での同じ置換。
- [DefaultTimelineRepository.kt:203](../../app/src/main/java/io/github/ponpokoo/mastodonclient/data/repository/DefaultTimelineRepository.kt#L203)：Account取得と登録表示情報の同期。

## 点滅の直接原因

現在のアイコン要求は`account-avatar:<URL>:<avatarRevision>`をmemoryCacheKey・diskCacheKeyに使う。
新番号には旧画像のキャッシュがないため、旧表示を残す処理がなければ読込中に空表示になる。
placeholder・error・fallback・旧画像を保持するtransformは、プロフィールの両AsyncImageに設定されていない。
背景色と枠は残るので、ユーザーには画像が一度消えたように見える。特定のplaceholder画像を明示表示しているわけではない。

利用中のCoilは3.2.0（gradle/libs.versions.toml）。公式ソースとローカルの3.2.0 sources.jarを確認した。
AsyncImagePainterは要求開始時にplaceholderからState.Loadingを作り、変換後のstate.painterへ描画対象を置換する。
placeholderがnullなら旧SuccessのPainterは引き継がれない。Errorも同様に旧Successを保持しない。
モデル比較にはmemoryCacheKey・diskCacheKeyが含まれるので、URL不変でも番号変更で要求が再起動する。
根拠：[Coil 3.2.0 AsyncImagePainter](https://github.com/coil-kt/coil/blob/3.2.0/coil-compose-core/src/commonMain/kotlin/coil3/compose/AsyncImagePainter.kt)、
[モデル比較](https://github.com/coil-kt/coil/blob/3.2.0/coil-compose-core/src/commonMain/kotlin/coil3/compose/LocalAsyncImageModelEqualityDelegate.kt)。

OwnProfileViewModelは通常更新中もprofileを維持し、ProfileContentの全画面Loadingはprofileがnullの場合だけ。
ヘッダー行のLazyColumnキーも固定で、画像URL・更新番号をComposable全体の破棄キーにしていない。
`key(profile.url, profile.author.id)`はタブ状態の記憶に使われており、通常更新で画像を作り直す主因とは判断しない。
画面離脱・アカウント切替・LazyColumnによる画面外項目の破棄は、通常更新とは別のライフサイクルとして扱う。

## キャッシュと画像URL

- MainActivityのSingletonImageLoaderはGIF/AnimatedImageDecoderを追加する。プロフィール専用の旧画像保持・再検証方針はない。
- アイコンは新しいキーとCache-Control: no-cacheで再取得する。旧キーは通常のCoil容量管理に任せており、更新時に全画像キャッシュを削除してはいない。
- 全画像キャッシュの削除は設定のAppMaintenanceDataSource.clearImageCacheの別操作。
- ヘッダーは通常のURLモデル。URLが不変ならモデルも不変で、同一Composableから新しい要求を起動しない。
- Coil 3.2.0の既定CacheStrategyはディスク応答を採用する。固定キーにno-cacheヘッダーを足すだけで毎回の再検証を保証する案は採用しない。
- AccountDtoはavatar/headerを使用し、avatar_static/header_staticを保持していない。現行の動画像対応を維持する。
- Mastodon公式文書はstaticを静止版として定義している。staticという名前はURL不変の意味ではない。URL一致を画像変更判定にしない。[Account仕様](https://docs.joinmastodon.org/entities/Account/#avatar_static)

## 最小修正案（未実装）

1. 自プロフィールのアイコンとヘッダーに、小さな共通画像表示部品を適用する。既存のサイズ・枠・角丸・タップ操作・AsyncImageを維持する。
2. 部品内で最後に取得成功したPainterを保持し、Loading・Errorではそれを描く。新しいSuccessでだけ置換する。
   Coil 3.2.0のAsyncImageにはtransform/onStateのAPIがあるため、新たな全画面状態管理やSubcomposeAsyncImageへの置換は不要。
   Loading・ErrorをSuccessへ偽装せず、取得状態と表示Painterを分ける。[AsyncImage API](https://github.com/coil-kt/coil/blob/3.2.0/coil-compose-core/src/commonMain/kotlin/coil3/compose/AsyncImage.kt)
3. 旧Painterの保持キーは画像URLではなく、セッション・サーバー・対象アカウント・画像種別で区別する。
   同じアカウントのURL変更では旧表示を保持し、別アカウントへ切り替わったら引き継がない。
   新要求・画面離脱では以前の要求をキャンセルし、遅れた応答を表示へ反映しない。
4. アイコンの既存更新番号による再取得は維持する。ヘッダーにもAccount取得成功に紐づく再取得の契機を追加する。
   まず既存更新番号を利用できる範囲を優先する。ただし登録情報の保存失敗時は番号が得られないため、
   保存処理だけに依存せず画面内の成功取得世代を補助に使う方法を実装時に詰める。番号は画像変更の証明ではなく再検証要求として扱う。
5. 別画面のAccountProfileViewModel.updateProfileはupdated.headerUrlをprofileへ反映していない。
   OwnProfileViewModel側は反映しているので、別画面の編集結果にもヘッダー更新を反映する。

保持する画像は表示中のアイコン・ヘッダー各一つに限定する。CoilのImage/PainterをドメインRepositoryや暗号化保存へ持ち込まない。
crossfadeだけの追加や固定キャッシュキーへの変更では、読込開始・失敗時の旧表示保持を満たせない。
placeholderMemoryCacheKeyだけに頼る案も、キャッシュ追い出し・取得失敗時の保持を保証できない。

## 共通化の範囲

メインと別画面の自プロフィールはProfileContentを共有するため、表示部分の修正は一か所へまとめられる。
登録済みアカウントの切替行・切替ダイアログ・下書き行・設定の登録行にもaccountAvatarModelが使われ、同じ読込中の空表示が起こり得る。
共通画像部品の再利用は可能だが、今回の初期適用は自プロフィールの2画像に限定する案とする。
他人の画像、投稿添付、絵文字の画像、メディアビューアー全体への適用は別判断。
ヘッダーの拡大経路には更新番号が渡されていないため、実際のヘッダー変更確認では拡大表示との整合も確認する。

## 調査時点のテスト評価と確認案

AccountDisplayRepositoryTestは各プロフィール取得経路での番号更新・応答再利用を確認する。
AccountDisplayAvatarDeviceTestは同一URLの最終色が新画像へ変わることと拡大表示を確認する。
いずれも新画像が届くまでの各フレーム、読込失敗時の旧画像、ヘッダーの同一URL更新は確認していない。

調査時点では、修正後の確認案として遅延応答を制御できる画像配信を使い、以下を確認する方針だった。

| 条件 | 期待する表示 |
| --- | --- |
| URL不変・内容不変でPull-to-Refresh | 旧画像を維持し、空表示を挟まない |
| URL不変・内容変更 | 背景で取得し、成功後に新画像へ置換 |
| URL変更・応答待ち | 同じアカウントの旧画像を維持 |
| HTTP失敗・オフライン・デコード失敗 | 表示可能な旧画像を維持。再試行で更新できる |
| API失敗 | 画像URL・再取得の契機・表示を不用意に変更しない |
| 画像読込中にアカウント切替・画面離脱 | 旧アカウントの画像・遅延応答を次の画面へ反映しない |
| 短時間に複数更新 | 古い要求の完了で新画像を巻き戻さない |
| 登録情報保存失敗・同一URL | Account取得成功時の画像再取得を保存の成否だけで止めない |
| ヘッダー編集・拡大・動画像 | 両プロフィール経路で更新。拡大との整合とGIF対応を維持 |

今回実施したのはアプリコード、依存のソース、既存テスト内容の確認と文書・参照先チェック。
アプリ実装・テスト変更、Gradle実行、エミュレーターでの再現、実サーバー画像の観測は行っていない。
調査時点では[表示同期仕様](../ui-guidelines.md#登録済み自アカウントの表示情報)、[開発ガイド](../project-setup.md#登録済みアカウント情報の同期)、
[ADR 0011](../adr/0011-account-display-synchronization.md)を変更せず、修正実装時に整合を更新する方針だった。現行動作は冒頭のリンクを参照する。
