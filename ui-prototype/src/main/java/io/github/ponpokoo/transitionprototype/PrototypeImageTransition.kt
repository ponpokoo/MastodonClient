package io.github.ponpokoo.transitionprototype

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import io.github.ponpokoo.mastodonclient.feature.media.BACKDROP_FADE_SPEED
import androidx.compose.foundation.layout.WindowInsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

internal val LocalPrototypeMotion = staticCompositionLocalOf<PrototypeMediaMotionState?> { null }
internal val LocalPrototypeThumbnailOwner = staticCompositionLocalOf { "main" }
internal val LocalPrototypeInsets = staticCompositionLocalOf<WindowInsets> { error("Prototype insets provider missing") }

internal val PrototypeMotionPhase = SemanticsPropertyKey<String>("PrototypeMotionPhase")
internal val PrototypeImageTransitionActive = SemanticsPropertyKey<Boolean>("PrototypeImageTransitionActive")
internal val PrototypeImageFit = SemanticsPropertyKey<Float>("PrototypeImageFit")
internal val PrototypeImageCorners = SemanticsPropertyKey<Float>("PrototypeImageCorners")
internal val PrototypeTransitionImage = SemanticsPropertyKey<String>("PrototypeTransitionImage")
internal val PrototypeImageBounds = SemanticsPropertyKey<Rect>("PrototypeImageBounds")
internal enum class MediaPhase { Closed, Opening, Viewing, ResettingZoom, Rounding, Closing }

@Stable
internal class PrototypeMediaMotionState(tootId: String? = null, index: Int = 0) {
    var tootId by mutableStateOf(tootId)
    var page by mutableIntStateOf(index)
    var phase by mutableStateOf(if (tootId == null) MediaPhase.Closed else MediaPhase.Viewing)
    var expanded by mutableStateOf(tootId != null)
    var media by mutableStateOf(PrototypeToots.firstOrNull { it.statusId == tootId }?.mediaAttachments.orEmpty())
    val fit = Animatable(if (tootId == null) 0f else 1f)
    val backdrop = Animatable(if (tootId == null) 0f else 1f)
    val corners = Animatable(if (tootId == null) 8f else 0f)
    var bounds by mutableStateOf(Animatable(Rect.Zero, Rect.VectorConverter, Rect(0.5f, 0.5f, 0.5f, 0.5f)))
    val thumbnailBounds = mutableStateMapOf<String, Rect>()
    var sourceOwner by mutableStateOf("main/Home")
    val selectedThumbnailBounds: Rect? get() = thumbnailBounds["$sourceOwner:$selectedKey"]
    var viewerSize by mutableStateOf(IntSize.Zero)
    var dragOffset by mutableFloatStateOf(0f)
    var selectedKey by mutableStateOf(
        media.getOrNull(index)?.let(::imageKey) ?: imageKey(PrototypeToots.first().mediaAttachments.first()),
    )
    val active: Boolean get() = phase != MediaPhase.Closed
    val fullBounds: Rect get() = Rect(0f, 0f, viewerSize.width.toFloat(), viewerSize.height.toFloat())

    fun open(attachments: List<MediaAttachment>, index: Int) {
        if (active) return
        if (attachments.isEmpty()) return
        selectedKey = imageKey(attachments[index.coerceIn(attachments.indices)])
        val source = selectedThumbnailBounds
        media = attachments
        tootId = selectedKey.substringBefore('/')
        if (source == null) {
            page = index.coerceIn(attachments.indices)
            expanded = true
            phase = MediaPhase.Viewing
            return
        }
        bounds = Animatable(source, Rect.VectorConverter, Rect(0.5f, 0.5f, 0.5f, 0.5f))
        page = index
        expanded = true
        phase = MediaPhase.Opening
    }

    fun close() {
        if (phase == MediaPhase.Opening || phase == MediaPhase.Viewing) phase = MediaPhase.ResettingZoom
    }

    companion object {
        val Saver = listSaver<PrototypeMediaMotionState, Any>(
            save = { listOf(if (it.phase in listOf(MediaPhase.ResettingZoom, MediaPhase.Rounding, MediaPhase.Closing)) "" else it.tootId.orEmpty(), it.page,
                Json.encodeToString(it.media), it.sourceOwner) },
            restore = { PrototypeMediaMotionState((it[0] as String).ifEmpty { null }, it[1] as Int).apply {
                (it.getOrNull(2) as? String)?.let { saved -> media = Json.decodeFromString<List<MediaAttachment>>(saved) }
                sourceOwner = it.getOrNull(3) as? String ?: "main/Home"
                selectedKey = media.getOrNull(page)?.let(::imageKey).orEmpty()
            } },
        )
    }
}

internal fun imageKey(image: MediaAttachment) = "${PrototypeToots.firstOrNull { post -> post.mediaAttachments.any { it.id == image.id } }?.statusId ?: "profile"}/${image.id}"

@Composable
internal fun Modifier.prototypeThumbnail(media: MediaAttachment, index: Int): Modifier {
    val motion = LocalPrototypeMotion.current ?: return this
    val owner = LocalPrototypeThumbnailOwner.current
    val key = imageKey(media)
    return onGloballyPositioned { motion.thumbnailBounds["$owner:$key"] = Rect(it.positionInRoot(), it.size.toSize()) }
        .testTag("toot_${key.substringBefore('/')}_media_$index")
        .graphicsLayer { alpha = if (motion.active && owner == motion.sourceOwner && key == motion.selectedKey) 0f else 1f }
}

