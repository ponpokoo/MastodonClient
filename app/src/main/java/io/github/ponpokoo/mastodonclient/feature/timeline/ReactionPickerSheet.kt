package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerConnection
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerHost
import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerSheetMotion

/** Only this status-card adapter reads the browsing picker connection. */
internal val LocalReactionEmojiPickerConnection = staticCompositionLocalOf<EmojiPickerConnection?> { null }

@Composable
internal fun ReactionPickerSheet(
    canUndo: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String?) -> Unit,
    motion: EmojiPickerSheetMotion = EmojiPickerSheetMotion.HeaderOnly,
) {
    val connection = LocalReactionEmojiPickerConnection.current ?: return
    EmojiPickerHost(connection, canUndo, onDismiss, onSelected, motion = motion)
}
