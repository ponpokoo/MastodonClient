package io.github.ponpokoo.transitionprototype

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeImageTransitionTest {
    @Test
    fun downwardDismissalStartsAtTheReleasedImageWithoutResettingPositionOrOpacity() {
        val motion = PrototypeMediaMotionState("gallery", 1).apply {
            viewerSize = IntSize(1080, 2400)
            dragOffset = 480f
            thumbnailFrames["$sourceOwner:gallery/tower"] = PrototypeThumbnailFrame(Rect(100f, 300f, 400f, 800f), true)
        }
        val before = prototypeImageGeometry(Size(600f, 1000f), null, motion.fullBounds.translate(0f, motion.dragOffset), 1f)
        motion.close(fromDrag = true)
        val after = prototypeImageGeometry(Size(600f, 1000f), motion.transitionThumbnail, motion.transitionViewport, 1f)
        assertEquals(MediaPhase.Closing, motion.phase)
        assertEquals(before.imageBounds, after.imageBounds)
        assertEquals(before.clipBounds, after.clipBounds)
        assertEquals(0.7f, motion.backdropAlpha, 0.001f)
        assertEquals("Dimming must not introduce a rounded-image jump", 0f, motion.cornerRadius, 0.001f)
        assertEquals(0f, motion.dragOffset, 0.001f)
        assertFalse(motion.roundedReturn)
    }

    @Test
    fun normalCloseStillResetsZoomAndANewViewerDoesNotInheritTheSwipeFade() {
        val motion = PrototypeMediaMotionState("gallery", 1).apply { viewerSize = IntSize(1080, 2400); dragOffset = 480f }
        motion.close()
        assertEquals(MediaPhase.ResettingZoom, motion.phase)
        assertEquals(motion.fullBounds, motion.transitionViewport)
        assertEquals(1f, motion.backdropAlpha, 0.001f)
        motion.phase = MediaPhase.Viewing
        motion.close(fromDrag = true)
        motion.phase = MediaPhase.Closed
        motion.open(PrototypeToots[1].mediaAttachments, 1)
        assertEquals(1f, motion.backdropAlpha, 0.001f)
        assertEquals(0f, motion.dragOffset, 0.001f)
    }

    @Test
    fun partiallyVisibleThumbnailKeepsItsScaleAndClipsTheHiddenEdgeEvenDuringTheBounce() {
        val viewport = Rect(0f, 0f, 1080f, 2400f)
        val source = Size(600f, 1000f)
        val edges = listOf(
            PrototypeThumbnailFrame(Rect(100f, -100f, 400f, 500f), true, Rect(100f, 200f, 400f, 500f)),
            PrototypeThumbnailFrame(Rect(100f, 1900f, 400f, 2500f), true, Rect(100f, 1900f, 400f, 2200f)),
        )
        for (thumbnail in edges) {
            val start = prototypeImageGeometry(source, thumbnail, viewport, 0f)
            val unclipped = prototypeImageGeometry(source, thumbnail.copy(visibleBounds = thumbnail.bounds), viewport, 0f)
            assertEquals("Clipping must not change the image scale or position", unclipped.imageBounds, start.imageBounds)
            assertEquals(thumbnail.bounds, start.clipBounds)
            assertEquals(thumbnail.visibleBounds, start.visibleClipBounds)
            val bounce = prototypeImageGeometry(source, thumbnail, viewport, -0.05f)
            assertTrue(bounce.visibleClipBounds.top >= thumbnail.visibleBounds.top)
            assertTrue(bounce.visibleClipBounds.bottom <= thumbnail.visibleBounds.bottom)
            val end = prototypeImageGeometry(source, thumbnail, viewport, 1f)
            assertEquals(end.imageBounds, end.visibleClipBounds)
        }
    }

    @Test
    fun entirelyHiddenReturnThumbnailFallsBackInsteadOfFlyingIntoAnotherScreenRegion() {
        val motion = PrototypeMediaMotionState("gallery", 1).apply { viewerSize = IntSize(1080, 2400) }
        motion.thumbnailFrames["${motion.sourceOwner}:gallery/tower"] =
            PrototypeThumbnailFrame(Rect(100f, -1000f, 400f, -400f), true, Rect.Zero)
        motion.close()
        assertEquals(null, motion.transitionThumbnail)
    }

    @Test
    fun croppedPortraitExpandsWithoutStretchingAndEndsAtTheFittedImage() {
        val source = Size(600f, 1000f)
        val thumbnail = PrototypeThumbnailFrame(Rect(120f, 180f, 420f, 380f), cropped = true)
        val viewport = Rect(0f, 0f, 1080f, 2400f)
        val start = prototypeImageGeometry(source, thumbnail, viewport, 0f)
        assertEquals(thumbnail.bounds, start.clipBounds)
        assertTrue(start.imageBounds.top < start.clipBounds.top)
        assertTrue(start.imageBounds.bottom > start.clipBounds.bottom)

        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val geometry = prototypeImageGeometry(source, thumbnail, viewport, progress)
            assertEquals(0.6f, geometry.imageBounds.width / geometry.imageBounds.height, 0.0001f)
            assertTrue(geometry.imageBounds.contains(geometry.clipBounds.center))
        }
        val end = prototypeImageGeometry(source, thumbnail, viewport, 1f)
        assertEquals(Rect(0f, 300f, 1080f, 2100f), end.imageBounds)
        assertEquals(end.imageBounds, end.clipBounds)
    }

    @Test
    fun fittedThumbnailDoesNotAnimateItsLetterboxingAsPartOfTheImage() {
        val thumbnail = PrototypeThumbnailFrame(Rect(0f, 0f, 300f, 200f), cropped = false)
        val geometry = prototypeImageGeometry(Size(600f, 1000f), thumbnail, Rect(0f, 0f, 1080f, 2400f), 0f)
        assertEquals(Rect(90f, 0f, 210f, 200f), geometry.imageBounds)
        assertEquals(geometry.imageBounds, geometry.clipBounds)
    }

    @Test
    fun closeFreezesTheViewedImageAndItsReturnLocationBeforeLayoutChanges() {
        val motion = PrototypeMediaMotionState("gallery", 1).apply { viewerSize = IntSize(1080, 2400) }
        val destination = PrototypeThumbnailFrame(Rect(20f, 400f, 500f, 800f), true)
        motion.thumbnailFrames["${motion.sourceOwner}:gallery/island"] = destination
        motion.close(2)

        motion.thumbnailFrames["${motion.sourceOwner}:gallery/island"] = PrototypeThumbnailFrame(Rect.Zero, true)
        motion.viewerSize = IntSize(2400, 1080)
        motion.close(0)

        assertEquals("gallery/island", motion.selectedKey)
        assertEquals(2, motion.page)
        assertEquals(destination, motion.transitionThumbnail)
        assertEquals(Rect(0f, 0f, 1080f, 2400f), motion.transitionViewport)
        assertEquals(MediaPhase.ResettingZoom, motion.phase)
    }

    @Test
    fun missingReturnThumbnailUsesTheFittedImageAndReopeningDoesNotReuseReadiness() {
        val motion = PrototypeMediaMotionState("gallery", 1).apply { viewerSize = IntSize(1080, 2400) }
        motion.reportImageReady("tower")
        motion.close()
        assertEquals(null, motion.transitionThumbnail)
        val geometry = prototypeImageGeometry(Size(600f, 1000f), null, motion.transitionViewport, 0.5f)
        assertEquals(geometry.imageBounds, geometry.clipBounds)
        motion.phase = MediaPhase.Closed
        motion.open(PrototypeToots[1].mediaAttachments, 1)
        assertFalse(motion.imageReady("tower"))
    }
}
