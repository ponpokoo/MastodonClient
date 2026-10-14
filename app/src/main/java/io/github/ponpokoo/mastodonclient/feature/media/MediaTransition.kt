package io.github.ponpokoo.mastodonclient.feature.media

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal val LocalMediaTransition = staticCompositionLocalOf<MediaTransitionState?> { null }
internal val LocalMediaContentInsets = staticCompositionLocalOf<WindowInsets?> { null }
internal val LocalMediaSourceIsHome = staticCompositionLocalOf { false }
private val LocalMediaSourceSession = staticCompositionLocalOf<String?> { "standalone" }
@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalMediaSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

internal val MediaReturnAnimation = tween<Float>(420, easing = FastOutSlowInEasing)
internal enum class MediaPhase { Closed, Opening, HandingOff, Viewing, ResettingZoom, Rounding, Closing }
internal data class MediaThumbnailFrame(
    val bounds: Rect,
    val cropped: Boolean,
    val visibleBounds: Rect = bounds,
    val cornerRadius: Float = 0f,
)
internal data class MediaImageGeometry(val imageBounds: Rect, val clipBounds: Rect, val visibleClipBounds: Rect = clipBounds)
private data class MediaSource(val group: String, val key: String, val home: Boolean)

/** UI-only state. Sources and painters are never persisted or shared across account generations. */
@Stable
internal class MediaTransitionState {
    var media by mutableStateOf<List<MediaAttachment>>(emptyList())
        private set
    var page by mutableIntStateOf(0)
    var phase by mutableStateOf(MediaPhase.Closed)
    var sessionKey by mutableStateOf("")
        private set
    var routeId by mutableStateOf("")
        private set
    var sourceGroup by mutableStateOf("")
        private set
    var sourceIsHome by mutableStateOf(false)
        private set
    var selectedKey by mutableStateOf("")
    val progress = Animatable(0f)
    val handoffAlpha = Animatable(1f)
    val roundedCorners = Animatable(0f)
    val thumbnailFrames = mutableStateMapOf<String, MediaThumbnailFrame>()
    val thumbnailPainters = mutableStateMapOf<String, Painter>()
    private val readyImages = mutableStateMapOf<String, Painter?>()
    private var pendingSource: MediaSource? = null
    private var sourceSession: String? = null
    var transitionThumbnail by mutableStateOf<MediaThumbnailFrame?>(null)
        private set
    var transitionPainter by mutableStateOf<Painter?>(null)
        private set
    private var transitionImageId: String? = null
    var transitionViewport by mutableStateOf(Rect.Zero)
        private set
    var roundedReturn by mutableStateOf(false)
        private set
    var viewerSize by mutableStateOf(IntSize.Zero)
    var dragOffset by mutableFloatStateOf(0f)
    private var closingBackdropFactor by mutableFloatStateOf(1f)
    val active get() = phase != MediaPhase.Closed
    val fullBounds get() = Rect(0f, 0f, viewerSize.width.toFloat(), viewerSize.height.toFloat())
    val backdropAlpha get() = progress.value.coerceIn(0f, 1f) * closingBackdropFactor
    val visibleBackdropAlpha get() = backdropAlpha *
        (1f - dragOffset / viewerSize.height.coerceAtLeast(1) * 1.5f).coerceIn(0f, 1f)
    val cornerRadius get() = if (roundedReturn) roundedCorners.value
        else (transitionThumbnail?.cornerRadius ?: 0f) * (1f - progress.value.coerceIn(0f, 1f))

