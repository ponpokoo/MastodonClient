package io.github.ponpokoo.transitionprototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize

// Exposed only to instrumentation, so tests can distinguish a shared transition from a fade.
internal val ImageMatched = SemanticsPropertyKey<Boolean>("ImageMatched")
internal val ImageTransitionActive = SemanticsPropertyKey<Boolean>("ImageTransitionActive")
internal val ImageCornerRadius = SemanticsPropertyKey<Float>("ImageCornerRadius")
internal val RoundingBeforeClose = SemanticsPropertyKey<Boolean>("RoundingBeforeClose")
internal val ImageFitFraction = SemanticsPropertyKey<Float>("ImageFitFraction")
private const val TRANSITION_MS = 250
private const val CLOSE_CORNERS_MS = 80

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    ImageTransitionPrototype()
                }
            }
        }
    }
}

@Composable
private fun ImageTransitionPrototype() {
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var closeRequested by remember { mutableStateOf(false) }
    val cornerRadius = remember { Animatable(if (viewerOpen) 0f else 8f) }
    LaunchedEffect(viewerOpen, closeRequested) {
        when {
            viewerOpen && closeRequested -> {
                cornerRadius.animateTo(8f, tween(CLOSE_CORNERS_MS))
                viewerOpen = false
                closeRequested = false
            }
            viewerOpen -> cornerRadius.animateTo(0f, tween(TRANSITION_MS))
            else -> cornerRadius.snapTo(8f)
        }
    }
    val listState = rememberLazyListState()
    val painter = painterResource(R.drawable.sample_landscape)
    var thumbnailSize by remember { mutableStateOf(IntSize.Zero) }
    var viewerSize by remember { mutableStateOf(IntSize.Zero) }
    val fitFraction by animateFloatAsState(
        targetValue = if (viewerOpen) 1f else 0f,
        animationSpec = if (viewerOpen) tween(TRANSITION_MS) else closeSpring(0.0001f),
        label = "crop-to-fit",
    )
    // Fade opacity separately: a spring's overshoot is useful for size, but not for alpha.
    val backdropFraction by animateFloatAsState(
        targetValue = if (viewerOpen) 1f else 0f,
        animationSpec = tween(TRANSITION_MS),
        label = "backdrop",
    )
    val imageScale = remember(fitFraction, thumbnailSize, viewerSize) {
        CropFitScale(fitFraction, thumbnailSize, viewerSize)
    }
    val imageShape = ImageContentShape(painter.intrinsicSize, imageScale, cornerRadius.value)
    BackHandler(enabled = viewerOpen) { closeRequested = true }

    SharedTransitionLayout(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
    ) {
        val imageState = rememberSharedContentState("single-local-image")
        val transitioning = isTransitionActive
        Box(Modifier.fillMaxSize().onSizeChanged { viewerSize = it }.testTag("prototype").semantics {
            this[ImageMatched] = imageState.isMatchFound
            this[ImageTransitionActive] = transitioning
            this[ImageCornerRadius] = cornerRadius.value
            this[RoundingBeforeClose] = closeRequested
            this[ImageFitFraction] = fitFraction
        }) {
            // Keep the list composed and the thumbnail slot fixed while the viewer is open.
            LazyColumn(
                state = listState,
                userScrollEnabled = !viewerOpen && !transitioning,
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().testTag("timeline").semantics {
                    if (viewerOpen) hideFromAccessibility()
                },
            ) {
                item(key = "heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("画像遷移の試作", style = MaterialTheme.typography.headlineSmall)
                        Text("画像をタップして拡大。閉じると同じ位置に戻ります。")
                        Text("開く ${TRANSITION_MS} ms・閉じる 角丸→弱いバウンス", color = MaterialTheme.colorScheme.outline)
                    }
                }
                items(2, key = { "before-$it" }) { index ->
                    Text("サンプル投稿 ${index + 1}", Modifier.fillMaxWidth().height(64.dp).padding(top = 16.dp))
                }
                item(key = "image-post") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("山と湖のサンプル画像")
                        Box(Modifier.fillMaxWidth().height(220.dp).onSizeChanged { thumbnailSize = it }) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !viewerOpen,
                                enter = EnterTransition.None,
                                exit = ExitTransition.None,
                            ) {
                                Image(
                                    painter = painter,
                                    contentDescription = "画像を開く",
                                    contentScale = imageScale,
                                    modifier = Modifier
                                        .sharedElement(
                                            sharedContentState = imageState,
                                            animatedVisibilityScope = this,
                                            boundsTransform = { _, _ ->
                                                if (viewerOpen) tween(TRANSITION_MS)
                                                else closeSpring(Rect(0.5f, 0.5f, 0.5f, 0.5f))
                                            },
                                        )
                                        .fillMaxSize()
                                        .clip(imageShape)
                                        .testTag("thumbnail")
                                        .clickable(enabled = !transitioning) { viewerOpen = true },
                                )
                            }
                        }
                    }
                }
                items(12, key = { "after-$it" }) { index ->
                    Text("サンプル投稿 ${index + 3}", Modifier.fillMaxWidth().height(72.dp).padding(top = 16.dp))
                }
            }
            AnimatedVisibility(
                visible = viewerOpen,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                modifier = Modifier.fillMaxSize(),
            ) {
                val viewerImageState = rememberSharedContentState("single-local-image")
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = backdropFraction)).testTag("viewer")) {
                    Image(
                        painter = painter,
                        contentDescription = "山と湖の全体画像",
                        contentScale = imageScale,
                        modifier = Modifier
                            .sharedElement(
                                sharedContentState = viewerImageState,
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ ->
                                    if (viewerOpen) tween(TRANSITION_MS)
                                    else closeSpring(Rect(0.5f, 0.5f, 0.5f, 0.5f))
                                },
                            )
                            .fillMaxSize()
                            .clip(imageShape)
                            .testTag("full_image"),
                    )
                    Button(
                        onClick = { closeRequested = true },
                        enabled = !closeRequested,
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                            .graphicsLayer { alpha = backdropFraction }.testTag("close"),
                    ) { Text("閉じる") }
                }
            }
        }
    }
}

