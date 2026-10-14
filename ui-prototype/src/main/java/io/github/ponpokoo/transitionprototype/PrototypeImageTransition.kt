package io.github.ponpokoo.transitionprototype

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import io.github.ponpokoo.mastodonclient.feature.media.BACKDROP_FADE_SPEED
import androidx.compose.foundation.layout.WindowInsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

internal val LocalPrototypeMotion = staticCompositionLocalOf<PrototypeMediaMotionState?> { null }
// Finish at the destination without a spring's overshoot or invisible settling tail.
internal val PrototypeReturnAnimation = tween<Float>(420, easing = FastOutSlowInEasing)
private val PrototypeCloseAnimation = tween<Float>(300, easing = FastOutSlowInEasing)
internal val LocalPrototypeThumbnailOwner = staticCompositionLocalOf { "main" }
internal val LocalPrototypeMainVisible = staticCompositionLocalOf { true }
internal val LocalPrototypeInsets = staticCompositionLocalOf<WindowInsets> { error("Prototype insets provider missing") }
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalPrototypeSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope> {
    error("Prototype shared transition scope missing")
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.prototypeOverlay(zIndex: Float, visible: () -> Boolean): Modifier =
    with(LocalPrototypeSharedTransitionScope.current) {
        renderInSharedTransitionScopeOverlay(zIndexInOverlay = zIndex, renderInOverlay = visible)
    }

@Composable
internal fun Modifier.prototypeForeground(): Modifier {
    val motion = LocalPrototypeMotion.current ?: return this
    val lift = LocalPrototypeMainVisible.current && motion.active
    return prototypeOverlay(3f) { lift }.graphicsLayer {
        // Keep floating controls above the returning picture, revealing them with the timeline.
        alpha = if (lift) 1f - motion.visibleBackdropAlpha else 1f
    }
}

internal val PrototypeMotionPhase = SemanticsPropertyKey<String>("PrototypeMotionPhase")
internal val PrototypeImageTransitionActive = SemanticsPropertyKey<Boolean>("PrototypeImageTransitionActive")
internal val PrototypeImageFit = SemanticsPropertyKey<Float>("PrototypeImageFit")
internal val PrototypeImageCorners = SemanticsPropertyKey<Float>("PrototypeImageCorners")
internal val PrototypeTransitionImage = SemanticsPropertyKey<String>("PrototypeTransitionImage")
internal val PrototypeImageBounds = SemanticsPropertyKey<Rect>("PrototypeImageBounds")
internal val PrototypeImageVisibleBounds = SemanticsPropertyKey<Rect>("PrototypeImageVisibleBounds")
internal val PrototypeViewerImageReady = SemanticsPropertyKey<Boolean>("PrototypeViewerImageReady")
internal enum class MediaPhase { Closed, Opening, HandingOff, Viewing, ResettingZoom, Rounding, Closing }
internal data class PrototypeThumbnailFrame(val bounds: Rect, val cropped: Boolean, val visibleBounds: Rect = bounds)

@Stable
internal class PrototypeMediaMotionState(tootId: String? = null, index: Int = 0) {
    var tootId by mutableStateOf(tootId)
    var page by mutableIntStateOf(index)
    var phase by mutableStateOf(if (tootId == null) MediaPhase.Closed else MediaPhase.Viewing)
    var media by mutableStateOf(PrototypeToots.firstOrNull { it.statusId == tootId }?.mediaAttachments.orEmpty())
    val progress = Animatable(if (tootId == null) 0f else 1f)
    val handoffAlpha = Animatable(if (tootId == null) 1f else 0f)
    val roundedCorners = Animatable(0f)
    val thumbnailFrames = mutableStateMapOf<String, PrototypeThumbnailFrame>()
    private val readyImages = mutableStateMapOf<String, Boolean>()
    var sourceOwner by mutableStateOf("main/Home")
    var transitionThumbnail by mutableStateOf<PrototypeThumbnailFrame?>(null)
        private set
    var transitionViewport by mutableStateOf(Rect.Zero)
        private set
    var roundedReturn by mutableStateOf(false)
        private set
    var viewerSize by mutableStateOf(IntSize.Zero)
    var dragOffset by mutableFloatStateOf(0f)
    private var closingBackdropFactor by mutableFloatStateOf(1f)
    var selectedKey by mutableStateOf(
        media.getOrNull(index)?.let(::imageKey) ?: imageKey(PrototypeToots.first().mediaAttachments.first()),
    )
    val active: Boolean get() = phase != MediaPhase.Closed
    val fullBounds: Rect get() = Rect(0f, 0f, viewerSize.width.toFloat(), viewerSize.height.toFloat())
    val backdropAlpha: Float get() = progress.value.coerceIn(0f, 1f) * closingBackdropFactor
    val visibleBackdropAlpha: Float get() = backdropAlpha *
        (1f - dragOffset / viewerSize.height.coerceAtLeast(1) * BACKDROP_FADE_SPEED).coerceIn(0f, 1f)
    private val shapeProgress: Float get() = progress.value.coerceIn(0f, 1f)
    val cornerRadius: Float get() = if (roundedReturn) roundedCorners.value else 8f * (1f - shapeProgress)
    fun imageReady(id: String): Boolean = readyImages[id] == true
    fun reportImageReady(id: String) {
        if (active && media.any { it.id == id }) readyImages[id] = true
    }

    fun open(attachments: List<MediaAttachment>, index: Int) {
        if (active) return
        if (attachments.isEmpty()) return
        val selectedPage = index.coerceIn(attachments.indices)
        selectedKey = imageKey(attachments[selectedPage])
        transitionThumbnail = thumbnailFrames["$sourceOwner:$selectedKey"]?.takeUnless { it.visibleBounds.isEmpty }
        transitionViewport = fullBounds
        media = attachments
        tootId = selectedKey.substringBefore('/')
        page = selectedPage
        roundedReturn = false
        closingBackdropFactor = 1f
        dragOffset = 0f
        readyImages.clear()
        phase = if (transitionThumbnail == null) MediaPhase.Viewing else MediaPhase.Opening
    }

    fun close(index: Int = page, fromDrag: Boolean = false) {
        if (phase !in listOf(MediaPhase.Opening, MediaPhase.HandingOff, MediaPhase.Viewing)) return
        page = index.coerceIn(media.indices)
        selectedKey = imageKey(media[page])
        // Resolve once, before restoring bars, stopping the pager or resetting zoom.
        transitionThumbnail = thumbnailFrames["$sourceOwner:$selectedKey"]?.takeUnless { it.visibleBounds.isEmpty }
        val releasedDrag = if (fromDrag) dragOffset else 0f
        transitionViewport = fullBounds.translate(Offset(0f, releasedDrag))
        closingBackdropFactor = if (fromDrag) {
            (1f - releasedDrag / viewerSize.height.coerceAtLeast(1) * BACKDROP_FADE_SPEED).coerceIn(0f, 1f)
        } else 1f
        roundedReturn = phase == MediaPhase.Viewing && !fromDrag
        if (fromDrag) dragOffset = 0f
        phase = if (roundedReturn) MediaPhase.ResettingZoom else MediaPhase.Closing
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
internal fun Modifier.prototypeThumbnail(media: MediaAttachment, index: Int, cropped: Boolean = true): Modifier {
    val motion = LocalPrototypeMotion.current ?: return this
    val owner = LocalPrototypeThumbnailOwner.current
    val key = imageKey(media)
    DisposableEffect(motion, owner, key) {
        onDispose { motion.thumbnailFrames.remove("$owner:$key") }
    }
    return onGloballyPositioned {
        val fullBounds = Rect(it.positionInRoot(), it.size.toSize())
        motion.thumbnailFrames["$owner:$key"] = PrototypeThumbnailFrame(fullBounds, cropped, it.boundsInRoot().intersect(fullBounds))
    }
        .testTag("toot_${key.substringBefore('/')}_media_$index")
        .graphicsLayer { alpha = if (motion.active && owner == motion.sourceOwner && key == motion.selectedKey) 0f else 1f }
}

@Composable
internal fun prototypeThumbnailSource(): () -> Unit {
    val motion = LocalPrototypeMotion.current
    val owner = LocalPrototypeThumbnailOwner.current
    return { if (motion?.active == false) motion.sourceOwner = owner }
}

// SharedTransitionLayout owns the common drawing space. Crop/Fit and shape morphs
// need custom drawing: derive every rectangle from the same progress, not separate animations.
@Composable
internal fun PrototypeMediaOverlay(motion: PrototypeMediaMotionState) {
    LaunchedEffect(motion.phase) {
        when (motion.phase) {
            MediaPhase.Opening -> {
                motion.handoffAlpha.snapTo(1f)
                motion.roundedCorners.snapTo(0f)
                motion.progress.snapTo(0f)
                motion.progress.animateTo(1f, tween(300))
                snapshotFlow { motion.imageReady(motion.media[motion.page].id) }.first { it }
                motion.phase = MediaPhase.HandingOff
            }
            MediaPhase.HandingOff -> {
                motion.handoffAlpha.animateTo(0f, tween(100))
                motion.phase = MediaPhase.Viewing
            }
            MediaPhase.Viewing -> {
                motion.progress.snapTo(1f)
                motion.handoffAlpha.snapTo(0f)
                motion.roundedCorners.snapTo(0f)
            }
            MediaPhase.Rounding -> {
                motion.roundedCorners.animateTo(8f, tween(110))
                motion.phase = MediaPhase.Closing
            }
            MediaPhase.Closing -> {
                motion.handoffAlpha.snapTo(1f)
                motion.progress.animateTo(0f, PrototypeCloseAnimation)
                motion.phase = MediaPhase.Closed
                motion.tootId = null
            }
            else -> Unit
        }
    }
    val drawTransition = motion.phase in listOf(MediaPhase.Opening, MediaPhase.HandingOff, MediaPhase.Rounding, MediaPhase.Closing)
    val image = motion.media.firstOrNull { imageKey(it) == motion.selectedKey }
    val painter = image?.let { painterResource(fixtureImageResource(it)) }
    val geometry = painter?.let {
        val viewport = when (motion.phase) {
            MediaPhase.Viewing -> motion.fullBounds.translate(Offset(0f, motion.dragOffset))
            MediaPhase.Opening, MediaPhase.HandingOff -> motion.transitionViewport.translate(Offset(0f, motion.dragOffset))
            else -> motion.transitionViewport
        }
        prototypeImageGeometry(it.intrinsicSize, motion.transitionThumbnail, viewport, motion.progress.value)
    }
    Box(Modifier.fillMaxSize().zIndex(if (motion.active) 1f else -1f)
        .pointerInput(motion.active) {
            if (motion.active) awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
            }
        }
        .onSizeChanged { motion.viewerSize = it }
        .background(Color.Black.copy(alpha = motion.visibleBackdropAlpha))
        .testTag("prototype_media_motion").semantics {
            this[PrototypeMotionPhase] = motion.phase.name
            this[PrototypeImageTransitionActive] = motion.progress.isRunning
            this[PrototypeImageFit] = motion.progress.value
            this[PrototypeImageCorners] = motion.cornerRadius
            this[PrototypeTransitionImage] = motion.selectedKey
            this[PrototypeImageBounds] = geometry?.clipBounds ?: Rect.Zero
            this[PrototypeImageVisibleBounds] = geometry?.visibleClipBounds ?: Rect.Zero
            this[PrototypeViewerImageReady] = image?.let { motion.imageReady(it.id) } == true
            if (!motion.active) hideFromAccessibility()
        }) {
        if (motion.active && motion.media.isNotEmpty()) {
            if (drawTransition && painter != null && geometry != null && !geometry.visibleClipBounds.isEmpty) {
                val clip = remember { Path() }
                Canvas(Modifier.fillMaxSize().prototypeOverlay(1f) { drawTransition }
                    .semantics { hideFromAccessibility() }) {
                    clip.reset()
                    clip.addRoundRect(RoundRect(geometry.clipBounds, CornerRadius(motion.cornerRadius.dp.toPx())))
                    clipPath(clip) {
                        // A scrolled-off edge is a straight viewport cut, not a new rounded image edge.
                        val visible = geometry.visibleClipBounds
                        clipRect(visible.left, visible.top, visible.right, visible.bottom) {
                            translate(geometry.imageBounds.left, geometry.imageBounds.top) {
                                with(painter) {
                                    val alpha = when {
                                        motion.phase == MediaPhase.HandingOff -> motion.handoffAlpha.value
                                        motion.phase == MediaPhase.Closing && motion.transitionThumbnail == null -> motion.progress.value.coerceIn(0f, 1f)
                                        else -> 1f
                                    }
                                    draw(geometry.imageBounds.size, alpha = alpha)
                                }
                            }
                        }
                    }
                }
            }
            key(motion.tootId) { MediaViewerScreen(motion.media, motion) }
        }
    }
}

internal data class PrototypeImageGeometry(val imageBounds: Rect, val clipBounds: Rect, val visibleClipBounds: Rect = clipBounds)

internal fun prototypeImageGeometry(
    source: Size,
    thumbnail: PrototypeThumbnailFrame?,
    viewport: Rect,
    progress: Float,
): PrototypeImageGeometry {
    if (!source.width.isFinite() || !source.height.isFinite() || source.width <= 0f || source.height <= 0f || viewport.isEmpty) {
        return PrototypeImageGeometry(Rect.Zero, Rect.Zero)
    }
    fun imageRect(frame: Rect, crop: Boolean): Rect {
        val ratios = listOf(frame.width / source.width, frame.height / source.height)
        val scale = if (crop) ratios.max() else ratios.min()
        val size = Size(source.width * scale, source.height * scale)
        return Rect(frame.center - androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f), size)
    }
    val fitted = imageRect(viewport, false)
    val start = thumbnail?.let { imageRect(it.bounds, it.cropped) } ?: fitted
    val startClip = thumbnail?.let { if (it.cropped) it.bounds else start } ?: fitted
    val startVisible = thumbnail?.let { startClip.intersect(it.visibleBounds) } ?: fitted
    val clip = lerp(startClip, fitted, progress)
    // The image can spring past its return position, but must not escape the list's clipping edge.
    val visibleClip = lerp(startVisible, fitted, progress.coerceIn(0f, 1f)).intersect(clip)
    return PrototypeImageGeometry(lerp(start, fitted, progress), clip, visibleClip)
}
