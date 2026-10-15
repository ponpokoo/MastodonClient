package io.github.ponpokoo.mastodonclient.feature.common

import android.util.LruCache
import android.text.Spanned
import android.text.style.URLSpan
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.core.net.toUri

@Suppress("DEPRECATION")
@Composable
fun StatusContentText(
    contentHtml: String,
    customEmojis: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onLinkClick: (String) -> Unit,
    onNonLinkClick: (() -> Unit)? = null,
    onOpenInBrowser: ((String) -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val source = remember(contentHtml, linkColor) { htmlToAnnotatedString(contentHtml, linkColor) }
    val text = remember(source, customEmojis) { replaceCustomEmojiShortcodes(source, customEmojis) }
    val layoutResult = remember { arrayOfNulls<TextLayoutResult>(1) }
    var pressedLink by remember(text) { mutableStateOf<AnnotatedString.Range<String>?>(null) }
    var menuLink by remember(text) { mutableStateOf<AnnotatedString.Range<String>?>(null) }
    val textOrigin = remember { arrayOf(Offset.Zero) }
    var menuPosition by remember { mutableStateOf(IntOffset.Zero) }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f)
    fun linkAt(position: Offset): AnnotatedString.Range<String>? {
        val offset = layoutResult[0]?.getOffsetForPosition(position) ?: return null
        return text.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()
    }
    Text(
        text = text,
        modifier = modifier.statusLinkDiagnostics(text) { layoutResult[0] }
            .onGloballyPositioned { textOrigin[0] = it.positionInWindow() }
            .drawBehind {
                (menuLink ?: pressedLink)?.let { link ->
                    layoutResult[0]?.let { drawRoundedLinkHighlight(it, link, highlightColor) }
                }
            }.pointerInput(text, onLinkClick, onNonLinkClick) {
                detectTapGestures(
                    onPress = { position ->
                        pressedLink = linkAt(position)
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressedLink = null
                        }
                    },
                    onLongPress = { position ->
                        linkAt(position)?.let { link ->
                            val anchor = textOrigin[0] + position
                            menuPosition = IntOffset(anchor.x.toInt(), anchor.y.toInt())
                            menuLink = link
                        }
                    },
                    onTap = { position ->
                        val offset = layoutResult[0]?.getOffsetForPosition(position) ?: return@detectTapGestures
                        val link = text.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()
                        recordStatusLinkTap(layoutResult[0], position, offset, link, onNonLinkClick != null)
                        if (link != null) onLinkClick(link.item) else onNonLinkClick?.invoke()
                    },
                )
            },
        style = style.copy(color = MaterialTheme.colorScheme.onSurface),
        inlineContent = rememberEmojiInlineContent(source.text, customEmojis),
        onTextLayout = { layoutResult[0] = it },
    )
    menuLink?.let { link ->
        LinkActionsPopup(
            url = link.item,
            positionInWindow = menuPosition,
            onDismiss = { menuLink = null },
            onOpen = { menuLink = null; onLinkClick(link.item) },
            onOpenInBrowser = onOpenInBrowser,
            copyText = linkCopyText(text, link),
        )
    }
}

private fun linkCopyText(text: AnnotatedString, link: AnnotatedString.Range<String>): String {
    val displayed = text.text.substring(link.start, link.end).trim()
    val segments = link.item.toUri().pathSegments
    val tagIndex = segments.indexOf("tags")
    return if (displayed.startsWith('#') && tagIndex >= 0 && !segments.getOrNull(tagIndex + 1).isNullOrBlank())
        displayed else link.item
}

private fun DrawScope.drawRoundedLinkHighlight(
    layout: TextLayoutResult,
    link: AnnotatedString.Range<String>,
    color: Color,
) {
    // Keep visual runs separate for wrapped/BiDi text.
    val radius = CornerRadius(3.dp.toPx())
    (link.start until link.end).groupBy(layout::getLineForOffset).values.forEach { offsets ->
        val boxes = offsets.map(layout::getBoundingBox).filterNot(Rect::isEmpty).sortedBy { it.left }
        var run: Rect? = null
        fun drawRun() { run?.let { drawRoundRect(color, it.topLeft, it.size, radius) } }
        boxes.forEach { box ->
            val current = run
            if (current != null && box.left <= current.right + .5f) {
                run = Rect(minOf(current.left, box.left), minOf(current.top, box.top),
                    maxOf(current.right, box.right), maxOf(current.bottom, box.bottom))
            } else {
                drawRun()
                run = box
            }
        }
        drawRun()
    }
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
    return AnnotatedString.Builder(spanned.toString().trim().replace('\u2028', '\n')).apply {
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
