package io.github.ponpokoo.mastodonclient.feature.status

import androidx.compose.ui.unit.sp
import io.github.ponpokoo.mastodonclient.core.preferences.FontSizePreset
import io.github.ponpokoo.mastodonclient.core.preferences.LineSpacingPreset
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.AutoplayPolicy
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences

internal data class StatusDisplayPreferences(
    val timelineDisplay: TimelineDisplayPreferences = TimelineDisplayPreferences(),
    val gifAutoplay: AutoplayPolicy = AutoplayPolicy.Always,
    val videoAutoplay: AutoplayPolicy = AutoplayPolicy.Never,
)

internal fun AppPreferences.toStatusDisplayPreferences() = StatusDisplayPreferences(
    timelineDisplay = timelineDisplay,
    gifAutoplay = gifAutoplay,
    videoAutoplay = videoAutoplay,
)

internal fun FontSizePreset.spValue() = when (this) {
    FontSizePreset.Small -> 14.sp
    FontSizePreset.Standard -> 16.sp
    FontSizePreset.Large -> 18.sp
    FontSizePreset.ExtraLarge -> 20.sp
}

internal fun TimelineDisplayPreferences.lineHeightSp(): Int {
    val base = when (fontSize) {
        FontSizePreset.Small -> 18
        FontSizePreset.Standard -> 22
        FontSizePreset.Large -> 26
        FontSizePreset.ExtraLarge -> 30
    }
    return when (lineSpacing) {
        LineSpacingPreset.Compact -> base - 2
        LineSpacingPreset.Standard -> base
        LineSpacingPreset.Relaxed -> base + 4
    }
}
