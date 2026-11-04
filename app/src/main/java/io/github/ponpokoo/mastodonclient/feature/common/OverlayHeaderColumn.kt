package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

private enum class HeaderSlot { Header, Body }

/** Keep the scrolling viewport fixed while chrome above it collapses independently. */
@Composable
internal fun OverlayHeaderColumn(
    modifier: Modifier = Modifier,
    collapseOffset: () -> Int = { 0 },
    header: @Composable () -> Unit,
    content: @Composable (topContentPadding: Dp) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val chrome = subcompose(HeaderSlot.Header, header).single().measure(loose)
        val expandedHeight = (chrome.height - collapseOffset()).coerceAtLeast(0).toDp()
        val body = subcompose(HeaderSlot.Body) { content(expandedHeight) }.single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.placeRelative(0, 0)
            chrome.placeRelative(0, 0)
        }
    }
}

/** Reserve only the visible header height in an overlay, never in the scrolling body. */
@Composable
internal fun CollapsingHeaderSpacer(expandedHeight: Dp, collapseOffset: () -> Int) {
    Spacer(Modifier.layout { measurable, constraints ->
        val expanded = expandedHeight.roundToPx()
        val visible = (expanded + collapseOffset()).coerceIn(0, expanded)
        val placeable = measurable.measure(constraints.copy(minHeight = visible, maxHeight = visible))
        layout(placeable.width, visible) { placeable.placeRelative(0, 0) }
    })
}
