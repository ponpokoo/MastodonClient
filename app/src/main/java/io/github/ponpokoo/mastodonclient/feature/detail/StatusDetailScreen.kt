package io.github.ponpokoo.mastodonclient.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusDetailScreen(
    viewModel: StatusDetailViewModel,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onReply: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (MediaAttachment) -> Unit,
    onStatusClick: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val status = state.detail?.status

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(status?.let { "${it.author.displayName}さんの投稿" } ?: "投稿") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading && status == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            status == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.errorMessage ?: "投稿を表示できませんでした")
                TextButton(onClick = viewModel::retry) { Text("再試行") }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("status_detail"),
            ) {
                state.detail?.ancestors?.let { ancestors ->
                    if (ancestors.isNotEmpty()) {
                        item { Text("この会話の前の投稿", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                        items(ancestors, key = { "ancestor-${it.timelineId}" }) { ancestor ->
                            StatusCard(
                                status = ancestor,
                                onStatusClick = onStatusClick,
                                onAuthorClick = onAccountClick,
                                onMediaClick = onMediaClick,
                                onOpenLink = onOpenLink,
                                onReply = { onReply(ancestor.statusId) },
                                onUnavailableAction = {},
                                displayPreferences = preferences.timelineDisplay,
                                gifAutoplay = preferences.gifAutoplay,
                                videoAutoplay = preferences.videoAutoplay,
                            )
                            HorizontalDivider()
                        }
                    }
                }
                item {
                    StatusCard(
                        status = status,
                        onStatusClick = null,
                        onAuthorClick = onAccountClick,
                        onMediaClick = onMediaClick,
                        onOpenLink = onOpenLink,
                        onReply = { onReply(status.statusId) },
                        onBoost = viewModel::toggleReblog,
                        onFavourite = viewModel::toggleFavourite,
                        onReact = viewModel::setReaction,
                        onUnavailableAction = {},
                        displayPreferences = preferences.timelineDisplay,
                        gifAutoplay = preferences.gifAutoplay,
                        videoAutoplay = preferences.videoAutoplay,
                    )
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            Row(Modifier.clickable(onClick = viewModel::showBoosters).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp)); Text("${status.boostsCount} ブースト", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(Modifier.clickable(onClick = viewModel::showFavourites).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp)); Text("${status.favouritesCount} お気に入り", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp)); Text(buildString { append(exactDate(status.createdAt)); status.applicationName?.let { append("  ·  ").append(it) } }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                    }
                }
                state.detail?.descendants?.let { replies ->
                    if (replies.isNotEmpty()) {
                        item { Text("返信", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                        items(replies, key = { it.timelineId }) { reply ->
                            StatusCard(
                                reply,
                                onStatusClick = onStatusClick,
                                onAuthorClick = onAccountClick,
                                onMediaClick = onMediaClick,
                                onOpenLink = onOpenLink,
                                onReply = { onReply(reply.statusId) },
                                onUnavailableAction = {},
                                displayPreferences = preferences.timelineDisplay,
                                gifAutoplay = preferences.gifAutoplay,
                                videoAutoplay = preferences.videoAutoplay,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    state.accountListTitle?.let { accountListTitle ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissAccounts) {
            Text(
                accountListTitle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.isLoadingAccounts) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.accounts.isEmpty()) {
                Text("表示できるアカウントはありません", Modifier.padding(20.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(state.accounts, key = StatusAuthor::id) { account ->
                        AccountRow(account) { onAccountClick(account.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: StatusAuthor, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = account.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(account.displayName, style = MaterialTheme.typography.titleSmall)
            Text("@${account.accountName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun exactDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("H:mm  ·  yyyy年M月d日", Locale.JAPAN)
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)
