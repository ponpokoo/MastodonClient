package io.github.ponpokoo.mastodonclient.feature.timeline.components

import io.github.ponpokoo.mastodonclient.core.preferences.FabPosition

internal data class FabOffset(val x: Float, val y: Float)

internal data class FabPlacementCalculator(
    val width: Int, val height: Int, val fabWidth: Int, val fabHeight: Int,
    val margin: Float, val rtl: Boolean,
) {
    val valid get() = fabWidth > 0 && fabHeight > 0 &&
        width >= fabWidth + margin * 2 && height >= fabHeight + margin * 2
    private val travelX get() = (width - fabWidth - margin * 2).coerceAtLeast(0f)
    private val travelY get() = (height - fabHeight - margin * 2).coerceAtLeast(0f)

    fun project(position: FabPosition): FabOffset {
        val safe = position.normalized() ?: FabPosition()
        return FabOffset(margin + travelX * (if (rtl) 1f - safe.xFraction else safe.xFraction),
            margin + travelY * safe.yFraction)
    }

    fun clamp(offset: FabOffset) = FabOffset(offset.x.coerceIn(margin, margin + travelX),
        offset.y.coerceIn(margin, margin + travelY))

    fun isInResetTarget(offset: FabOffset, radius: Float): Boolean {
        if (!valid || !radius.isFinite() || radius < 0f) return false
        // Both positions use the same FAB dimensions, so the center delta equals the offset delta.
        val initial = project(FabPosition())
        val dx = offset.x - initial.x
        val dy = offset.y - initial.y
        return dx * dx + dy * dy <= radius * radius
    }

    fun relative(offset: FabOffset, previous: FabPosition): FabPosition? {
        if (!valid || !offset.x.isFinite() || !offset.y.isFinite()) return null
        val bounded = clamp(offset)
        val x = if (travelX == 0f) previous.xFraction else
            ((bounded.x - margin) / travelX).let { if (rtl) 1f - it else it }
        val y = if (travelY == 0f) previous.yFraction else (bounded.y - margin) / travelY
        return FabPosition(x, y).normalized()
    }
}
