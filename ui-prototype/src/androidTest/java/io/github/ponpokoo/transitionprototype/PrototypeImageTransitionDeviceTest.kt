package io.github.ponpokoo.transitionprototype

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performSemanticsAction
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
import kotlin.math.roundToInt
import kotlin.math.abs

class PrototypeImageTransitionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun returningImageStaysBehindTheComposeButtonWithoutShowingItInTheFullScreenViewer() {
        val timeline = rule.onNodeWithTag("timeline_list")
        timeline.performScrollToIndex(1)
        val thumbnail = rule.onNodeWithTag("toot_gallery_media_3")
        val fab = rule.onNodeWithTag("prototype_compose").fetchSemanticsNode().boundsInRoot
        val beforeSource = thumbnail.fetchSemanticsNode().boundsInRoot
        timeline.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, beforeSource.center.y - fab.center.y) }
        rule.waitForIdle()
        val source = thumbnail.fetchSemanticsNode().boundsInRoot
        val sample = fab.center + Offset(fab.width * 0.25f, 0f)
        assertTrue("The return image must overlap the compose button for this test", source.contains(sample))
        val x = sample.x.roundToInt()
        val y = sample.y.roundToInt()
        val normalButton = rule.onRoot().captureToImage().toPixelMap()[x, y]

        thumbnail.performClick()
        rule.waitForIdle()
        val fullScreenPixel = rule.onRoot().captureToImage().toPixelMap()[x, y]
        assertTrue("Do not show the timeline button over the full-screen white picture", fullScreenPixel.red > 0.98f)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("prototype_media_close").performClick()
        advance(160)
        var checkedOverlap = false
        repeat(60) {
            advance(16)
            val progress = motion()[PrototypeImageFit]
            if (!checkedOverlap && phase() == "Closing" && progress in 0.05f..0.25f &&
                motion()[PrototypeImageVisibleBounds].contains(sample)) {
                val returning = rule.onRoot().captureToImage().toPixelMap()[x, y]
                val distanceToButton = abs(returning.red - normalButton.red) + abs(returning.green - normalButton.green) +
                    abs(returning.blue - normalButton.blue)
                val distanceToWhite = abs(returning.red - 1f) + abs(returning.green - 1f) + abs(returning.blue - 1f)
                assertTrue("The button must appear in front of the returning white picture: $returning",
                    distanceToButton < distanceToWhite)
                capture("return-behind-compose-button")
                checkedOverlap = true
            }
        }
        assertTrue("Inspect the actual overlapping part of the return animation", checkedOverlap)
        advance(1_000)
        assertEquals("Closed", phase())
        val finalButton = rule.onRoot().captureToImage().toPixelMap()[x, y]
        assertEquals(normalButton.red, finalButton.red, 0.01f)
        assertEquals(normalButton.green, finalButton.green, 0.01f)
        assertEquals(normalButton.blue, finalButton.blue, 0.01f)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun downwardSwipeDuringHandoffContinuesWhenViewingStarts() {
        rule.onNodeWithTag("toot_single_media_0").assertExists()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("toot_single_media_0").performClick()
        repeat(60) { if (phase() == "Opening") advance(16) }
        assertEquals("HandingOff", phase())
        val image = rule.onNodeWithTag("prototype_media_viewer")
        val bounds = image.fetchSemanticsNode().boundsInRoot
        val origin = Offset(bounds.width / 2f, bounds.height / 2f)
        val initialImageTop = motion()[PrototypeImageBounds].top
        image.performTouchInput {
            down(origin)
            moveTo(origin + Offset(0f, bounds.height * 0.06f), 16)
        }
        advance(16)
        val handoffOffset = viewer()[PrototypeViewerDismissOffset]
        assertTrue("The first swipe must move the image during handoff", handoffOffset > 0f)
        assertTrue("The transition cover must follow the swipe too", motion()[PrototypeImageBounds].top > initialImageTop)
        advance(160)
        assertEquals("Viewing", phase())
        assertEquals("Do not reset an in-progress swipe at handoff", handoffOffset,
            viewer()[PrototypeViewerDismissOffset], 0.01f)
        image.performTouchInput { moveTo(origin + Offset(0f, bounds.height * 0.25f), 32) }
        advance(16)
        assertTrue(viewer()[PrototypeViewerDismissOffset] > handoffOffset)
        image.performTouchInput { up() }
        advance(16)
        assertEquals("Closing", phase())
        advance(500)
        assertEquals("Closed", phase())
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun fingerDownDuringOpeningCanDismissOnceTheImageFillsTheScreen() {
        rule.onNodeWithTag("toot_single_media_0").assertExists()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("toot_single_media_0").performClick()
        advance(96)
        assertEquals("Opening", phase())
        val image = rule.onNodeWithTag("prototype_media_viewer")
        val bounds = image.fetchSemanticsNode().boundsInRoot
        val origin = Offset(bounds.width / 2f, bounds.height / 2f)
        image.performTouchInput { down(origin) }
        repeat(60) { if (phase() == "Opening") advance(16) }
        assertEquals("HandingOff", phase())
        image.performTouchInput { moveTo(origin + Offset(0f, bounds.height * 0.25f), 32) }
        advance(16)
        assertTrue("Keep tracking the finger placed before full-screen display",
            viewer()[PrototypeViewerDismissOffset] > 0f)
        image.performTouchInput { up() }
        advance(500)
        assertEquals("Closed", phase())
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun downwardDismissalTracksFramesAndClosesFromTheReleasedPosition() {
        rule.onNodeWithTag("timeline_list").performScrollToIndex(1)
        rule.onNodeWithTag("toot_gallery_media_3").performClick()
        rule.waitForIdle()
        val image = rule.onNodeWithTag("prototype_media_viewer")
        rule.mainClock.autoAdvance = false
        val bounds = image.fetchSemanticsNode().boundsInRoot
        val origin = Offset(bounds.width / 2f, bounds.height / 2f)
        image.performTouchInput { down(origin) }
        var previousOffset = 0f
        // Deliver separate moves and render between them, as a real finger drag does.
        repeat(10) { step ->
            image.performTouchInput { moveTo(origin + Offset(0f, bounds.height * 0.025f * (step + 1)), 32) }
            advance(32)
            val offset = viewer()[PrototypeViewerDismissOffset]
            assertTrue("The image must follow each downward movement: $offset after $previousOffset", offset > previousOffset)
            previousOffset = offset
            assertEquals("Viewing", phase())
        }
        val releasedBounds = motion()[PrototypeImageBounds]
        capture("downward-drag")
        image.performTouchInput { up() }
        advance(16)
        assertEquals("Closing", phase())
        val firstClosingBounds = motion()[PrototypeImageBounds]
        assertEquals("The picture must not snap back to the center", releasedBounds.top, firstClosingBounds.top, 1f)
        assertEquals(releasedBounds.left, firstClosingBounds.left, 1f)
        capture("downward-close-start")
        advance(1_500)
        assertEquals("Closed", phase())
        rule.onNodeWithTag("toot_gallery_media_3").assertExists()
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun shortDownwardDragReturnsToFitAndTheNextDragCanDismiss() {
        rule.onNodeWithTag("toot_single_media_0").performClick()
        rule.waitForIdle()
        val image = rule.onNodeWithTag("prototype_media_viewer")
        rule.mainClock.autoAdvance = false
        image.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, height * 0.07f), 300)
        }
        advance(16)
        var previousOffset = viewer()[PrototypeViewerDismissOffset]
        assertTrue("Start with a visible displacement", previousOffset > 0f)
        image.performTouchInput {
            up()
        }
        repeat(32) {
            advance(16)
            val offset = viewer()[PrototypeViewerDismissOffset]
            assertTrue("The image must return without oscillating: $offset after $previousOffset",
                offset >= 0f && offset <= previousOffset + 0.001f)
            previousOffset = offset
        }
        assertEquals("Viewing", phase())
        assertEquals(0f, viewer()[PrototypeViewerDismissOffset], 0.001f)
        rule.mainClock.autoAdvance = true
        image.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, height * 0.25f), 300)
            up()
        }
        rule.waitForIdle()
        assertEquals("Closed", phase())
    }

    @Test
    fun topClippedThumbnailReturnsWithoutPaintingOverTheToolbar() {
        clippedThumbnailReturns(top = true)
    }

    @Test
    fun bottomClippedThumbnailReturnsWithoutPaintingOverTheNavigation() {
        clippedThumbnailReturns(top = false)
    }

    private fun clippedThumbnailReturns(top: Boolean) {
        val timeline = rule.onNodeWithTag("timeline_list")
        timeline.performScrollToIndex(if (top) 0 else 1)
        val tag = if (top) "toot_single_media_0" else "toot_gallery_media_3"
        val thumbnail = rule.onNodeWithTag(tag)
        val fullSource = thumbnail.fetchSemanticsNode().boundsInRoot
        val viewport = timeline.fetchSemanticsNode().boundsInRoot
        val delta = if (top) fullSource.top - viewport.top + fullSource.height * 0.4f
            else fullSource.bottom - viewport.bottom - fullSource.height * 0.4f
        timeline.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        rule.waitForIdle()
        val visibleSource = thumbnail.fetchSemanticsNode().boundsInRoot
        assertTrue("The test must start with a partially clipped image: $visibleSource from $fullSource, viewport=$viewport",
            visibleSource.height < fullSource.height - 10f)
        assertEquals(if (top) viewport.top else viewport.bottom, if (top) visibleSource.top else visibleSource.bottom, 1f)
        val before = rule.onRoot().captureToImage().toPixelMap()
        val sampleX = visibleSource.center.x.roundToInt().coerceIn(0, before.width - 1)
        val sampleY = (if (top) viewport.top - 4f else viewport.bottom + 4f).roundToInt().coerceIn(0, before.height - 1)
        val outsideColor = before[sampleX, sampleY]

        val composeBounds = rule.onNodeWithTag("prototype_compose").fetchSemanticsNode().boundsInRoot
        val tapPosition = Offset(visibleSource.left + visibleSource.width * 0.2f, visibleSource.center.y)
        assertFalse("The thumbnail tap must avoid the compose button: source=$visibleSource compose=$composeBounds",
            composeBounds.contains(tapPosition))
        thumbnail.performTouchInput { click(Offset(width * 0.2f, height / 2f)) }
        rule.waitForIdle()
        assertEquals("Viewing", phase())
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("prototype_media_close").performClick()
        advance(160)
        var checkedReturn = false
        repeat(60) {
            advance(16)
            if (!checkedReturn && phase() == "Closing" && motion()[PrototypeImageFit] in 0f..0.001f) {
                val visible = motion()[PrototypeImageVisibleBounds]
                assertTrue("The return image must stay inside the list clipping edge: $visible",
                    if (top) visible.top >= viewport.top - 1f else visible.bottom <= viewport.bottom + 1f)
                val during = rule.onRoot().captureToImage().toPixelMap()[sampleX, sampleY]
                assertEquals(outsideColor.red, during.red, 0.01f)
                assertEquals(outsideColor.green, during.green, 0.01f)
                assertEquals(outsideColor.blue, during.blue, 0.01f)
                capture(if (top) "closing-top-clipped" else "closing-bottom-clipped")
                checkedReturn = true
            }
        }
        assertTrue("Check the actual image just before it reaches the thumbnail", checkedReturn)
        advance(1_000)
        assertEquals("Closed", phase())
        val returned = motion()[PrototypeImageVisibleBounds]
        assertEquals(visibleSource.top, returned.top, 1f)
        assertEquals(visibleSource.bottom, returned.bottom, 1f)
        assertEquals(visibleSource.left, returned.left, 1f)
        assertEquals(visibleSource.right, returned.right, 1f)
        assertEquals(visibleSource, thumbnail.fetchSemanticsNode().boundsInRoot)
        rule.mainClock.autoAdvance = true
    }

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
        assertTrue("The picture should expand from the scrolled thumbnail: $opening from $source", opening.width > source.width)
        capture("opening-portrait")
        rule.onNodeWithTag("main_tab_explore").performTouchInput { click(center) }
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
        advance(600)
        assertEquals("Viewing", phase())
        assertTrue("Do not hand the picture off before the viewer has drawn it", motion()[PrototypeViewerImageReady])

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
        var previousProgress = motion()[PrototypeImageFit]
        repeat(32) {
            advance(16)
            val progress = motion()[PrototypeImageFit]
            assertTrue("The picture must return without overshoot or oscillation: $progress after $previousProgress",
                progress >= 0f && progress <= previousProgress + 0.0001f)
            previousProgress = progress
        }
        assertEquals("Closed", phase())
        val returned = motion()[PrototypeImageBounds]
        assertEquals(destination.left, returned.left, 1f)
        assertEquals(destination.top, returned.top, 1f)
        assertEquals(destination.width, returned.width, 1f)
        assertEquals(destination.height, returned.height, 1f)
        rule.onNodeWithTag("prototype_media_viewer").assertDoesNotExist()
        rule.onNodeWithTag("toot_gallery_media_2").assertExists()
        rule.onNodeWithTag("toot_gallery_media_2").performTouchInput { click(center) }
        advance(700)
        assertEquals("Viewing", phase())
        assertEquals("gallery/island", motion()[PrototypeTransitionImage])
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
