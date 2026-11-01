package io.github.ponpokoo.mastodonclient.feature.emoji

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Stable for one ViewModel session generation; each opening creates its own state. */
internal class EmojiPickerConnection(val sessionKey: String, val createState: () -> EmojiPickerState)

@Composable
internal fun EmojiPickerHost(
    connection: EmojiPickerConnection,
    canUndo: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String?) -> Unit,
    title: String = "リアクション",
    selectionLabel: String = "この絵文字でリアクション",
    motion: EmojiPickerSheetMotion = EmojiPickerSheetMotion.HeaderOnly,
) {
    key(connection) {
        val controller = remember { connection.createState() }
        DisposableEffect(controller) {
            controller.start()
            onDispose { controller.close() }
        }
        val data by controller.state.collectAsStateWithLifecycle()
        EmojiPickerSheet(data, controller.actions, canUndo, onDismiss, onSelected, title, selectionLabel, motion)
    }
}
