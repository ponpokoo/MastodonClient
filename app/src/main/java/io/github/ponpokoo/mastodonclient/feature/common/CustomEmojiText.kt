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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage

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
): Map<String, InlineTextContent> = remember(source, emojis) {
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
