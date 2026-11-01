package io.github.ponpokoo.mastodonclient.feature.emoji

import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji

internal data class EmojiPickerDataState(
    val customEmojis: List<CustomEmoji> = emptyList(),
    val loading: Boolean = false,
    val loadError: String? = null,
    val history: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
    val favoritesLoaded: Boolean = true,
    val favoritesError: String? = null,
    val favoriteSaving: Boolean = false,
    val canEditFavorites: Boolean = false,
    val historyLoaded: Boolean = true,
    val historyError: String? = null,
    val historySaving: Boolean = false,
)

internal class EmojiPickerUndo(val restore: suspend () -> Unit)

internal class EmojiPickerActions(
    val retryFavorites: () -> Unit = {},
    val setFavorite: suspend (String, Boolean) -> Boolean = { _, _ -> false },
    val removeHistory: suspend (String) -> EmojiPickerUndo? = { null },
    val retryHistory: () -> Unit = {},
)
