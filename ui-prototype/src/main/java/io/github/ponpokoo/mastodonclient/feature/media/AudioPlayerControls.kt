package io.github.ponpokoo.mastodonclient.feature.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

@Composable
internal fun AudioSkipButton(seconds: Int, backwards: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        val color = LocalContentColor.current
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(40.dp).graphicsLayer { scaleX = if (backwards) 1f else -1f }
                .semantics { contentDescription = if (backwards) "${seconds}秒戻る" else "${seconds}秒進む" }) {
                withTransform({ scale(size.width / 40f, size.height / 40f, pivot = Offset.Zero) }) {
                    val stroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawArc(color, -90f, 300f, useCenter = false, topLeft = Offset(5f, 5f),
                        size = Size(30f, 30f), style = stroke)
                    drawPath(Path().apply {
                        moveTo(7f, 5.5f); lineTo(7f, 12.5f); lineTo(14f, 12.5f)
                    }, color, style = stroke)
                }
            }
            Text(seconds.toString(), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
internal fun MediaPlaybackIcon(playing: Boolean, label: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(32.dp).semantics {
        contentDescription = if (playing) "${label}を一時停止" else "${label}を再生"
    }) {
        withTransform({ scale(size.width / 32f, size.height / 32f, pivot = Offset.Zero) }) {
            if (playing) {
                drawLine(color, Offset(11f, 6f), Offset(11f, 26f), 2.5f, StrokeCap.Round)
                drawLine(color, Offset(21f, 6f), Offset(21f, 26f), 2.5f, StrokeCap.Round)
            } else {
                drawPath(Path().apply {
                    moveTo(8f, 4f); lineTo(27f, 16f); lineTo(8f, 28f); close()
                }, color, style = Stroke(2f, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun MediaVolumeIcon(muted: Boolean, label: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp).semantics {
        contentDescription = "${label}の音量調整"
        stateDescription = if (muted) "ミュート" else "音量あり"
    }) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(Path().apply {
                moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f)
                lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
            }, color, style = stroke)
            if (muted) {
                drawLine(color, Offset(17f, 9f), Offset(22f, 15f), 1.7f, StrokeCap.Round)
                drawLine(color, Offset(22f, 9f), Offset(17f, 15f), 1.7f, StrokeCap.Round)
            } else {
                drawArc(color, -55f, 110f, useCenter = false, topLeft = Offset(9f, 8f),
                    size = Size(8f, 8f), style = stroke)
                drawArc(color, -55f, 110f, useCenter = false, topLeft = Offset(8f, 5f),
                    size = Size(14f, 14f), style = stroke)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, onValueChangeFinished: (() -> Unit)? = null,
    trackThickness: Dp = 5.dp, thumbDiameter: Dp = 10.dp, trackOffset: Dp = 0.dp) {
    val activeColor = Color.White.copy(alpha = if (enabled) 1f else 0.38f)
    val inactiveColor = Color.White.copy(alpha = 0.25f)
    Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished,
        enabled = enabled, modifier = modifier,
        thumb = {
            Box(Modifier.size(width = thumbDiameter, height = 48.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(thumbDiameter).graphicsLayer { translationY = trackOffset.toPx() }
                    .background(activeColor, CircleShape))
            }
        },
        track = {
            // Lower the visible seek line inside its 48 dp touch area, without overlapping footer buttons.
            Canvas(Modifier.fillMaxWidth().height(trackThickness).graphicsLayer { translationY = trackOffset.toPx() }) {
                val y = size.height / 2
                drawLine(inactiveColor, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(activeColor, Offset(0f, y), Offset(size.width * value.coerceIn(0f, 1f), y), size.height, StrokeCap.Round)
            }
        },
    )
}

@Composable
private fun MediaVolumeButton(playback: MediaPlayback, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp),
        enabled = playback.url != null && playback.controlsEnabled) {
        MediaVolumeIcon(muted = playback.volume == 0f, label = label)
    }
}

@Composable
internal fun MediaVolumeControl(playback: MediaPlayback, background: Color, label: String, popupTag: String,
    muteOnRepeatedTap: Boolean = false) {
    var expanded by remember(playback) { mutableStateOf(false) }
    var sliderHeight by remember { mutableStateOf(124.dp) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(playback, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) expanded = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playback.controlsEnabled) { if (!playback.controlsEnabled) expanded = false }
    BackHandler(enabled = expanded) { expanded = false }
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val popupOffset = if (muteOnRepeatedTap) IntOffset.Zero else with(density) { IntOffset(0, -56.dp.roundToPx()) }
    val onVolumeClick: () -> Unit = {
        if (expanded && muteOnRepeatedTap) playback.toggleMute() else expanded = !expanded
    }
    Box(Modifier.size(48.dp).onGloballyPositioned { coordinates ->
        // Keep the popup above its button even when the audio card is near the screen top.
        sliderHeight = with(density) {
            ((coordinates.boundsInWindow().top - statusBarTop).toDp() - 40.dp).coerceIn(48.dp, 124.dp)
        }
    }) {
        if (expanded && muteOnRepeatedTap) Box(Modifier.size(48.dp))
        else MediaVolumeButton(playback, label, onVolumeClick)
        if (expanded) Popup(alignment = Alignment.BottomEnd, offset = popupOffset,
            onDismissRequest = { expanded = false }, properties = PopupProperties(
                focusable = true,
                // Video's replacement footer button must stay at the anchor's screen position.
                // WindowManager clipping can shift the entire popup near landscape system bars.
                // The anchor is already inset and sliderHeight limits the bar above it.
                clippingEnabled = !muteOnRepeatedTap,
            )) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(12.dp),
                    color = Color(background.red * 0.84f, background.green * 0.84f, background.blue * 0.84f),
                    contentColor = Color.White, shadowElevation = 2.dp, modifier = Modifier.testTag(popupTag)) {
                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(playback.volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                        // Rotation includes hit testing, so an upward drag increases the volume.
                        // Lock LTR here even if the rest of the application uses an RTL locale.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Layout(content = {
                                MediaSlider(value = playback.volume, onValueChange = playback::changeVolume,
                                    trackThickness = 3.dp, thumbDiameter = 8.dp,
                                    modifier = Modifier.graphicsLayer { rotationZ = -90f }.testTag("media_volume")
                                        .semantics { contentDescription = "音量" })
                            }) { measurables, _ ->
                                val width = 48.dp.roundToPx()
                                val height = sliderHeight.roundToPx()
                                val slider = measurables.single().measure(Constraints.fixed(height, width))
                                layout(width, height) { slider.place((width - height) / 2, (height - width) / 2) }
                            }
                        }
                    }
                }
                if (muteOnRepeatedTap) {
                    // A focusable popup consumes taps outside its window. Keep the same footer button
                    // inside that window, at its original position, so a real second tap can mute.
                    Spacer(Modifier.height(8.dp))
                    MediaVolumeButton(playback, label, onVolumeClick)
                }
            }
        }
    }
}
