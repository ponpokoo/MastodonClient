package io.github.ponpokoo.mastodonclient.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaDisplay {
    Default, ShowAll, HideAll;

    fun initiallyVisible(sensitive: Boolean): Boolean = when (this) {
        Default -> !sensitive
        ShowAll -> true
        HideAll -> false
    }
}

/** Per-account reading choices stored only on this device. */
@Serializable
data class ReadingPreferences(val media: MediaDisplay = MediaDisplay.Default, val expandSpoilers: Boolean = false)
