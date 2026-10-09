package io.github.ponpokoo.transitionprototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

// Patterns used here, without importing either sample's navigation or application architecture:
// AndroidX: sharedElementWithCallerManagedVisibility keeps both image slots available.
// https://github.com/androidx/androidx/blob/androidx-main/compose/animation/animation/integration-tests/animation-demos/src/main/java/androidx/compose/animation/demos/sharedelement/CallerManagedVisibilityDemo.kt
// Jetsnack: identify the shared image by a stable item key instead of its list position.
// https://github.com/android/compose-samples/pull/1314
// Telephoto 0.19.0: page-local zoom state and resetting off-screen pages.
// https://github.com/saket/telephoto/blob/0.19.0/sample/src/androidMain/kotlin/me/saket/telephoto/sample/viewer/MediaViewerScreen.kt
// https://saket.github.io/telephoto/zoomable/recipes/#resetting-zoom
// The crop/fit interpolation and corner-first close reuse this prototype's existing helpers.

internal val ReferenceViewerOpen = SemanticsPropertyKey<Boolean>("ReferenceViewerOpen")
internal val ReferencePage = SemanticsPropertyKey<Int>("ReferencePage")
internal val ReferenceZoom = SemanticsPropertyKey<Float>("ReferenceZoom")
internal val ReferenceImageReady = SemanticsPropertyKey<Boolean>("ReferenceImageReady")

class ReferenceGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    ReferenceGallery(onExit = { finish() })
                }
            }
        }
    }
}

private data class ReferenceImage(val id: String, val resource: Int, val title: String)
private val referenceImages = listOf(
    ReferenceImage("lake", R.drawable.sample_landscape, "横長・山と湖"),
    ReferenceImage("tower", R.drawable.sample_portrait, "縦長・夕暮れの塔"),
    ReferenceImage("island", R.drawable.sample_square, "正方形・海の島"),
)

private const val REFERENCE_OPEN_MS = 350
private const val REFERENCE_CORNERS_MS = 110
private const val REFERENCE_ZOOM_RESET_MS = 150
private const val REFERENCE_CLOSE_STIFFNESS = 900f
private enum class ViewerPhase { Gallery, Opening, Viewing, ResettingZoom, Rounding, Closing }

