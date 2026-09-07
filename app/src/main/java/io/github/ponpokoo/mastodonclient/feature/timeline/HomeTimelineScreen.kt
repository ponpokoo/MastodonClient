package io.github.ponpokoo.mastodonclient.feature.timeline

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private enum class MainDestination(
    val label: String,
    val icon: ImageVector,
) {
    Home("ホーム", Icons.Outlined.Home),
    Explore("探索", Icons.Outlined.Search),
    Notifications("通知", Icons.Outlined.NotificationsNone),
    Profile("プロフィール", Icons.Outlined.PersonOutline),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeTimelineScreen(
    viewModel: TimelineViewModel,
    onLoggedOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(MainDestination.Home) }

    LaunchedEffect(state.requiresLogin) {
        if (state.requiresLogin) onLoggedOut()
    }
    LaunchedEffect(state.errorMessage, state.statuses.isNotEmpty()) {
        if (state.statuses.isNotEmpty()) {
            state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
        }
    }

    Scaffold(
        topBar = {
            if (destination == MainDestination.Home) {
                TimelineTopBar(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refresh,
                    onLogout = viewModel::logout,
                )
            } else {
                TopAppBar(title = { Text(destination.label) })
            }
        },
        bottomBar = {
            NavigationBar {
                MainDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (destination == MainDestination.Home) {
                FloatingActionButton(
                    onClick = {
                        scope.launch { snackbarHostState.showSnackbar("投稿機能は次の実装で追加します") }
                    },
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = "新規投稿")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (destination) {
            MainDestination.Home -> TimelineContent(
                state = state,
                padding = padding,
                onRetry = viewModel::retry,
                onLoadMore = viewModel::loadNextPage,
                onUnavailableAction = { label ->
                    scope.launch { snackbarHostState.showSnackbar("$label は次の実装で追加します") }
                },
            )
            else -> FeaturePlaceholder(destination.label, padding)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TimelineTopBar(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    var feedMenuOpen by remember { mutableStateOf(false) }
    var settingsMenuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Box {
                Row(
                    modifier = Modifier.clickable { feedMenuOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ホーム")
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = "フィードを切り替える")
                }
                DropdownMenu(expanded = feedMenuOpen, onDismissRequest = { feedMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("ホーム") }, onClick = { feedMenuOpen = false })
                    DropdownMenuItem(text = { Text("ローカル（準備中）") }, onClick = {}, enabled = false)
                    DropdownMenuItem(text = { Text("連合（準備中）") }, onClick = {}, enabled = false)
                }
            }
        },
        actions = {
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "再読み込み",
                        modifier = Modifier.testTag("timeline_refresh"),
                    )
                }
            }
            Box {
                IconButton(onClick = { settingsMenuOpen = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "設定")
                }
                DropdownMenu(
                    expanded = settingsMenuOpen,
                    onDismissRequest = { settingsMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("表示設定（準備中）") },
                        onClick = {},
                        enabled = false,
                    )
                    DropdownMenuItem(
                        text = { Text("ログアウト") },
                        onClick = {
                            settingsMenuOpen = false
                            onLogout()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun TimelineContent(
    state: TimelineUiState,
    padding: PaddingValues,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onUnavailableAction: (String) -> Unit,
) {
    when {
        state.isInitialLoading -> CenteredMessage(padding) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("ホームタイムラインを読み込んでいます")
        }
        state.statuses.isEmpty() && state.errorMessage != null -> CenteredMessage(padding) {
            Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("再試行") }
        }
        state.statuses.isEmpty() -> CenteredMessage(padding) {
            Text("ホームタイムラインに投稿がありません")
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("再読み込み") }
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("timeline_list"),
        ) {
            itemsIndexed(
                items = state.statuses,
                key = { _, status -> status.timelineId },
            ) { index, status ->
                if (index >= state.statuses.lastIndex - 3) {
                    LaunchedEffect(state.nextMaxId) { onLoadMore() }
                }
                StatusCard(status, onUnavailableAction)
                HorizontalDivider()
            }
            if (state.isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (state.errorMessage != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onRetry) { Text("続きを再試行") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: TimelineStatus,
    onUnavailableAction: (String) -> Unit,
) {
    var contentExpanded by rememberSaveable(status.statusId) {
        mutableStateOf(status.spoilerText.isBlank())
    }
    var mediaRevealed by rememberSaveable(status.statusId) { mutableStateOf(!status.sensitive) }
    val plainContent = remember(status.contentHtml) {
        HtmlCompat.fromHtml(status.contentHtml, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("timeline_status"),
    ) {
        status.boostedBy?.let {
            Text(
                "${it.displayName}さんがブースト",
                modifier = Modifier.padding(start = 48.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = status.author.avatarUrl,
                contentDescription = "${status.author.displayName}のプロフィール画像",
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status.author.displayName,
                        modifier = Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        relativeTime(status.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "@${status.author.accountName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { onUnavailableAction("投稿メニュー") },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "投稿メニュー")
            }
        }

        Column(modifier = Modifier.padding(start = 48.dp)) {
            if (status.spoilerText.isNotBlank()) {
                Text(status.spoilerText, modifier = Modifier.padding(top = 8.dp))
                TextButton(
                    onClick = { contentExpanded = !contentExpanded },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(if (contentExpanded) "隠す" else "表示する")
                }
            }
            if (contentExpanded && plainContent.isNotBlank()) {
                Text(
                    text = plainContent,
                    modifier = Modifier.padding(top = if (status.spoilerText.isBlank()) 8.dp else 0.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight),
                )
            }
            if (status.mediaAttachments.isNotEmpty() && contentExpanded) {
                Spacer(Modifier.height(8.dp))
                if (mediaRevealed) {
                    MediaGrid(status.mediaAttachments)
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(144.dp)
                            .clickable { mediaRevealed = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("閲覧注意のメディアを表示")
                        }
                    }
                }
            }
            StatusActionRow(
                status = status,
                onReply = { onUnavailableAction("返信") },
                onBoost = { onUnavailableAction("ブースト") },
                onFavourite = { onUnavailableAction("お気に入り") },
                onShare = {
                    val url = status.url
                    if (url == null) {
                        onUnavailableAction("共有")
                    } else {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                },
                                "投稿を共有",
                            ),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun MediaGrid(attachments: List<MediaAttachment>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        attachments.take(4).chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rowItems.forEach { media ->
                    AsyncImage(
                        model = media.previewUrl ?: media.url,
                        contentDescription = media.description ?: "添付メディア",
                        modifier = Modifier.weight(1f).aspectRatio(if (attachments.size == 1) 1.6f else 1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (rowItems.size == 1 && attachments.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusActionRow(
    status: TimelineStatus,
    onReply: () -> Unit,
    onBoost: () -> Unit,
    onFavourite: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatusAction(Icons.Outlined.ChatBubbleOutline, "返信", status.repliesCount, onReply)
        StatusAction(Icons.Outlined.Repeat, "ブースト", status.boostsCount, onBoost)
        StatusAction(Icons.Outlined.FavoriteBorder, "お気に入り", status.favouritesCount, onFavourite)
        StatusAction(Icons.Outlined.Share, "共有", null, onShare)
    }
}

@Composable
private fun StatusAction(
    icon: ImageVector,
    label: String,
    count: Long?,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
        if (count != null && count > 0) {
            Text(
                compactCount(count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CenteredMessage(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun FeaturePlaceholder(label: String, padding: PaddingValues) {
    CenteredMessage(padding) {
        Text("$label は次の実装で追加します", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun relativeTime(value: String): String = runCatching {
    val instant = Instant.parse(value)
    val duration = Duration.between(instant, Instant.now()).coerceAtLeast(Duration.ZERO)
    when {
        duration.seconds < 60 -> "今"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}分"
        duration.toHours() < 24 -> "${duration.toHours()}時間"
        duration.toDays() < 7 -> "${duration.toDays()}日"
        else -> DateTimeFormatter.ofPattern("M月d日")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
}.getOrDefault("")

private fun compactCount(count: Long): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0).replace(".0K", "K")
    else -> String.format(Locale.US, "%.1fM", count / 1_000_000.0).replace(".0M", "M")
}
