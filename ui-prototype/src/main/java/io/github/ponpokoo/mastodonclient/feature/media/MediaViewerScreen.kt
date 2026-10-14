package io.github.ponpokoo.mastodonclient.feature.media

import io.github.ponpokoo.transitionprototype.*
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import coil3.compose.AsyncImage
import androidx.compose.ui.geometry.Size
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationControlListenerCompat
import androidx.core.view.WindowInsetsAnimationControllerCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class PrototypeBarsChange(
    val controller: WindowInsetsAnimationControllerCompat,
    val visible: Boolean,
    val uncontrolledTypes: Int,
)

@Stable
private class PrototypeZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(IntSize.Zero)
}

// Production gesture values, integrated into the restored image transition.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaViewerScreen(media: List<MediaAttachment>, motion: PrototypeMediaMotionState) {
    val pager = rememberPagerState(initialPage = motion.page, pageCount = media::size)
    val zoomStates = media.map { image -> remember(image.id) { PrototypeZoomState() } }
    val currentZoom = zoomStates[pager.currentPage]
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val window = context.findActivity()?.window
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
    BackHandler(enabled = motion.active) { motion.close(pager.currentPage) }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
        if (!pager.isScrollInProgress && motion.phase == MediaPhase.Viewing) {
            motion.page = pager.settledPage
            motion.selectedKey = imageKey(media[pager.settledPage])
            zoomStates.forEachIndexed { page, zoom ->
                if (page != pager.settledPage) { zoom.scale = 1f; zoom.offset = Offset.Zero }
            }
        }
    }
    LaunchedEffect(motion.phase) {
        if (motion.phase == MediaPhase.ResettingZoom) {
            setControlsVisible(true)
            pager.scrollToPage(motion.page)
            val zoom = zoomStates[motion.page]
            val startScale = zoom.scale
            val startOffset = zoom.offset
            val startDrag = motion.dragOffset
            if (startScale != 1f || startOffset != Offset.Zero || startDrag != 0f) {
                animate(0f, 1f, animationSpec = tween(150)) { fraction, _ ->
                    zoom.scale = startScale + (1f - startScale) * fraction
                    zoom.offset = startOffset * (1f - fraction)
                    motion.dragOffset = startDrag * (1f - fraction)
                }
            }
            zoom.scale = 1f
            zoom.offset = Offset.Zero
            motion.dragOffset = 0f
            motion.phase = MediaPhase.Rounding
        }
    }
    var returnJob by remember { mutableStateOf<Job?>(null) }
    val viewportHeight = motion.viewerSize.height.toFloat().coerceAtLeast(1f)
    val interactive = motion.phase == MediaPhase.Viewing
    val dismissEnabled = motion.phase in listOf(MediaPhase.Opening, MediaPhase.HandingOff, MediaPhase.Viewing)
    val drawPager = motion.phase in listOf(MediaPhase.HandingOff, MediaPhase.Viewing, MediaPhase.ResettingZoom)
    val controlsAlpha = motion.visibleBackdropAlpha
    DisposableEffect(interactive) {
        if (!interactive) returnJob?.cancel()
        onDispose { }
    }
    Box(Modifier.fillMaxSize().testTag("prototype_media_viewer")
                // Observe in stationary window coordinates, outside the translated pager.
                // Keep the same detector as the picture opens and hands off to the pager.
                .pointerInput(pager.currentPage, dismissEnabled, viewportHeight) {
                    if (!dismissEnabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        returnJob?.cancel()
                        var eligible = currentZoom.scale <= MIN_DISMISS_SCALE
                        var dragging = false
                        var distance = Offset.Zero
                        motion.dragOffset = 0f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.first()
                            val delta = change.position - change.previousPosition
                            // The image/pager get first refusal. After vertical slop, consume only
                            // one-finger movement; a second finger still takes over for pinch.
                            if (event.changes.size > 1 || currentZoom.scale > MIN_DISMISS_SCALE ||
                                (change.isConsumed && delta != Offset.Zero)) eligible = false
                            // Track an already-down finger, but only move the picture once
                            // it fills the screen, including a pending load or handoff.
                            if (motion.progress.value < 1f) {
                                distance = Offset.Zero
                                continue
                            }
                            distance += delta
                            if (eligible && !dragging && distance.getDistance() > viewConfiguration.touchSlop) {
                                dragging = distance.y > abs(distance.x)
                                if (!dragging) eligible = false
                            }
                            motion.dragOffset = if (eligible && dragging) (distance.y - viewConfiguration.touchSlop).coerceAtLeast(0f) else 0f
                            if (eligible && dragging && delta != Offset.Zero) change.consume()
                        } while (event.changes.any { it.pressed })
                        if (eligible && dragging && motion.dragOffset >= viewportHeight * DISMISS_THRESHOLD) {
                            motion.close(pager.currentPage, fromDrag = true)
                        }
                        else returnJob = scope.launch {
                            animate(motion.dragOffset, 0f, animationSpec = PrototypeReturnAnimation) {
                                value, _ -> motion.dragOffset = value
                            }
                        }
                    }
                }.semantics {
                    this[PrototypeViewerPage] = pager.currentPage
                    this[PrototypeViewerScale] = currentZoom.scale
                    this[PrototypeViewerOffset] = currentZoom.offset
                    this[PrototypeViewerDismissOffset] = motion.dragOffset
                }) {
        HorizontalPager(pager,
            Modifier.fillMaxSize().graphicsLayer { translationY = motion.dragOffset; alpha = if (drawPager) 1f else 0f }
                .semantics { if (!drawPager) hideFromAccessibility() },
            userScrollEnabled = interactive && currentZoom.scale <= MIN_DISMISS_SCALE, beyondViewportPageCount = 1,
        ) { page ->
            val item = media[page]
            when (item.type) {
                "audio" -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    AudioPlayer(item, active = page == pager.currentPage)
                }
                "video", "gifv" -> VideoPlayer(item.url, loop = item.type == "gifv", active = page == pager.currentPage,
                    controlsVisible = controlsVisible && page == pager.currentPage,
                    onTap = { setControlsVisible(!controlsVisible) })
                else -> ZoomableImage(item.url ?: item.previewUrl.orEmpty(), item.previewUrl, item.description,
                    item.cacheRevision, zoomStates[page], enabled = interactive, fallbackResource = fixtureImageResource(item),
                    onDisplayed = { motion.reportImageReady(item.id) },
                    onTap = { setControlsVisible(!controlsVisible) })
            }
        }
        if (controlsVisible) {
            Box(Modifier.fillMaxSize().prototypeOverlay(2f) { motion.active }) {
                // Keep the control backgrounds lighter and smaller than their touch targets.
                val controlsBackground = Color(0xFF303030).copy(alpha = 0.35f)
                val controlsShape = RoundedCornerShape(8.dp)
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
                    .background(Color(0xFF303030).copy(alpha = 0.6f * controlsAlpha)))
                IconButton(onClick = { motion.close(pager.currentPage) },
                    enabled = motion.phase in listOf(MediaPhase.Opening, MediaPhase.HandingOff, MediaPhase.Viewing),
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                        .graphicsLayer { alpha = controlsAlpha }
                        .testTag("prototype_media_close")) {
                    Box(Modifier.size(32.dp).background(controlsBackground, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "閉じる", modifier = Modifier.size(20.dp), tint = Color.White)
                    }
                }
                IconButton(onClick = { Toast.makeText(context, "メディアを保存をタップしました", Toast.LENGTH_SHORT).show() },
                    enabled = interactive, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
                        .graphicsLayer { alpha = controlsAlpha }) {
                    Box(Modifier.size(32.dp).background(controlsBackground, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Download, "メディアを保存", modifier = Modifier.size(20.dp), tint = Color.White)
                    }
                }
                if (media.size > 1) {
                    Text("${pager.currentPage + 1} / ${media.size}", color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
                            .graphicsLayer { alpha = controlsAlpha }
                            .background(controlsBackground, controlsShape).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    previewUrl: String?,
    description: String?,
    cacheRevision: Long,
    zoom: PrototypeZoomState,
    enabled: Boolean,
    fallbackResource: Int,
    onDisplayed: () -> Unit,
    onTap: () -> Unit,
) {
    var sourceSize by remember(url) { mutableStateOf(Size.Unspecified) }
    var originalLoaded by remember(url) { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDisplayed by rememberUpdatedState(onDisplayed)
    val fallback = painterResource(fallbackResource)
    LaunchedEffect(originalLoaded) {
        if (originalLoaded) {
            // Coil success can precede the first draw. Keep the transition cover for that frame.
            withFrameNanos { }
            currentOnDisplayed()
        }
    }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) settleJob?.cancel()
        onDispose { }
    }
    fun boundedOffset(candidate: Offset, newScale: Float): Offset {
        val viewportWidth = zoom.viewport.width.toFloat()
        val viewportHeight = zoom.viewport.height.toFloat()
        if (viewportWidth <= 0f || viewportHeight <= 0f ||
            !sourceSize.width.isFinite() || !sourceSize.height.isFinite() ||
            sourceSize.width <= 0f || sourceSize.height <= 0f
        ) return Offset.Zero
        val fit = minOf(viewportWidth / sourceSize.width, viewportHeight / sourceSize.height)
        val maxX = ((sourceSize.width * fit * newScale - viewportWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((sourceSize.height * fit * newScale - viewportHeight) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!originalLoaded && previewUrl != null && previewUrl != url) {
            AsyncImage(
                model = io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel(previewUrl, cacheRevision),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        AsyncImage(
            model = io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel(url, cacheRevision),
            contentDescription = description ?: "添付画像",
            error = fallback,
            onSuccess = {
                originalLoaded = true
                sourceSize = it.painter.intrinsicSize
            },
            onError = {
                sourceSize = fallback.intrinsicSize
                originalLoaded = true
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { zoom.viewport = it }
                .graphicsLayer(
                    scaleX = zoom.scale,
                    scaleY = zoom.scale,
                    translationX = zoom.offset.x,
                    translationY = zoom.offset.y,
                )
                .pointerInput(url, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onTap = { currentOnTap() },
                        onDoubleTap = { tapPosition ->
                            settleJob?.cancel()
                            if (zoom.scale > 1f) {
                                zoom.scale = 1f
                                zoom.offset = Offset.Zero
                            } else {
                                zoom.scale = DOUBLE_TAP_SCALE
                                zoom.offset = boundedOffset(Offset(
                                    x = (zoom.viewport.width / 2f - tapPosition.x) * (DOUBLE_TAP_SCALE - 1f),
                                    y = (zoom.viewport.height / 2f - tapPosition.y) * (DOUBLE_TAP_SCALE - 1f),
                                ), DOUBLE_TAP_SCALE)
                            }
                        },
                    )
                }
                .pointerInput(url, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        settleJob?.cancel()
                        var panDistance = Offset.Zero
                        var panning = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedPointers = event.changes.count { it.pressed }
                            val isPinching = pressedPointers >= 2
                            val isPanningZoomedImage = zoom.scale > 1f && pressedPointers > 0
                            var panChange = event.calculatePan()
                            if (isPinching) {
                                // Keep an established pinch active when one finger is lifted.
                                panning = true
                            } else if (isPanningZoomedImage && !panning) {
                                // Pointer coordinates are inside the scaled layer. Compare slop
                                // in screen pixels so zoom does not change the drag threshold.
                                panDistance += panChange * zoom.scale
                                val distance = panDistance.getDistance()
                                if (distance > viewConfiguration.touchSlop) {
                                    panning = true
                                    panChange = panDistance * (1f - viewConfiguration.touchSlop / distance) / zoom.scale
                                }
                            }
                            if (isPinching || (isPanningZoomedImage && panning)) {
                                val zoomChange = if (isPinching) event.calculateZoom() else 1f
                                val nextScale = (zoom.scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                                zoom.scale = nextScale
                                zoom.offset = if (nextScale <= 1f) {
                                    Offset.Zero
                                } else {
                                    boundedOffset(zoom.offset + panChange * PAN_SPEED_MULTIPLIER, nextScale)
                                }
                                if (isPinching || panChange != Offset.Zero) {
                                    event.changes.forEach { change -> change.consume() }
                                }
                            }
                            if (event.changes.none { it.pressed }) {
                                if (zoom.scale < 1f) {
                                    val start = zoom.scale
                                    settleJob = scope.launch {
                                        animate(start, 1f) { value, _ -> zoom.scale = value }
                                        zoom.offset = Offset.Zero
                                    }
                                }
                                break
                            }
                        }
                    }
                },
            contentScale = ContentScale.Fit,
        )
    }
}

private const val PAN_SPEED_MULTIPLIER = 2.25f
private const val MAX_SCALE = 5f
private const val MIN_SCALE = 0.5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MIN_DISMISS_SCALE = 1.01f
private const val DISMISS_THRESHOLD = 0.12f
internal const val BACKDROP_FADE_SPEED = 1.5f

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
