package io.github.ponpokoo.mastodonclient.feature.tag

import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HashtagTimelineScreen(
    hashtag: String,
    viewModel: HashtagTimelineViewModel,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onStatusClick: (String) -> Unit,
    onReply: (String) -> Unit,
    onQuote: (TimelineStatus) -> Unit,
    onOpenLink: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.statuses.size, state.nextMaxId) {
        if (state.nextMaxId != null && !state.endReached) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .distinctUntilChanged().collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= listState.layoutInfo.totalItemsCount - 4) viewModel.loadMore()
                }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("#$hashtag") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.statuses.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.statuses.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.errorMessage ?: "投稿はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = viewModel::refresh) { Text("再試行") }
            }
            else -> PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(state.statuses, key = { it.timelineId }) { status ->
                        StatusCard(
                            status = status,
                            onStatusClick = onStatusClick,
                            onAuthorClick = onAccountClick,
                            onMediaClick = onMediaClick,
                            onOpenLink = onOpenLink,
                            onReply = { onReply(status.statusId) },
                            onBoost = { viewModel.toggleReblog(status) },
                            onQuote = { onQuote(status) },
                            onFavourite = { viewModel.toggleFavourite(status) },
                            onReact = { viewModel.setReaction(status, it) },
                            onUnavailableAction = {},
                            displayPreferences = preferences.timelineDisplay,
                            gifAutoplay = preferences.gifAutoplay,
                            videoAutoplay = preferences.videoAutoplay,
                        )
                        HorizontalDivider()
                    }
                    if (state.isLoadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                        }
                    } else if (!state.endReached && state.nextMaxId != null) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = viewModel::loadMore) { Text("さらに読み込む") }
                            }
                        }
                    }
                }
            }
        }
    }
}
