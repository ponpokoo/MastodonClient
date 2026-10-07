package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.rememberScroller

/** Projects the drag order locally; only a completed drop writes to preferences/session storage. */
@Stable
internal class DragAndDropListState<T : Any, K : Any>(
    private val source: State<List<T>>,
    private val enabled: State<Boolean>,
    private val saveFailed: State<Boolean>,
    private val itemKey: (T) -> K,
    private val save: State<(T, T?) -> Unit>,
) {
    private var projectedKeys by mutableStateOf<List<K>?>(null)
    private var startKeys: List<K>? = null
    private var draggedKey: K? = null
    private var cancelled = false
    var awaitingSave by mutableStateOf(false)
        private set

    private val sourceKeys get() = source.value.map(itemKey)
    val canDrag get() = enabled.value && !awaitingSave && source.value.size > 1
    val items: List<T> get() {
        val byKey = source.value.associateBy(itemKey)
        val keys = projectedKeys ?: sourceKeys
        return (keys + sourceKeys.filterNot(keys::contains)).mapNotNull(byKey::get)
    }

    fun key(item: T): String = "reorder_${itemKey(item)}"

    fun start(item: T) {
        startKeys = sourceKeys
        draggedKey = itemKey(item)
        projectedKeys = sourceKeys
        cancelled = false
    }

    fun cancel() { cancelled = true }

    fun move(from: Any, to: Any) {
        if (cancelled || startKeys != sourceKeys) return
        val order = (projectedKeys ?: sourceKeys).toMutableList()
        val fromIndex = order.indexOfFirst { "reorder_$it" == from }
        val toIndex = order.indexOfFirst { "reorder_$it" == to }
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        order.add(toIndex, order.removeAt(fromIndex))
        projectedKeys = order
    }

    fun finish() {
        val moved = draggedKey
        val order = projectedKeys
        if (!cancelled && enabled.value && startKeys == sourceKeys && order != startKeys && moved != null && order != null) {
            persist(order, moved)
        } else {
            projectedKeys = null
        }
        startKeys = null
        draggedKey = null
        cancelled = false
    }

    fun moveBy(item: T, direction: Int): Boolean {
        if (!canDrag || draggedKey != null) return false
        val order = sourceKeys.toMutableList()
        val key = itemKey(item)
        val index = order.indexOf(key)
        val target = index + direction
        if (index < 0 || target !in order.indices) return false
        order.add(target, order.removeAt(index))
        persist(order, key)
        return true
    }

    private fun persist(order: List<K>, moved: K) {
        val byKey = source.value.associateBy(itemKey)
        val item = byKey[moved] ?: return
        val before = order.getOrNull(order.indexOf(moved) + 1)?.let(byKey::get)
        projectedKeys = order
        awaitingSave = true
        save.value(item, before)
    }

    fun reconcile() {
        if (awaitingSave && (sourceKeys == projectedKeys || saveFailed.value)) clearPending()
    }

    fun clearPending() {
        projectedKeys = null
        awaitingSave = false
    }
}

@Composable
internal fun <T : Any, K : Any> rememberDragAndDropListState(
    items: List<T>,
    itemKey: (T) -> K,
    onMove: (T, T?) -> Unit,
    enabled: Boolean = true,
    saveFailed: Boolean = false,
): DragAndDropListState<T, K> {
    val source = rememberUpdatedState(items)
    val enabledState = rememberUpdatedState(enabled)
    val failureState = rememberUpdatedState(saveFailed)
    val save = rememberUpdatedState(onMove)
    val state = remember { DragAndDropListState(source, enabledState, failureState, itemKey, save) }
    LaunchedEffect(items.map(itemKey), saveFailed, state.awaitingSave) { state.reconcile() }
    LaunchedEffect(state.awaitingSave) {
        if (state.awaitingSave) {
            delay(5_000)
            state.clearPending()
        }
    }
    return state
}

@Composable
internal fun rememberSettingsReorderableState(
    listState: LazyListState,
    onMove: (Any, Any) -> Unit,
): ReorderableLazyListState {
    // The library increases this base speed near the edge, up to 900 dp/s (previously 448).
    val speed = with(LocalDensity.current) { 180.dp.toPx() }
    val scroller = rememberScroller(listState, pixelPerSecond = speed)
    return rememberReorderableLazyListState(
        listState,
        scrollThreshold = 56.dp,
        scroller = scroller,
    ) { from, to ->
        onMove(from.key, to.key)
    }
}

/** Use the same LazyColumn for headers and reorderable rows, as in the upstream example. */
internal fun <T : Any, K : Any> LazyListScope.dragAndDropItems(
    state: DragAndDropListState<T, K>,
    reorderableState: ReorderableLazyListState,
    label: (T) -> String,
    rowModifier: (T) -> Modifier = { Modifier },
    content: @Composable RowScope.(T) -> Unit,
) {
    items(state.items, key = state::key) { item ->
        val detector = remember(state) {
            object : DragGestureDetector {
                override suspend fun PointerInputScope.detect(
                    onDragStart: (Offset) -> Unit,
                    onDragEnd: () -> Unit,
                    onDragCancel: () -> Unit,
                    onDrag: (PointerInputChange, Offset) -> Unit,
                ) {
                    detectDragGestures(onDragStart, onDragEnd, {
                        state.cancel()
                        onDragCancel()
                    }, onDrag)
                }
            }
        }
        ReorderableItem(
            reorderableState, key = state.key(item),
            animateItemModifier = Modifier.animateItem(
                fadeInSpec = null, placementSpec = tween(120), fadeOutSpec = null,
            ),
        ) { isDragging ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 56.dp)
                    .background(if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                    .then(rowModifier(item)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content(item)
                Box(
                    Modifier.size(48.dp)
                        .draggableHandle(
                            enabled = state.canDrag,
                            dragGestureDetector = detector,
                            onDragStarted = { state.start(item) },
                            onDragStopped = state::finish,
                        )
                        .testTag("reorder_handle_${state.key(item).removePrefix("reorder_")}")
                        .semantics {
                            contentDescription = label(item) + "の並び替え"
                            if (!state.canDrag) disabled()
                            val index = state.items.indexOf(item)
                            customActions = if (!state.canDrag) emptyList() else buildList {
                                if (index > 0) add(CustomAccessibilityAction("上へ移動") { state.moveBy(item, -1) })
                                if (index < state.items.lastIndex) add(CustomAccessibilityAction("下へ移動") { state.moveBy(item, 1) })
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Saving temporarily blocks input without flashing the handle's color.
                    Icon(Icons.Outlined.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun <T : Any, K : Any> SettingsDragAndDropList(
    items: List<T>,
    itemKey: (T) -> K,
    label: (T) -> String,
    onMove: (T, T?) -> Unit,
    enabled: Boolean = true,
    saveFailed: Boolean = false,
    rowModifier: (T) -> Modifier = { Modifier },
    content: @Composable RowScope.(T) -> Unit,
) {
    val state = rememberDragAndDropListState(items, itemKey, onMove, enabled, saveFailed)
    val listState = rememberLazyListState()
    val reorderableState = rememberSettingsReorderableState(listState, onMove = state::move)
    LazyColumn(state = listState) { dragAndDropItems(state, reorderableState, label, rowModifier, content) }
}
