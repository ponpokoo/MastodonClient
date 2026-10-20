package io.github.ponpokoo.mastodonclient.feature.common

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import io.github.ponpokoo.mastodonclient.feature.web.WebLinkLauncher

@Composable
internal fun LinkActionsPopup(
    url: String,
    positionInWindow: IntOffset,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenInBrowser: ((String) -> Unit)? = null,
    copyText: String = url,
) {
    val context = LocalContext.current
    val launcher = remember(context) { WebLinkLauncher(context) }
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(positionInWindow, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) positionInWindow.x
                    else positionInWindow.x - popupContentSize.width
                val y = if (positionInWindow.y + popupContentSize.height + margin <= windowSize.height)
                    positionInWindow.y else positionInWindow.y - popupContentSize.height
                return IntOffset(
                    x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
                )
            }
        }
    }
    Popup(positionProvider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(shape = MaterialTheme.shapes.extraSmall, tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 160.dp, max = 280.dp)) {
                DropdownMenuItem(text = { Text("開く") }, onClick = onOpen)
                DropdownMenuItem(text = { Text("ブラウザーで開く") }, onClick = {
                    onDismiss()
                    if (onOpenInBrowser != null) onOpenInBrowser(url)
                    else if (!launcher.openInBrowser(url.toUri())) {
                        Toast.makeText(context, "リンクを開けるブラウザーが見つかりません", Toast.LENGTH_SHORT).show()
                    }
                })
                DropdownMenuItem(text = { Text("コピー") }, onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("リンク", copyText))
                    onDismiss()
                })
            }
        }
    }
}
