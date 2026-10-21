package io.github.ponpokoo.mastodonclient.feature.timeline.components

import io.github.ponpokoo.mastodonclient.core.preferences.FabPosition
import org.junit.Assert.*
import org.junit.Test

class FabPlacementCalculatorTest {
    @Test fun resetTargetUsesCircularDistanceFromDefaultCenter() {
        val bounds = FabPlacementCalculator(400, 800, 56, 56, 16f, false)
        val initial = bounds.project(FabPosition())
        assertTrue(bounds.isInResetTarget(initial.copy(x = initial.x - 32f), 32f))
        assertFalse(bounds.isInResetTarget(initial.copy(x = initial.x - 33f), 32f))
        assertFalse(bounds.isInResetTarget(FabOffset(initial.x - 24f, initial.y - 24f), 32f))
        assertFalse(bounds.copy(width = 0).isInResetTarget(initial, 32f))
    }
    @Test fun roundTripResizeAndRtlKeepRelativePosition() {
        val position = FabPosition(.25f, .75f)
        for (rtl in listOf(false, true)) {
            for ((width, height) in listOf(400 to 800, 800 to 400)) {
                val bounds = FabPlacementCalculator(width, height, 56, 56, 16f, rtl)
                val projected = bounds.project(position)
                assertEquals(position, bounds.relative(projected, position))
                assertEquals(projected, bounds.clamp(projected))
                val mirrored = bounds.copy(rtl = !rtl).project(position)
                assertEquals((width - 56).toFloat(), projected.x + mirrored.x, .001f)
            }
        }
    }
    @Test fun clampsEdgesAndRejectsUnmeasuredOrTooSmallArea() {
        val bounds = FabPlacementCalculator(400, 800, 56, 56, 16f, false)
        assertEquals(FabPosition(0f, 1f), bounds.relative(FabOffset(-500f, 5000f), FabPosition()))
        assertNull(bounds.copy(width = 50).relative(FabOffset(16f, 16f), FabPosition()))
        assertNull(bounds.copy(fabWidth = 0).relative(FabOffset(16f, 16f), FabPosition()))
        assertNull(bounds.relative(FabOffset(Float.NaN, 0f), FabPosition()))
        val exact = bounds.copy(width = 88, height = 88)
        assertEquals(FabPosition(.3f, .6f), exact.relative(FabOffset(16f, 16f), FabPosition(.3f, .6f)))
    }
}
