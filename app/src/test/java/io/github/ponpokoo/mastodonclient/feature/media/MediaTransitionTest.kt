package io.github.ponpokoo.mastodonclient.feature.media

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntSize
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import org.junit.Assert.*
import org.junit.Test

class MediaTransitionTest {
    @Test fun videosUseTheThumbnailForBothDirectionsAndFadeWithoutAPreview() {
        for (type in listOf("video", "gifv")) {
            val state = MediaTransitionState().apply { viewerSize = IntSize(1080, 2400) }
            val item = image("movie").copy(type = type)
            val group = MediaSourceGroup(state, false)
            group.displayed(item, painter)
            val thumbnail = MediaThumbnailFrame(Rect(20f, 100f, 500f, 400f), false)
            state.thumbnailFrames[group.key(item)] = thumbnail
            group.prepare(item)
            state.open(listOf(item), 0, "session", "route")
            assertEquals(MediaPhase.Opening, state.phase)
            assertSame(painter, state.openingPainter())
            state.phase = MediaPhase.Viewing
            state.close()
            assertSame(painter, state.transitionPainter)
            assertEquals(thumbnail, state.transitionThumbnail)
            state.clear()
            state.thumbnailPainters.clear()
            group.prepare(item)
            state.open(listOf(item), 0, "session", "route")
            state.phase = MediaPhase.Viewing

            state.close()
            assertNull(state.transitionPainter)
            assertNull(state.transitionThumbnail)
        }
    }
    @Test fun zoomedReturnStartsAtTheDisplayedImageAndKeepsTheViewportClip() {
        val viewport = Rect(0f, 0f, 1080f, 2400f)
        val start = mediaViewerGeometry(Size(600f, 1000f), viewport, 2f, Offset(120f, -200f))
        assertEquals(Rect(-420f, -800f, 1740f, 2800f), start.imageBounds)
        assertEquals(viewport, start.visibleClipBounds)
        val end = mediaImageGeometry(Size(600f, 1000f),
            MediaThumbnailFrame(Rect(20f, 100f, 500f, 400f), true), viewport, 0f)
        assertEquals(start, mediaReturnGeometry(start, end, 0f, 0f))
        assertEquals(end, mediaReturnGeometry(start, end, 1f, 0f))
        val halfway = mediaReturnGeometry(start, end, 0.5f, 0f)
        assertEquals(0.6f, halfway.imageBounds.width / halfway.imageBounds.height, 0.0001f)
    }

    @Test fun swipeReturnPreservesReleaseVelocityAndSettlesAtTheDestination() {
        val start = MediaImageGeometry(Rect(0f, 400f, 600f, 1400f), Rect(0f, 400f, 600f, 1400f))
        val end = MediaImageGeometry(Rect(100f, 100f, 400f, 600f), Rect(100f, 100f, 400f, 600f))
        for (velocity in listOf(-1000f, 1000f, 4000f)) {
            val step = 0.0001f
            val next = mediaReturnGeometry(start, end, step, velocity)
            assertEquals(velocity, (next.imageBounds.top - start.imageBounds.top) / (step * 0.18f), 5f)
            assertEquals(start, mediaReturnGeometry(start, end, 0f, velocity))
            assertEquals(end, mediaReturnGeometry(start, end, 1f, velocity))
            val nearEnd = mediaReturnGeometry(start, end, 1f - step, velocity)
            assertEquals(0f, (end.imageBounds.top - nearEnd.imageBounds.top) / (step * 0.18f), 5f)
        }
    }

    @Test fun openingDefersAnIncompatibleAspectRatioButAcceptsHigherResolutionOfTheSameShape() {
        val state = MediaTransitionState().apply { viewerSize = IntSize(1080, 2400) }
        val item = image("picture")
        val group = MediaSourceGroup(state, false)
        group.displayed(item, painter)
        state.thumbnailFrames[group.key(item)] = MediaThumbnailFrame(Rect(0f, 0f, 100f, 100f), true)
        group.prepare(item)
        state.open(listOf(item), 0, "session", "route")
        val landscape = object : Painter() {
            override val intrinsicSize = Size(1000f, 600f)
            override fun DrawScope.onDraw() = Unit
        }
        state.reportImageReady(item.id, landscape)
        assertSame(painter, state.openingPainter())
        val larger = object : Painter() {
            override val intrinsicSize = Size(1200f, 2000f)
            override fun DrawScope.onDraw() = Unit
        }
        state.reportImageReady(item.id, larger)
        assertSame(larger, state.openingPainter())
        state.phase = MediaPhase.Viewing
        state.close(scale = 2f, offset = Offset(120f, -200f))
        assertEquals(MediaPhase.Closing, state.phase)
        assertEquals(Rect(-420f, -800f, 1740f, 2800f), state.closingGeometry!!.imageBounds)
    }
    private val painter = object : Painter() {
        override val intrinsicSize = Size(600f, 1000f)
        override fun DrawScope.onDraw() = Unit
    }
    private fun image(id: String) = MediaAttachment(id, "image", "https://example.test/$id", null, id)

