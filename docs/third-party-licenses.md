# Androidアプリのライセンスと第三者通知

## アプリ内の表示と適用範囲

設定 → このアプリについてのバージョン直下に「ライセンス」の行を置く。タップすると本文だけの画面を開く。先頭のNagisaはアプリ本体のApache License 2.0・適用範囲・NOTICE。続いて使用ライブラリ名と同梱した原文・第三者通知をスクロールして読める。同一本文は対象のライブラリ名をまとめて一度だけ表示し、著作権表示の異なる本文は別々に残す。通信は行わない。[UI仕様](ui-guidelines.md)を参照。

アプリ本体の適用範囲はルートの[LICENSE](../LICENSE)に明記する。`app/` のうちプロジェクトが権利を持つ独自コード・素材が対象で、第三者コード・Relay・試作アプリ・文書には自動適用しない。依存コンポーネントはそれぞれの条件に従う。

表示はUI → LicensesViewModel → AppLicensesRepository → DefaultAppLicensesRepository → AppLicensesLocalDataSourceの順で行う。本文は `app/src/main/assets/licenses/` に収録し、IOディスパッチャーで読み込む。読み込み失敗は再試行でき、ViewModel破棄時には読み込みをキャンセルする。長いSDK通知は文字を欠落させずに分割表示し、Unicodeのサロゲートペアを分断しない。

## 収録内容（2026-10-07）

現在の作業ツリーで `releaseRuntimeClasspath` を解決し、156個の元のAAR/JARから同一Maven座標の重複をまとめた153件と、Nagisa本体1件を収録した。計154項目、共通本文を共有する10個のテキストをAPKへ同梱する。

以前の231件は既存AGP依存メタデータから得たBOM・プラットフォーム別の参照などを含む座標一覧だった。今回の153件は現在のソースで解決した実体のあるRuntimeアーカイブを数えたもので、単純に依存を減らした件数ではない。ReorderableのRelease用Android実装も今回の解決結果に含まれる。

| POMの宣言・上流確認による区分 | Maven座標数 |
| --- | ---: |
| Apache License 2.0 | 145 |
| BSD 3-Clause | 1 |
| Google SDK利用条件 | 7 |

この表はMavenコンポーネントの分類であり、SDK内部で使用される全ソフトウェアのライセンス種類を表すものではない。Google AARの `third_party_licenses.txt` にはMIT・BSDなど他のライセンスも含まれるため、原文をそのまま各SDKの本文へ収録している。POMに宣言がないGuava関連3件とZXingは上流のApache-2.0表記を確認した。

- LazyColumnはCompose Foundationの一部。`androidx.compose.foundation:foundation-android:1.10.5` を収録している。
- 並べ替え補助には `sh.calvin.reorderable:reorderable-android:3.1.0` を収録し、同版POMのApache-2.0宣言を確認した。
- AAR/JARと内部の `classes.jar`・`libs/*.jar` からLICENSE・NOTICE・COPYING・Googleの第三者通知本文を抽出した。空の通知と同一本文の重複は追加しない。
- DataStoreのExternal Protobufには同梱BSD本文と、Protocol Buffers上流の著作権表示を含むライセンス本文を収録した。
- OkHttpのPublic Suffix Listには元のNOTICEとMPL 2.0全文を収録する。原文の取得先案内も保持する。
- Google SDKは利用規約のURLを案内し、同梱第三者通知の本文をオフライン表示する。

## 本文の出典

Apache本文はルートLICENSEの原文を使用する。補足に用いる次の原文は `tools/license-texts/` に保存し、再生成時にネットワークへ接続しない。

