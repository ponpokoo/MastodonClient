package io.github.ponpokoo.mastodonclient.feature.common

import android.util.LruCache
import android.text.Spanned
import android.text.style.URLSpan
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat

@Suppress("DEPRECATION")
@Composable
fun StatusContentText(
    contentHtml: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onLinkClick: (String) -> Unit,
    onNonLinkClick: (() -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(contentHtml, linkColor) { htmlToAnnotatedString(contentHtml, linkColor) }
    ClickableText(
        text = text,
        modifier = modifier,
        style = style.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            val link = text.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()
            if (link != null) onLinkClick(link.item) else onNonLinkClick?.invoke()
        },
    )
}

private data class TextCacheKey(val html: String, val color: Color)
private val parsedTextCache = object : LruCache<TextCacheKey, AnnotatedString>(512 * 1024) {
    override fun sizeOf(key: TextCacheKey, value: AnnotatedString): Int =
        (key.html.length + value.length) * 2 + 128
}

internal fun htmlToAnnotatedString(html: String, linkColor: Color): AnnotatedString {
    val key = TextCacheKey(html, linkColor)
    parsedTextCache.get(key)?.let { return it }
    val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY) as Spanned
    return AnnotatedString.Builder(spanned.toString().trim()).apply {
        spanned.getSpans(0, spanned.length, URLSpan::class.java).forEach { span ->
            val start = spanned.getSpanStart(span).coerceAtMost(length)
            val end = spanned.getSpanEnd(span).coerceAtMost(length)
            if (start in 0 until end) {
                addStringAnnotation(URL_TAG, span.url, start, end)
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    start,
                    end,
                )
            }
        }
    }.toAnnotatedString().also { parsedTextCache.put(key, it) }
}

private const val URL_TAG = "url"
