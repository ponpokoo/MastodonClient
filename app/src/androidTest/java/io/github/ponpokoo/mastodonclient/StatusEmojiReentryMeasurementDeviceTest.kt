package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.ImageRequest
import coil3.request.ErrorResult
import coil3.request.Options
import coil3.request.SuccessResult
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic diagnostics, not a release performance benchmark. No server or account is used. */
@OptIn(coil3.annotation.DelicateCoilApi::class)
@SdkSuppress(minSdkVersion = 28)
class StatusEmojiReentryMeasurementDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLoader: ImageLoader? = null
    private var loader: ImageLoader? = null
    private val shown = mutableStateOf(false)
    private val fixture = mutableStateOf(Fixture("empty", 0, 0))
    private val starts = AtomicInteger()
    private val successes = AtomicInteger()
    private val memoryHits = AtomicInteger()
    private val decodes = AtomicInteger()
    private val errors = AtomicInteger()
    private val frames = Collections.synchronizedList(mutableListOf<Triple<Long, Long, Long>>())
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null
    private val listState = LazyListState()
    private val measures = Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
    private var gate = CompletableDeferred<Unit>().apply { complete(Unit) }

    private data class Fixture(val name: String, val occurrences: Int, val urls: Int, val gif: Boolean = false) {
        val emojis = (0 until urls).associate { "e$it" to "https://emoji.test/$name/$it.${if (gif) "gif" else "png"}" }
        val html = if (urls == 0) "<p>${"text ".repeat(occurrences)}</p>" else
            "<p>${(0 until occurrences).joinToString("") { ":e${it % urls}:" }}</p>"
    }

    private fun render(predecoded: Boolean = false) {
        val png = imageBytes(32)
        val widePng = imageBytes(160)
        val gif = animatedGif()
        // The 520-URL regression exercises metadata retention, independently of hundreds of native decoders.
        val decoded = if (predecoded) Bitmap.createBitmap(160, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }.asImage() else null
        previousLoader = SingletonImageLoader.get(context)
        val testLoader = ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(Fetcher.Factory<Uri> { data, _, _ ->
                    Fetcher {
                        gate.await()
                        if (decoded != null) return@Fetcher ImageFetchResult(decoded, false, DataSource.DISK)
                        val bytes = when {
                            data.toString().endsWith("gif") -> gif
                            data.toString().contains("wide") -> widePng
                            else -> png
                        }
                        SourceFetchResult(
                            ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
                            if (data.toString().endsWith("gif")) "image/gif" else "image/png",
                            DataSource.DISK,
                        )
                    }
                })
            }
            .eventListenerFactory {
                object : EventListener() {
                    override fun onStart(request: ImageRequest) { starts.incrementAndGet() }
                    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) { decodes.incrementAndGet() }
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        if (result.dataSource == DataSource.MEMORY_CACHE) memoryHits.incrementAndGet()
                        successes.incrementAndGet()
                    }
                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        errors.incrementAndGet()
                        android.util.Log.e("EmojiReentry", "Fixture image failed: ${result.throwable.javaClass.simpleName}")
                    }
                }
            }.build()
        loader = testLoader
        SingletonImageLoader.setUnsafe(testLoader)
        rule.runOnUiThread {
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames.add(Triple(metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), metrics.getMetric(FrameMetrics.DRAW_DURATION)))
            }
            frameListener = listener
            rule.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }
        rule.setContent {
            MaterialTheme {
                LazyColumn(Modifier.width(300.dp).height(500.dp), state = listState) {
                    item(key = "target") {
                        if (shown.value) StatusContentText(
                            contentHtml = fixture.value.html,
                            customEmojis = fixture.value.emojis,
                            modifier = Modifier.testTag("body").layout { measurable, constraints ->
                                val start = System.nanoTime()
                                val placeable = measurable.measure(constraints)
                                measures.add(placeable.height to (System.nanoTime() - start))
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            },
                            emojiHeight = 24.dp,
                            onLinkClick = {},
                        )
                    }
                    items(40) { Text("Filler $it", Modifier.height(80.dp)) }
                }
            }
        }
    }

    @After fun cleanup() {
        gate.complete(Unit)
        frameListener?.let { listener -> rule.runOnUiThread { rule.activity.window.removeOnFrameMetricsAvailableListener(listener) } }
        previousLoader?.let(SingletonImageLoader::setUnsafe)
        loader?.shutdown()
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun hideAndReset() {
        rule.runOnIdle { shown.value = false }
        rule.waitForIdle()
        scrollTo(0)
        resetCounters()
    }

    private fun resetCounters() {
        starts.set(0); successes.set(0); memoryHits.set(0); decodes.set(0); errors.set(0); measures.clear(); frames.clear()
    }

    private fun scrollTo(index: Int, offset: Int = 0) {
        runBlocking { withContext(Dispatchers.Main) { listState.scrollToItem(index, offset) } }
        rule.waitForIdle()
    }

    private fun show(value: Fixture) {
        rule.runOnIdle { fixture.value = value; shown.value = true }
        scrollTo(0)
        awaitImageResults(value.urls)
        rule.waitForIdle()
    }

    private fun awaitImageResults(expected: Int) {
        try {
            rule.waitUntil(20_000) { successes.get() >= expected || errors.get() > 0 }
        } catch (failure: Throwable) {
            throw AssertionError("Expected=$expected requests=${starts.get()} successes=${successes.get()} errors=${errors.get()} decodes=${decodes.get()} index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}", failure)
        }
        assertEquals("Fixture images failed", 0, errors.get())
    }

    @Test fun measureRepeatedMountsAndIdleDrawsForStaticAndAnimatedEmoji() {
        render()
        val csv = StringBuilder("fixture,round,occurrences,urls,requests,memoryHits,decodes,measureCalls,measureTotalMs,measureMaxMs,distinctHeights,idleFrames500ms,mountFrameMaxMs,mountLayoutMaxMs,mountDrawMaxMs\n")
        listOf(
            Fixture("plain", 128, 0),
            Fixture("static-repeat", 128, 1),
            Fixture("static-distinct", 128, 128),
            Fixture("animated-repeat", 128, 1, gif = true),
            Fixture("animated-distinct", 128, 128, gif = true),
        ).forEach { value ->
            hideAndReset()
            scrollTo(0)
            repeat(4) { round ->
                if (round == 0) show(value) else {
                    scrollTo(25)
                    resetCounters()
                    scrollTo(0)
                    rule.waitUntil(20_000) { successes.get() >= value.urls }
                    rule.waitForIdle()
                }
                val snapshot = measures.toList()
                val mountFrames = frames.toList()
                val before = frames.size
                SystemClock.sleep(500)
                val idleFrames = frames.size - before
                val row = listOf(value.name, round, value.occurrences, value.urls, starts.get(), memoryHits.get(), decodes.get(),
                    snapshot.size, snapshot.sumOf { it.second } / 1e6, (snapshot.maxOfOrNull { it.second } ?: 0L) / 1e6,
                    snapshot.map { it.first }.distinct().joinToString("/"), idleFrames,
                    (mountFrames.maxOfOrNull { it.first } ?: 0L) / 1e6,
                    (mountFrames.maxOfOrNull { it.second } ?: 0L) / 1e6,
                    (mountFrames.maxOfOrNull { it.third } ?: 0L) / 1e6).joinToString(",")
                csv.appendLine(row)
                android.util.Log.i("EmojiReentry", row)
                assertEquals(value.occurrences.takeIf { value.urls > 0 } ?: 0, layout().placeholderRects.size)
            }
        }
        output("mounts.csv", csv.toString())
    }

    @Test fun observeGeometryAfterWarmReentryAndRatioCacheEviction() {
        render()
        val target = Fixture("wide-target", 8, 8)
        show(target)
        rule.waitUntil(10_000) { layout().placeholderRects.first()!!.width > layout().placeholderRects.first()!!.height * 4 }
        val resolved = layout().placeholderRects
        val csv = StringBuilder("phase,firstWidthPx,firstHeightPx,bodyHeightPx,measuredHeightsPx,memoryHits,decodes\n")
        fun record(phase: String) {
            val current = layout()
            val rect = current.placeholderRects.first()!!
            csv.appendLine("$phase,${rect.width},${rect.height},${current.size.height},${measures.map { it.first }.distinct().joinToString("/")},${memoryHits.get()},${decodes.get()}")
        }
        record("first-loaded")
        hideAndReset()
        // Clear only image pixels: ratio retention should be independent of successful reloading.
        loader!!.memoryCache!!.clear()
        gate = CompletableDeferred()
        rule.runOnIdle { shown.value = true }
        scrollTo(0)
        assertEquals(resolved, layout().placeholderRects)
        record("warm-before-images")
        gate.complete(Unit)
        rule.waitUntil(10_000) { successes.get() >= target.urls }
        rule.waitForIdle()
        record("warm-loaded")
        hideAndReset()
        // Exercise the real production ratio cache through other posts, without accessing it directly.
        show(Fixture("churn", 520, 520))
        hideAndReset()
        loader!!.memoryCache!!.clear()
        gate = CompletableDeferred()
        rule.runOnIdle { fixture.value = target; shown.value = true }
        scrollTo(0)
        assertEquals(resolved, layout().placeholderRects)
        record("evicted-before-images")
        gate.complete(Unit)
        rule.waitUntil(10_000) { successes.get() >= target.urls }
        rule.waitUntil(10_000) { layout().placeholderRects == resolved }
        record("evicted-loaded")
        hideAndReset()
        show(Fixture("churn", 520, 520))
        hideAndReset()
        // Leave decoded pixels cached this time: observe whether losing only ratios still reflows.
        show(target)
        rule.waitUntil(10_000) { layout().placeholderRects == resolved }
        record("evicted-memory-hit-loaded")
        output("geometry.csv", csv.toString())
    }

    @Test fun moreThan512WideEmojiInOneHeldPostKeepTheirDimensionsAfterReentry() {
        render(predecoded = true)
        val target = Fixture("wide-oversized", 520, 520)
        show(target)
        rule.waitUntil(20_000) { layout().placeholderRects.all { it != null && it.width > it.height * 4 } }
        val resolved = layout().placeholderRects
        hideAndReset()
        loader!!.memoryCache!!.clear()
        gate = CompletableDeferred()
        rule.runOnIdle { shown.value = true }
        scrollTo(0)
        assertEquals(resolved, layout().placeholderRects)
        gate.complete(Unit)
        awaitImageResults(target.urls)
        assertEquals(resolved, layout().placeholderRects)
    }

    @Test fun readingPositionIsPreservedWhileWarmBodyImagesReload() {
        render()
        val target = Fixture("wide-reading-position", 128, 8)
        show(target)
        rule.waitUntil(10_000) { layout().placeholderRects.all { it != null && it.width > it.height * 4 } }
        val resolved = layout()
        scrollTo(25)
        resetCounters()
        loader!!.memoryCache!!.clear()
        gate = CompletableDeferred()
        scrollTo(0, 83)
        fun assertReadingPosition() {
            rule.runOnIdle {
                assertEquals(0, listState.firstVisibleItemIndex)
                assertEquals(83, listState.firstVisibleItemScrollOffset)
            }
            assertEquals(resolved.size, layout().size)
            assertEquals(resolved.placeholderRects, layout().placeholderRects)
        }
        assertReadingPosition()
        gate.complete(Unit)
        awaitImageResults(target.urls)
        assertReadingPosition()
    }

    private fun output(name: String, content: String) {
        File(context.filesDir, "emoji-reentry-measurements/$name").apply { parentFile!!.mkdirs(); writeText(content) }
        android.util.Log.i("EmojiReentry", content)
    }

    private fun imageBytes(width: Int): ByteArray = ByteArrayOutputStream().use { stream ->
        val bitmap = Bitmap.createBitmap(width, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        stream.toByteArray()
    }

    /** Two solid 32x32 frames, 20fps, looping. Clear each pixel to keep LZW codes at three bits. */
    private fun animatedGif(): ByteArray = ByteArrayOutputStream().apply {
        fun bytes(vararg values: Int) { values.forEach(::write) }
        write("GIF89a".toByteArray(Charsets.US_ASCII))
        bytes(32, 0, 32, 0, 0x80, 0, 0, 255, 0, 255, 0, 255, 255)
        bytes(0x21, 0xff, 11)
        write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        bytes(3, 1, 0, 0, 0)
        repeat(2) { color ->
            bytes(0x21, 0xf9, 4, 0, 5, 0, 0, 0, 0x2c, 0, 0, 0, 0, 32, 0, 32, 0, 0, 2)
            val data = ByteArrayOutputStream()
            var pending = 0
            var bits = 0
            fun code(value: Int) {
                pending = pending or (value shl bits); bits += 3
                while (bits >= 8) { data.write(pending and 255); pending = pending ushr 8; bits -= 8 }
            }
            repeat(32 * 32) { code(4); code(color) }
            code(5)
            if (bits > 0) data.write(pending)
            data.toByteArray().asList().chunked(255).forEach { block ->
                write(block.size); block.forEach { write(it.toInt() and 255) }
            }
            write(0)
        }
        write(0x3b)
    }.toByteArray()
}