    fun imageKey(item: MediaAttachment) = "$sourceGroup/${item.id}/${item.url ?: item.previewUrl}/${item.cacheRevision}"
    fun prepareSource(group: String, key: String, home: Boolean) {
        if (!active) pendingSource = MediaSource(group, key, home)
    }
    fun imageReady(id: String) = readyImages.containsKey(id)
    fun reportImageReady(id: String, painter: Painter?) {
        if (active && media.any { it.id == id }) readyImages[id] = painter
    }
    fun viewerPainter(id: String) = readyImages[id]
    fun fallbackPainter(item: MediaAttachment) = thumbnailPainters[imageKey(item)]
        ?: transitionPainter.takeIf { transitionImageId == item.id }
    fun open(items: List<MediaAttachment>, index: Int, session: String, route: String) {
        if (active || items.isEmpty()) return
        val selected = index.coerceIn(items.indices)
        val source = pendingSource
        pendingSource = null
        sourceGroup = source?.group.orEmpty()
        sourceIsHome = source?.home == true
        media = items
        page = selected
        sessionKey = session
        routeId = route
        selectedKey = imageKey(items[selected])
        val frame = source?.takeIf { it.key == selectedKey }?.let { thumbnailFrames[it.key] }
            ?.takeUnless { it.visibleBounds.isEmpty }
        transitionPainter = if (items[selected].type == "image") source?.let { thumbnailPainters[it.key] } else null
        transitionImageId = items[selected].id
        transitionThumbnail = frame.takeIf { transitionPainter != null }
        transitionViewport = fullBounds
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
        // Freeze the source before bars, zoom or layout are restored.
        transitionThumbnail = thumbnailFrames[selectedKey]?.takeUnless { it.visibleBounds.isEmpty }
        transitionPainter = if (media[page].type == "image")
            readyImages[media[page].id] ?: fallbackPainter(media[page]) else null
        transitionImageId = media[page].id
        if (transitionPainter == null) transitionThumbnail = null
        val releasedDrag = if (fromDrag) dragOffset else 0f
        transitionViewport = fullBounds.translate(Offset(0f, releasedDrag))
        closingBackdropFactor = if (fromDrag)
            (1f - releasedDrag / viewerSize.height.coerceAtLeast(1) * 1.5f).coerceIn(0f, 1f) else 1f
        roundedReturn = phase == MediaPhase.Viewing && !fromDrag
        if (fromDrag) dragOffset = 0f
        phase = if (roundedReturn) MediaPhase.ResettingZoom else MediaPhase.Closing
    }
    fun clear() {
        phase = MediaPhase.Closed
        media = emptyList()
        readyImages.clear()
        pendingSource = null
        transitionPainter = null
        transitionThumbnail = null
        dragOffset = 0f
    }
    fun validateScope(session: String?, route: String?, sessionReady: Boolean = true) {
        if (active && ((route != null && routeId != route) || (sessionReady && sessionKey != session))) clear()
        if (sessionReady && sourceSession != session) {
            thumbnailFrames.clear()
            thumbnailPainters.clear()
            pendingSource = null
            sourceSession = session
        }
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val Saver: Saver<MediaTransitionState, Any> = listSaver(
            save = { listOf(if (it.phase in listOf(MediaPhase.Closed, MediaPhase.ResettingZoom, MediaPhase.Rounding, MediaPhase.Closing))
                "" else json.encodeToString(it.media), it.page, it.sessionKey, it.routeId) },
            restore = { saved -> MediaTransitionState().apply {
                val items = runCatching { json.decodeFromString<List<MediaAttachment>>(saved[0] as String) }.getOrDefault(emptyList())
                if (items.isNotEmpty()) open(items, saved[1] as Int, saved[2] as String, saved[3] as String)
            } },
        )
    }
}

@Composable
internal fun rememberMediaTransitionState() = rememberSaveable(saver = MediaTransitionState.Saver) { MediaTransitionState() }

internal class MediaSourceGroup internal constructor(private val motion: MediaTransitionState?, private val home: Boolean) {
    val id = UUID.randomUUID().toString()
    fun key(media: MediaAttachment) = "$id/${media.id}/${media.url ?: media.previewUrl}/${media.cacheRevision}"
    fun prepare(media: MediaAttachment) { motion?.prepareSource(id, key(media), home) }
    fun displayed(media: MediaAttachment, painter: Painter) { motion?.thumbnailPainters?.set(key(media), painter) }
}
@Composable
internal fun rememberMediaSourceGroup(): MediaSourceGroup {
    val motion = LocalMediaTransition.current
    val home = LocalMediaSourceIsHome.current
    val session = LocalMediaSourceSession.current
    return remember(motion, home, session) { MediaSourceGroup(motion, home) }
}

