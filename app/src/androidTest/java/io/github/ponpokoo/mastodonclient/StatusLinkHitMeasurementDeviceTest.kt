package io.github.ponpokoo.mastodonclient

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpSize
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.StatusLinkDiagnostics
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Measurements of delivered callbacks, including points outside the Text layout bounds. */
class StatusLinkHitMeasurementDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun actualStatusCardDispatchesLinkAndDetailExactlyOnce() {
        val events = mutableListOf<String>()
        val status = TimelineStatus(
            timelineId = "fixture", statusId = "fixture", createdAt = "2026-10-15T00:00:00Z",
            author = StatusAuthor("fixture", "測定用", "fixture@example.test", ""), boostedBy = null,
            contentHtml = "<p>前文 <a href=\"https://example.test/one\">example.test/one</a> 後文</p>",
            spoilerText = "", sensitive = false, visibility = "public", url = null,
            repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
        )
        rule.setContent {
            MaterialTheme {
                StatusCard(status, onStatusClick = { events.add("detail") },
                    onOpenLink = { events.add("link") }, onUnavailableAction = {})
            }
        }
        try {
            val node = rule.onNodeWithText("前文 example.test/one 後文", useUnmergedTree = true)
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            rule.runOnIdle { StatusLinkDiagnostics.enabled = true }
            node.performTouchInput { click(layout.getBoundingBox(5).center) }
            node.performTouchInput { click(layout.getBoundingBox(0).center) }
            rule.runOnIdle { assertEquals(listOf("link", "detail"), events) }
        } finally {
            rule.runOnIdle { StatusLinkDiagnostics.enabled = false }
        }
    }

    @Test fun measureDispatchAndVerifyOverlayDoesNotChangeIt() {
        val fixtures = listOf(
            Fixture("single", "<p><a href=\"https://example.test/one\">example.test/one</a></p>"),
            Fixture("wrapped", "<p>前文 <a href=\"https://example.test/long\">https://example.test/very/long/path/with/many/segments</a> 後文</p>"),
            Fixture("adjacent", "<p><a href=\"https://example.test/a\">リンクA</a> <a href=\"https://example.test/b\">リンクB</a> 後文<br>リンクのない次の行</p>"),
            Fixture("wrapped_large", "<p>前文 <a href=\"https://example.test/long\">https://example.test/very/long/path/with/many/segments</a> 後文</p>", 20, 36),
        )
        val current = mutableStateOf(fixtures.first())
        val events = mutableListOf<String>()
        var minimumTarget = DpSize.Zero
        rule.setContent {
            MaterialTheme {
                minimumTarget = LocalViewConfiguration.current.minimumTouchTargetSize
                Column(Modifier.fillMaxWidth().testTag("measurement_root")) {
                    Text("緑:リンク範囲 橙:座標変換 青:本文 桃:最小ターゲット候補")
                    Text("${current.value.name} / ${current.value.fontSp}sp / 行高${current.value.lineSp}sp")
                    Column(Modifier.fillMaxWidth().clickable { events.add("parent") }.padding(32.dp)) {
                        StatusContentText(
                            current.value.html,
                            modifier = Modifier.width(280.dp).testTag("measurement_text"),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = current.value.fontSp.sp, lineHeight = current.value.lineSp.sp,
                            ),
                            onLinkClick = { events.add("link:${it.substringAfterLast('/')}") },
                            onNonLinkClick = { events.add("nonLink") },
                        )
                    }
                }
            }
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "link-hit-measurements").apply { mkdirs() }
        val csv = StringBuilder("fixture,overlay,density,fontScale,widthPx,heightPx,xPx,yPx,xDp,yDp,offset,rangeStart,rangeEnd,predictedLink,actual\n")
        val geometry = StringBuilder("fixture,linkIndex,offset,line,leftDp,topDp,rightDp,bottomDp\n")
        try {
            fixtures.forEach { fixture ->
                rule.runOnIdle { current.value = fixture; StatusLinkDiagnostics.enabled = false }
                val node = rule.onNodeWithTag("measurement_text", useUnmergedTree = true)
                val results = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single()
                val density = layout.layoutInput.density.density
                val text = layout.layoutInput.text
                val links = text.getStringAnnotations("url", 0, text.length)
                links.forEachIndexed { linkIndex, link ->
                    (link.start until link.end).forEach { offset ->
                        val box = layout.getBoundingBox(offset)
                        geometry.appendLine(listOf(fixture.name, linkIndex, offset, layout.getLineForOffset(offset),
                            box.left / density, box.top / density, box.right / density, box.bottom / density).joinToString(","))
                    }
                }
                val xs = mutableSetOf(0f, 4 * density, layout.size.width / 2f, layout.size.width - 1f)
                links.forEach { link ->
                    listOf(link.start, link.end - 1).forEach { offset ->
                        val box = layout.getBoundingBox(offset)
                        listOf(box.left - 2 * density, box.left + density, box.center.x, box.right - density, box.right + 2 * density)
                            .filter { it >= -16 * density && it <= layout.size.width + 16 * density }.forEach(xs::add)
                    }
                }
                val ys = mutableSetOf(-16 * density, -8 * density, -2 * density,
                    layout.size.height + 2 * density, layout.size.height + 8 * density, layout.size.height + 16 * density)
                val paddingY = ((minimumTarget.height.value * density - layout.size.height) / 2).coerceAtLeast(0f)
                if (paddingY > 0) {
                    listOf(-.5f, 0f, .5f).forEach { delta ->
                        ys.add(-paddingY + delta * density)
                        ys.add(layout.size.height + paddingY + delta * density)
                    }
                }
                repeat(layout.lineCount) { line ->
                    ys.add(layout.getLineTop(line) + density)
                    ys.add((layout.getLineTop(line) + layout.getLineBottom(line)) / 2)
                    ys.add(layout.getLineBottom(line) - density)
                }
                val points = ys.sorted().flatMap { y -> xs.sorted().map { x -> Offset(x, y) } }
                var baseline: List<String>? = null
                listOf(false, true).forEach { overlay ->
                    rule.runOnIdle { StatusLinkDiagnostics.enabled = overlay; events.clear() }
                    // Every point is inside the padded parent, even when outside the Text.
                    node.performTouchInput { points.forEach { click(it) } }
                    rule.waitForIdle()
                    val actual = events.toList()
                    assertEquals("Each tap must produce exactly one callback: ${fixture.name}", points.size, actual.size)
                    if (baseline == null) baseline = actual else assertEquals("Overlay changed dispatch: ${fixture.name}", baseline, actual)
                    points.forEachIndexed { index, point ->
                        val offset = layout.getOffsetForPosition(point)
                        val link = text.getStringAnnotations("url", offset, offset).firstOrNull()
                        csv.appendLine(listOf(fixture.name, overlay, density, layout.layoutInput.density.fontScale,
                            layout.size.width, layout.size.height, point.x, point.y, point.x / density, point.y / density,
                            offset, link?.start ?: -1, link?.end ?: -1, link != null, actual[index]).joinToString(","))
                    }
                    if (overlay) {
                        rule.mainClock.advanceTimeBy(1000)
                        rule.waitForIdle()
                        File(directory, "${fixture.name}.png").outputStream().use {
                            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
                rule.runOnIdle { events.clear() }
                node.performTouchInput { longClick(layout.getBoundingBox(links.first().start + 1).center) }
                rule.runOnIdle { assertEquals("Long hold must not open the link before choosing an action", 0, events.size) }
                rule.onNodeWithText("開く").performClick()
                rule.runOnIdle { assertEquals(1, events.size) }
                assertEquals(true, events.single().startsWith("link:"))
                Log.i("NagisaLinkHitMeasurement", "fixture=${fixture.name} points=${points.size} overlayInvariant=true longHold=menu_then_open")
            }
        } finally {
            File(directory, "measurements.csv").writeText(csv.toString())
            File(directory, "geometry.csv").writeText(geometry.toString())
            rule.runOnIdle { StatusLinkDiagnostics.enabled = false }
        }
    }

    private data class Fixture(val name: String, val html: String, val fontSp: Int = 16, val lineSp: Int = 24)
}
