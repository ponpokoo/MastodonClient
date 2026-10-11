package io.github.ponpokoo.mastodonclient.feature.media

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun InlineVideo(url: String, loop: Boolean, onDimensionsKnown: (Int, Int) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hostView = LocalView.current
    val dimensionsKnown by rememberUpdatedState(onDimensionsKnown)
    var videoView by remember(url) { mutableStateOf<VideoView?>(null) }
    var prepared by remember(url) { mutableStateOf(false) }
    var requested by remember(url) { mutableStateOf(false) }
    var completed by remember(url) { mutableStateOf(false) }
    var started by remember(url) { mutableStateOf(false) }
    var visible by remember(url) { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val enabled = prepared && visible && resumed
    DisposableEffect(lifecycle, url) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) {
                videoView?.pause()
                requested = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled, videoView) {
        if (!enabled) {
            videoView?.pause()
            requested = false
        } else if (!started) {
            started = true
            requested = true
            videoView?.start()
        }
    }
    Box(Modifier.fillMaxSize().testTag("inline_video")
        .semantics { stateDescription = if (requested) "再生中" else "一時停止" }
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.right > 0 &&
                bounds.top < hostView.height && bounds.left < hostView.width
        }) {
        key(url) {
            AndroidView(factory = { context ->
                VideoView(context).also { view ->
                    videoView = view
                    view.setVideoURI(Uri.parse(url))
                    view.setOnPreparedListener { player ->
                        dimensionsKnown(player.videoWidth, player.videoHeight)
                        player.isLooping = loop
                        player.setVolume(0f, 0f)
                        prepared = true
                        // Surface recreation must resume a requested playback, but not a manual pause.
                        if (requested && visible && resumed) view.start()
                    }
                    view.setOnCompletionListener {
                        if (!loop) {
                            requested = false
                            completed = true
                        }
                    }
                }
            }, onRelease = { view ->
                view.setOnPreparedListener(null)
                view.setOnCompletionListener(null)
                view.stopPlayback()
            }, modifier = Modifier.fillMaxSize())
        }
        Surface(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp), shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
            IconButton(enabled = enabled, modifier = Modifier.size(48.dp), onClick = {
                if (requested) {
                    videoView?.pause()
                    requested = false
                } else {
                    if (completed) videoView?.seekTo(0)
                    completed = false
                    started = true
                    requested = true
                    videoView?.start()
                }
            }) {
                MediaPlaybackIcon(playing = requested, label = "動画")
            }
        }
    }
}
