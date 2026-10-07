package io.github.ponpokoo.mastodonclient.feature.tag

import android.content.Intent
import androidx.core.net.toUri
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusMenuDialog
import io.github.ponpokoo.mastodonclient.feature.timeline.ConfirmStatusActionDialog
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusReportDialog
import io.github.ponpokoo.mastodonclient.feature.timeline.ListPickerSheet
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.common.AppPullToRefreshBox
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HashtagTimelineScreen(
    hashtag: String,
    viewModel: HashtagTimelineViewModel,
    actionsViewModel: StatusActionsViewModel,
    currentAccountId: String?,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onStatusClick: (String) -> Unit,
    onReply: (String) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onOpenLink: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onEditStatus: (String) -> Unit,
    fromTrend: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actionsState by actionsViewModel.uiState.collectAsStateWithLifecycle()
    val moderationMenu by actionsViewModel.moderationMenuState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuStatus by remember(state.sessionKey) { mutableStateOf<TimelineStatus?>(null) }
    var confirmation by remember(state.sessionKey) { mutableStateOf<Pair<String, TimelineStatus>?>(null) }
    var reportStatus by remember(state.sessionKey) { mutableStateOf<TimelineStatus?>(null) }
    var listStatus by remember(state.sessionKey) { mutableStateOf<TimelineStatus?>(null) }
    val listState = key(state.sessionKey) { rememberLazyListState() }
    val snackbarHostState = remember(state.sessionKey) { SnackbarHostState() }
    LaunchedEffect(state.sessionKey, state.actionMessage) {
        state.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(state.sessionKey, actionsState.actionMessage) {
        actionsState.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            actionsViewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(state.sessionKey, state.subscriptionError) { state.subscriptionError?.let {
        if (snackbarHostState.showSnackbar(it, actionLabel = "再試行") == SnackbarResult.ActionPerformed) viewModel.retrySubscription()
    } }
    LaunchedEffect(listState, state.statuses.size, state.nextMaxId) {
        if (state.nextMaxId != null && !state.endReached) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .distinctUntilChanged().collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= listState.layoutInfo.totalItemsCount - 4) viewModel.loadMore()
                }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            key(state.sessionKey) { HashtagHeader(hashtag, fromTrend, state.tag?.following, state.isChangingSubscription,
                onBack, viewModel::toggleSubscription, viewModel::retrySubscription) }
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
            else -> AppPullToRefreshBox(
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
                            onQuote = { mode -> onQuote(status, mode) },
                            onFavourite = { viewModel.toggleFavourite(status) },
                            onReact = { viewModel.setReaction(status, it) },
                            onUnavailableAction = {},
                            onMoreClick = { menuStatus = it },
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
    LaunchedEffect(state.sessionKey, menuStatus?.author?.id) {
        menuStatus?.author?.id?.let { actionsViewModel.loadModerationMenu(it) }
    }
    menuStatus?.let { status ->
        StatusMenuDialog(
            status = status,
            moderation = moderationMenu.takeIf { it.accountId == status.author.id },
            onRetryRelationship = { actionsViewModel.loadModerationMenu(status.author.id) },
            isOwnStatus = status.author.id == currentAccountId,
            onDismiss = { menuStatus = null },
            onOpenBrowser = {
                menuStatus = null
                status.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
            },
            onPin = { menuStatus = null; actionsViewModel.setPinned(status) },
            onEdit = { menuStatus = null; onEditStatus(status.statusId) },
            onDelete = { menuStatus = null; confirmation = "delete" to status },
            onAddToList = { menuStatus = null; listStatus = status; actionsViewModel.loadLists() },
            onUnfollow = { menuStatus = null; confirmation = "unfollow" to status },
            onMute = { menuStatus = null; confirmation = (if (moderationMenu.relationship?.muting == true) "unmute" else "mute") to status },
            onBlock = { menuStatus = null; confirmation = (if (moderationMenu.relationship?.blocking == true) "unblock" else "block") to status },
            onReport = { menuStatus = null; reportStatus = status },
        )
    }
    confirmation?.let { (action, status) ->
        ConfirmStatusActionDialog(action, status, { confirmation = null }) {
            when (action) {
                "delete" -> actionsViewModel.deleteStatus(status)
                "unfollow" -> actionsViewModel.unfollow(status)
                "mute" -> actionsViewModel.mute(status)
                "unmute" -> actionsViewModel.mute(status, false)
                "block" -> actionsViewModel.block(status)
                "unblock" -> actionsViewModel.block(status, false)
            }
            confirmation = null
        }
    }
    reportStatus?.let { status ->
        StatusReportDialog(status, { reportStatus = null }) { comment ->
            actionsViewModel.report(status, comment)
            reportStatus = null
        }
    }
    listStatus?.let { status ->
        ListPickerSheet(actionsState.lists, actionsState.isLoadingLists, { listStatus = null }) { listId ->
            actionsViewModel.addToList(status, listId)
            listStatus = null
        }
    }
}
