package io.github.ponpokoo.mastodonclient.feature.status

import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmationAction
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText



@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StatusMenuDialog(
    status: TimelineStatus,
    isOwnStatus: Boolean,
    onDismiss: () -> Unit,
    onOpenBrowser: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToList: () -> Unit,
    onUnfollow: () -> Unit,
    onMute: () -> Unit,
    onBlock: () -> Unit,
    onReport: () -> Unit,
    moderation: io.github.ponpokoo.mastodonclient.feature.common.AccountModerationMenuState? = null,
    onRetryRelationship: () -> Unit = {},
) {
    val context = LocalContext.current
    val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.55f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CustomEmojiText(
                status.author.displayName,
                status.author.customEmojis,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxContentHeight).verticalScroll(rememberScrollState())) {
                @Composable fun Action(
                    label: String,
                    icon: ImageVector,
                    destructive: Boolean = false,
                    enabled: Boolean = true,
                    action: () -> Unit,
                ) {
                    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = enabled, onClick = action)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = color)
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color)
                    }
                }
                Action("本文をコピー", Icons.Outlined.ContentCopy) {
                    val plainText = androidx.core.text.HtmlCompat.fromHtml(
                        status.contentHtml,
                        androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY,
                    ).toString().trim()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("投稿本文", plainText))
                    onDismiss()
                }
                if (isOwnStatus) {
                    Action(if (status.pinned) "プロフィールへの固定解除" else "プロフィールに固定", Icons.Outlined.PushPin, action = onPin)
                    Action("ブラウザで開く", Icons.Outlined.Public, action = onOpenBrowser)
                    Action("編集", Icons.Outlined.Edit, action = onEdit)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Action("削除", Icons.Outlined.Delete, destructive = true, action = onDelete)
                } else {
                    Action("ブラウザで開く", Icons.Outlined.Public, action = onOpenBrowser)
                    Action("リストに追加", Icons.Outlined.PlaylistAdd, action = onAddToList)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Action("フォロー解除", Icons.Outlined.PersonRemove, action = onUnfollow)
                    Action(if (moderation?.relationship?.muting == true) "ミュート（解除）" else "ミュート",
                        Icons.Outlined.VolumeOff, destructive = true,
                        enabled = moderation?.relationship != null && !moderation.loading && !moderation.busy, action = onMute)
                    Action(if (moderation?.relationship?.blocking == true) "ブロック（解除）" else "ブロック",
                        Icons.Outlined.Block, destructive = true,
                        enabled = moderation?.relationship != null && !moderation.loading && !moderation.busy, action = onBlock)
                    Action("報告", Icons.Outlined.Flag, destructive = true, action = onReport)
                    moderation?.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetryRelationship) { Text("再試行") } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
internal fun ConfirmStatusActionDialog(action: StatusConfirmationAction, status: TimelineStatus, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, message) = when (action) {
        StatusConfirmationAction.Delete -> "投稿を削除" to "この投稿を削除します。この操作は元に戻せません。"
        StatusConfirmationAction.Unfollow -> "フォロー解除" to "${status.author.displayName}さんのフォローを解除しますか？"
        StatusConfirmationAction.Unmute -> "ミュート解除" to "${status.author.displayName}さんのミュートを解除しますか？"
        StatusConfirmationAction.Unblock -> "ブロック解除" to "${status.author.displayName}さんのブロックを解除しますか？"
        StatusConfirmationAction.Mute -> "ミュート" to "${status.author.displayName}さんをミュートしますか？"
        StatusConfirmationAction.Block -> "ブロック" to "${status.author.displayName}さんをブロックしますか？"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("実行") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
internal fun StatusReportDialog(status: TimelineStatus, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${status.author.displayName}さんを報告") },
        text = { OutlinedTextField(comment, { comment = it.take(1000) }, label = { Text("理由・補足") }, minLines = 3) },
        confirmButton = { TextButton(enabled = comment.isNotBlank(), onClick = { onSubmit(comment) }) { Text("送信") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ListPickerSheet(
    lists: List<io.github.ponpokoo.mastodonclient.domain.model.MastodonList>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("追加するリストを選択", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        when {
            loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            lists.isEmpty() -> Text("利用できるリストがありません", Modifier.padding(20.dp))
            else -> lists.forEach { list ->
                TextButton(onClick = { onSelected(list.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(list.title, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