@Composable
internal fun prototypeThumbnailSource(): () -> Unit {
    val motion = LocalPrototypeMotion.current
    val owner = LocalPrototypeThumbnailOwner.current
    return { motion?.sourceOwner = owner }
}

// Restore Crop -> Fit and zoom -> corners -> spring close in viewport coordinates.
// Keep the image outside LazyColumn and the pager, so scrolling and paging do not
// add their placement offsets to the picture or start another opening transition.
@Composable
internal fun PrototypeMediaOverlay(motion: PrototypeMediaMotionState) {
    LaunchedEffect(motion.expanded, motion.viewerSize) {
        if (!motion.active) return@LaunchedEffect
        if (motion.phase == MediaPhase.Viewing) {
            motion.bounds.snapTo(motion.fullBounds)
            motion.fit.snapTo(1f)
            motion.backdrop.snapTo(1f)
            motion.corners.snapTo(0f)
            return@LaunchedEffect
        }
        coroutineScope {
            launch { motion.bounds.animateTo(if (motion.expanded) motion.fullBounds else motion.selectedThumbnailBounds ?: motion.fullBounds,
                if (motion.expanded) tween(350) else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 900f)) }
            launch { motion.fit.animateTo(if (motion.expanded) 1f else 0f,
                if (motion.expanded) tween(350) else spring(dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = 900f, visibilityThreshold = 0.0001f)) }
            launch { motion.backdrop.animateTo(if (motion.expanded) 1f else 0f, tween(350)) }
        }
    }
    LaunchedEffect(motion.phase) {
        when (motion.phase) {
            MediaPhase.Opening -> {
                motion.corners.animateTo(0f, tween(350))
                snapshotFlow { motion.fit.value == 1f && motion.bounds.value == motion.fullBounds && !motion.bounds.isRunning }.first { it }
                motion.phase = MediaPhase.Viewing
            }
            MediaPhase.Rounding -> {
                motion.corners.animateTo(8f, tween(110))
                motion.expanded = false
                motion.phase = MediaPhase.Closing
            }
            MediaPhase.Closing -> {
                snapshotFlow { motion.fit.value == 0f && motion.backdrop.value == 0f && !motion.bounds.isRunning }.first { it }
                motion.phase = MediaPhase.Closed
                motion.tootId = null
            }
            else -> Unit
        }
    }
    val drawTransition = motion.phase in listOf(MediaPhase.Opening, MediaPhase.Rounding, MediaPhase.Closing)
    val fade = (1f - motion.dragOffset / motion.viewerSize.height.coerceAtLeast(1) * BACKDROP_FADE_SPEED).coerceIn(0f, 1f)
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().zIndex(if (motion.active) 1f else -1f)
        .pointerInput(motion.active) {
            if (motion.active) awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
            }
        }
        .onSizeChanged { motion.viewerSize = it }
        .background(Color.Black.copy(alpha = motion.backdrop.value * fade))
        .testTag("prototype_media_motion").semantics {
            this[PrototypeMotionPhase] = motion.phase.name
            this[PrototypeImageTransitionActive] = motion.bounds.isRunning
            this[PrototypeImageFit] = motion.fit.value
            this[PrototypeImageCorners] = motion.corners.value
            this[PrototypeTransitionImage] = motion.selectedKey
            this[PrototypeImageBounds] = motion.bounds.value
            if (!motion.active) hideFromAccessibility()
        }) {
        if (motion.active && motion.media.isNotEmpty()) {
            val image = motion.media.firstOrNull { imageKey(it) == motion.selectedKey } ?: motion.media[motion.page]
            if (drawTransition) {
                val painter = painterResource(fixtureImageResource(image))
                val bounds = motion.bounds.value
                val scale = PrototypeCropFitScale(motion.fit.value,
                    motion.selectedThumbnailBounds?.size ?: Size.Zero, motion.viewerSize.toSize())
                Image(painter, null, contentScale = scale,
                    modifier = Modifier.offset(with(density) { bounds.left.toDp() }, with(density) { bounds.top.toDp() })
                        .size(with(density) { bounds.width.coerceAtLeast(0f).toDp() }, with(density) { bounds.height.coerceAtLeast(0f).toDp() })
                        .clip(PrototypeImageShape(painter.intrinsicSize, scale, motion.corners.value))
                        .semantics { hideFromAccessibility() })
            }
            key(motion.tootId) { MediaViewerScreen(motion.media, motion) }
        }
    }
}

internal class PrototypeImageShape(private val source: Size, private val scale: ContentScale, private val corners: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val factor = scale.computeScaleFactor(source, size)
        val width = minOf(size.width, source.width * factor.scaleX)
        val height = minOf(size.height, source.height * factor.scaleY)
        val x = (size.width - width) / 2f
        val y = (size.height - height) / 2f
        return Outline.Rounded(RoundRect(Rect(x, y, x + width, y + height), CornerRadius(with(density) { corners.dp.toPx() })))
    }
}

private class PrototypeCropFitScale(private val fraction: Float, private val thumbnail: Size, private val viewer: Size) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        if (thumbnail == Size.Zero || viewer == Size.Zero) return ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
        val crop = ContentScale.Crop.computeScaleFactor(srcSize, thumbnail)
        val fit = ContentScale.Fit.computeScaleFactor(srcSize, viewer)
        return ScaleFactor(crop.scaleX + (fit.scaleX - crop.scaleX) * fraction, crop.scaleY + (fit.scaleY - crop.scaleY) * fraction)
    }
}