@Composable
internal fun Modifier.mediaTransitionThumbnail(
    media: MediaAttachment,
    group: MediaSourceGroup,
    cropped: Boolean = true,
    cornerRadiusDp: Float = 8f,
    circular: Boolean = false,
): Modifier {
    val motion = LocalMediaTransition.current ?: return this
    val key = group.key(media)
    val density = LocalDensity.current
    DisposableEffect(motion, key) {
        onDispose { motion.thumbnailFrames.remove(key); motion.thumbnailPainters.remove(key) }
    }
    return onGloballyPositioned {
        val bounds = Rect(it.positionInRoot(), it.size.toSize())
        val radius = if (circular) minOf(bounds.width, bounds.height) / 2f else with(density) { cornerRadiusDp.dp.toPx() }
        motion.thumbnailFrames[key] = MediaThumbnailFrame(bounds, cropped, it.boundsInRoot().intersect(bounds), radius)
    }.graphicsLayer { alpha = if (motion.active && key == motion.selectedKey) 0f else 1f }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.mediaOverlay(z: Float, visible: () -> Boolean): Modifier {
    val scope = LocalMediaSharedScope.current ?: return this
    return with(scope) { renderInSharedTransitionScopeOverlay(zIndexInOverlay = z, renderInOverlay = visible) }
}
@Composable
internal fun Modifier.mediaComposeButton(): Modifier {
    val motion = LocalMediaTransition.current
    val lift = motion?.active == true && motion.sourceIsHome
    return mediaOverlay(3f) { lift }.graphicsLayer {
        alpha = if (lift) 1f - motion!!.visibleBackdropAlpha else 1f
    }
}
@Composable
internal fun mediaContentInsets(): WindowInsets = LocalMediaContentInsets.current ?: ScaffoldDefaults.contentWindowInsets

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MediaTransitionHost(
    motion: MediaTransitionState,
    sessionKey: String? = "standalone",
    routeId: String? = "standalone",
    sessionReady: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = ScaffoldDefaults.contentWindowInsets
    fun currentInsets() = WindowInsets(insets.getLeft(density, direction), insets.getTop(density),
        insets.getRight(density, direction), insets.getBottom(density))
    var contentInsets by remember { mutableStateOf(currentInsets()) }
    SideEffect {
        motion.validateScope(sessionKey, routeId, sessionReady)
        if (!motion.active) contentInsets = currentInsets()
    }
    val visible = motion.active && motion.sessionKey == sessionKey && motion.routeId == routeId
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalMediaTransition provides motion, LocalMediaContentInsets provides contentInsets,
            LocalMediaSourceSession provides sessionKey,
            LocalMediaSharedScope provides this@SharedTransitionLayout) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().semantics { if (visible) hideFromAccessibility() }) { content() }
                Box(Modifier.fillMaxSize().zIndex(if (visible) 1f else -1f)
                    .onSizeChanged { motion.viewerSize = it }) {
                    if (visible) MediaTransitionOverlay(motion)
                }
            }
        }
    }
}

@Composable
internal fun MediaTransitionAnimations(motion: MediaTransitionState, onClosed: () -> Unit) {
    val closed by rememberUpdatedState(onClosed)
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
                motion.roundedCorners.animateTo(motion.transitionThumbnail?.cornerRadius ?: 0f, tween(110))
                motion.phase = MediaPhase.Closing
            }
            MediaPhase.Closing -> {
                motion.handoffAlpha.snapTo(1f)
                motion.progress.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                motion.clear()
                closed()
            }
            else -> Unit
        }
    }
}

