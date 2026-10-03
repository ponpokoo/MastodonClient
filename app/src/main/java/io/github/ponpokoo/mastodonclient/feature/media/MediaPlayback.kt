package io.github.ponpokoo.mastodonclient.feature.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.delay

// Audio and full-screen video share one foreground playback owner. Main-thread only.
private object MediaPlaybackOwner {
    private var active: Player? = null
    fun activate(player: Player) {
        if (active !== player) active?.pause()
        active = player
    }
    fun detach(player: Player) {
        if (active === player) active = null
    }
}

internal class MediaPlayback(private val context: Context, val url: String?, private val loop: Boolean) {
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set
    var requested by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var seekable by mutableStateOf(false)
        private set
    var videoAspectRatio by mutableStateOf(16f / 9f)
        private set
    var volume by mutableStateOf(1f)
        private set
    private var audibleVolume = 1f
    private var seekedToEnd = false
    var controlsEnabled by mutableStateOf(false)
        internal set
    var visibilityModifier: Modifier = Modifier
        internal set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Seeking after completion must not silently restart playback.
            if (playbackState == Player.STATE_ENDED) player?.pause()
        }
        override fun onEvents(player: Player, events: Player.Events) { update() }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun prepare() {
        if (url == null || player != null) return
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .build().also {
                it.addListener(listener)
                it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                it.setHandleAudioBecomingNoisy(true)
                it.volume = volume
                it.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                it.setMediaItem(MediaItem.fromUri(url))
            }
    }

    fun play() {
        if (!controlsEnabled) return
        prepare()
        player?.let {
            MediaPlaybackOwner.activate(it)
            if (seekedToEnd || it.playbackState == Player.STATE_ENDED) it.seekTo(0)
            seekedToEnd = false
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
        update()
    }

    fun pause() { player?.pause(); update() }
    fun togglePlayback() { if (requested) pause() else play() }

    fun seekTo(milliseconds: Long) {
        if (!seekable || !controlsEnabled) return
        val target = milliseconds.coerceIn(0, duration)
        seekedToEnd = target == duration && player?.playWhenReady == false
        player?.seekTo(target)
        position = target
        update()
    }

    fun skip(milliseconds: Long) { seekTo((player?.currentPosition ?: position) + milliseconds) }

    fun changeVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        if (volume > 0f) audibleVolume = volume
        player?.volume = volume
    }

    fun toggleMute() { changeVolume(if (volume == 0f) audibleVolume else 0f) }

    fun update() {
        val current = player ?: return
        position = current.currentPosition.coerceAtLeast(0)
        duration = current.duration.takeIf { it > 0 } ?: 0L
        ended = current.playbackState == Player.STATE_ENDED
        failed = current.playerError != null
        requested = current.playWhenReady && !ended && !failed
        playing = current.isPlaying
        buffering = current.playbackState == Player.STATE_BUFFERING
        seekable = !failed && duration > 0 && current.isCurrentMediaItemSeekable &&
            current.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        val size = current.videoSize
        if (size.width > 0 && size.height > 0) {
            videoAspectRatio = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
        }
    }

    fun release() {
        player?.let {
            MediaPlaybackOwner.detach(it)
            it.removeListener(listener)
            it.release()
        }
        player = null
    }
}

@Composable
internal fun rememberMediaPlayback(url: String?, active: Boolean = true, autoplay: Boolean = false,
    loop: Boolean = false): MediaPlayback {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val playback = remember(context, url, loop) { MediaPlayback(context, url?.takeIf(String::isNotBlank), loop) }
    var visible by remember(playback) { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var started by remember(playback) { mutableStateOf(false) }
    playback.controlsEnabled = active && visible && resumed
    playback.visibilityModifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.right > 0 &&
            bounds.top < view.height && bounds.left < view.width
    }
    DisposableEffect(playback) { onDispose { playback.release() } }
    DisposableEffect(playback, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) playback.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playback, active, visible, resumed) {
        if (!playback.controlsEnabled) playback.pause()
        else if (autoplay && !started) { started = true; playback.play() }
    }
    LaunchedEffect(playback, playback.player) {
        while (playback.player != null) { playback.update(); delay(250) }
    }
    return playback
}
