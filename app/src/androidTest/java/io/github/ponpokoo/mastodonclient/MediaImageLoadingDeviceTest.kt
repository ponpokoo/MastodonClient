package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.media.*
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@OptIn(coil3.annotation.DelicateCoilApi::class)
class MediaImageLoadingDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private var previousLoader: ImageLoader? = null
    private var loader: ImageLoader? = null
    private lateinit var motion: MediaTransitionState

    private fun bitmap(color: Int) = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun render(fail: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previousLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<Uri> { _, _, _ -> Fetcher {
                started.complete(Unit)
                release.await()
                if (fail) error("Simulated image loading failure")
                ImageFetchResult(bitmap(android.graphics.Color.GREEN).asImage(), false, DataSource.MEMORY)
            } })
        }.build()
        SingletonImageLoader.setUnsafe(loader!!)
        val item = MediaAttachment("delayed", "image", "https://images.example.test/delayed.png", null, "Delayed image")
        motion = MediaTransitionState().apply {
            viewerSize = IntSize(1080, 2424)
            validateScope("standalone", "standalone")
            val group = MediaSourceGroup(this, false)
            thumbnailFrames[group.key(item)] = MediaThumbnailFrame(Rect(100f, 300f, 300f, 700f), true)
            group.displayed(item, BitmapPainter(bitmap(android.graphics.Color.BLUE).asImageBitmap()))
            group.prepare(item)
            open(listOf(item), 0, "standalone", "standalone")
        }
        rule.setContent { MediaTransitionHost(motion) {} }
        rule.waitUntil(5_000) { started.isCompleted }
    }

    @After fun restoreLoader() {
        release.complete(Unit)
        previousLoader?.let { SingletonImageLoader.setUnsafe(it) }
        loader?.shutdown()
    }

    private fun assertCenterIsBlue(verticalFraction: Float = 0.5f) {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, (pixels.height * verticalFraction).toInt()]
        assertTrue("Keep the displayed source image: $center", center.blue > 0.98f && center.red < 0.02f && center.green < 0.02f)
    }

    @Test fun delayedImageKeepsItsSourceUntilTheFirstFrameAndThenDisplaysTheLoadedImage() {
        render()
        rule.onNodeWithText("画像が取得できませんでした").assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(MediaPhase.Viewing, motion.phase)
        assertEquals(1f, motion.progress.value, 0.001f)
        assertFalse(motion.imageReady("delayed"))
        assertCenterIsBlue()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("media_viewer").performTouchInput { doubleClick(center) }
        assertEquals(2.5f, rule.onNodeWithTag("media_viewer").fetchSemanticsNode().config[MediaViewerScale], 0.01f)
        assertFalse(release.isCompleted)
        assertCenterIsBlue()
        release.complete(Unit)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) { motion.imageReady("delayed") }
        assertEquals(2.5f, rule.onNodeWithTag("media_viewer").fetchSemanticsNode().config[MediaViewerScale], 0.01f)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width / 2, pixels.height / 2].green > 0.98f)
        rule.onNodeWithText("画像が取得できませんでした").assertDoesNotExist()
    }

    @Test fun fullScreenDownwardSwipeClosesBeforeTheDelayedImageLoads() {
        render()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithTag("media_viewer").performTouchInput { down(center); moveTo(center + Offset(0f, height * 0.25f), 32) }
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        assertTrue(motion.dragOffset > 0f)
        val releasedOffset = motion.dragOffset
        rule.onNodeWithTag("media_viewer").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(16)
        assertEquals(releasedOffset, motion.transitionViewport.top, 0.01f)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(MediaPhase.Closed, motion.phase)
        assertFalse(release.isCompleted)
        rule.mainClock.autoAdvance = true
    }

    @Test fun backButtonDuringLoadingClosesWithoutWaitingForTheImage() {
        render()
        rule.onNodeWithTag("media_viewer_close").performClick()
        rule.waitForIdle()
        assertEquals(MediaPhase.Closed, motion.phase)
        assertFalse(release.isCompleted)
        rule.onNodeWithTag("media_viewer").assertDoesNotExist()
    }

    @Test fun failedImageLoadingHandsOffToTheDisplayedSource() {
        release.complete(Unit)
        render(fail = true)
        rule.waitUntil(5_000) { motion.imageReady("delayed") }
        assertTrue(motion.imageReady("delayed"))
        rule.onNodeWithText("画像が取得できませんでした").assertIsDisplayed()
        assertCenterIsBlue(verticalFraction = 0.25f)
    }

    @Test fun timelineUsesSufficientPreviewButViewerStillRequestsOriginal() {
        val requests = renderTimelinePreview()
        rule.waitUntil(5_000) { requests.contains("preview") }
        rule.waitForIdle()
        assertFalse(requests.contains("original"))
        rule.onNodeWithTag("media_attachment").performClick()
        rule.waitUntil(5_000) { requests.contains("original") }
    }

    @Test fun denserDisplaySwitchesToOriginal() {
        val density = mutableStateOf(2f)
        val requests = renderTimelinePreview(density = { density.value })
        rule.waitUntil(5_000) { requests.contains("preview") }
        rule.runOnIdle { density.value = 4f }
        rule.waitUntil(5_000) { requests.contains("original") }
    }

    @Test fun failedTimelinePreviewFallsBackToOriginal() {
        val requests = renderTimelinePreview(failPreview = true)
        rule.waitUntil(5_000) { requests.contains("original") }
        assertTrue(requests.contains("preview"))
        rule.waitForIdle()
        rule.onNodeWithText("画像が取得できませんでした").assertDoesNotExist()
    }

    @Test fun failedPreviewAndOriginalShowFailureInTimelineAndViewer() {
        renderTimelinePreview(failPreview = true, failOriginal = true)
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("画像が取得できませんでした").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("画像が取得できませんでした").assertIsDisplayed()
        rule.onNodeWithTag("media_attachment").performClick()
        rule.waitUntil(5_000) { motion.imageReady("picture") }
        rule.onNode(hasText("画像が取得できませんでした") and hasAnyAncestor(hasTestTag("media_viewer")))
            .assertIsDisplayed()
    }

    @Test fun imageFailureSurvivesScrollingAndRetryUntilSuccess() =
        failureSurvivesScrollingAndRetryUntilSuccess("image")

    @Test fun videoThumbnailFailureSurvivesScrollingAndRetryUntilSuccess() =
        failureSurvivesScrollingAndRetryUntilSuccess("video")

    private fun failureSurvivesScrollingAndRetryUntilSuccess(type: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previousLoader = SingletonImageLoader.get(context)
        val attempts = AtomicInteger()
        loader = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<Uri> { uri, _, _ -> Fetcher {
                if (uri.toString() == "https://images.example.test/retry") {
                    if (attempts.incrementAndGet() == 1) error("Media unavailable")
                    release.await()
                }
                ImageFetchResult(bitmap(android.graphics.Color.GREEN).asImage(), false, DataSource.MEMORY)
            } })
        }.build()
        SingletonImageLoader.setUnsafe(loader!!)
        val item = MediaAttachment("retry", type, "https://images.example.test/retry", "https://images.example.test/retry", null,
            aspectRatio = 0.5f)
        val status = TimelineStatus("retry", "retry", "2026-10-19T00:00:00Z",
            StatusAuthor("author", "Author", "author", ""), null, "", "", false, "public", null,
            0, 0, 0, mediaAttachments = listOf(item))
        val message = if (type == "video") "動画が取得できませんでした" else "画像が取得できませんでした"
        rule.setContent { MaterialTheme {
            LazyColumn(Modifier.width(300.dp).height(500.dp).testTag("retry_list")) {
                item(key = "media") { StatusCard(status, onStatusClick = null, onUnavailableAction = {}) }
                items(80) { Text("Row $it", Modifier.height(100.dp)) }
            }
        } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("retry_list").performScrollToIndex(80)
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.onNodeWithTag("retry_list").performScrollToIndex(0)
        rule.waitUntil(5_000) { attempts.get() >= 2 }
        // A fresh request is still pending: keep the saved error rather than flash an empty frame.
        rule.onNodeWithText(message).assertIsDisplayed()
        release.complete(Unit)
        rule.waitUntil(5_000) { rule.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("media_attachment").assertIsDisplayed()
    }

    @Test fun fullScreenVideoFailureSurvivesDisposalWhileInactive() {
        val active = mutableStateOf(true)
        val source = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "missing-video-${System.nanoTime()}.mp4").toURI().toString()
        rule.setContent { MaterialTheme {
            LazyColumn(Modifier.height(500.dp).testTag("retry_list")) {
                item(key = "video") {
                    Box(Modifier.height(300.dp)) { VideoPlayer(source, loop = false, active = active.value) }
                }
                items(80) { Text("Row $it", Modifier.height(100.dp)) }
            }
        } }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("動画が取得できませんでした").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("retry_list").performScrollToIndex(80)
        rule.onNodeWithTag("video_player").assertDoesNotExist()
        rule.runOnIdle { active.value = false }
        rule.onNodeWithTag("retry_list").performScrollToIndex(0)
        rule.onNodeWithText("動画が取得できませんでした").assertIsDisplayed()
    }

    @Test fun inlineVideoFailureSurvivesScrolling() {
        val source = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "missing-inline-video-${System.nanoTime()}.mp4").toURI().toString()
        rule.setContent { MaterialTheme {
            LazyColumn(Modifier.height(500.dp).testTag("retry_list")) {
                item(key = "video") {
                    Box(Modifier.height(300.dp)) { InlineVideo(source, loop = false, onDimensionsKnown = { _, _ -> }) }
                }
                items(80) { Text("Row $it", Modifier.height(100.dp)) }
            }
        } }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("動画が取得できませんでした").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("retry_list").performScrollToIndex(80)
        rule.onNodeWithTag("inline_video").assertDoesNotExist()
        rule.onNodeWithTag("retry_list").performScrollToIndex(0)
        rule.onNodeWithText("動画が取得できませんでした").assertIsDisplayed()
    }

    private fun renderTimelinePreview(failPreview: Boolean = false, failOriginal: Boolean = false,
        density: () -> Float = { 2f }): ConcurrentLinkedQueue<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requests = ConcurrentLinkedQueue<String>()
        previousLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<Uri> { uri, _, _ -> Fetcher {
                val source = uri.toString().substringAfterLast('/')
                if (source == "preview" || source == "original") requests.add(source)
                if (source == "preview" && failPreview) error("Preview unavailable")
                if (source == "original" && failOriginal) error("Original unavailable")
                ImageFetchResult(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.MEMORY)
            } })
        }.build()
        SingletonImageLoader.setUnsafe(loader!!)
        val item = MediaAttachment("picture", "image", "https://images.example.test/original", "https://images.example.test/preview", null,
            aspectRatio = 2f, previewWidth = 600, previewHeight = 300)
        val status = TimelineStatus("picture", "picture", "2026-10-19T00:00:00Z",
            StatusAuthor("author", "Author", "author", ""), null, "", "", false, "public", null,
            0, 0, 0, mediaAttachments = listOf(item))
        motion = MediaTransitionState()
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(density())) {
                MediaTransitionHost(motion) {
                    Box(Modifier.width(300.dp)) {
                        StatusCard(status, onStatusClick = null, onUnavailableAction = {}, useTimelineImagePreviews = true,
                            onMediaClick = { items, index -> motion.open(items, index, "standalone", "standalone") })
                    }
                }
            }
        } }
        return requests
    }
}
