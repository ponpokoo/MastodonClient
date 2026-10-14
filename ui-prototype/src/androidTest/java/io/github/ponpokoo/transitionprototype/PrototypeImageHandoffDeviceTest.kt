package io.github.ponpokoo.transitionprototype

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(coil3.annotation.DelicateCoilApi::class)
class PrototypeImageHandoffDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private var previousLoader: ImageLoader? = null
    private var loader: ImageLoader? = null
    private lateinit var motion: PrototypeMediaMotionState

    @OptIn(ExperimentalSharedTransitionApi::class)
    private fun render(fail: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previousLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<Uri> { _, _, _ ->
                Fetcher {
                    started.complete(Unit)
                    release.await()
                    if (fail) error("Simulated fixture loading failure")
                    val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    ImageFetchResult(bitmap.asImage(), false, DataSource.MEMORY)
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader!!)
        val image = PrototypeToots[1].mediaAttachments[3]
        motion = PrototypeMediaMotionState().apply {
            viewerSize = IntSize(1080, 2424)
            thumbnailFrames["$sourceOwner:${imageKey(image)}"] = PrototypeThumbnailFrame(Rect(100f, 300f, 300f, 700f), true)
            open(listOf(image), 0)
        }
        rule.runOnUiThread {
            rule.activity.setContent {
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPrototypeSharedTransitionScope provides this@SharedTransitionLayout) {
                        PrototypeMediaOverlay(motion)
                    }
                }
            }
        }
        rule.waitUntil(5_000) { started.isCompleted }
    }

    @After fun restoreLoader() {
        release.complete(Unit)
        previousLoader?.let { SingletonImageLoader.setUnsafe(it) }
        loader?.shutdown()
    }

    @Test
    fun delayedViewerKeepsTheTransitionPictureUntilItsFirstFrame() {
        render()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(MediaPhase.Opening, motion.phase)
        assertEquals(1f, motion.progress.value, 0.001f)
        assertFalse(motion.imageReady("white_background"))
        assertWhiteCenter()

        release.complete(Unit)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) { motion.phase == MediaPhase.Viewing }
        assertTrue(motion.imageReady("white_background"))
        assertWhiteCenter()
    }

    @Test
    fun closeDuringDelayedLoadingDoesNotWaitForTheImage() {
        render()
        rule.onNodeWithTag("prototype_media_close").performClick()
        rule.waitForIdle()
        assertEquals(MediaPhase.Closed, motion.phase)
        assertFalse(release.isCompleted)
        rule.onNodeWithTag("prototype_media_viewer").assertDoesNotExist()
    }

    @Test
    fun failedViewerLoadingHandsOffToTheFixtureFallback() {
        release.complete(Unit)
        render(fail = true)
        rule.waitUntil(5_000) { motion.phase == MediaPhase.Viewing }
        assertTrue(motion.imageReady("white_background"))
        assertWhiteCenter()
    }

    private fun assertWhiteCenter() {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("The image must remain visible during the handoff: $center",
            center.red > 0.98f && center.green > 0.98f && center.blue > 0.98f)
    }
}
