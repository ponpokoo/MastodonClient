package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ponpokoo.mastodonclient.core.preferences.ComposerAction
import io.github.ponpokoo.mastodonclient.core.preferences.StatusAction

@Composable
internal fun <T : Any> ReorderActionList(
    order: List<T>,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    onReorder: (List<T>) -> Unit,
    leading: @Composable (T) -> Unit = {},
) {
    val bounds = remember { mutableMapOf<T, Rect>() }
    var dragged by remember { mutableStateOf<T?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var origin by remember { mutableFloatStateOf(0f) }
    val latestOrder by rememberUpdatedState(order)
    val save by rememberUpdatedState(onReorder)
    fun move(action: T, target: Int) {
        val updated = latestOrder.toMutableList()
        val from = updated.indexOf(action)
        if (from < 0 || target !in updated.indices || from == target) return
        updated.add(target, updated.removeAt(from))
        save(updated)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        order.forEachIndexed { index, action ->
            key(action) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .onGloballyPositioned { bounds[action] = it.boundsInParent() }
                        .zIndex(if (dragged == action) 1f else 0f)
                        .graphicsLayer { translationY = if (dragged == action) offset else 0f }
                        .background(if (dragged == action) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading(action)
                    Icon(icon(action), null, Modifier.padding(horizontal = 8.dp).size(24.dp))
                    Text(label(action), Modifier.weight(1f))
                    Box(
                        Modifier.size(48.dp).semantics {
                            contentDescription = label(action) + "の並び替え"
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("上へ移動") { move(action, index - 1); true })
                                if (index < order.lastIndex) add(CustomAccessibilityAction("下へ移動") { move(action, index + 1); true })
                            }
                        }.pointerInput(action) {
                            detectDragGestures(
                                onDragStart = { dragged = action; offset = 0f; origin = bounds[action]?.center?.y ?: 0f },
                                onDragCancel = { dragged = null; offset = 0f },
                                onDragEnd = {
                                    val center = origin
                                    if (center != null) {
                                        val target = latestOrder.indices.minByOrNull {
                                            kotlin.math.abs((if (latestOrder[it] == action) origin else bounds[latestOrder[it]]?.center?.y ?: center) - (center + offset))
                                        }
                                        if (target != null) move(action, target)
                                    }
                                    dragged = null; offset = 0f
                                },
                                onDrag = { change, amount -> change.consume(); offset += amount.y },
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.DragHandle, null) }
                }
            }
        }
    }
}

internal fun StatusAction.settingsIcon(): ImageVector = when (this) {
    StatusAction.Reply -> Icons.Outlined.ChatBubbleOutline
    StatusAction.Boost -> Icons.Outlined.Repeat
    StatusAction.Favourite -> Icons.Outlined.FavoriteBorder
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
    ComposerAction.SaveDraft -> Icons.Outlined.Drafts
    ComposerAction.DeleteDraft -> Icons.Outlined.DeleteOutline
}
