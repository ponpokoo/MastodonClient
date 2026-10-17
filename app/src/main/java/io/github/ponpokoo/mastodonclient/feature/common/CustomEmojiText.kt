package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.util.LruCache
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import java.util.WeakHashMap

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
    size: EmojiInlineSize? = null,
): Map<String, InlineTextContent> {
    if (size != null) return rememberSizedEmojiInlineContent(source, emojis, size)
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

private val emojiRatioCaches = WeakHashMap<ImageLoader, LruCache<String, Float>>()

@Composable
private fun rememberSizedEmojiInlineContent(
    source: String,
    emojis: Map<String, String>,
    size: EmojiInlineSize,
): Map<String, InlineTextContent> {
    val context = LocalContext.current
    val density = LocalDensity.current
    val loader = SingletonImageLoader.get(context)
    val cache = remember(loader) { emojiRatioCaches.getOrPut(loader) { LruCache(512) } }
    val used = remember(source, emojis) {
        shortcodePattern.findAll(source).map { it.groupValues[1] }.distinct().mapNotNull { name ->
            emojis[name]?.takeIf(String::isNotBlank)?.let { name to it }
        }.toMap()
    }
    // One painter/request per URL, also used for drawing. Bound decoding without distorting it.
    val painters = used.values.distinct().associateWith { url ->
        key(url) {
            val request = remember(context, url) { ImageRequest.Builder(context).data(url).size(1024, 128).build() }
            rememberAsyncImagePainter(request, imageLoader = loader, contentScale = ContentScale.Fit)
        }
    }
    val states = painters.mapValues { (_, painter) -> painter.state.collectAsState().value }
    var ratios by remember(used, loader) {
        mutableStateOf(used.values.distinct().associateWith { cache.get(it) ?: 1f })
    }
    LaunchedEffect(states) {
        // Commit dimensions together, so a row with several new emoji only reflows once.
        // Cached URLs already have their final slot on the first frame after scrolling back.
        if (states.values.all { it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error }) {
            ratios = states.mapValues { (url, state) ->
                val image = (state as? AsyncImagePainter.State.Success)?.result?.image
                val ratio = image?.takeIf { it.width > 0 && it.height > 0 }
                    ?.let { it.width.toFloat() / it.height }
                if (ratio != null) cache.put(url, ratio)
                ratio ?: ratios.getValue(url)
            }
        }
    }
    return used.map { (name, url) ->
        val dimensions = emojiInlineDimensions(ratios.getValue(url), size)
        val placeholder = with(density) {
            // toSp is the inverse density conversion, including Android's nonlinear font scaling.
            Placeholder(dimensions.width.toSp(), dimensions.height.toSp(), PlaceholderVerticalAlign.Center)
        }
        (emojiIdPrefix + name) to InlineTextContent(placeholder) {
            Image(painter = painters.getValue(url), contentDescription = ":$name:",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }.toMap()
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