    @Test fun cropAndFitPreserveImageAspectRatioAndTheVisibleClippingEdge() {
        val source = Size(600f, 1000f)
        val viewport = Rect(0f, 0f, 1080f, 2400f)
        val thumbnail = MediaThumbnailFrame(Rect(100f, -100f, 400f, 500f), true, Rect(100f, 200f, 400f, 500f))
        val initial = mediaImageGeometry(source, thumbnail, viewport, 0f)
        assertEquals(thumbnail.bounds, initial.clipBounds)
        assertEquals(thumbnail.visibleBounds, initial.visibleClipBounds)
        val unclipped = mediaImageGeometry(source, thumbnail.copy(visibleBounds = thumbnail.bounds), viewport, 0f)
        assertEquals(unclipped.imageBounds, initial.imageBounds)
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val geometry = mediaImageGeometry(source, thumbnail, viewport, progress)
            assertEquals(0.6f, geometry.imageBounds.width / geometry.imageBounds.height, 0.0001f)
            assertTrue(geometry.imageBounds.contains(geometry.clipBounds.center))
        }
        assertEquals(Rect(0f, 300f, 1080f, 2100f), mediaImageGeometry(source, thumbnail, viewport, 1f).clipBounds)
        val bounce = mediaImageGeometry(source, thumbnail, viewport, -0.05f).visibleClipBounds
        assertTrue(bounce.top >= thumbnail.visibleBounds.top)
        assertTrue(bounce.bottom <= thumbnail.visibleBounds.bottom)
    }

    @Test fun fittedThumbnailDoesNotStretchOrIncludeItsLetterboxing() {
        val source = Size(600f, 1000f)
        val thumbnail = MediaThumbnailFrame(Rect(0f, 0f, 300f, 200f), false)
        val geometry = mediaImageGeometry(source, thumbnail, Rect(0f, 0f, 1080f, 2400f), 0f)
        assertEquals(Rect(90f, 0f, 210f, 200f), geometry.imageBounds)
        assertEquals(geometry.imageBounds, geometry.clipBounds)
        assertEquals(Rect.Zero, mediaImageGeometry(Size.Zero, thumbnail, Rect.Zero, 0f).clipBounds)
    }

    @Test fun returnUsesTheClickedGalleryAndFreezesTheViewedImageBeforeLayoutChanges() {
        val state = MediaTransitionState().apply { viewerSize = IntSize(1080, 2400); validateScope("session", "route") }
        val media = listOf(image("first"), image("second"))
        val clicked = MediaSourceGroup(state, false)
        val other = MediaSourceGroup(state, false)
        val destination = MediaThumbnailFrame(Rect(20f, 400f, 500f, 800f), true)
        state.thumbnailFrames[clicked.key(media[0])] = MediaThumbnailFrame(Rect(20f, 100f, 500f, 300f), true)
        state.thumbnailFrames[clicked.key(media[1])] = destination
        state.thumbnailFrames[other.key(media[1])] = MediaThumbnailFrame(Rect(600f, 800f, 800f, 1000f), true)
        clicked.displayed(media[0], painter)
        clicked.displayed(media[1], painter)
        clicked.prepare(media[0])
        state.open(media, 0, "session", "route")
        state.phase = MediaPhase.Viewing
        state.close(1)
        state.thumbnailFrames.remove(clicked.key(media[1]))
        state.viewerSize = IntSize(2400, 1080)
        state.close(0)
        assertEquals(1, state.page)
        assertEquals(clicked.key(media[1]), state.selectedKey)
        assertEquals(destination, state.transitionThumbnail)
        assertEquals(Rect(0f, 0f, 1080f, 2400f), state.transitionViewport)
    }

    @Test fun accountGenerationAndNavigationChangesCloseTheViewerAndDiscardSourcePainters() {
        val state = MediaTransitionState().apply { validateScope("account-A:1", "route") }
        val media = image("picture")
        val group = MediaSourceGroup(state, false)
        group.displayed(media, painter)
        state.thumbnailFrames[group.key(media)] = MediaThumbnailFrame(Rect(0f, 0f, 100f, 100f), true)
        group.prepare(media)
        state.open(listOf(media), 0, "account-A:1", "route")
        state.reportImageReady(media.id, painter)
        state.validateScope("account-A:3", "route") // A -> B -> A must not reuse the earlier A.
        assertFalse(state.active)
        assertTrue(state.media.isEmpty())
        assertTrue(state.thumbnailFrames.isEmpty())
        assertTrue(state.thumbnailPainters.isEmpty())
        assertFalse(state.imageReady(media.id))
        state.open(listOf(media), 0, "account-A:3", "route")
        state.validateScope("account-A:3", "different-route")
        assertFalse(state.active)
    }

    @Test fun missingReturnSourceFadesAndNewOpeningDoesNotInheritSwipeOrReadiness() {
        val state = MediaTransitionState().apply { viewerSize = IntSize(1080, 2400) }
        val media = image("picture")
        state.open(listOf(media), 0, "session", "route")
        state.reportImageReady(media.id, painter)
        state.dragOffset = 480f
        state.close(fromDrag = true)
        assertNull(state.transitionThumbnail)
        assertEquals(Rect(0f, 480f, 1080f, 2880f), state.transitionViewport)
        assertEquals(0f, state.dragOffset, 0.001f)
        state.clear()
        state.open(listOf(media), 0, "session", "route")
        assertFalse(state.imageReady(media.id))
        assertEquals(0f, state.dragOffset, 0.001f)
        assertEquals(MediaPhase.Viewing, state.phase)
    }
}
