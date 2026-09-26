package io.github.ponpokoo.mastodonclient.feature.media

import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationControlListenerCompat
import androidx.core.view.WindowInsetsAnimationControllerCompat
import androidx.core.view.WindowInsetsCompat
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private data class PendingSystemBarsChange(
    val controller: WindowInsetsAnimationControllerCompat,
    val visible: Boolean,
    val uncontrolledTypes: Int,
)

@Composable
fun MediaViewerScreen(
    media: List<MediaAttachment>,
    initialIndex: Int,
    onBack: () -> Unit,
) {
    if (media.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    BackHandler(onBack = onBack)
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(media.indices),
        pageCount = media::size,
    )
    val pageScales = remember { mutableStateMapOf<Int, Float>() }
    val currentScale = pageScales[pagerState.currentPage] ?: 1f
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    val viewerActive = remember(dialogWindow) { AtomicBoolean(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var barsRequestPending by remember { mutableStateOf(false) }
    var pendingBarsChange by remember { mutableStateOf<PendingSystemBarsChange?>(null) }
    var barsCancellation by remember { mutableStateOf<CancellationSignal?>(null) }
    val barTypes = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible || barsRequestPending) return
        val window = dialogWindow
        if (window == null) {
            controlsVisible = visible
            return
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Build.VERSION.SDK_INT < 30) {
            controlsVisible = visible
            if (visible) controller.show(barTypes) else controller.hide(barTypes)
            return
        }
        barsRequestPending = true
        val cancellation = CancellationSignal()
        barsCancellation = cancellation
        controller.controlWindowInsetsAnimation(
            barTypes, 0L, null, cancellation,
            object : WindowInsetsAnimationControlListenerCompat {
                override fun onReady(animationController: WindowInsetsAnimationControllerCompat, types: Int) {
                    if (!viewerActive.get()) {
                        animationController.finish(true)
                        return
                    }
                    controlsVisible = visible
                    pendingBarsChange = PendingSystemBarsChange(
                        animationController, visible, barTypes and types.inv(),
                    )
                }

                override fun onFinished(animationController: WindowInsetsAnimationControllerCompat) {
                    barsRequestPending = false
                    barsCancellation = null
                }

                override fun onCancelled(animationController: WindowInsetsAnimationControllerCompat?) {
                    barsRequestPending = false
                    barsCancellation = null
                    pendingBarsChange = null
                    if (!viewerActive.get()) return
                    controlsVisible = visible
                    if (visible) controller.show(barTypes) else controller.hide(barTypes)
                }
            },
        )
    }
    LaunchedEffect(pagerState.currentPage, barsRequestPending) {
        if (!barsRequestPending && media[pagerState.currentPage].type in setOf("video", "gifv")) {
            setControlsVisible(true)
        }
    }
    SideEffect {
        pendingBarsChange?.let { change ->
            change.controller.finish(change.visible)
            if (change.uncontrolledTypes != 0) {
                dialogWindow?.let { window ->
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    if (change.visible) controller.show(change.uncontrolledTypes)
                    else controller.hide(change.uncontrolledTypes)
                }
            }
            pendingBarsChange = null
        }
    }
    DisposableEffect(dialogWindow) {
        onDispose {
            viewerActive.set(false)
            barsCancellation?.cancel()
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(barTypes)
            }
        }
    }
    var pendingSaveSource by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val source = pendingSaveSource
        pendingSaveSource = null
        if (destination != null && source != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        require(Uri.parse(source).scheme == "https")
                        URL(source).openStream().use { input ->
                            context.contentResolver.openOutputStream(destination)?.use { output ->
                                input.copyTo(output)
                            } ?: error("保存先を開けません")
                        }
                    }.isSuccess
                }
                Toast.makeText(context, if (saved) "保存しました" else "保存できませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(1f) }
    val backdropAlpha = (1f - dragOffsetY / viewportHeight * BACKDROP_FADE_SPEED).coerceIn(0f, 1f)

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = backdropAlpha)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportHeight = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(currentScale, viewportHeight) {
                    if (currentScale <= MIN_DISMISS_SCALE) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                if (dragOffsetY > 0f || dragAmount > 0f) {
                                    change.consume()
                                    dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                }
                            },
                            onDragEnd = {
                                if (dragOffsetY >= viewportHeight * DISMISS_THRESHOLD) {
                                    onBack()
                                } else {
                                    val start = dragOffsetY
                                    scope.launch {
                                        animate(start, 0f) { value, _ -> dragOffsetY = value }
                                    }
                                }
                            },
                            onDragCancel = {
                                val start = dragOffsetY
                                scope.launch {
                                    animate(start, 0f) { value, _ -> dragOffsetY = value }
                                }
                            },
                        )
                    }
                }
                .testTag("media_viewer"),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = dragOffsetY },
                userScrollEnabled = currentScale <= MIN_DISMISS_SCALE,
                beyondViewportPageCount = 1,
            ) { page ->
                val item = media[page]
                if (item.type == "video" || item.type == "gifv") {
                    LaunchedEffect(page) { pageScales[page] = 1f }
                    VideoViewer(item.url ?: item.previewUrl.orEmpty(), loop = item.type == "gifv")
                } else {
                    ZoomableImage(
                        url = item.url ?: item.previewUrl.orEmpty(),
                        previewUrl = item.previewUrl,
                        description = item.description,
                        onScaleChanged = { pageScales[page] = it },
                        onTap = { setControlsVisible(!controlsVisible) },
                    )
                }
            }

            if (controlsVisible) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "閉じる",
                        tint = Color.White,
                    )
                }

                IconButton(
                    onClick = {
                        val item = media[pagerState.currentPage]
                        val source = item.url ?: item.previewUrl
                        if (source != null) {
                            pendingSaveSource = source
                            val path = Uri.parse(source).lastPathSegment.orEmpty().substringBefore('?')
                            val extension = path.substringAfterLast('.', "").lowercase()
                                .takeIf { it.isNotBlank() && it.length <= 5 }
                                ?: if (item.type == "video" || item.type == "gifv") "mp4" else "jpg"
                            val baseName = path.substringBeforeLast('.', "media-${item.id}")
                                .takeIf { it.isNotBlank() } ?: "media-${item.id}"
                            saveLauncher.launch("$baseName.$extension")
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = "メディアを保存", tint = Color.White)
                }

                if (media.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${media.size}",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
                    )
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
    onScaleChanged: (Float) -> Unit,
    onTap: () -> Unit,
) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var imageSize by remember(url) { mutableStateOf(IntSize.Zero) }
    var sourceSize by remember(url) { mutableStateOf(Size.Unspecified) }
    var originalLoaded by remember(url) { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val scope = rememberCoroutineScope()
    fun boundedOffset(candidate: Offset, newScale: Float): Offset {
        val viewportWidth = imageSize.width.toFloat()
        val viewportHeight = imageSize.height.toFloat()
        if (viewportWidth <= 0f || viewportHeight <= 0f ||
            !sourceSize.width.isFinite() || !sourceSize.height.isFinite() ||
            sourceSize.width <= 0f || sourceSize.height <= 0f
        ) return Offset.Zero
        val fit = minOf(viewportWidth / sourceSize.width, viewportHeight / sourceSize.height)
        val maxX = ((sourceSize.width * fit * newScale - viewportWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((sourceSize.height * fit * newScale - viewportHeight) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
    LaunchedEffect(scale) { onScaleChanged(scale) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!originalLoaded && previewUrl != null && previewUrl != url) {
            AsyncImage(
                model = previewUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        AsyncImage(
            model = url,
            contentDescription = description ?: "添付画像",
            onSuccess = {
                originalLoaded = true
                sourceSize = it.painter.intrinsicSize
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { imageSize = it }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(url, scale, imageSize) {
                    detectTapGestures(
                        onTap = { currentOnTap() },
                        onDoubleTap = { tapPosition ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_SCALE
                                offset = boundedOffset(Offset(
                                    x = (imageSize.width / 2f - tapPosition.x) * (DOUBLE_TAP_SCALE - 1f),
                                    y = (imageSize.height / 2f - tapPosition.y) * (DOUBLE_TAP_SCALE - 1f),
                                ), DOUBLE_TAP_SCALE)
                            }
                        },
                    )
                }
                .pointerInput(url) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedPointers = event.changes.count { it.pressed }
                            val isPinching = pressedPointers >= 2
                            val isPanningZoomedImage = scale > 1f && pressedPointers > 0
                            if (isPinching || isPanningZoomedImage) {
                                val zoomChange = if (isPinching) event.calculateZoom() else 1f
                                val nextScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                                val panChange = event.calculatePan()
                                scale = nextScale
                                offset = if (nextScale <= 1f) {
                                    Offset.Zero
                                } else {
                                    boundedOffset(offset + panChange * PAN_SPEED_MULTIPLIER, nextScale)
                                }
                                if (isPinching || panChange != Offset.Zero) {
                                    event.changes.forEach { change -> change.consume() }
                                }
                            }
                            if (event.changes.none { it.pressed }) {
                                if (scale < 1f) {
                                    val start = scale
                                    scope.launch {
                                        animate(start, 1f) { value, _ -> scale = value }
                                        offset = Offset.Zero
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

@Composable
private fun VideoViewer(url: String, loop: Boolean) {
    var videoView by remember(url) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(url) {
        onDispose { videoView?.stopPlayback() }
    }
    AndroidView(
        factory = { context ->
            VideoView(context).also { view ->
                val controls = MediaController(context)
                controls.setAnchorView(view)
                view.setMediaController(controls)
                view.setVideoURI(Uri.parse(url))
                view.setOnPreparedListener { player ->
                    player.isLooping = loop
                    view.start()
                    controls.show()
                }
                videoView = view
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private const val PAN_SPEED_MULTIPLIER = 2.25f
private const val MAX_SCALE = 5f
private const val MIN_SCALE = 0.5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MIN_DISMISS_SCALE = 1.01f
private const val DISMISS_THRESHOLD = 0.12f
private const val BACKDROP_FADE_SPEED = 1.5f
