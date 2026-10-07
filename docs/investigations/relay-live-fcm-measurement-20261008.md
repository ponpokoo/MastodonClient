# 実FCMの通信量・表示遅延（2026-10-08）

## 結論と範囲

本番ハイブリッドRelayから、背景のPixel_10aエミュレーター（Android 17）へ送った実メンション5件を測定した。
5件ともinlineで受信し、復号・Android通知表示まで確認した。受信からOS通知のpostTimeまでは中央値63ms。
今回の少量実FCM調査はこの記録で終了する。大通知・欠落回復・物理端末の負担・一般公開の運用合格は未判定。
方式採用の判断は[ADR 0013](../adr/0013-adopt-hybrid-push-delivery.md)、本番版は[切替記録](relay-production-hybrid-cutover-20261008.md)を参照する。

## 方法と匿名化

- Firebaseを設定した2.4.1 Debugと計測用androidTest APKを使用。既存ログイン・購読を引き継いだ。
- 利用者が別の管理下アカウントから5件を送信。アプリを背景に置き、通知を開かず観測した。
- APK更新前に送られた最初の5件は測定対象外。更新後の保持通知からは時刻を復元できず、準備完了後の追加5件を対象にした。
- 起動後10秒待ち、15.002秒の無通信基準を取得。通知待ち・表示後5秒を含む観測窓は121.932秒。
- Debug受信WorkにFCM送信時刻と端末受信時刻を記録。Work・復号済みID・OS通知・APIの照合は端末プロセス内で行った。
- 100ms間隔でOS通知を観測し、最初に観測したpostTimeを採用。アイコン更新が初回観測より早い場合は、その更新時刻が含まれ得る。
- DB全体・本文・トークン・通知ID・アカウント識別子は取り出さず、件数・サイズ・時間差だけを保存した。
- 通信カウンター取得後に時刻照合のNotifications APIを1回呼んだ。この応答12,934 bytesは観測窓の通信量に含めない。

匿名原票：[通知観測JSON](relay-live-fcm-observe-20261008.json)、[時計確認JSON](relay-live-fcm-clock-20261008.json)。

## 結果

| 指標 | 実測 |
| --- | --- |
| FCM受信・IDを照合したOS表示 | 5件／5件 |
| 配送方式 | inline 5件、sync_required 0件、fetch 0件 |
| 暗号文 | 各369 bytes、5件合計1,845 bytes |
| 端末で受け取ったFCM dataのJSONサイズ | 各850 bytes、合計4,250 bytes |
| 受信→OS postTime | 107／63／54／60／68ms。中央値63ms、平均70.4ms、範囲54〜107ms |
| アプリUID受信／送信 | 111,444／14,723 bytes |
| アプリUID合計 | 126,167 bytes、123.2KiB。5件で割ると24.6KiB／件 |
| 端末全体受信／送信 | 135,296／23,934 bytes、合計159,230 bytes（155.5KiB） |
| 15秒の基準 | アプリ0／0 bytes、端末全体144／144 bytes |

FCM dataのJSONは論理サイズであり、FCM送信用HTTP全体や端末との通信量ではない。
[TrafficStats](https://developer.android.com/reference/android/net/TrafficStats)はネットワーク層の累積カウンター差分を使った。
アプリUID値には通知アイコン等の通信・プロトコル費用が含まれ、Google Play servicesのFCM受信通信は別UIDである。
端末全体値にはGoogle Play servicesや他の背景通信も含むため、純粋なFCM通信量へ分離できない。
15秒の基準を観測窓へ外挿して差し引いていない。1件当たりの値は今回の窓の平均で、通知単体の通信を個別に追跡した値ではない。

## 時計と送信からの遅延

[FCM sentTime](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/RemoteMessage)と端末受信時刻の生の差は−2,501〜−2,441ms。
サーバーの[Notification.created_at](https://docs.joinmastodon.org/entities/Notification/)からOS表示までの生の差も負になった。
これらは時計が異なるため、確定した遅延として採用しない。受信→OS表示は同じ端末時計なので有効。

観測窓の後に公開HTTPS応答のDateを8回調べた。1秒の精度と往復時間を考慮した区間の共通部分では、
公開時刻が端末より2,479〜2,680ms先行した。端末時計は変更していない。
観測中も同じずれが続き、各送信元の時計が公開時刻と一致したと仮定すると、
FCM送信→OS表示は約0.04〜0.35秒、サーバー通知作成→OS表示は約0.44〜1.19秒となる。
この補正は事後の参考推定であり、送信元の時計・観測中のずれを独立に保証できない。
厳密な全区間の遅延やp95／p99、Doze・強制停止・負荷集中時の遅延を確定する測定ではない。

## 負担軽減についての判断

この5件では大通知向けの差分同期は発動していない。inlineは実装上Notifications APIなしで通知を表示する。
API呼出回数を本番クライアント全体へ計装した測定ではないため、観測窓の全API要求数を0件と断定はしない。
通知ごとのアイコン取得がある実装と通信量は整合するが、今回の匿名カウンターだけで費用の内訳は確定できない。
小通知が多い条件でAPI同期を増やさないハイブリッド採用方針を変更する根拠はない。
旧方式との同一条件の通信比較や、100人全体への平均サイズ・端末通信量の外挿には使わない。

## 再現と確認

計測器：[PushPerformanceDeviceTest](../../app/src/androidTest/java/io/github/ponpokoo/mastodonclient/PushPerformanceDeviceTest.kt)。
Debugだけに受信時刻を加え、Releaseの受信入力に測定フィールドを追加しない。
以下はAPKをビルド・導入した後、対象端末を明示して実行する。observeの準備完了表示後に外部から通知を送る。

```powershell
adb -s emulator-5554 shell am instrument -w -e class io.github.ponpokoo.mastodonclient.PushPerformanceDeviceTest#preflight -e pushPerformance preflight io.github.ponpokoo.mastodonclient.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class io.github.ponpokoo.mastodonclient.PushPerformanceDeviceTest#measureNewRealPushWindow -e pushPerformance observe -e expectedCount 5 -e observeSeconds 240 io.github.ponpokoo.mastodonclient.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class io.github.ponpokoo.mastodonclient.PushPerformanceDeviceTest#inspectClockOffset -e pushPerformance clock io.github.ponpokoo.mastodonclient.test/androidx.test.runner.AndroidJUnitRunner
```

結果は端末のアプリ外部cacheの`push-performance-{observe,clock}.json`へ匿名保存する。
preflight・実通知観測・時計確認各1件、FcmEnvelopeTest 4件、HybridPushDeviceTest 2件は成功。
Debug／androidTest APKのビルドも成功。全単体テストの再実行・Release APKの測定は行っていない。
測定後に計測用APKと端末の匿名結果ファイルを削除し、アプリ・ログイン・本番購読は保持した。

実FCMの大通知同期、自然なonDeletedMessages、2時間障害からの回復、電池・物理端末、
Workerの実Google通信CPU、日次清掃・24時間運用は[採用仕様の継続確認](../../relay/workers/hybrid.md#採用後の対応と確認)に残る。
