package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.runtime.compositionLocalOf
import io.github.ponpokoo.mastodonclient.domain.model.ReadingPreferences

internal data class ReadingSettings(
    val accountKey: String = "",
    val preferences: ReadingPreferences = ReadingPreferences(),
    val transientSessionKey: String = accountKey,
)

internal val LocalReadingSettings = compositionLocalOf { ReadingSettings() }
