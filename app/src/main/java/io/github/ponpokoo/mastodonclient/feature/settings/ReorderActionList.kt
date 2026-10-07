package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.core.preferences.ComposerAction
import io.github.ponpokoo.mastodonclient.core.preferences.StatusAction
import sh.calvin.reorderable.ReorderableLazyListState

@Composable
internal fun <T : Any> rememberActionReorderState(
    order: List<T>,
    onReorder: (List<T>) -> Unit,
): DragAndDropListState<T, T> = rememberDragAndDropListState(order, { it }, { action, before ->
    val updated = order.toMutableList().apply { remove(action) }
    val target = if (before == null) updated.size else updated.indexOf(before)
    if (target >= 0) { updated.add(target, action); onReorder(updated) }
})

internal fun <T : Any> LazyListScope.reorderActionItems(
    state: DragAndDropListState<T, T>,
    reorderableState: ReorderableLazyListState,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    leading: @Composable (T) -> Unit = {},
) {
    dragAndDropItems(state, reorderableState, label) { action ->
        leading(action)
        Icon(icon(action), null, Modifier.padding(horizontal = 8.dp).size(24.dp))
        Text(label(action), Modifier.weight(1f))
    }
}

@Composable
internal fun <T : Any> ReorderActionList(
    order: List<T>,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    onReorder: (List<T>) -> Unit,
    leading: @Composable (T) -> Unit = {},
) {
    SettingsDragAndDropList(items = order, itemKey = { it }, label = label,
        onMove = { action, before ->
            val updated = order.toMutableList().apply { remove(action) }
            val target = if (before == null) updated.size else updated.indexOf(before)
            if (target >= 0) { updated.add(target, action); onReorder(updated) }
        }) { action ->
        leading(action)
        Icon(icon(action), null, Modifier.padding(horizontal = 8.dp).size(24.dp))
        Text(label(action), Modifier.weight(1f))
    }
}

internal fun StatusAction.settingsIcon(): ImageVector = when (this) {
    StatusAction.Reply -> Icons.AutoMirrored.Filled.Reply
    StatusAction.Boost -> Icons.Outlined.Repeat
    StatusAction.Favourite -> Icons.Outlined.StarBorder
    StatusAction.Reaction -> Icons.Outlined.SentimentSatisfiedAlt
    StatusAction.Bookmark -> Icons.Outlined.BookmarkBorder
    StatusAction.Share -> Icons.Outlined.Share
}

internal fun ComposerAction.settingsIcon(): ImageVector = when (this) {
    ComposerAction.Media -> Icons.Outlined.Image
    ComposerAction.Poll -> Icons.Outlined.Poll
    ComposerAction.Emoji -> Icons.Outlined.SentimentSatisfiedAlt
    ComposerAction.ContentWarning -> Icons.Outlined.WarningAmber
    ComposerAction.Mention -> Icons.Outlined.AlternateEmail
    ComposerAction.Language -> Icons.Outlined.Language
    ComposerAction.Hashtag -> Icons.Outlined.Tag
    ComposerAction.SaveDraft -> Icons.Outlined.Drafts
    ComposerAction.DeleteDraft -> Icons.Outlined.DeleteOutline
}
