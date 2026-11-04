package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/** Apply the same Material scroll state to the search field without moving its category tabs. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.collapsingSearchHeader(state: TopAppBarState): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    state.heightOffsetLimit = -placeable.height.toFloat()
    val offset = state.heightOffset.roundToInt().coerceIn(-placeable.height, 0)
    layout(placeable.width, placeable.height + offset) {
        placeable.placeRelative(0, offset)
    }
}
