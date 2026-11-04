package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection

/** Keep top/side protection while a scrolling viewport draws behind a fixed bottom bar. */
@Composable
internal fun PaddingValues.withoutBottomPadding(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction), top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}
