package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(coil3.annotation.DelicateCoilApi::class)
class StatusEmojiLayoutDeviceTest {
    @get:Rule val rule = createComposeRule()
    private var loader: ImageLoader? = null
    private var previousLoader: ImageLoader? = null
    private val completed = AtomicInteger()
    private val gate = CompletableDeferred<Unit>()
    private val lastGate = CompletableDeferred<Unit>()
    private val fontSize = mutableStateOf(14)
    private val fontScale = mutableStateOf(1f)
    private val shown = mutableStateOf(true)
    private val events = mutableListOf<String>()
    private val url = "https://example.test/link"
    private val emojis = listOf("square", "wide", "tall", "banner", "broken").associateWith { "https://emoji.test/$it" }

    private fun render(delayed: Boolean = false) {
        if (!delayed) { gate.complete(Unit); lastGate.complete(Unit) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previousLoader = SingletonImageLoader.get(context)
        val testLoader = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<Uri> { data, _, _ ->
                Fetcher {
                    gate.await()
                    val (width, height) = when (data.toString().substringAfterLast('/')) {
                        "wide" -> 160 to 32
                        "tall" -> 8 to 32
                        "banner" -> { lastGate.await(); 1024 to 16 }
                        "broken" -> { completed.incrementAndGet(); error("Fixture failure") }
                        else -> 32 to 32
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val name = data.toString().substringAfterLast('/')
                    bitmap.eraseColor(when (name) {
                        "wide" -> android.graphics.Color.CYAN
                        "tall" -> android.graphics.Color.YELLOW
                        else -> android.graphics.Color.MAGENTA
                    })
                    val canvas = Canvas(bitmap)
                    val paint = Paint().apply { color = android.graphics.Color.BLACK; textSize = 24f; isAntiAlias = true }
                    canvas.drawText(if (name == "wide") "WIDE EMOJI" else "S", 1f, 24f, paint)
                    paint.style = Paint.Style.STROKE
                    canvas.drawRect(.5f, .5f, width - .5f, height - .5f, paint)
                    completed.incrementAndGet()
                    ImageFetchResult(bitmap.asImage(), false, DataSource.NETWORK)
                }
            })
        }.build()
        loader = testLoader
        SingletonImageLoader.setUnsafe(testLoader)
        rule.setContent {
            MaterialTheme {
                val current = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale.value)) {
                    Column(Modifier.width(180.dp)) {
                        if (shown.value) StatusContentText(
                            contentHtml = "<p>A :square::wide::tall: Z<br>:banner:<br>:wide::wide: <a href=\"$url\">link</a> end :broken:</p>",
                            customEmojis = emojis,
                            modifier = Modifier.testTag("body"),
                            style = TextStyle(fontSize = fontSize.value.sp, lineHeight = 16.sp),
                            emojiHeight = 24.dp,
                            onLinkClick = { events.add(it) },
                            onNonLinkClick = { events.add("detail") },
                        )
                    }
                }
            }
        }
    }

    @After fun cleanup() {
        previousLoader?.let { SingletonImageLoader.setUnsafe(it) }
        loader?.shutdown()
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun awaitImages() {
        rule.waitUntil(10_000) { completed.get() >= emojis.size }
        rule.waitUntil(10_000) { layout().placeholderRects[1]?.let { it.width > it.height * 4 } == true }
    }

    private fun checkGeometry() {
        val layout = layout()
        val density = layout.layoutInput.density.density
        val rectangles = layout.placeholderRects.map { requireNotNull(it) }
        assertEquals(7, rectangles.size)
        rectangles.forEach { rect ->
            assertEquals(24f * density, rect.height, 1.1f)
            assertTrue(rect.left >= -.1f && rect.right <= layout.size.width + .1f)
            assertTrue(rect.top >= -.1f && rect.bottom <= layout.size.height + .1f)
        }
        assertEquals(120f * density, rectangles[1].width, 1.1f)
        assertEquals(6f * density, rectangles[2].width, 1.1f)
        assertEquals(180f * density, rectangles[3].width, 1.1f)
        rectangles.forEachIndexed { index, rect ->
            rectangles.drop(index + 1).forEach { other -> assertFalse("Emoji slots overlap", rect.overlaps(other)) }
        }
        val text = layout.layoutInput.text
        text.indices.filter { offset ->
            !text[offset].isWhitespace() && layout.layoutInput.placeholders.none { offset in it.start until it.end }
        }.forEach { offset ->
            rectangles.forEach { rect -> assertFalse("Text overlaps emoji at $offset", rect.overlaps(layout.getBoundingBox(offset))) }
        }
    }

    @Test fun aspectRatiosFitAcrossFontSizesAndScalesAndLinksKeepWorking() {
        render()
        awaitImages()
        listOf(14 to 1f, 20 to 1f, 14 to .85f, 16 to 1.5f, 20 to 2f).forEach { (size, scale) ->
            rule.runOnIdle { fontSize.value = size; fontScale.value = scale }
            checkGeometry()
            saveScreenshot("emoji-$size-$scale.png")
        }
        val textLayout = layout()
        val link = textLayout.layoutInput.text.getStringAnnotations("url", 0, textLayout.layoutInput.text.length).single()
        val point = textLayout.getBoundingBox(link.start + 1).center
        rule.onNodeWithTag("body").performTouchInput { click(point) }
        rule.runOnIdle { assertEquals(listOf(url), events); events.clear() }
        rule.onNodeWithTag("body").performTouchInput { longClick(point) }
        rule.onNodeWithText("開く").performClick()
        rule.runOnIdle { assertEquals(listOf(url), events); events.clear() }
        val emojiPoint = layout().placeholderRects.first()!!.center
        rule.onNodeWithTag("body").performTouchInput { click(emojiPoint) }
        rule.runOnIdle { assertEquals(listOf("detail"), events) }
    }

    @Test fun dimensionsSettleTogetherAndAreReusedAfterRecomposition() {
        render(delayed = true)
        val before = layout().placeholderRects
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitUntil(10_000) { completed.get() == emojis.size - 1 }
        assertEquals(before, layout().placeholderRects)
        rule.runOnIdle { lastGate.complete(Unit) }
        awaitImages()
        checkGeometry()
        val resolved = layout().placeholderRects
        assertNotEquals(before, resolved)
        rule.runOnIdle { shown.value = false }
        rule.waitForIdle()
        rule.runOnIdle { shown.value = true }
        assertEquals(resolved, layout().placeholderRects)
    }

    private fun saveScreenshot(name: String) {
        val image = rule.onNodeWithTag("body").captureToImage()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "emoji-layout/$name")
        file.parentFile!!.mkdirs()
        android.graphics.Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
