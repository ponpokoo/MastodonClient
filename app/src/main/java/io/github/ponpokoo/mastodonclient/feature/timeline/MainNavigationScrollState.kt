package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The header observes input without consuming the list's distance or velocity. */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
internal class MainNavigationScrollState(private val header: TopAppBarState) {
    val hiddenFraction: Float get() = header.collapsedFraction

    private var settleJob: Job? = null

    fun show() {
        settleJob?.cancel()
        header.heightOffset = 0f
        header.contentOffset = 0f
    }

    private fun dragBy(deltaY: Float) {
        if (deltaY == 0f) return
        settleJob?.cancel()
        header.heightOffset += deltaY
    }

    private fun settle(scope: CoroutineScope) {
        settleJob?.cancel()
        val target = if (hiddenFraction < 0.5f) 0f else 1f
        if (hiddenFraction == target) return
        settleJob = scope.launch {
            animate(
                initialValue = hiddenFraction,
                targetValue = target,
                animationSpec = tween(160, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)),
            ) { value, _ -> header.heightOffset = header.heightOffsetLimit * value }
        }
    }

    fun connection(
        scope: CoroutineScope,
        canScroll: () -> Boolean,
    ): NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && canScroll()) {
                    dragBy(available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Start at release, not after the child's potentially long fling. Do not delay it.
                if (canScroll()) settle(scope)
                return Velocity.Zero
            }
        }
}
