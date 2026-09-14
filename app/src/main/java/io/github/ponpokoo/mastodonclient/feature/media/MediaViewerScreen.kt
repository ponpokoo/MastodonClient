package io.github.ponpokoo.mastodonclient.feature.media

import android.net.Uri
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

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
    val hiddenPages = remember { mutableStateMapOf<Int, Boolean>() }
    val currentScale = pageScales[pagerState.currentPage] ?: 1f
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                if (item.sensitive && hiddenPages[page] == true) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { hiddenPages[page] = false }) {
                            Text("閲覧注意のメディアを表示", color = Color.White)
                        }
                    }
                } else if (item.type == "video" || item.type == "gifv") {
                    LaunchedEffect(page) { pageScales[page] = 1f }
                    VideoViewer(item.url ?: item.previewUrl.orEmpty(), loop = item.type == "gifv")
                } else {
                    ZoomableImage(
                        url = item.url ?: item.previewUrl.orEmpty(),
                        previewUrl = item.previewUrl,
                        description = item.description,
                        onScaleChanged = { pageScales[page] = it },
                    )
                }
            }

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

            val currentItem = media[pagerState.currentPage]
            if (currentItem.sensitive && hiddenPages[pagerState.currentPage] != true) {
                TextButton(
                    onClick = { hiddenPages[pagerState.currentPage] = true },
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(8.dp),
                ) {
                    Text("再び隠す", color = Color.White)
                }
            }

            if (media.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${media.size}",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                )
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
) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var imageSize by remember(url) { mutableStateOf(IntSize.Zero) }
    var originalLoaded by remember(url) { mutableStateOf(false) }
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
            onSuccess = { originalLoaded = true },
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
                        onDoubleTap = { tapPosition ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_SCALE
                                offset = Offset(
                                    x = (imageSize.width / 2f - tapPosition.x) * (DOUBLE_TAP_SCALE - 1f),
                                    y = (imageSize.height / 2f - tapPosition.y) * (DOUBLE_TAP_SCALE - 1f),
                                )
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
                                    offset + panChange * PAN_SPEED_MULTIPLIER
                                }
                                event.changes.forEach { change -> change.consume() }
                            }
                            if (event.changes.none { it.pressed }) break
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
private const val DISMISS_THRESHOLD = 0.2f