@Composable
private fun ReferenceGallery(onExit: () -> Unit) {
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var phase by remember { mutableStateOf(if (viewerOpen) ViewerPhase.Viewing else ViewerPhase.Gallery) }
    // Only an open/close operation changes the shared image selection. Paging never changes it.
    var transitionIndex by rememberSaveable { mutableStateOf(0) }
    val pager = rememberPagerState(pageCount = { referenceImages.size })
    val scope = rememberCoroutineScope()
    val zoomStates = referenceImages.map { rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f)) }
    val imageStates = zoomStates.map { rememberZoomableImageState(it) }
    val painters = referenceImages.map { painterResource(it.resource) }
    val corners = remember { Animatable(if (viewerOpen) 0f else 8f) }
    val thumbnailSizes = remember { mutableStateMapOf<Int, IntSize>() }
    var viewerSize by remember { mutableStateOf(IntSize.Zero) }
    val thumbnailSize = thumbnailSizes[transitionIndex] ?: IntSize.Zero
    val fit by animateFloatAsState(
        if (viewerOpen) 1f else 0f,
        if (viewerOpen) tween(REFERENCE_OPEN_MS) else closeSpring(0.0001f, REFERENCE_CLOSE_STIFFNESS),
        label = "reference-fit",
    )
    val backdrop by animateFloatAsState(
        if (viewerOpen) 1f else 0f, tween(REFERENCE_OPEN_MS), label = "reference-backdrop",
    )
    val imageScale = remember(fit, thumbnailSize, viewerSize) { CropFitScale(fit, thumbnailSize, viewerSize) }
    val context = LocalContext.current
    val requests = remember(context) {
        referenceImages.map { image ->
            ImageRequest.Builder(context).data(image.resource)
                .memoryCacheKey(image.id).placeholderMemoryCacheKey(image.id).crossfade(false).build()
        }
    }

    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
        if (!pager.isScrollInProgress) {
            zoomStates.forEachIndexed { index, state ->
                if (index != pager.settledPage && (state.zoomFraction ?: 0f) > 0f) state.resetZoom(snap())
            }
        }
    }
    val closing = phase == ViewerPhase.ResettingZoom || phase == ViewerPhase.Rounding || phase == ViewerPhase.Closing
    BackHandler(viewerOpen) { if (!closing) phase = ViewerPhase.ResettingZoom }

    SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val sharedStates = referenceImages.map { rememberSharedContentState("reference-image-${it.id}") }
        val transitioning = isTransitionActive
        val showTransitionImage = phase == ViewerPhase.Opening || phase == ViewerPhase.Rounding || phase == ViewerPhase.Closing
        val showPager = phase == ViewerPhase.Viewing || phase == ViewerPhase.ResettingZoom
        val boundsTransforms = referenceImages.indices.map { index ->
            BoundsTransform { _, _ ->
                when {
                    index != transitionIndex -> snap()
                    viewerOpen -> tween(REFERENCE_OPEN_MS)
                    else -> closeSpring(Rect(0.5f, 0.5f, 0.5f, 0.5f), REFERENCE_CLOSE_STIFFNESS)
                }
            }
        }

        LaunchedEffect(phase) {
            when (phase) {
                ViewerPhase.Opening -> {
                    corners.animateTo(0f, tween(REFERENCE_OPEN_MS))
                    // Hand the exact fitted endpoint to the pager after both animations and
                    // the image loader have finished. The pager's own viewport never resizes.
                    snapshotFlow { fit == 1f && !isTransitionActive && imageStates[transitionIndex].isImageDisplayed }
                        .first { it }
                    phase = ViewerPhase.Viewing
                }
                ViewerPhase.ResettingZoom -> {
                    pager.scrollToPage(pager.currentPage)
                    transitionIndex = pager.settledPage
                    val zoomState = zoomStates[transitionIndex]
                    if ((zoomState.zoomFraction ?: 0f) > 0f) zoomState.resetZoom(tween(REFERENCE_ZOOM_RESET_MS))
                    phase = ViewerPhase.Rounding
                }
                ViewerPhase.Rounding -> {
                    corners.animateTo(8f, tween(REFERENCE_CORNERS_MS))
                    phase = ViewerPhase.Closing
                    viewerOpen = false
                }
                ViewerPhase.Closing -> {
                    snapshotFlow { fit == 0f && !isTransitionActive }.first { it }
                    phase = ViewerPhase.Gallery
                }
                else -> Unit
            }
        }

        Box(Modifier.fillMaxSize().onSizeChanged { viewerSize = it }.testTag("reference_gallery").semantics {
            this[ReferenceViewerOpen] = viewerOpen
            this[ReferencePage] = pager.settledPage
            this[ReferenceZoom] = zoomStates[pager.settledPage].zoomFraction ?: 0f
            this[ReferenceImageReady] = imageStates[pager.settledPage].isImageDisplayed
            this[ImageMatched] = sharedStates[transitionIndex].isMatchFound
            this[ImageTransitionActive] = transitioning
            this[RoundingBeforeClose] = closing
            this[ImageCornerRadius] = corners.value
            this[ImageFitFraction] = fit
        }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = phase == ViewerPhase.Gallery)
                    .padding(20.dp).semantics { if (viewerOpen) hideFromAccessibility() },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("参考実装の画像ビューア", style = MaterialTheme.typography.headlineSmall)
                Text("画像を開く → 左右で切り替え → ピンチ／ダブルタップで拡大")
                Button(onClick = onExit, enabled = phase == ViewerPhase.Gallery) { Text("最初の試作に戻る") }
                referenceImages.forEachIndexed { index, image ->
                    val painter = painters[index]
                    val selected = index == transitionIndex
                    val thumbnailScale = if (selected) imageScale else ContentScale.Crop
                    Text(image.title)
                    // Measure the fixed slot, never the image whose bounds are animating.
                    Box(Modifier.fillMaxWidth().height(140.dp).onSizeChanged { thumbnailSizes[index] = it }
                        .testTag("reference_thumbnail_$index")
                        .clickable(enabled = phase == ViewerPhase.Gallery) {
                            transitionIndex = index
                            phase = ViewerPhase.Opening
                            scope.launch {
                                pager.scrollToPage(index)
                                viewerOpen = true
                            }
                        }) {
                        if (!selected) {
                            Image(painter, image.title, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop)
                        }
                        Image(
                            painter = painter, contentDescription = if (selected) image.title else null,
                            contentScale = thumbnailScale,
                            modifier = Modifier
                                .sharedElementWithCallerManagedVisibility(
                                    sharedStates[index], visible = !viewerOpen, boundsTransform = boundsTransforms[index],
                                )
                                .fillMaxSize()
                                .clip(ImageContentShape(painter.intrinsicSize, thumbnailScale, corners.value))
                                .graphicsLayer { alpha = if (selected) 1f else 0f },
                        )
                    }
                }
            }

            // Both slots stay composed, as in the caller-managed visibility sample. Move the
            // inactive viewer behind the gallery so its gesture surface cannot intercept taps.
            Box(Modifier.fillMaxSize().zIndex(if (phase != ViewerPhase.Gallery) 1f else -1f)
                .background(Color.Black.copy(alpha = backdrop))
                .semantics { if (!viewerOpen) hideFromAccessibility() }) {
                HorizontalPager(
                    state = pager, key = { referenceImages[it].id },
                    userScrollEnabled = phase == ViewerPhase.Viewing,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (showPager) 1f else 0f }
                        .semantics { if (!showPager) hideFromAccessibility() }.testTag("reference_pager"),
                ) { index ->
                    val image = referenceImages[index]
                    ZoomableAsyncImage(
                        model = requests[index], contentDescription = image.title,
                        state = imageStates[index], contentScale = ContentScale.Fit,
                        gestures = if (phase == ViewerPhase.Viewing) EnabledZoomGestures.ZoomAndPan
                            else EnabledZoomGestures.None,
                        modifier = Modifier.fillMaxSize().testTag("reference_image_$index"),
                    )
                }
                // Keep stable shared counterparts outside the pager. Only the selected picture
                // is painted/animated; the other pairs snap invisibly. A page being composed or
                // becoming settled can therefore never start a thumbnail-to-viewer transition.
                referenceImages.forEachIndexed { index, image ->
                    val selected = index == transitionIndex
                    val fullScale = if (selected) imageScale else ContentScale.Fit
                    val painter = painters[index]
                    Image(
                        painter = painter, contentDescription = image.title, contentScale = fullScale,
                        modifier = Modifier
                            .sharedElementWithCallerManagedVisibility(
                                sharedStates[index], visible = viewerOpen, boundsTransform = boundsTransforms[index],
                            )
                            .fillMaxSize()
                            .clip(ImageContentShape(painter.intrinsicSize, fullScale, corners.value))
                            .graphicsLayer { alpha = if (selected && showTransitionImage) 1f else 0f }
                            .semantics { if (!selected || !showTransitionImage) hideFromAccessibility() },
                    )
                }
                if (viewerOpen) {
                    Row(Modifier.align(Alignment.TopStart).padding(16.dp).graphicsLayer { alpha = backdrop },
                        verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { phase = ViewerPhase.ResettingZoom },
                            enabled = phase == ViewerPhase.Viewing && !pager.isScrollInProgress,
                            modifier = Modifier.testTag("reference_close")) { Text("閉じる") }
                        Text("${pager.settledPage + 1} / ${referenceImages.size}", Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}
