package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

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

    private fun assertCenterIsBlue() {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("Keep the displayed source image: $center", center.blue > 0.98f && center.red < 0.02f && center.green < 0.02f)
    }

    @Test fun delayedImageKeepsItsSourceUntilTheFirstFrameAndThenDisplaysTheLoadedImage() {
        render()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(MediaPhase.Opening, motion.phase)
        assertEquals(1f, motion.progress.value, 0.001f)
        assertFalse(motion.imageReady("delayed"))
        assertCenterIsBlue()
        release.complete(Unit)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) { motion.phase == MediaPhase.Viewing }
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width / 2, pixels.height / 2].green > 0.98f)
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
        rule.waitUntil(5_000) { motion.phase == MediaPhase.Viewing }
        assertTrue(motion.imageReady("delayed"))
        assertCenterIsBlue()
    }
}
