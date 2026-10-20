package io.github.ponpokoo.mastodonclient

import android.content.ClipboardManager
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatusLinkInteractionDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val url = "https://example.test/original/path?value=123"
    private val events = mutableListOf<String>()

    private fun show(scrollable: Boolean = false) {
        rule.setContent {
            MaterialTheme {
                val scroll = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
                Column(scroll.clickable { events.add("parent") }.padding(32.dp)) {
                    StatusContentText(
                        "<p>前文 <a href=\"$url\">https://example.test/very/long/path/with/segments</a> 後文</p>",
                        modifier = Modifier.width(200.dp).testTag("link_text"),
                        onLinkClick = { events.add("open:$it") },
                        onOpenInBrowser = { events.add("browser:$it") },
                        onNonLinkClick = { events.add("detail") },
                    )
                    if (scrollable) Spacer(Modifier.height(1600.dp))
                }
            }
        }
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("link_text", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun holdLink() {
        val layout = layout()
        val link = layout.layoutInput.text.getStringAnnotations("url", 0, layout.layoutInput.text.length).single()
        rule.onNodeWithTag("link_text", useUnmergedTree = true).performTouchInput { longClick(layout.getBoundingBox(link.start + 2).center) }
    }

    @Test fun tapOpensOnceAndLongPressOffersActionsWithoutNavigating() {
        show()
        val point = layout().getBoundingBox(5).center
        rule.onNodeWithTag("link_text", useUnmergedTree = true).performTouchInput { click(point) }
        rule.runOnIdle { assertEquals(listOf("open:$url"), events); events.clear() }
        holdLink()
        rule.onNodeWithText("開く").assertExists()
        rule.onNodeWithText("ブラウザーで開く").assertExists()
        rule.onNodeWithText("コピー").assertExists()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        save(rule.onNode(isPopup()).captureToImage(), "menu.png")
        rule.onNodeWithText("開く").performClick()
        rule.runOnIdle { assertEquals(listOf("open:$url"), events); events.clear() }
        holdLink()
        rule.onNodeWithText("ブラウザーで開く").performClick()
        rule.runOnIdle { assertEquals(listOf("browser:$url"), events); events.clear() }
        holdLink()
        rule.onNodeWithText("コピー").performClick()
        rule.runOnIdle {
            assertTrue(events.isEmpty())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals(url, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
        rule.onNodeWithText("開く").assertDoesNotExist()
    }

    @Test fun wrappedLinkHighlightsOnPressAndClearsWhenCanceled() {
        show()
        val node = rule.onNodeWithTag("link_text", useUnmergedTree = true)
        val layout = layout()
        val link = layout.layoutInput.text.getStringAnnotations("url", 0, layout.layoutInput.text.length).single()
        val baseline = node.captureToImage()
        rule.mainClock.autoAdvance = false
        try {
            node.performTouchInput { down(layout.getBoundingBox(link.start + 2).center) }
            rule.mainClock.advanceTimeByFrame()
            val pressed = node.captureToImage()
            save(pressed, "wrapped-pressed.png")
            val before = baseline.toPixelMap()
            val after = pressed.toPixelMap()
            val lines = (link.start until link.end).groupBy(layout::getLineForOffset)
            assertTrue(lines.size > 1)
            lines.forEach { (line, offsets) ->
                val boxes = offsets.map(layout::getBoundingBox)
                val left = boxes.minOf { it.left }.toInt().coerceAtLeast(0)
                val right = boxes.maxOf { it.right }.toInt().coerceAtMost(before.width)
                val top = layout.getLineTop(line).toInt().coerceAtLeast(0)
                val bottom = layout.getLineBottom(line).toInt().coerceAtMost(before.height)
                assertTrue("Each linked line must be highlighted", (left until right).any { x ->
                    (top until bottom).any { y -> before[x, y] != after[x, y] }
                })
            }
            node.performTouchInput { cancel() }
            rule.mainClock.advanceTimeByFrame()
            val canceled = node.captureToImage().toPixelMap()
            assertTrue((0 until before.width).all { x -> (0 until before.height).all { y -> before[x, y] == canceled[x, y] } })
            rule.runOnIdle { assertTrue(events.isEmpty()) }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun dismissAndScrollDoNotOpenLinkOrPost() {
        show(scrollable = true)
        holdLink()
        Espresso.pressBack()
        rule.onNodeWithText("開く").assertDoesNotExist()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        val node = rule.onNodeWithTag("link_text", useUnmergedTree = true)
        val layout = layout()
        val link = layout.layoutInput.text.getStringAnnotations("url", 0, layout.layoutInput.text.length).single()
        val point = layout.getBoundingBox(link.start + 2).center
        val originalTop = node.fetchSemanticsNode().boundsInRoot.top
        node.performTouchInput { swipe(point, point + Offset(0f, -60 * layout.layoutInput.density.density)) }
        assertTrue(node.fetchSemanticsNode().boundsInRoot.top < originalTop)
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithText("開く").assertDoesNotExist()
    }

    @Test fun hashtagCopiesVisibleTextAndOtherLinksKeepTheirOriginalUrls() {
        val cases = listOf(
            Triple("https://example.test/tags/%E6%97%A5%E6%9C%AC%E8%AA%9E", "#日本語タグ", "#日本語タグ"),
            Triple("https://example.test/page#section", "#見出し", "https://example.test/page#section"),
            Triple("https://example.test/tags/topic", "タグのページ", "https://example.test/tags/topic"),
        )
        val current = mutableStateOf(cases.first())
        rule.setContent {
            MaterialTheme {
                StatusContentText(
                    "<p><a href=\"${current.value.first}\">${current.value.second}</a></p>",
                    modifier = Modifier.padding(32.dp).testTag("link_text"),
                    onLinkClick = { events.add("open") },
                )
            }
        }
        cases.forEach { case ->
            rule.runOnIdle { current.value = case }
            holdLink()
            rule.onNodeWithText("コピー").performClick()
            rule.runOnIdle {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                assertEquals(case.third, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
                assertTrue(events.isEmpty())
            }
        }
    }

    @Test fun previewImageAndDescriptionOfferTheSameActionsWithoutOpeningThePost() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            val intents = mutableListOf<Intent>()
            override fun startActivity(intent: Intent) { intents.add(Intent(intent)) }
        }
        val image = File(context.cacheDir, "link-preview-test.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val status = TimelineStatus(
            timelineId = "preview", statusId = "preview", createdAt = "2026-10-15T00:00:00Z",
            author = StatusAuthor("fixture", "測定用", "fixture@example.test", ""), boostedBy = null,
            contentHtml = "", spoilerText = "", sensitive = false, visibility = "public", url = null,
            repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
            previewCard = PreviewCard(url, "URLプレビュー", "説明部分からも長押し", "link", "", image.toURI().toString(), 1f),
        )
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        StatusCard(status, onStatusClick = { events.add("detail") },
                            onOpenLink = { events.add("open:$it") }, onUnavailableAction = {})
                    }
                }
            }
        }
        val thumbnail = rule.onNodeWithTag("preview_card_image", useUnmergedTree = true)
        thumbnail.performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(listOf("open:$url"), events); events.clear() }
        thumbnail.performTouchInput { longClick(center) }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithText("開く").performClick()
        rule.runOnIdle { assertEquals(listOf("open:$url"), events); events.clear() }
        rule.onNodeWithText("説明部分からも長押し", useUnmergedTree = true).performTouchInput { longClick(center) }
        rule.onNodeWithText("コピー").performClick()
        rule.runOnIdle {
            assertTrue(events.isEmpty())
            assertEquals(url, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
        thumbnail.performTouchInput { longClick(center) }
        rule.onNodeWithText("ブラウザーで開く").performClick()
        rule.runOnIdle {
            assertTrue(events.isEmpty())
            assertEquals(url, context.intents.single().dataString)
            assertTrue(context.intents.single().selector!!.hasCategory(Intent.CATEGORY_APP_BROWSER))
        }
    }

    private fun save(bitmap: ImageBitmap, name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "link-interaction-checks").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
