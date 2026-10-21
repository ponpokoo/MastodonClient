package io.github.ponpokoo.mastodonclient.feature.timeline.components

import android.util.Log
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.core.preferences.FabPosition
import io.github.ponpokoo.mastodonclient.core.preferences.FabPositionStore
import io.github.ponpokoo.mastodonclient.feature.media.mediaComposeButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

private data class LoadedFabPosition(val position: FabPosition?)

@Composable
internal fun DraggableComposeFab(
    store: FabPositionStore,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val flow = remember(store) { store.fabPosition.distinctUntilChanged().map { LoadedFabPosition(it) } }
    val loaded by flow.collectAsStateWithLifecycle(initialValue = null)
    val latestLoaded by rememberUpdatedState(loaded)
    val scope = rememberCoroutineScope()
    val saveMutex = remember(store) { Mutex() }
    var pending by remember(store) { mutableStateOf<LoadedFabPosition?>(null) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(LocalDensity.current) { 8.dp.toPx() }
    val resetRadius = with(LocalDensity.current) { 32.dp.toPx() }
    val targetColor = MaterialTheme.colorScheme.primary
    val bounds = FabPlacementCalculator(area.width, area.height, size.width, size.height,
        margin, LocalLayoutDirection.current == LayoutDirection.Rtl)
    var dragOffset by remember(bounds, visible) { mutableStateOf<FabOffset?>(null) }
    var dragStart by remember(bounds, visible) { mutableStateOf<FabOffset?>(null) }
    var suppressClick by remember { mutableStateOf(false) }
    val position = (pending ?: loaded)?.position ?: FabPosition()
    val latestPosition by rememberUpdatedState(position)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(loaded) {
        if (loaded == pending) pending = null
    }
    fun save(value: FabPosition?) {
        val request = LoadedFabPosition(value)
        pending = request
        scope.launch {
            saveMutex.withLock {
                try {
                    if (value == null) store.resetFabPosition() else store.setFabPosition(value)
                    // Resetting an already-default value does not emit a new distinct Flow value.
                    if (latestLoaded == request && pending === request) pending = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w("ComposeFab", "Could not save FAB position")
                    if (pending === request) pending = null
                }
            }
        }
    }
    Box(modifier.fillMaxSize().onSizeChanged { area = it }.drawBehind {
        val current = dragOffset
        if (visible && bounds.valid && current != null) {
            val initial = bounds.project(FabPosition())
            val center = Offset(initial.x + bounds.fabWidth / 2f, initial.y + bounds.fabHeight / 2f)
            val active = bounds.isInResetTarget(current, resetRadius)
            drawCircle(targetColor.copy(alpha = if (active) .25f else .08f), resetRadius, center)
            drawCircle(targetColor.copy(alpha = if (active) .9f else .35f), resetRadius, center,
                style = Stroke(if (active) 3.dp.toPx() else 1.dp.toPx()))
        }
    }, contentAlignment = AbsoluteAlignment.TopLeft) {
        if (visible && loaded != null) {
            FloatingActionButton(
                onClick = { if (bounds.valid && !suppressClick) onCompose() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .absoluteOffset {
                        val offset = bounds.clamp(dragOffset ?: bounds.project(latestPosition))
                        IntOffset(offset.x.roundToInt(), offset.y.roundToInt())
                    }
                    .onSizeChanged { size = it }
                    .graphicsLayer { alpha = if (bounds.valid) 1f else 0f }
                    .mediaComposeButton()
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("投稿ボタンを開始側の下へ移動") { save(FabPosition(0f, 1f)); true },
                            CustomAccessibilityAction("投稿ボタンを終了側の下へ移動") { save(FabPosition()); true },
                            CustomAccessibilityAction("投稿ボタンを開始側の上へ移動") { save(FabPosition(0f, 0f)); true },
                            CustomAccessibilityAction("投稿ボタンを終了側の上へ移動") { save(FabPosition(1f, 0f)); true },
                        )
                    }
                    .pointerInput(bounds, visible) {
                        // Material's clickable sees the release before the outer drag detector.
                        // Keep the long-press guard through Main, then clear it in Final.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            suppressClick = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                            suppressClick = false
                        }
                    }
                    .pointerInput(bounds) {
                        if (!bounds.valid) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                suppressClick = true
                                dragStart = bounds.project(latestPosition)
                                dragOffset = dragStart
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset?.let { dragOffset = bounds.clamp(FabOffset(it.x + amount.x, it.y + amount.y)) }
                            },
                            onDragEnd = {
                                val end = dragOffset
                                if (end != null && end != dragStart) {
                                    if (bounds.isInResetTarget(end, resetRadius)) save(null)
                                    else bounds.relative(end, latestPosition)?.let(::save)
                                }
                                dragOffset = null
                                dragStart = null
                            },
                            onDragCancel = {
                                dragOffset = null
                                dragStart = null
                            },
                        )
                    },
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = "新規投稿")
            }
        }
    }
}
