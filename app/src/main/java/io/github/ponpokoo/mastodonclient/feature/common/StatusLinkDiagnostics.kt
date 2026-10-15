package io.github.ponpokoo.mastodonclient.feature.common

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.BuildConfig
import java.util.Locale

/** Opt-in, process-local diagnostics. Never stores or logs URLs or post contents. */
internal object StatusLinkDiagnostics {
    var enabled by mutableStateOf(false)
}

/** Drawing only: does not add pointer handlers, padding, semantics, or hit-test bounds. */
@Composable
internal fun Modifier.statusLinkDiagnostics(
    text: AnnotatedString,
    layout: () -> TextLayoutResult?,
): Modifier {
    if (!BuildConfig.DEBUG || !StatusLinkDiagnostics.enabled) return this
    val minimumTarget = LocalViewConfiguration.current.minimumTouchTargetSize
    return drawWithCache {
        val result = layout()
        val textSize = size
        val links = text.getStringAnnotations("url", 0, text.length)
        val paddingX = ((minimumTarget.width.toPx() - size.width) / 2).coerceAtLeast(0f)
        val paddingY = ((minimumTarget.height.toPx() - size.height) / 2).coerceAtLeast(0f)
        val step = 2.dp.toPx()
        // These are offset-mapping samples, not proof that an outside pointer reaches this node.
        val cells = buildList {
            if (result != null && links.isNotEmpty()) {
                var y = -paddingY
                while (y < textSize.height + paddingY) {
                    val height = minOf(step, textSize.height + paddingY - y)
                    var x = -paddingX
                    while (x < textSize.width + paddingX) {
                        val width = minOf(step, textSize.width + paddingX - x)
                        val offset = result.getOffsetForPosition(Offset(x + width / 2, y + height / 2))
                        if (text.getStringAnnotations("url", offset, offset).isNotEmpty()) {
                            add(Offset(x, y) to Size(width, height))
                        }
                        x += step
                    }
                    y += step
                }
            }
        }
        val paths = result?.let { r -> links.map { r.getPathForRange(it.start, it.end) } }.orEmpty()
        onDrawWithContent {
            cells.forEach { (origin, cellSize) ->
                drawRect(Color(0xFFFF9800).copy(alpha = .22f), origin, cellSize)
            }
            drawContent()
            paths.forEach { drawPath(it, Color(0xFF009688), style = Stroke(1.dp.toPx())) }
            drawRect(Color(0xFF1976D2), style = Stroke(1.dp.toPx()))
            if (paddingX > 0 || paddingY > 0) {
                drawRect(
                    Color(0xFFE91E63), Offset(-paddingX, -paddingY),
                    Size(size.width + paddingX * 2, size.height + paddingY * 2),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
    }
}

internal fun recordStatusLinkTap(
    layout: TextLayoutResult?,
    position: Offset,
    offset: Int,
    link: AnnotatedString.Range<String>?,
    hasNonLinkAction: Boolean,
) {
    if (!BuildConfig.DEBUG || !StatusLinkDiagnostics.enabled || layout == null) return
    val density = layout.layoutInput.density.density
    val branch = if (link != null) "link" else if (hasNonLinkAction) "nonLink" else "none"
    Log.d("NagisaLinkHit", String.format(
        Locale.ROOT,
        "layout=%x upPx=(%.2f,%.2f) upDp=(%.2f,%.2f) sizePx=%dx%d density=%.3f line=%d offset=%d range=%s branch=%s",
        System.identityHashCode(layout), position.x, position.y, position.x / density, position.y / density,
        layout.size.width, layout.size.height, density, layout.getLineForVerticalPosition(position.y), offset,
        link?.let { "${it.start}..${it.end}" } ?: "-", branch,
    ))
}
