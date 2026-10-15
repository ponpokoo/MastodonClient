package io.github.ponpokoo.mastodonclient.feature.common

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme

/** Synthetic, offline fixtures; callbacks report their branch without opening a browser. */
class LinkHitDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        StatusLinkDiagnostics.enabled = true
        setContent {
            MastodonClientTheme {
                var result by remember { mutableStateOf("本文をタップして判定を確認") }
                var largeText by remember { mutableStateOf(false) }
                Surface {
                    Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("URLタップ領域の測定", style = MaterialTheme.typography.titleLarge)
                        Row {
                            Checkbox(StatusLinkDiagnostics.enabled, { StatusLinkDiagnostics.enabled = it })
                            Text("可視化・座標ログ", Modifier.padding(top = 12.dp))
                        }
                        Row {
                            Checkbox(largeText, { largeText = it })
                            Text("文字20sp・行高36sp", Modifier.padding(top = 12.dp))
                        }
                        Text("緑：リンクのレイアウト範囲\n橙：2dp間隔の座標変換結果\n青：本文境界／桃：最小ターゲット候補")
                        Text("橙・桃は他要素との競合を含まない予測です。実際の到達はタップログで確認します。", style = MaterialTheme.typography.bodySmall)
                        Text(result, Modifier.padding(vertical = 12.dp).testTag("diagnostic_result"))
                        val examples = listOf(
                            "単行・前後に本文" to "<p>前文 <a href=\"https://example.test/one\">example.test/one</a> 後文</p>",
                            "URLだけの一行" to "<p><a href=\"https://example.test/only\">example.test/only</a></p>",
                            "一つのURLの折り返し" to "<p><a href=\"https://example.test/long\">https://example.test/very/long/path/with/many/segments/and?a=123456789</a> 後文</p>",
                            "隣接する二つのリンク" to "<p><a href=\"https://example.test/a\">リンクA</a> <a href=\"https://example.test/b\">リンクB</a> 後文</p>",
                            "改行後の短いリンク" to "<p>リンクのない長い一行目の本文です<br><a href=\"https://example.test/short\">短いURL</a><br>三行目の本文</p>",
                        )
                        examples.forEachIndexed { index, (title, html) ->
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Column(Modifier.fillMaxWidth().clickable {
                                result = "例${index + 1}：親の投稿タップ"
                                Log.d("NagisaLinkHit", "fixture=$index branch=parent")
                            }.padding(vertical = 20.dp).testTag("fixture_parent_$index")) {
                                StatusContentText(
                                    html, modifier = Modifier.width(280.dp).testTag("fixture_text_$index"),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = if (largeText) 20.sp else 16.sp,
                                        lineHeight = if (largeText) 36.sp else 24.sp,
                                    ),
                                    onLinkClick = { result = "例${index + 1}：URL" },
                                    onNonLinkClick = { result = "例${index + 1}：本文から投稿詳細" },
                                )
                            }
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                        }
                        Text("実投稿でも有効になります。終了時は可視化をオフにしてください。プロセス終了でも解除されます。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
