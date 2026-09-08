package io.github.ponpokoo.mastodonclient.feature.media

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage

@Composable
fun MediaViewerScreen(
    url: String,
    type: String,
    description: String?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).testTag("media_viewer"),
        contentAlignment = Alignment.Center,
    ) {
        if (type == "video" || type == "gifv") {
            VideoViewer(url, loop = type == "gifv")
        } else {
            ZoomableImage(url, description)
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
    }
}

@Composable
private fun ZoomableImage(url: String, description: String?) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale == 1f) {
            Offset.Zero
        } else {
            offset + panChange * PAN_SPEED_MULTIPLIER
        }
    }
    AsyncImage(
        model = url,
        contentDescription = description ?: "添付画像",
        modifier = Modifier.fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )
            .transformable(transformState),
        contentScale = ContentScale.Fit,
    )
}

private const val PAN_SPEED_MULTIPLIER = 2.25f

@Composable
private fun VideoViewer(url: String, loop: Boolean) {
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) {
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
