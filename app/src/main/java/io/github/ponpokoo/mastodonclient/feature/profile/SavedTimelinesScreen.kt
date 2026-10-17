package io.github.ponpokoo.mastodonclient.feature.profile

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.*
import io.github.ponpokoo.mastodonclient.feature.media.mediaContentInsets
import io.github.ponpokoo.mastodonclient.feature.status.*
import io.github.ponpokoo.mastodonclient.feature.timeline.animateToTimelineTop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SavedTimelinesScreen(
    title: String,
    viewModel: SavedTimelinesViewModel,
    actionsViewModel: StatusActionsViewModel,
    currentAccountId: String?,
    preferences: AppPreferences,
    showLists: Boolean,
    onBack: () -> Unit,
    onListClick: (MastodonList) -> Unit,
    onStatusClick: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (String) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onEditStatus: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actionsState by actionsViewModel.uiState.collectAsStateWithLifecycle()
    val moderationMenu by actionsViewModel.moderationMenuState.collectAsStateWithLifecycle()
    val listState = key(state.sessionKey) { rememberLazyListState() }
    val scope = key(state.sessionKey) { rememberCoroutineScope() }
    val context = LocalContext.current
    val snackbarHostState = remember(state.sessionKey) { SnackbarHostState() }
    var menuStatus by remember(state.sessionKey) { mutableStateOf<TimelineStatus?>(null) }
    var confirmation by remember(state.sessionKey) { mutableStateOf<StatusConfirmation?>(null) }
    var reportStatus by remember(state.sessionKey) { mutableStateOf<TimelineStatus?>(null) }
    var listStatus by remember(state.sessionKey) { mutableStateOf<TimelineStatus?>(null) }
    LaunchedEffect(state.sessionKey, actionsState.actionMessage) {
        actionsState.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            actionsViewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(state.sessionKey, listState, state.statuses.size, state.nextMaxId, state.error, state.loading, showLists) {
        if (!showLists && !state.loading && state.error == null && state.nextMaxId != null && !state.endReached) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .distinctUntilChanged().collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= listState.layoutInfo.totalItemsCount - 4) viewModel.loadMore()
                }
        }
    }
    Scaffold(
        contentWindowInsets = mediaContentInsets(),
        modifier = Modifier.testTag("saved_timelines_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(
            windowInsets = mediaContentInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            title = { Text(title, Modifier.clickable { scope.launch { listState.animateToTimelineTop() } }) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") } },
        ) },
    ) { padding ->
        if (state.loading && !state.loaded) {
            ListLoadingContent(Modifier.padding(padding))
        } else {
            AppPullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(Modifier.fillMaxSize().testTag("saved_timelines_list"), state = listState) {
                    if (showLists) {
                        items(state.lists, key = MastodonList::id) { list ->
                            Text(list.title, Modifier.fillMaxWidth().clickable { onListClick(list) }
                                .heightIn(min = 48.dp).padding(20.dp), style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                        }
                    } else {
                        items(state.statuses, key = TimelineStatus::timelineId) { status ->
                            SocialStatus(
                                status = status,
                                onStatusClick = onStatusClick,
                                onAccountClick = onAccountClick,
                                onMediaClick = onMediaClick,
                                onOpenLink = onOpenLink,
                                onReply = { onReply(it.statusId) },
                                onBoost = actionsViewModel::toggleReblog,
                                onFavourite = actionsViewModel::toggleFavourite,
                                onBookmark = actionsViewModel::toggleBookmark,
                                onReact = actionsViewModel::setReaction,
                                onVotePoll = actionsViewModel::votePoll,
                                onQuote = onQuote,
                                onMoreClick = { menuStatus = it },
                                preferences = preferences,
                            )
                        }
                    }
                    if (state.error != null) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = viewModel::retry) { Text("再試行") }
                            }
                        }
                    } else if (!state.loading && (if (showLists) state.lists.isEmpty() else state.statuses.isEmpty())) {
                        item { MessageContent(if (showLists) "リストがありません" else "投稿がありません") }
                    }
                    if (state.loadingMore) item { ListLoadingMoreIndicator() }
                    else if (!showLists && !state.loading && state.error == null && !state.endReached && state.nextMaxId != null) {
                        item { TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") } }
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
            onDelete = { menuStatus = null; confirmation = StatusConfirmation(StatusConfirmationAction.Delete, status) },
            onAddToList = { menuStatus = null; listStatus = status; actionsViewModel.loadLists() },
            onUnfollow = { menuStatus = null; confirmation = StatusConfirmation(StatusConfirmationAction.Unfollow, status) },
            onMute = { menuStatus = null; confirmation = StatusConfirmation((if (moderationMenu.relationship?.muting == true) StatusConfirmationAction.Unmute else StatusConfirmationAction.Mute), status) },
            onBlock = { menuStatus = null; confirmation = StatusConfirmation((if (moderationMenu.relationship?.blocking == true) StatusConfirmationAction.Unblock else StatusConfirmationAction.Block), status) },
            onReport = { menuStatus = null; reportStatus = status },
        )
    }
    confirmation?.let { (action, status) ->
        ConfirmStatusActionDialog(action, status, { confirmation = null }) {
            when (action) {
                StatusConfirmationAction.Delete -> actionsViewModel.deleteStatus(status)
                StatusConfirmationAction.Unfollow -> actionsViewModel.unfollow(status)
                StatusConfirmationAction.Mute -> actionsViewModel.mute(status)
                StatusConfirmationAction.Unmute -> actionsViewModel.mute(status, false)
                StatusConfirmationAction.Block -> actionsViewModel.block(status)
                StatusConfirmationAction.Unblock -> actionsViewModel.block(status, false)
            }
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
