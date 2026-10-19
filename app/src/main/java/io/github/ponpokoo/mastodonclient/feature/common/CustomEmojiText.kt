package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

private val shortcodePattern = Regex(":([A-Za-z0-9_@.+-]+):")
private const val emojiIdPrefix = "custom-emoji:"

/** Only shortcodes advertised by this account or status are replaced. Unknown ones remain readable. */
internal fun replaceCustomEmojiShortcodes(
    source: AnnotatedString,
    emojis: Map<String, String>,
): AnnotatedString {
    if (emojis.isEmpty() || ':' !in source.text) return source
    val matches = shortcodePattern.findAll(source.text)
        .filter { emojis.containsKey(it.groupValues[1]) }
        .toList()
    if (matches.isEmpty()) return source
    return AnnotatedString.Builder().apply {
        var cursor = 0
        matches.forEach { match ->
            append(source.subSequence(cursor, match.range.first))
            appendInlineContent(emojiIdPrefix + match.groupValues[1], match.value)
            cursor = match.range.last + 1
        }
        append(source.subSequence(cursor, source.length))
    }.toAnnotatedString()
}

@Composable
internal fun rememberEmojiInlineContent(
    source: String,
    emojis: Map<String, String>,
): Map<String, InlineTextContent> {
    return remember(source, emojis) {
        shortcodePattern.findAll(source).map { it.groupValues[1] }.distinct().mapNotNull { name ->
            val url = emojis[name]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            (emojiIdPrefix + name) to InlineTextContent(
                Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.Center),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = ":$name:",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }.toMap()
    }
}

/** Explicit opt-in; names, CWs and profile text keep their existing em sizing. */
internal data class EmojiInlineSize(val height: Dp, val maxWidth: Dp)

internal fun emojiInlineDimensions(ratio: Float, size: EmojiInlineSize): DpSize {
    val safeRatio = ratio.takeIf { it.isFinite() && it > 0f } ?: 1f
    // Keep a full-height slot even when the available width forces the image to shrink.
    return DpSize(minOf(size.height * safeRatio, size.maxWidth), size.height)
}

@Composable
internal fun rememberBodyEmojiContent(
    source: String,
    emojis: Map<String, String>,
    size: EmojiInlineSize,
): BodyEmojiContent {
    val density = LocalDensity.current
    val names = remember(source, emojis) {
        shortcodePattern.findAll(source).map { it.groupValues[1] }
            .filter { !emojis[it].isNullOrBlank() }.toList()
    }
    val used = remember(names, emojis) { names.distinct().associateWith { emojis.getValue(it) } }
    val urls = remember(used) { used.values.distinct() }
    val images = rememberEmojiImages(urls, emojis)
    val inline = remember(used, images.ratios, size, density) {
        used.map { (name, url) ->
            val dimensions = emojiInlineDimensions(images.ratios.getValue(url), size)
            val placeholder = with(density) {
                // Inverse conversion includes Android's nonlinear font scaling.
                Placeholder(dimensions.width.toSp(), dimensions.height.toSp(), PlaceholderVerticalAlign.Center)
            }
            // Text still measures its normal placeholders and exposes the original shortcode text.
            // Paint the images together, without an Image/modifier/semantics subtree per occurrence.
            (emojiIdPrefix + name) to InlineTextContent(placeholder) {}
        }.toMap()
    }
    val orderedPainters = remember(names, used, images.painters) {
        names.map { images.painters.getValue(used.getValue(it)) }
    }
    return remember(inline, orderedPainters) { BodyEmojiContent(inline, orderedPainters) }
}

internal class BodyEmojiContent(
    val inline: Map<String, InlineTextContent>,
    val painters: List<AsyncImagePainter>,
)

internal fun Modifier.drawBodyEmojis(content: BodyEmojiContent?, layout: () -> TextLayoutResult?): Modifier {
    if (content == null || content.painters.isEmpty()) return this
    return drawWithContent {
        drawContent()
        layout()?.placeholderRects?.forEachIndexed { index, rect ->
            val painter = content.painters.getOrNull(index) ?: return@forEachIndexed
            if (rect == null || rect.isEmpty) return@forEachIndexed
            // Reading intrinsicSize/drawing the painter here invalidates drawing, not composition.
            val intrinsic = painter.intrinsicSize
            if (!intrinsic.width.isFinite() || !intrinsic.height.isFinite() || intrinsic.width <= 0 || intrinsic.height <= 0) {
                return@forEachIndexed
            }
            val scale = minOf(rect.width / intrinsic.width, rect.height / intrinsic.height)
            val imageSize = Size(intrinsic.width * scale, intrinsic.height * scale)
            translate(rect.left + (rect.width - imageSize.width) / 2, rect.top + (rect.height - imageSize.height) / 2) {
                with(painter) { draw(imageSize) }
            }
        }
    }
}

@Composable
fun CustomEmojiText(
    text: String,
    emojis: Map<String, String>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val annotated = remember(text, emojis) {
        replaceCustomEmojiShortcodes(AnnotatedString(text), emojis)
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = rememberEmojiInlineContent(text, emojis),
    )
}
