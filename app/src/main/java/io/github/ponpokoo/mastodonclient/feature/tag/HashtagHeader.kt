package io.github.ponpokoo.mastodonclient.feature.tag

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun HashtagHeader(name: String, fromTrend: Boolean, following: Boolean?, busy: Boolean,
    onBack: () -> Unit, onToggle: () -> Unit, onRetry: () -> Unit) {
    var menu by remember(name) { mutableStateOf(false) }
    var confirm by remember(name) { mutableStateOf(false) }
    val toggle = { if (following == true) confirm = true else onToggle() }
    TopAppBar(title = { Text("#$name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") } },
        actions = {
            if (fromTrend) TagSubscriptionButton(following == true, following != null && !busy,
                toggle, Modifier.testTag("tag_subscription"))
            else {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("tag_menu")) { Icon(Icons.Outlined.MoreVert, "ハッシュタグのメニュー") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (following == null) "購読状態を確認" else if (following) "購読を解除" else "購読する") },
                        enabled = !busy, onClick = { menu = false; if (following == null) onRetry() else toggle() })
                }
            }
        })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("購読を解除しますか？") },
        text = { Text("#$name") }, confirmButton = { TextButton(onClick = { confirm = false; onToggle() }) { Text("解除する") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } })
}
