package io.github.ponpokoo.mastodonclient.feature.profile

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import io.github.ponpokoo.mastodonclient.domain.repository.ProfileImageCrop
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 4f

@Composable
internal fun ProfileImageCropDialog(
    pending: PendingProfileImageCrop,
    onCancel: () -> Unit,
    onConfirm: (ProfileImageCrop) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(pending.path) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(pending.path) { mutableStateOf(false) }
    LaunchedEffect(pending.path) {
        bitmap = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context).data(File(pending.path))
                .size(1536).scale(Scale.FIT).allowHardware(false).build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
            result?.image?.toBitmap()
        }
        loadFailed = bitmap == null
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            val title = if (pending.slot == ProfileImageSlot.Avatar) "アイコンの切り出し" else "ヘッダーの切り出し"
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val image = bitmap
                if (image != null) {
                    CropControls(title, pending, image, onCancel, onConfirm)
                } else {
                    CropToolbar(title, onCancel, confirmEnabled = false, onConfirm = {})
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loadFailed) Text("画像を開けませんでした", color = MaterialTheme.colorScheme.error)
                        else CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.CropControls(
    title: String,
    pending: PendingProfileImageCrop,
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (ProfileImageCrop) -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val imageSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    val aspect = pending.slot.aspectRatio.toFloat()
    val density = LocalDensity.current
    val margin = with(density) { 20.dp.toPx() }
    val geometry = remember(viewport, imageSize, aspect, margin) { CropGeometry(viewport, imageSize, aspect, margin) }
    fun setZoom(newZoom: Float, anchor: Offset, delta: Offset = Offset.Zero) {
        val nextZoom = newZoom.coerceIn(1f, MAX_ZOOM)
        val nextPan = (pan - anchor) * (nextZoom / zoom) + anchor + delta
        zoom = nextZoom
        pan = geometry.clamp(nextPan, nextZoom)
    }

    CropToolbar(title, onCancel, geometry.isReady && !pending.isProcessing) {
        onConfirm(geometry.selection(pan, zoom))
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val processing by rememberUpdatedState(pending.isProcessing)
    Canvas(
        Modifier.fillMaxWidth().weight(1f).background(Color.Black).clipToBounds()
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(geometry) {
                detectTransformGestures { centroid, delta, zoomChange, _ ->
                    if (!processing && geometry.isReady) {
                        setZoom(zoom * zoomChange, centroid - geometry.frame.center, delta)
                    }
                }
            },
    ) {
        if (!geometry.isReady) return@Canvas
        val bounds = geometry.imageBounds(pan, zoom)
        withTransform({
            translate(bounds.left, bounds.top)
            scale(bounds.width / image.width, bounds.height / image.height, pivot = Offset.Zero)
        }) {
            drawImage(image)
        }
        val frame = geometry.frame
        val shade = Color.Black.copy(alpha = 0.62f)
        drawRect(shade, size = Size(size.width, frame.top))
        drawRect(shade, Offset(0f, frame.bottom), Size(size.width, size.height - frame.bottom))
        drawRect(shade, Offset(0f, frame.top), Size(frame.left, frame.height))
        drawRect(shade, Offset(frame.right, frame.top), Size(size.width - frame.right, frame.height))
        drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(1.dp.toPx()))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        pending.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("拡大率", Modifier.weight(1f))
            TextButton(enabled = !pending.isProcessing && (zoom != 1f || pan != Offset.Zero), onClick = {
                zoom = 1f
                pan = Offset.Zero
            }) { Text("リセット") }
        }
        Slider(
            value = zoom,
            onValueChange = { setZoom(it, Offset.Zero) },
            valueRange = 1f..MAX_ZOOM,
            enabled = geometry.isReady && !pending.isProcessing,
            modifier = Modifier.semantics { contentDescription = "拡大率" },
        )
        if (pending.isProcessing) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun CropToolbar(title: String, onCancel: () -> Unit, confirmEnabled: Boolean, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text("キャンセル") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text("決定") }
    }
}

internal class CropGeometry(viewport: Size, private val image: Size, aspect: Float, margin: Float) {
    private val frameWidth = minOf(viewport.width - 2 * margin, (viewport.height - 2 * margin) * aspect).coerceAtLeast(0f)
    private val frameHeight = frameWidth / aspect
    val frame = Rect(Offset((viewport.width - frameWidth) / 2, (viewport.height - frameHeight) / 2), Size(frameWidth, frameHeight))
    val isReady = frameWidth > 0f
    private val baseScale = maxOf(frameWidth / image.width, frameHeight / image.height)

    fun clamp(pan: Offset, zoom: Float): Offset {
        val scale = baseScale * zoom
        val x = ((image.width * scale - frame.width) / 2).coerceAtLeast(0f)
        val y = ((image.height * scale - frame.height) / 2).coerceAtLeast(0f)
        return Offset(pan.x.coerceIn(-x, x), pan.y.coerceIn(-y, y))
    }

    fun imageBounds(pan: Offset, zoom: Float): Rect {
        val scale = baseScale * zoom
        val scaled = Size(image.width * scale, image.height * scale)
        val center = frame.center + clamp(pan, zoom)
        return Rect(center - Offset(scaled.width / 2, scaled.height / 2), scaled)
    }

    fun selection(pan: Offset, zoom: Float): ProfileImageCrop {
        val bounds = imageBounds(pan, zoom)
        return ProfileImageCrop(
            (frame.left - bounds.left) / bounds.width,
            (frame.top - bounds.top) / bounds.height,
            (frame.right - bounds.left) / bounds.width,
            (frame.bottom - bounds.top) / bounds.height,
        )
    }
}