internal val MediaMotionPhase = SemanticsPropertyKey<String>("MediaMotionPhase")
internal val MediaImageBounds = SemanticsPropertyKey<Rect>("MediaImageBounds")
internal val MediaImageVisibleBounds = SemanticsPropertyKey<Rect>("MediaImageVisibleBounds")
internal val MediaImageFit = SemanticsPropertyKey<Float>("MediaImageFit")

@Composable
private fun MediaTransitionOverlay(motion: MediaTransitionState) {
    val drawTransition = motion.phase in listOf(MediaPhase.Opening, MediaPhase.HandingOff, MediaPhase.Rounding, MediaPhase.Closing)
    val painter = motion.transitionPainter
    val geometry = painter?.let {
        val viewport = when (motion.phase) {
            MediaPhase.Viewing -> motion.fullBounds.translate(Offset(0f, motion.dragOffset))
            MediaPhase.Opening, MediaPhase.HandingOff -> motion.transitionViewport.translate(Offset(0f, motion.dragOffset))
            else -> motion.transitionViewport
        }
        mediaImageGeometry(it.intrinsicSize, motion.transitionThumbnail, viewport, motion.progress.value)
    }
    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
            }
        }
        .background(Color.Black.copy(alpha = motion.visibleBackdropAlpha))
        .testTag("media_transition").semantics {
            this[MediaMotionPhase] = motion.phase.name
            this[MediaImageFit] = motion.progress.value
            this[MediaImageBounds] = geometry?.clipBounds ?: Rect.Zero
            this[MediaImageVisibleBounds] = geometry?.visibleClipBounds ?: Rect.Zero
        }) {
        if (drawTransition && painter != null && geometry != null && !geometry.visibleClipBounds.isEmpty) {
            val clip = remember { Path() }
            Canvas(Modifier.fillMaxSize().mediaOverlay(1f) { drawTransition }.semantics { hideFromAccessibility() }) {
                clip.reset()
                clip.addRoundRect(RoundRect(geometry.clipBounds, CornerRadius(motion.cornerRadius)))
                clipPath(clip) {
                    val visible = geometry.visibleClipBounds
                    clipRect(visible.left, visible.top, visible.right, visible.bottom) {
                        translate(geometry.imageBounds.left, geometry.imageBounds.top) {
                            with(painter) {
                                val alpha = if (motion.phase == MediaPhase.HandingOff) motion.handoffAlpha.value
                                    else if (motion.phase == MediaPhase.Closing && motion.transitionThumbnail == null) motion.progress.value else 1f
                                draw(geometry.imageBounds.size, alpha = alpha)
                            }
                        }
                    }
                }
            }
        }
        MediaViewerContent(motion.media, motion, onBack = {})
    }
}

internal fun mediaImageGeometry(source: Size, thumbnail: MediaThumbnailFrame?, viewport: Rect, progress: Float): MediaImageGeometry {
    if (!source.width.isFinite() || !source.height.isFinite() || source.width <= 0f || source.height <= 0f || viewport.isEmpty)
        return MediaImageGeometry(Rect.Zero, Rect.Zero)
    fun imageRect(frame: Rect, crop: Boolean): Rect {
        val scale = if (crop) maxOf(frame.width / source.width, frame.height / source.height)
            else minOf(frame.width / source.width, frame.height / source.height)
        val size = Size(source.width * scale, source.height * scale)
        return Rect(frame.center - Offset(size.width / 2f, size.height / 2f), size)
    }
    val fitted = imageRect(viewport, false)
    val start = thumbnail?.let { imageRect(it.bounds, it.cropped) } ?: fitted
    val startClip = thumbnail?.let { if (it.cropped) it.bounds else start } ?: fitted
    val startVisible = thumbnail?.let { startClip.intersect(it.visibleBounds) } ?: fitted
    val clip = lerp(startClip, fitted, progress)
    val visibleClip = lerp(startVisible, fitted, progress.coerceIn(0f, 1f)).intersect(clip)
    return MediaImageGeometry(lerp(start, fitted, progress), clip, visibleClip)
}