private fun <T> closeSpring(visibilityThreshold: T): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = visibilityThreshold,
)

/** Clip the visible picture itself, including the letterboxed picture in the full-screen frame. */
private class ImageContentShape(
    private val sourceSize: Size,
    private val imageScale: ContentScale,
    private val cornerRadiusDp: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val scale = imageScale.computeScaleFactor(sourceSize, size)
        val width = minOf(size.width, sourceSize.width * scale.scaleX)
        val height = minOf(size.height, sourceSize.height * scale.scaleY)
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        val radius = with(density) { cornerRadiusDp.dp.toPx() }
        return Outline.Rounded(RoundRect(Rect(left, top, left + width, top + height), CornerRadius(radius)))
    }
}

/** Interpolate the image's endpoint scales, rather than blending two differently cropped images. */
private class CropFitScale(
    private val fitFraction: Float,
    private val thumbnailSize: IntSize,
    private val viewerSize: IntSize,
) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        if (thumbnailSize == IntSize.Zero || viewerSize == IntSize.Zero) {
            return ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
        }
        // Stable endpoint sizes avoid a temporary zoom as the animated frame grows taller.
        val crop = ContentScale.Crop.computeScaleFactor(srcSize, thumbnailSize.toSize())
        val fit = ContentScale.Fit.computeScaleFactor(srcSize, viewerSize.toSize())
        return ScaleFactor(
            crop.scaleX + (fit.scaleX - crop.scaleX) * fitFraction,
            crop.scaleY + (fit.scaleY - crop.scaleY) * fitFraction,
        )
    }
}
