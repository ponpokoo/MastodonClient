package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.copy
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState

/** Animate only the last two viewports, keeping long returns quick regardless of row heights. */
internal suspend fun LazyListState.animateToTimelineTop() {
    scroll {
        val layout = LazyLayoutScrollScope(this@animateToTimelineTop, this)
        val maxAnimatedDistance = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1) * 2
        val originalIndex = firstVisibleItemIndex
        val originalOffset = firstVisibleItemScrollOffset
        if (originalIndex > 0 || originalOffset > maxAnimatedDistance) {
            layout.snapToItem(0, maxAnimatedDistance)
            // Measurements normalize this offset across rows. Keep the closer position so
            // a short return never jumps away from the top, even with very tall first rows.
            if (firstVisibleItemIndex > originalIndex ||
                (firstVisibleItemIndex == originalIndex && firstVisibleItemScrollOffset > originalOffset)) {
                layout.snapToItem(originalIndex, originalOffset)
            }
        }
        val chunkDistance = maxAnimatedDistance.toFloat()
        var animation = AnimationState(0f)
        var chunks = 0
        var reachedBoundary = false
        while (firstVisibleItemIndex > 0 && canScrollBackward && !reachedBoundary) {
            animation = animation.copy(value = 0f)
            var previous = 0f
            animation.animateTo(-chunkDistance, tween(250, easing = LinearEasing), sequentialAnimation = chunks > 0) {
                val delta = value - previous
                val consumed = layout.scrollBy(delta)
                previous = value
                if (delta < -1f && consumed == 0f) reachedBoundary = true
                if (firstVisibleItemIndex == 0 || reachedBoundary) cancelAnimation()
            }
            chunks++
        }
        if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset > 0) {
            var previous = 0f
            AnimationState(0f).animateTo(-firstVisibleItemScrollOffset.toFloat(), tween(220, easing = LinearOutSlowInEasing)) {
                layout.scrollBy(value - previous)
                previous = value
            }
        }
        // Cancellation by a gesture or a context change exits the scroll session before this.
        layout.snapToItem(0)
    }
}
