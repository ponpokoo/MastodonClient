package io.github.ponpokoo.transitionprototype

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationControlListenerCompat
import androidx.core.view.WindowInsetsAnimationControllerCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

internal val PrototypeViewerPage = SemanticsPropertyKey<Int>("PrototypeViewerPage")
internal val PrototypeViewerScale = SemanticsPropertyKey<Float>("PrototypeViewerScale")

@Composable
internal fun PrototypeMediaViewer(media: List<PrototypeMedia>, initialIndex: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        BackHandler(onBack = onClose)
        PrototypeViewerContent(media, initialIndex, onClose)
    }
}

private data class PrototypeBarsChange(
    val controller: WindowInsetsAnimationControllerCompat,
    val visible: Boolean,
    val uncontrolledTypes: Int,
)

// Baseline: production MediaViewerScreen.kt. Only the data source and save action are fixtures.
@Composable
private fun PrototypeViewerContent(media: List<PrototypeMedia>, initialIndex: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(media.indices), pageCount = media::size)
    val scales = remember { mutableStateMapOf<Int, Float>() }
    val currentScale = scales[pager.currentPage] ?: 1f
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val active = remember(window) { AtomicBoolean(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var barsPending by remember { mutableStateOf(false) }
    var barsChange by remember { mutableStateOf<PrototypeBarsChange?>(null) }
    var barsCancellation by remember { mutableStateOf<CancellationSignal?>(null) }
    val barTypes = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible || barsPending) return
        if (window == null) { controlsVisible = visible; return }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Build.VERSION.SDK_INT < 30) {
            controlsVisible = visible
            if (visible) controller.show(barTypes) else controller.hide(barTypes)
            return
        }
        barsPending = true
        barsCancellation = CancellationSignal()
        controller.controlWindowInsetsAnimation(barTypes, 0L, null, barsCancellation,
            object : WindowInsetsAnimationControlListenerCompat {
                override fun onReady(animationController: WindowInsetsAnimationControllerCompat, types: Int) {
                    if (!active.get()) { animationController.finish(true); return }
                    controlsVisible = visible
                    barsChange = PrototypeBarsChange(animationController, visible, barTypes and types.inv())
                }
                override fun onFinished(animationController: WindowInsetsAnimationControllerCompat) {
                    barsPending = false
                    barsCancellation = null
                }
                override fun onCancelled(animationController: WindowInsetsAnimationControllerCompat?) {
                    barsPending = false
                    barsCancellation = null
                    barsChange = null
                    if (!active.get()) return
                    controlsVisible = visible
                    if (visible) controller.show(barTypes) else controller.hide(barTypes)
                }
            },
        )
    }
    SideEffect {
        window?.setDimAmount(0f)
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        barsChange?.let { change ->
            change.controller.finish(change.visible)
            if (change.uncontrolledTypes != 0 && window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (change.visible) controller.show(change.uncontrolledTypes) else controller.hide(change.uncontrolledTypes)
            }
            barsChange = null
        }
    }
    DisposableEffect(window) {
        window?.let { WindowCompat.getInsetsController(it, it.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        } }
        onDispose {
            active.set(false)
            barsCancellation?.cancel()
            window?.let { WindowCompat.getInsetsController(it, it.decorView).show(barTypes) }
        }
    }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(1f) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - dragOffset / viewportHeight * 1.5f).coerceIn(0f, 1f)))) {
        Box(
            Modifier.fillMaxSize().onSizeChanged { viewportHeight = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(currentScale, viewportHeight) {
                    if (currentScale <= 1.01f) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                if (dragOffset > 0f || amount > 0f) {
                                    change.consume()
                                    dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                                }
                            },
                            onDragEnd = {
                                if (dragOffset >= viewportHeight * 0.12f) onClose()
                                else scope.launch { animate(dragOffset, 0f) { value, _ -> dragOffset = value } }
                            },
                            onDragCancel = { scope.launch { animate(dragOffset, 0f) { value, _ -> dragOffset = value } } },
                        )
                    }
                }.testTag("prototype_media_viewer").semantics {
                    this[PrototypeViewerPage] = pager.currentPage
                    this[PrototypeViewerScale] = currentScale
                },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { translationY = dragOffset },
                userScrollEnabled = currentScale <= 1.01f, beyondViewportPageCount = 1) { page ->
                PrototypeZoomableImage(media[page], onScaleChanged = { scales[page] = it },
                    onTap = { setControlsVisible(!controlsVisible) })
            }
            if (controlsVisible) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                    .testTag("prototype_media_close")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "閉じる", tint = Color.White)
                }
                IconButton(onClick = { Toast.makeText(context, "メディアを保存をタップしました", Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                    Icon(Icons.Outlined.Download, "メディアを保存", tint = Color.White)
                }
                if (media.size > 1) Text("${pager.currentPage + 1} / ${media.size}", color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp))
            }
        }
    }
}

@Composable
private fun PrototypeZoomableImage(media: PrototypeMedia, onScaleChanged: (Float) -> Unit, onTap: () -> Unit) {
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember(media.id) { mutableStateOf(IntSize.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val scope = rememberCoroutineScope()
    fun boundedOffset(candidate: Offset, zoom: Float): Offset {
        if (viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
        val fitHeight = minOf(viewport.width / media.aspectRatio, viewport.height.toFloat())
        val maxX = ((fitHeight * media.aspectRatio * zoom - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitHeight * zoom - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
    LaunchedEffect(scale) { onScaleChanged(scale) }
    Image(
        painterResource(media.resource), media.description, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .pointerInput(media.id, scale, viewport) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { position ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                        else {
                            scale = 2.5f
                            offset = boundedOffset(Offset(
                                (viewport.width / 2f - position.x) * 1.5f,
                                (viewport.height / 2f - position.y) * 1.5f,
                            ), 2.5f)
                        }
                    },
                )
            }
            .pointerInput(media.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val pinching = pressed >= 2
                        if (pinching || (scale > 1f && pressed > 0)) {
                            val nextScale = (scale * if (pinching) event.calculateZoom() else 1f).coerceIn(0.5f, 5f)
                            val pan = event.calculatePan()
                            scale = nextScale
                            offset = if (nextScale <= 1f) Offset.Zero else boundedOffset(offset + pan * 2.25f, nextScale)
                            if (pinching || pan != Offset.Zero) event.changes.forEach { it.consume() }
                        }
                        if (event.changes.none { it.pressed }) {
                            if (scale < 1f) scope.launch {
                                animate(scale, 1f) { value, _ -> scale = value }
                                offset = Offset.Zero
                            }
                            break
                        }
                    }
                }
            },
    )
}
