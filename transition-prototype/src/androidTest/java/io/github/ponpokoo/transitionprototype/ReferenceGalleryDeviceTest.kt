package io.github.ponpokoo.transitionprototype

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReferenceGalleryDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ReferenceGalleryActivity>()

    @Test
    fun opensSelectedImageWithSharedTransitionAndClosesWithBounce() {
        rule.waitForIdle()
        capture("reference-01-gallery")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("reference_thumbnail_2").performClick()
        rule.mainClock.advanceTimeBy(80)
        assertSharedTransition()
        root().assert(SemanticsMatcher.expectValue(ReferencePage, 2))
        capture("reference-02-opening")
        rule.mainClock.advanceTimeBy(800)
        capture("reference-03-fullscreen")
        rule.onNodeWithTag("reference_close").performClick()
        var bounced = false
        repeat(64) {
            rule.mainClock.advanceTimeBy(16)
            bounced = bounced || root().fetchSemanticsNode().config[ImageFitFraction] < 0f
        }
        assertTrue("Closing should use the weak spring bounce", bounced)
        root().assert(SemanticsMatcher.expectValue(ReferenceViewerOpen, false))
            .assert(SemanticsMatcher.expectValue(ImageTransitionActive, false))
        rule.onNodeWithTag("reference_thumbnail_0").performClick()
        rule.mainClock.advanceTimeBy(80)
        assertSharedTransition()
        root().assert(SemanticsMatcher.expectValue(ReferencePage, 0))
    }

    @Test
    fun pagingAndZoomingResetBeforeReturningToCurrentImagesThumbnail() {
        rule.onNodeWithTag("reference_thumbnail_0").performClick()
        rule.onNodeWithTag("reference_pager").performTouchInput { swipeLeft() }
        root().assert(SemanticsMatcher.expectValue(ReferencePage, 1))
        rule.waitUntil(10_000) { root().fetchSemanticsNode().config[ReferenceImageReady] }
        rule.onNodeWithTag("reference_image_1").performTouchInput { doubleClick(center) }
        root().assert(SemanticsMatcher("Telephoto has zoomed the image") { it.config[ReferenceZoom] > 0f })
        capture("reference-04-zoomed")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("reference_close").performClick()
        rule.mainClock.advanceTimeBy(48)
        root().assert(SemanticsMatcher("zoom resets before the shared transition") {
            it.config[RoundingBeforeClose] && !it.config[ImageTransitionActive]
        })
        // The image library also waits for layout synchronization. Observe the order of
        // phases rather than assuming that this wait has a fixed number of frames.
        var sawRoundedPhase = false
        var startedShrinking = false
        for (frame in 0 until 60) {
            rule.mainClock.advanceTimeBy(16)
            val state = root().fetchSemanticsNode().config
            if (state[ImageTransitionActive]) {
                startedShrinking = true
                break
            }
            if (state[RoundingBeforeClose] && state[ImageCornerRadius] > 0f) {
                assertTrue("Zoom resets before corners round", state[ReferenceZoom] == 0f)
                if (!sawRoundedPhase) capture("reference-05-rounding-after-zoom")
                sawRoundedPhase = true
            }
        }
        assertTrue("Corners round before the shared image starts shrinking", sawRoundedPhase)
        assertTrue("The close transition starts after rounding", startedShrinking)
        assertSharedTransition()
        root().assert(SemanticsMatcher.expectValue(ReferencePage, 1))
        capture("reference-05-closing-current-page")
        rule.mainClock.advanceTimeBy(1000)
        root().assert(SemanticsMatcher.expectValue(ReferenceViewerOpen, false))
        rule.onNodeWithTag("reference_thumbnail_1").performClick()
        rule.mainClock.advanceTimeBy(800)
        root().assert(SemanticsMatcher.expectValue(ReferencePage, 1))
            .assert(SemanticsMatcher.expectValue(ReferenceZoom, 0f))
    }

    @Test
    fun backDuringOpeningCanCloseAndReopenAnotherImage() {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("reference_thumbnail_1").performClick()
        rule.mainClock.advanceTimeBy(80)
        assertSharedTransition()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(1200)
        root().assert(SemanticsMatcher.expectValue(ReferenceViewerOpen, false))
        rule.onNodeWithTag("reference_thumbnail_2").performClick()
        rule.mainClock.advanceTimeBy(80)
        assertSharedTransition()
        root().assert(SemanticsMatcher.expectValue(ReferencePage, 2))
    }

    private fun root() = rule.onNodeWithTag("reference_gallery")

    private fun assertSharedTransition() {
        root().assert(SemanticsMatcher.expectValue(ImageMatched, true))
            .assert(SemanticsMatcher.expectValue(ImageTransitionActive, true))
    }

    private fun capture(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "transition-captures")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { output ->
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
