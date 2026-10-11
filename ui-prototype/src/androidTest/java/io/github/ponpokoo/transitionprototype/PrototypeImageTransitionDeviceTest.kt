package io.github.ponpokoo.transitionprototype

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.espresso.Espresso
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrototypeImageTransitionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun imageTransitionReturnsToTheLastViewedGalleryThumbnail() {
        rule.onNodeWithTag("timeline_list").performScrollToIndex(1)
        val source = rule.onNodeWithTag("toot_gallery_media_1").fetchSemanticsNode().boundsInRoot
        val destination = rule.onNodeWithTag("toot_gallery_media_2").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("toot_gallery_media_1").performClick()
        advance(80)
        assertEquals("Opening", phase())
        assertTrue(motion()[PrototypeImageTransitionActive])
        assertTrue(motion()[PrototypeImageFit] > 0f && motion()[PrototypeImageFit] < 1f)
        val opening = motion()[PrototypeImageBounds]
        assertTrue("The picture should start at the scrolled thumbnail and expand toward the viewport: $opening from $source",
            opening.left > 0f && opening.left < source.left && opening.top > 0f && opening.top < source.top && opening.width > source.width)
        capture("opening-portrait")
        rule.onNodeWithTag("main_tab_explore").performTouchInput { click(center) }
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
        advance(600)
        assertEquals("Viewing", phase())

        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("prototype_media_viewer").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(2, viewer()[PrototypeViewerPage])
        assertFalse(motion()[PrototypeImageTransitionActive])

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("prototype_media_close").performClick()
        advance(64)
        assertEquals("Rounding", phase())
        assertEquals("gallery/island", motion()[PrototypeTransitionImage])
        assertEquals(1f, motion()[PrototypeImageFit], 0.001f)
        assertTrue(motion()[PrototypeImageCorners] > 0f)
        capture("rounding-square")
        advance(96)
        assertEquals("Closing", phase())
        advance(32)
        assertTrue("Closing: fit=${motion()[PrototypeImageFit]}, corners=${motion()[PrototypeImageCorners]}",
            motion()[PrototypeImageTransitionActive])
        advance(48)
        assertTrue(motion()[PrototypeImageBounds].top > 0f)
        capture("closing-square")
        var bounced = false
        repeat(32) {
            advance(16)
            if (motion()[PrototypeImageFit] < 0f) bounced = true
        }
        assertTrue("The restored spring should briefly overshoot the thumbnail", bounced)
        advance(1_000)
        assertEquals("Closed", phase())
        val returned = motion()[PrototypeImageBounds]
        assertEquals(destination.left, returned.left, 1f)
        assertEquals(destination.top, returned.top, 1f)
        assertEquals(destination.width, returned.width, 1f)
        assertEquals(destination.height, returned.height, 1f)
        rule.onNodeWithTag("prototype_media_viewer").assertDoesNotExist()
        rule.onNodeWithTag("toot_gallery_media_2").assertExists()
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun zoomResetsBeforeCornersAndTheThumbnailTransition() {
        rule.onNodeWithTag("toot_single_media_0").performClick()
        rule.onNodeWithTag("prototype_media_viewer").performTouchInput { doubleClick(center) }
        assertEquals(2.5f, viewer()[PrototypeViewerScale], 0.01f)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("prototype_media_close").performClick()
        advance(64)
        assertEquals("ResettingZoom", phase())
        assertTrue(viewer()[PrototypeViewerScale] in 1f..2.5f)
        assertEquals(0f, motion()[PrototypeImageCorners], 0.001f)
        assertFalse(motion()[PrototypeImageTransitionActive])
        advance(128)
        assertEquals("Rounding", phase())
        assertEquals(1f, viewer()[PrototypeViewerScale], 0.001f)
        assertEquals(Offset.Zero, viewer()[PrototypeViewerOffset])
        assertEquals(1f, motion()[PrototypeImageFit], 0.001f)
        advance(160)
        assertEquals("Closing", phase())
        advance(1_200)
        assertEquals("Closed", phase())
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun backDuringOpeningReturnsAndAllowsAnotherImageToOpen() {
        rule.onNodeWithTag("timeline_list").performScrollToIndex(1)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("toot_gallery_media_0").performClick()
        advance(80)
        Espresso.pressBackUnconditionally()
        advance(1_500)
        assertEquals("Closed", phase())
        rule.onNodeWithTag("prototype_media_viewer").assertDoesNotExist()
        rule.onNodeWithTag("toot_gallery_media_1").performClick()
        advance(700)
        assertEquals("Viewing", phase())
        assertEquals(1, viewer()[PrototypeViewerPage])
        assertEquals("gallery/tower", motion()[PrototypeTransitionImage])
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun secondFingerTakesOverDownwardDragAndReturningToOneFingerDoesNotDismiss() {
        rule.onNodeWithTag("toot_single_media_0").performClick()
        val image = rule.onNodeWithTag("prototype_media_viewer")
        image.performTouchInput {
            down(0, center)
            moveTo(0, center + Offset(0f, height * 0.2f), 200)
        }
        assertTrue(viewer()[PrototypeViewerDismissOffset] > 0f)
        image.performTouchInput {
            down(1, center + Offset(width * 0.1f, height * 0.2f))
            updatePointerTo(0, center + Offset(-width * 0.2f, height * 0.2f))
            updatePointerTo(1, center + Offset(width * 0.3f, height * 0.2f))
            move(200)
            up(1)
            moveTo(0, center + Offset(-width * 0.2f, height * 0.3f), 100)
            up(0)
        }
        assertEquals("Viewing", phase())
        assertTrue(viewer()[PrototypeViewerScale] > 1f)
        assertEquals(0f, viewer()[PrototypeViewerDismissOffset], 0.01f)
        image.performTouchInput { doubleClick(center) }

        // Multiple fingers can also move without changing the scale. The remaining
        // finger must not turn that same gesture into a downward dismissal.
        image.performTouchInput {
            down(0, center - Offset(width * 0.2f, 0f))
            down(1, center + Offset(width * 0.2f, 0f))
            updatePointerTo(0, center + Offset(-width * 0.2f, height * 0.15f))
            updatePointerTo(1, center + Offset(width * 0.2f, height * 0.15f))
            move(150)
            up(1)
            moveTo(0, center + Offset(-width * 0.2f, height * 0.35f), 200)
            up(0)
        }
        assertEquals("Viewing", phase())
        assertEquals(1f, viewer()[PrototypeViewerScale], 0.01f)
        assertEquals(0f, viewer()[PrototypeViewerDismissOffset], 0.01f)
    }

    @Test
    fun hidingSystemBarsAndClosingPreservesTheScrolledTimeline() {
        rule.onNodeWithTag("timeline_list").performScrollToIndex(1)
        val position = timelinePosition()
        rule.onNodeWithTag("toot_gallery_media_1").performClick()
        rule.onNodeWithTag("prototype_media_viewer").performTouchInput { click(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("prototype_media_close").fetchSemanticsNodes().isEmpty() }
        assertEquals(position, timelinePosition(), 1f)
        Espresso.pressBackUnconditionally()
        rule.waitForIdle()
        assertEquals("Closed", phase())
        assertEquals(position, timelinePosition(), 1f)
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
    }

    private fun timelinePosition() = rule.onNodeWithTag("timeline_list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun motion() = rule.onNodeWithTag("prototype_media_motion").fetchSemanticsNode().config
    private fun viewer() = rule.onNodeWithTag("prototype_media_viewer").fetchSemanticsNode().config
    private fun phase() = motion()[PrototypeMotionPhase]
    private fun advance(milliseconds: Long) {
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "transition-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
