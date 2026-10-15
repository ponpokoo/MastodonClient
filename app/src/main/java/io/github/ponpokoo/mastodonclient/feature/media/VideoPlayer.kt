package io.github.ponpokoo.mastodonclient.feature.media

import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun VideoPlayer(url: String?, loop: Boolean, active: Boolean,
    controlsVisible: Boolean = true, onTap: () -> Unit = {},
    fallbackPainter: Painter? = null, previewUrl: String? = null) {
    val playback = rememberMediaPlayback(url, active = active, autoplay = true, loop = loop)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("video_player").then(playback.visibilityModifier)
        .semantics { stateDescription = mediaPlaybackStatus(playback, "動画") }) {
        val landscape = maxWidth > maxHeight
        val bottomPadding = if (landscape) 0.dp else if (maxHeight < 360.dp) 8.dp else 56.dp
        playback.player?.let { player ->
            key(player) {
                AndroidView(factory = { context -> TextureView(context).also {
                    player.setVideoTextureView(it)
                } },
                    onRelease = {
                        if (playback.player === player) player.clearVideoTextureView(it)
                    },
                    modifier = Modifier.align(Alignment.Center)
                        .width(minOf(maxWidth, maxHeight * playback.videoAspectRatio)).aspectRatio(playback.videoAspectRatio)
                        .testTag("video_surface"))
            }
        }
        if (!playback.videoFrameRendered) {
            if (fallbackPainter != null) Image(fallbackPainter, contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("video_preview"), contentScale = ContentScale.Fit)
            else if (previewUrl != null) AsyncImage(previewUrl, contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("video_preview"), contentScale = ContentScale.Fit)
        }
        // Keep this above the video and below the controls. Drags still reach the pager.
        Box(Modifier.matchParentSize().testTag("video_tap_target").pointerInput(active, onTap) {
            detectTapGestures { if (active) onTap() }
        })
        if (controlsVisible) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter)
                // Keep both the fixed buttons and their upward popups inside camera/cutout insets.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .padding(horizontal = if (landscape) 8.dp else 16.dp).padding(bottom = bottomPadding)
                .testTag("video_controls").pointerInput(Unit) {
                    // Empty space and disabled buttons inside the control band must not hide it.
                    detectTapGestures { }
                }, color = Color.Black.copy(alpha = 0.8f), contentColor = Color.White) {
                Box(Modifier.padding(horizontal = 8.dp)) {
                    MediaPlaybackControls(playback, label = "動画", compact = true)
                }
            }
        }
    }
}
