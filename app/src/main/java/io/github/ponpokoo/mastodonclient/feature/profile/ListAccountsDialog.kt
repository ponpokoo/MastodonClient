package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.feature.common.*

@Composable
internal fun ListAccountsDialog(
    state: ListAccountsUiState,
    preferences: AppPreferences,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onRemove: (String) -> Unit,
    onAccountClick: (String) -> Unit,
) {
    val busy = state.loading || state.removingId != null
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.55f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リストのメンバー") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("list_accounts")) {
                    items(state.accounts, key = { it.id }) { account ->
                        Row(Modifier.fillMaxWidth().testTag("list_account_${account.id}").padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable(enabled = state.removingId == null) {
                                onAccountClick(account.id)
                            }.heightIn(min = 48.dp).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AsyncImage(model = accountAvatarModel(account.avatarUrl),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp).clip(preferences.timelineDisplay.avatarIconShape.toShape()))
                                Column(Modifier.weight(1f)) {
                                    CustomEmojiText(account.displayName.ifBlank { account.accountName }, account.customEmojis,
                                        style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("@${account.accountName}", style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            TextButton(onClick = { onRemove(account.id) }, enabled = !busy,
                                modifier = Modifier.testTag("remove_list_account_${account.id}")) {
                                if (state.removingId == account.id) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else Text("リストから外す")
                            }
                        }
                    }
                    if (state.loading) item {
                        if (state.loaded) ListLoadingMoreIndicator()
                        else ListLoadingContent(Modifier.fillMaxWidth().height(120.dp))
                    }
                    if (state.error != null) item {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onLoadMore, enabled = !busy) { Text("再試行") }
                    } else if (!state.loading) {
                        if (state.loaded && state.accounts.isEmpty() && state.nextMaxId == null) item {
                            Text("登録されているアカウントはありません", Modifier.padding(vertical = 16.dp))
                        }
                        if (state.nextMaxId != null) item {
                            TextButton(onClick = onLoadMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("さらに読み込む")
                            }
                        }
                    }
                }
                state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
