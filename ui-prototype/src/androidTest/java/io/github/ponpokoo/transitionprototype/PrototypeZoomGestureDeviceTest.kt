package io.github.ponpokoo.transitionprototype

import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrototypeZoomGestureDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun smallFingerMovementDuringDoubleTapShrinksAndAllowsReEnlargingAfterPanning() {
        rule.onNodeWithTag("toot_single_media_0").performClick()
        val viewer = rule.onNodeWithTag("prototype_media_viewer")
        viewer.performTouchInput { doubleClick(center) }
        viewer.performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.2f, 0f), 200)
            up()
        }
        assertTrue(offset().getDistance() > 0f)
        viewer.performTouchInput {
            repeat(2) {
                down(center)
                moveTo(center + Offset(1f, 0f), 16)
                up()
                advanceEventTime(100)
            }
        }
        assertEquals("A slightly moving double tap must still shrink", 1f, scale(), 0.01f)
        assertEquals(Offset.Zero, offset())
        rule.onNodeWithTag("prototype_media_close").assertExists()
        viewer.performTouchInput { doubleClick(center) }
        assertEquals("The next double tap must enlarge again", 2.5f, scale(), 0.01f)
    }

    @Test
    fun dragWaitsForScreenTouchSlopThenKeepsMovingWhenTheFingerReturnsNearItsStart() {
        rule.onNodeWithTag("toot_single_media_0").performClick()
        val viewer = rule.onNodeWithTag("prototype_media_viewer")
        viewer.performTouchInput { doubleClick(center) }
        val slop = ViewConfiguration.get(rule.activity).scaledTouchSlop.toFloat()
        viewer.performTouchInput {
            down(center)
            moveTo(center + Offset(slop * 0.5f, 0f), 32)
        }
        assertEquals("Small movements must not move the zoomed image", Offset.Zero, offset())
        viewer.performTouchInput { moveTo(center + Offset(slop * 1.5f, 0f), 32) }
        val afterCrossing = offset().x
        assertTrue("Drag threshold must use screen pixels even while zoomed", afterCrossing > 0f)
        viewer.performTouchInput { moveTo(center + Offset(slop * 0.25f, 0f), 32) }
        assertTrue("An established drag must continue on reversal", offset().x < afterCrossing)
        viewer.performTouchInput { up() }
        assertEquals(2.5f, scale(), 0.01f)
        rule.onNodeWithTag("prototype_media_close").assertExists()
    }

    @Test
    fun pinchContinuesAsAOneFingerPanWithoutStartingAnotherGesture() {
        rule.onNodeWithTag("toot_single_media_0").performClick()
        val viewer = rule.onNodeWithTag("prototype_media_viewer")
        viewer.performTouchInput {
            down(0, center - Offset(width * 0.1f, 0f))
            down(1, center + Offset(width * 0.1f, 0f))
            updatePointerTo(0, center - Offset(width * 0.25f, 0f))
            updatePointerTo(1, center + Offset(width * 0.25f, 0f))
            move(100)
            up(1)
        }
        val pinchedScale = scale()
        assertTrue(pinchedScale > 1f)
        val beforePan = offset().x
        viewer.performTouchInput { moveBy(0, Offset(1f, 0f), 16) }
        assertTrue("Remaining finger must continue the established pinch gesture", offset().x > beforePan)
        viewer.performTouchInput { up(0) }
        assertEquals(pinchedScale, scale(), 0.01f)
        rule.onNodeWithTag("prototype_media_close").assertExists()
    }

    private fun scale() = viewerConfig()[PrototypeViewerScale]
    private fun offset() = viewerConfig()[PrototypeViewerOffset]
    private fun viewerConfig() = rule.onNodeWithTag("prototype_media_viewer").fetchSemanticsNode().config
}
