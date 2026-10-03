package io.github.ponpokoo.mastodonclient.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AudioPlayer(attachment: MediaAttachment, modifier: Modifier = Modifier, active: Boolean = true,
    authorAvatarUrl: String? = attachment.authorAvatarUrl) {
    // A preview image must never become the audio source.
    val playback = rememberMediaPlayback(attachment.url, active = active)
    AudioPlayerContent(attachment, playback, modifier, authorAvatarUrl)
}

@Composable
internal fun AudioPlayerContent(attachment: MediaAttachment, playback: MediaPlayback,
    modifier: Modifier = Modifier, authorAvatarUrl: String? = attachment.authorAvatarUrl) {
    val context = LocalContext.current
    var artwork by remember(authorAvatarUrl) { mutableStateOf<Image?>(null) }
    var background by remember(authorAvatarUrl) { mutableStateOf(AudioFallbackBackground) }
    var seekFraction by remember(playback) { mutableStateOf<Float?>(null) }
    val canSeek = playback.seekable && playback.controlsEnabled
    LaunchedEffect(canSeek) { if (!canSeek) seekFraction = null }
    LaunchedEffect(artwork) {
        val image = artwork ?: return@LaunchedEffect
        background = withContext(Dispatchers.Default) { audioArtworkBackground(image) }
    }
    val avatarRequest = remember(context, authorAvatarUrl) {
        ImageRequest.Builder(context).data(authorAvatarUrl).size(256).allowHardware(false).build()
    }
    Surface(modifier.fillMaxWidth().testTag("audio_attachment").then(playback.visibilityModifier)
        .semantics { stateDescription = mediaPlaybackStatus(playback, "音声") },
        shape = RoundedCornerShape(16.dp), color = background, contentColor = Color.White) {
        BoxWithConstraints(Modifier.fillMaxWidth()
            .semantics { contentDescription = attachment.description?.takeIf(String::isNotBlank) ?: "添付音声" }) {
            // Combining heightIn and aspectRatio can centre a shorter layout inside the card.
            // Give the artwork and bottom controls the same actual height instead.
            val cardHeight = (maxWidth / 1.67f).coerceAtLeast(220.dp).coerceAtMost(maxHeight)
            Box(Modifier.fillMaxWidth().height(cardHeight)) {
                val avatarSize = if (cardHeight < 200.dp) 72.dp else 96.dp
                Row(Modifier.align(Alignment.Center).offset(y = (-16).dp).fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    AudioSkipButton(5, backwards = true, enabled = canSeek) { playback.skip(-5_000) }
                    Box(Modifier.size(avatarSize).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center) {
                        if (artwork == null) Icon(Icons.Outlined.Person, contentDescription = "投稿者アイコンの代替表示",
                            modifier = Modifier.size(56.dp).testTag("audio_avatar_placeholder"), tint = Color.White.copy(alpha = 0.6f))
                        if (!authorAvatarUrl.isNullOrBlank()) AsyncImage(
                            model = avatarRequest, contentDescription = "音声の投稿者アイコン",
                            modifier = Modifier.size(avatarSize).testTag("audio_author_avatar"), contentScale = ContentScale.Crop,
                            onSuccess = { artwork = it.result.image }, onError = { artwork = null },
                        )
                        Box(Modifier.size(avatarSize).background(Color.Black.copy(alpha = 0.25f)))
                        IconButton(onClick = playback::togglePlayback,
                            enabled = playback.url != null && playback.controlsEnabled,
                            modifier = Modifier.size(64.dp)) {
                            MediaPlaybackIcon(playing = playback.requested, label = "音声")
                        }
                    }
                    AudioSkipButton(10, backwards = false, enabled = canSeek) { playback.skip(10_000) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp)) {
                    MediaSlider(
                        value = seekFraction ?: if (playback.duration > 0) (playback.position.toFloat() / playback.duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { seekFraction = it }, onValueChangeFinished = {
                            seekFraction?.let { playback.seekTo((it * playback.duration).toLong()) }
                            seekFraction = null
                        }, enabled = canSeek, trackOffset = 18.dp,
                        modifier = Modifier.fillMaxWidth().testTag("media_seek").semantics { contentDescription = "再生位置" },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val displayedPosition = seekFraction?.let { (it * playback.duration).toLong() } ?: playback.position
                            Text("${mediaTime(displayedPosition)} / ${mediaTime(playback.duration)}", style = MaterialTheme.typography.labelMedium)
                            if (playback.failed || playback.url == null) {
                                Text(mediaPlaybackStatus(playback, "音声"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        MediaVolumeControl(playback, background, label = "音声", popupTag = "audio_volume_popup")
                    }
                }
            }
        }
    }
}

private val AudioFallbackBackground = Color(0xFF243746)

// At most 32 x 32 samples; this runs off the UI thread on a software image.
private fun audioArtworkBackground(image: Image): Color {
    val bitmap = image.toBitmap(32, 32)
    var red = 0L; var green = 0L; var blue = 0L; var weight = 0L
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val pixel = bitmap.getPixel(x, y)
        val alpha = android.graphics.Color.alpha(pixel)
        red += android.graphics.Color.red(pixel).toLong() * alpha
        green += android.graphics.Color.green(pixel).toLong() * alpha
        blue += android.graphics.Color.blue(pixel).toLong() * alpha
        weight += alpha
    }
    if (weight == 0L) return AudioFallbackBackground
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt(), hsv)
    hsv[1] = hsv[1].coerceAtMost(0.65f)
    hsv[2] = 0.27f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
