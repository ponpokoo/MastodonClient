package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountListScreen(
    title: String,
    viewModel: AccountListViewModel,
    onBack: () -> Unit,
    onAccountClick: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var pendingUnfollow by remember { mutableStateOf<io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor?>(null) }
    LaunchedEffect(listState, state.accounts.size, state.endReached, state.errorMessage) {
        if (state.accounts.isNotEmpty() && !state.endReached && state.errorMessage == null) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .distinctUntilChanged().collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= listState.layoutInfo.totalItemsCount - 4) viewModel.loadMore()
                }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.errorMessage != null && state.accounts.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retry) { Text("再試行") }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
                if (state.relationshipError != null) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.relationshipError.orEmpty(), modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retryRelationships) { Text("再試行") }
                    }
                }
                items(state.accounts, key = { it.id }) { account ->
                    val relationship = state.relationships[account.id]
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable { onAccountClick(account.id) },
                        leadingContent = {
                            AsyncImage(
                                model = account.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).clip(CircleShape)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        },
                        headlineContent = {
                            CustomEmojiText(
                                account.displayName,
                                account.customEmojis,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("@${account.accountName}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                                if (account.locked) {
                                    Icon(Icons.Outlined.Lock, contentDescription = "非公開アカウント",
                                        modifier = Modifier.padding(start = 4.dp).size(15.dp))
                                }
                            }
                        },
                        trailingContent = {
                            if (account.id != state.viewerAccountId) {
                                val label = when {
                                    relationship?.requested == true -> "リクエスト中"
                                    relationship?.following == true -> "フォロー解除"
                                    account.locked -> "リクエスト"
                                    relationship == null -> "確認中"
                                    else -> "フォロー"
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (relationship?.following == true) pendingUnfollow = account
                                        else viewModel.toggleFollow(account.id)
                                    },
                                    enabled = relationship != null && !relationship.requested &&
                                        account.id !in state.mutatingAccountIds,
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                ) { Text(label, maxLines = 1) }
                            }
                        },
                    )
                    HorizontalDivider()
                }
                if (state.accounts.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("アカウントはいません") }
                } else if (state.isLoadingMore) item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (state.errorMessage != null) item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::loadMore) { Text("再試行") }
                    }
                } else if (!state.endReached) item {
                    TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") }
                }
            }
        }
    }
    pendingUnfollow?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingUnfollow = null },
            title = { Text("フォローを解除しますか？") },
            text = { Text("${account.displayName.ifBlank { account.accountName }}さんのフォローを解除します。") },
            confirmButton = { TextButton(onClick = {
                viewModel.toggleFollow(account.id)
                pendingUnfollow = null
            }) { Text("フォロー解除") } },
            dismissButton = { TextButton(onClick = { pendingUnfollow = null }) { Text("キャンセル") } },
        )
    }
}
