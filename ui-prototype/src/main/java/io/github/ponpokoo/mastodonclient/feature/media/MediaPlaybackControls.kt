package io.github.ponpokoo.mastodonclient.feature.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun MediaPlaybackControls(playback: MediaPlayback, label: String, compact: Boolean = false) {
    var seekFraction by remember(playback) { mutableStateOf<Float?>(null) }
    val duration = playback.duration
    val canSeek = playback.seekable && playback.controlsEnabled
    LaunchedEffect(canSeek) { if (!canSeek) seekFraction = null }
    val displayedPosition = seekFraction?.let { (it * duration).toLong() } ?: playback.position
    val seekValue = seekFraction ?: if (duration > 0) (playback.position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val onSeekChange: (Float) -> Unit = { seekFraction = it }
    val onSeekFinished: () -> Unit = {
        seekFraction?.let { playback.seekTo((it * playback.duration).toLong()) }
        seekFraction = null
    }
    val seekModifier = Modifier.testTag("media_seek").semantics { contentDescription = "再生位置" }
    Column(Modifier.fillMaxWidth()) {
        if (!compact) {
            MediaSlider(value = seekValue, onValueChange = onSeekChange, onValueChangeFinished = onSeekFinished,
                enabled = canSeek, trackOffset = 18.dp, modifier = Modifier.fillMaxWidth().then(seekModifier))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = playback.url != null && playback.controlsEnabled, onClick = playback::togglePlayback) {
                MediaPlaybackIcon(playing = playback.requested, label = label)
            }
            Text("${mediaTime(displayedPosition)} / ${mediaTime(duration)}",
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                modifier = (if (compact) Modifier else Modifier.weight(1f)).padding(horizontal = 4.dp))
            if (compact) {
                MediaSlider(value = seekValue, onValueChange = onSeekChange, onValueChangeFinished = onSeekFinished,
                    enabled = canSeek, modifier = Modifier.weight(1f).then(seekModifier))
                Spacer(Modifier.width(8.dp))
            }
            MediaVolumeControl(playback, background = Color(0xFF243746), label = label,
                popupTag = "video_volume_popup", muteOnRepeatedTap = true)
        }
        if (playback.failed || playback.url == null) {
            Text(mediaPlaybackStatus(playback, label), style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun mediaPlaybackStatus(playback: MediaPlayback, label: String): String = when {
    playback.url == null -> "${label}URLがありません"
    playback.failed -> "${label}を再生できません。再試行してください"
    playback.ended -> "再生終了"
    playback.buffering && playback.requested -> "読み込み中"
    playback.playing -> "再生中"
    else -> "一時停止"
}

internal fun mediaTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