- [Apache License 2.0公式本文](https://www.apache.org/licenses/LICENSE-2.0.html)
- [Protocol BuffersのLICENSE](https://github.com/protocolbuffers/protobuf/blob/main/LICENSE) → `Protocol-Buffers-LICENSE.txt`（2026-10-07取得）
- [SPDXのMPL-2.0本文](https://github.com/spdx/license-list-data/blob/main/text/MPL-2.0.txt) → `MPL-2.0.txt`（2026-10-07取得）。[Mozillaの公式本文](https://www.mozilla.org/en-US/MPL/2.0/)も参照。
- [Guava](https://github.com/google/guava)・[ZXing](https://github.com/zxing/zxing)・[Reorderable](https://github.com/Calvin-LL/Reorderable)

## 依存更新・公開候補作成時の更新

依存ライブラリを更新した場合、公開候補を生成する前に次の順で一覧と本文を再生成する。Androidアプリの通常ビルドがライセンス原文をインターネットから取得する仕組みは追加していない。

```powershell
.\gradlew.bat -I tools/export-license-artifacts.gradle :app:exportLicenseArtifacts --no-configuration-cache
.\tools\generate-license-assets.ps1
```

Gradleキャッシュの場所を変更している環境では、生成スクリプトの `-GradleCache` に `caches/modules-2/files-2.1` の場所を渡す。作業用一覧は `build/license-audit/` に出力し、絶対キャッシュパスを含む作業ファイルをGitへ含めない。

スクリプトは未確認のライセンス種類・POM不足では失敗する。新しいライセンスを検出した場合は、本文・著作権表示・NOTICEを確認して対応を追加する。元アーカイブの通知を補う必要がある場合も確認した根拠を記録する。

生成後は `assets/licenses/index.json` の各項目と参照先本文を照合し、変更画面を確認する。署名済みAPK/AABを作った時点でも同じ索引・本文が同梱されることを照合する。生成済みの一覧は今後の依存追加を自動追跡しない。

## 公開前に残る確認

- 公開候補の正確なAPK/AABに索引と本文が収録され、候補の依存解決結果と一致することを照合する。
- Maven一覧以外の同梱画像・フォント・アイコンなどの素材の権利を確認する。
- Google SDKの利用条件・第三者通知、上流の通知補足やソース取得先が配布条件を満たすことを最終確認する。
- アプリ自身の権利者名・ライセンスの適用範囲を公開前に確認する。

## 確認結果（2026-10-07）

- 本文を直接表示する中間実装で、関連単体テスト `LicensesViewModelTest` 5件が成功。重複読み込み、同梱本文の読み込み失敗からの再試行、破棄時のキャンセル、長文とUnicodeを欠落させない分割、同一本文の集約でライブラリ名と異なる著作権通知を保持することを確認した。
- 同じ中間実装でDebugアプリ・テストAPKの生成が成功。Debug APKに索引1個と本文10個が入り、154項目の参照先と本文のハッシュをすべて確認した。
- 同じ中間実装でPixel_10a（Android 17）の `LicensesDeviceTest` 1件が成功。アプリ本体・Reorderable・長いGoogle SDK通知の表示と末尾へのスクロール、Androidの戻る操作を確認した。画面テストはメモリ上の設定・テスト用バージョン表示と実際の同梱assetsを使った。
- その後、利用者の指定に合わせて「ライセンス」の行から本文だけの画面を開く構成に変更した。変更後のテストは利用者の指示により実行していない。実機確認用APKの作成依頼により `:app:assembleDebug` が成功し、現在の構成を含むDebug APKを生成した。端末確認は未実施。
- さらに実機確認用として `:app:assembleRelease --rerun-tasks --no-build-cache` が成功。署名済みRelease APK `app/release/Nagisa-2.4.1-release-20261007-165712.apk`（2.4.1／16）を生成し、通常の `app/release/app-release.apk` も同じ成果物へ更新した。単体・端末テストは実行していない。
- APK署名を検証し、従来の配布証明書SHA-256 `42a7a07e8fdd8fa16fe7360b6f45ad6c024daefe9727c9f7cc5dfaae378620c1` と一致した。APK内に本文画面のコードと154項目の索引・参照先本文が含まれ、以前の検索画面のコードは含まれないことを確認した。
- この実機確認用Release APKのSHA-256は `498fac446fa25f08ad989abde43f298c626ea93ef107e5b366d672c5dda78b7b`。実機は未接続のため、利用者の端末で古い画面が残る原因とインストール済みAPKとの一致は確認していない。
- 本件の変更を含むRelease AABの生成・公開候補との依存解決結果の照合は未実施。

## 現在のRuntimeアーカイブ一覧

| Maven座標 | ライセンス分類 | 追加収録した同梱本文数 |
| --- | --- | ---: |
| `androidx.activity:activity-compose:1.13.0` | Apache License 2.0 | 1 |
| `androidx.activity:activity-ktx:1.13.0` | Apache License 2.0 | 1 |
| `androidx.activity:activity:1.13.0` | Apache License 2.0 | 1 |
| `androidx.annotation:annotation-experimental:1.5.0` | Apache License 2.0 | 1 |
| `androidx.annotation:annotation-jvm:1.10.0` | Apache License 2.0 | 1 |
| `androidx.appcompat:appcompat-resources:1.7.0` | Apache License 2.0 | 0 |
| `androidx.arch.core:core-common:2.2.0` | Apache License 2.0 | 0 |
| `androidx.arch.core:core-runtime:2.2.0` | Apache License 2.0 | 0 |
| `androidx.autofill:autofill:1.0.0` | Apache License 2.0 | 0 |
| `androidx.browser:browser:1.9.0` | Apache License 2.0 | 1 |
| `androidx.collection:collection-jvm:1.5.0` | Apache License 2.0 | 1 |
| `androidx.collection:collection-ktx:1.5.0` | Apache License 2.0 | 1 |
| `androidx.compose.animation:animation-android:1.10.5` | Apache License 2.0 | 1 |
| `androidx.compose.animation:animation-core-android:1.10.5` | Apache License 2.0 | 1 |
| `androidx.compose.foundation:foundation-android:1.10.5` | Apache License 2.0 | 1 |
| `androidx.compose.foundation:foundation-layout-android:1.10.5` | Apache License 2.0 | 1 |
| `androidx.compose.material:material-icons-core-android:1.7.8` | Apache License 2.0 | 0 |
| `androidx.compose.material:material-icons-extended-android:1.7.8` | Apache License 2.0 | 0 |
| `androidx.compose.material:material-ripple-android:1.10.4` | Apache License 2.0 | 1 |
| `androidx.compose.material3:material3-android:1.4.0` | Apache License 2.0 | 1 |
| `androidx.compose.runtime:runtime-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.runtime:runtime-annotation-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.runtime:runtime-retain-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.runtime:runtime-saveable-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.ui:ui-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.ui:ui-geometry-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.ui:ui-graphics-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.ui:ui-text-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.ui:ui-tooling-preview-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.ui:ui-unit-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.compose.ui:ui-util-android:1.11.2` | Apache License 2.0 | 1 |
| `androidx.concurrent:concurrent-futures-ktx:1.1.0` | Apache License 2.0 | 0 |
| `androidx.concurrent:concurrent-futures:1.1.0` | Apache License 2.0 | 0 |
| `androidx.core:core-ktx:1.19.0` | Apache License 2.0 | 1 |
| `androidx.core:core-viewtree:1.0.0` | Apache License 2.0 | 1 |
| `androidx.core:core:1.19.0` | Apache License 2.0 | 1 |
| `androidx.customview:customview-poolingcontainer:1.0.0` | Apache License 2.0 | 0 |
| `androidx.customview:customview:1.0.0` | Apache License 2.0 | 0 |
| `androidx.datastore:datastore-android:1.2.1` | Apache License 2.0 | 1 |
| `androidx.datastore:datastore-core-android:1.2.1` | Apache License 2.0 | 1 |
| `androidx.datastore:datastore-core-okio-jvm:1.2.1` | Apache License 2.0 | 1 |
| `androidx.datastore:datastore-preferences-android:1.2.1` | Apache License 2.0 | 1 |
| `androidx.datastore:datastore-preferences-core-android:1.2.1` | Apache License 2.0 | 1 |
| `androidx.datastore:datastore-preferences-external-protobuf:1.2.1` | BSD 3-Clause | 1 |
| `androidx.datastore:datastore-preferences-proto:1.2.1` | Apache License 2.0 | 1 |
| `androidx.documentfile:documentfile:1.0.0` | Apache License 2.0 | 0 |
| `androidx.dynamicanimation:dynamicanimation:1.0.0` | Apache License 2.0 | 0 |
| `androidx.emoji2:emoji2:1.4.0` | Apache License 2.0 | 0 |
| `androidx.exifinterface:exifinterface:1.4.1` | Apache License 2.0 | 1 |
| `androidx.fragment:fragment:1.1.0` | Apache License 2.0 | 0 |
| `androidx.graphics:graphics-path:1.0.1` | Apache License 2.0 | 0 |
| `androidx.interpolator:interpolator:1.0.0` | Apache License 2.0 | 0 |
| `androidx.legacy:legacy-support-core-utils:1.0.0` | Apache License 2.0 | 0 |
| `androidx.lifecycle:lifecycle-common-java8:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-common-jvm:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-livedata-core:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-livedata:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-process:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-runtime-android:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-runtime-ktx-android:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-service:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-viewmodel-android:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.11.0` | Apache License 2.0 | 1 |
| `androidx.lifecycle:lifecycle-viewmodel:2.11.0` | Apache License 2.0 | 1 |
| `androidx.loader:loader:1.0.0` | Apache License 2.0 | 0 |
| `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0` | Apache License 2.0 | 0 |
| `androidx.media3:media3-common:1.11.1` | Apache License 2.0 | 0 |
| `androidx.media3:media3-container:1.11.1` | Apache License 2.0 | 0 |
| `androidx.media3:media3-database:1.11.1` | Apache License 2.0 | 0 |
| `androidx.media3:media3-datasource:1.11.1` | Apache License 2.0 | 0 |
| `androidx.media3:media3-decoder:1.11.1` | Apache License 2.0 | 0 |
| `androidx.media3:media3-exoplayer:1.11.1` | Apache License 2.0 | 0 |
| `androidx.media3:media3-extractor:1.11.1` | Apache License 2.0 | 0 |
| `androidx.navigation:navigation-common-android:2.10.0` | Apache License 2.0 | 1 |
| `androidx.navigation:navigation-compose-android:2.10.0` | Apache License 2.0 | 1 |
| `androidx.navigation:navigation-runtime-android:2.10.0` | Apache License 2.0 | 1 |
| `androidx.navigationevent:navigationevent-android:1.1.2` | Apache License 2.0 | 1 |
| `androidx.navigationevent:navigationevent-compose-android:1.1.2` | Apache License 2.0 | 1 |
| `androidx.print:print:1.0.0` | Apache License 2.0 | 0 |
| `androidx.profileinstaller:profileinstaller:1.4.1` | Apache License 2.0 | 1 |
| `androidx.room:room-common-jvm:2.8.4` | Apache License 2.0 | 1 |
| `androidx.room:room-ktx:2.8.4` | Apache License 2.0 | 1 |
| `androidx.room:room-runtime-android:2.8.4` | Apache License 2.0 | 1 |
| `androidx.savedstate:savedstate-android:1.5.0` | Apache License 2.0 | 1 |
| `androidx.savedstate:savedstate-compose-android:1.5.0` | Apache License 2.0 | 1 |
| `androidx.savedstate:savedstate-ktx:1.5.0` | Apache License 2.0 | 1 |
| `androidx.sqlite:sqlite-android:2.6.2` | Apache License 2.0 | 1 |
| `androidx.sqlite:sqlite-framework-android:2.6.2` | Apache License 2.0 | 1 |
| `androidx.startup:startup-runtime:1.1.1` | Apache License 2.0 | 0 |
| `androidx.tracing:tracing-ktx:1.2.0` | Apache License 2.0 | 0 |
| `androidx.tracing:tracing:1.2.0` | Apache License 2.0 | 0 |
| `androidx.transition:transition:1.6.0` | Apache License 2.0 | 1 |
| `androidx.vectordrawable:vectordrawable-animated:1.2.0` | Apache License 2.0 | 0 |
| `androidx.vectordrawable:vectordrawable:1.2.0` | Apache License 2.0 | 0 |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | Apache License 2.0 | 0 |
| `androidx.viewpager:viewpager:1.0.0` | Apache License 2.0 | 0 |
| `androidx.window:window-core-android:1.5.0` | Apache License 2.0 | 1 |
| `androidx.window:window:1.5.0` | Apache License 2.0 | 1 |
| `androidx.work:work-runtime:2.11.2` | Apache License 2.0 | 1 |
| `com.google.accompanist:accompanist-drawablepainter:0.37.3` | Apache License 2.0 | 0 |
| `com.google.android.datatransport:transport-api:3.1.0` | Apache License 2.0 | 0 |
| `com.google.android.datatransport:transport-backend-cct:3.1.9` | Apache License 2.0 | 0 |
| `com.google.android.datatransport:transport-runtime:3.1.9` | Apache License 2.0 | 1 |
| `com.google.android.gms:play-services-base:18.9.0` | Google SDK利用条件・第三者通知 | 1 |
| `com.google.android.gms:play-services-basement:18.9.0` | Google SDK利用条件・第三者通知 | 1 |
| `com.google.android.gms:play-services-cloud-messaging:17.4.0` | Google SDK利用条件・第三者通知 | 1 |
| `com.google.android.gms:play-services-stats:17.0.2` | Google SDK利用条件・第三者通知 | 1 |
| `com.google.android.gms:play-services-tasks:18.4.0` | Google SDK利用条件・第三者通知 | 1 |
| `com.google.errorprone:error_prone_annotations:2.26.0` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-annotations:17.0.0` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-common:22.2.1` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-components:19.0.0` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-datatransport:18.2.0` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-encoders-json:18.0.0` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-encoders-proto:16.0.0` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-encoders:17.0.0` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-iid-interop:17.1.0` | Google SDK利用条件・第三者通知 | 1 |
| `com.google.firebase:firebase-installations-interop:17.3.0` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-installations:19.1.2` | Apache License 2.0 | 0 |
| `com.google.firebase:firebase-measurement-connector:19.0.0` | Google SDK利用条件・第三者通知 | 1 |
| `com.google.firebase:firebase-messaging:25.1.3` | Apache License 2.0 | 0 |
| `com.google.guava:failureaccess:1.0.2` | Apache License 2.0 | 0 |
| `com.google.guava:guava:33.3.1-android` | Apache License 2.0 | 0 |
| `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` | Apache License 2.0 | 0 |
| `com.google.zxing:core:3.5.4` | Apache License 2.0 | 0 |
| `com.squareup.okhttp3:logging-interceptor:4.12.0` | Apache License 2.0 | 0 |
| `com.squareup.okhttp3:okhttp:4.12.0` | Apache License 2.0 | 1 |
| `com.squareup.okio:okio-jvm:3.11.0` | Apache License 2.0 | 0 |
| `com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0` | Apache License 2.0 | 0 |
| `com.squareup.retrofit2:retrofit:3.0.0` | Apache License 2.0 | 0 |
| `io.coil-kt.coil3:coil-android:3.2.0` | Apache License 2.0 | 0 |
| `io.coil-kt.coil3:coil-compose-android:3.2.0` | Apache License 2.0 | 0 |
| `io.coil-kt.coil3:coil-compose-core-android:3.2.0` | Apache License 2.0 | 0 |
| `io.coil-kt.coil3:coil-core-android:3.2.0` | Apache License 2.0 | 0 |
| `io.coil-kt.coil3:coil-gif:3.2.0` | Apache License 2.0 | 0 |
| `io.coil-kt.coil3:coil-network-core-android:3.2.0` | Apache License 2.0 | 0 |
| `io.coil-kt.coil3:coil-network-okhttp-jvm:3.2.0` | Apache License 2.0 | 0 |
| `javax.inject:javax.inject:1` | Apache License 2.0 | 0 |
| `org.jetbrains:annotations:23.0.0` | Apache License 2.0 | 0 |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.0` | Apache License 2.0 | 0 |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0` | Apache License 2.0 | 0 |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.10` | Apache License 2.0 | 0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2` | Apache License 2.0 | 0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2` | Apache License 2.0 | 0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2` | Apache License 2.0 | 0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0` | Apache License 2.0 | 0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0` | Apache License 2.0 | 0 |
| `org.jspecify:jspecify:1.0.0` | Apache License 2.0 | 0 |
| `sh.calvin.reorderable:reorderable-android:3.1.0` | Apache License 2.0 | 0 |
