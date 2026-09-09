package io.github.ponpokoo.mastodonclient.feature.timeline

import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.FontSizePreset
import io.github.ponpokoo.mastodonclient.core.preferences.LineSpacingPreset
import io.github.ponpokoo.mastodonclient.core.preferences.ActionIconSize
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconSize
import io.github.ponpokoo.mastodonclient.core.preferences.ThumbnailSize
import io.github.ponpokoo.mastodonclient.core.preferences.StatusAction
import io.github.ponpokoo.mastodonclient.core.preferences.AutoplayPolicy
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

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
    onStatusClick: (String) -> Unit,
    onCompose: (String?) -> Unit,
    onOpenLink: (String) -> Unit,
    openLinksInApp: Boolean,
    onOpenLinksInAppChange: (Boolean) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (MediaAttachment) -> Unit,
    onSettings: () -> Unit,
    onEditProfile: (String) -> Unit,
    onFollowers: (String) -> Unit,
    onFollowing: (String) -> Unit,
    onEditStatus: (String) -> Unit,
    onOpenLists: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenFavourites: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val destinations = MainDestination.entries
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val timelineListState = rememberLazyListState()
    val notificationListState = rememberLazyListState()
    val profileListState = rememberLazyListState()
    var notificationFilter by rememberSaveable { mutableStateOf(NotificationFilter.All) }
    var menuStatus by remember { mutableStateOf<TimelineStatus?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, TimelineStatus>?>(null) }
    var reportStatus by remember { mutableStateOf<TimelineStatus?>(null) }
    var listStatus by remember { mutableStateOf<TimelineStatus?>(null) }
    val destination = destinations[pagerState.currentPage]
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.requiresLogin) {
        if (state.requiresLogin) onLoggedOut()
    }
    LaunchedEffect(state.errorMessage, state.statuses.isNotEmpty()) {
        if (state.statuses.isNotEmpty()) {
            state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
        }
    }
    LaunchedEffect(state.refreshNewStatusCount) {
        state.refreshNewStatusCount?.let { count ->
            val showJob = launch {
                snackbarHostState.showSnackbar(
                    if (count == 0) "新しい投稿はありません" else "新しい投稿 ${count}件を取得しました",
                )
            }
            delay(1_800)
            snackbarHostState.currentSnackbarData?.dismiss()
            showJob.join()
            viewModel.consumeRefreshResult()
        }
    }
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(destination) {
        when (destination) {
            MainDestination.Notifications -> viewModel.loadNotifications()
            MainDestination.Profile -> viewModel.loadProfile()
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            if (destination == MainDestination.Home) {
                TimelineTopBar(
                    selectedFeed = state.selectedFeed,
                    onFeedSelected = viewModel::selectFeed,
                    onAnnouncements = viewModel::showAnnouncements,
                    onLogout = viewModel::logout,
                    activeSession = state.session,
                    sessions = state.sessions,
                    onAccountSelected = viewModel::switchAccount,
                    onSettings = onSettings,
                )
            } else {
                TopAppBar(title = { Text(destination.label) })
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                MainDestination.entries.forEach { item ->
                    val page = destinations.indexOf(item)
                    NavigationBarItem(
                        modifier = Modifier.testTag("main_tab_${item.name.lowercase()}"),
                        selected = destination == item,
                        onClick = {
                            scope.launch {
                                if (pagerState.currentPage == page) {
                                    when (item) {
                                        MainDestination.Home -> timelineListState.animateScrollToItem(0)
                                        MainDestination.Notifications -> notificationListState.animateScrollToItem(0)
                                        MainDestination.Profile -> profileListState.animateScrollToItem(0)
                                        MainDestination.Explore -> Unit
                                    }
                                } else {
                                    pagerState.scrollToPage(page)
                                }
                            }
                        },
                        icon = {
                            if (item == MainDestination.Notifications && state.unreadNotifications > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge { Text(state.unreadNotifications.coerceAtMost(99).toString()) }
                                    },
                                ) { Icon(item.icon, contentDescription = null) }
                            } else {
                                Icon(item.icon, contentDescription = null)
                            }
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (destination == MainDestination.Home) {
                FloatingActionButton(
                    onClick = { onCompose(null) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = "新規投稿")
                }
            }
        },
        snackbarHost = {},
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { destinations[it] },
        ) { page ->
            when (val pageDestination = destinations[page]) {
                MainDestination.Home -> TimelineContent(
                    state = state,
                    padding = padding,
                    listState = timelineListState,
                    onRefresh = viewModel::refresh,
                    onRetry = viewModel::retry,
                    onLoadMore = viewModel::loadNextPage,
                    onStatusClick = onStatusClick,
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = viewModel::toggleReblog,
                    onFavourite = viewModel::toggleFavourite,
                    onBookmark = viewModel::toggleBookmark,
                    onReact = viewModel::setReaction,
                    onMoreClick = { menuStatus = it },
                    onUnavailableAction = { label ->
                        scope.launch { snackbarHostState.showSnackbar("$label は次の実装で追加します") }
                    },
                    preferences = state.preferences,
                )
                MainDestination.Explore -> SearchContent(
                    state = state,
                    padding = padding,
                    onQueryChanged = viewModel::onSearchQueryChanged,
                    onSearch = viewModel::search,
                    onStatusClick = onStatusClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = viewModel::toggleReblog,
                    onFavourite = viewModel::toggleFavourite,
                    onBookmark = viewModel::toggleBookmark,
                    onReact = viewModel::setReaction,
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    preferences = state.preferences,
                )
                MainDestination.Notifications -> NotificationsContent(
                    state = state,
                    padding = padding,
                    onRefresh = { viewModel.loadNotifications(force = true) },
                    onLoadMore = viewModel::loadNextNotifications,
                    onStatusClick = onStatusClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = viewModel::toggleReblog,
                    onFavourite = viewModel::toggleFavourite,
                    onBookmark = viewModel::toggleBookmark,
                    onReact = viewModel::setReaction,
                    onMoreClick = { menuStatus = it },
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    preferences = state.preferences,
                    listState = notificationListState,
                    selectedFilter = notificationFilter,
                    onSelectFilter = { notificationFilter = it },
                )
                MainDestination.Profile -> ProfileContent(
                    state = state,
                    padding = padding,
                    onRetry = viewModel::loadProfile,
                    onStatusClick = onStatusClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = viewModel::toggleReblog,
                    onFavourite = viewModel::toggleFavourite,
                    onBookmark = viewModel::toggleBookmark,
                    onReact = viewModel::setReaction,
                    onMoreClick = { menuStatus = it },
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    preferences = state.preferences,
                    selectedTab = state.profileSelectedTab,
                    isLoadingMore = state.isLoadingMoreProfile,
                    onSelectTab = viewModel::selectProfileTab,
                    onLoadMore = viewModel::loadMoreProfile,
                    onFollowers = { state.profile?.author?.id?.let(onFollowers) },
                    onFollowing = { state.profile?.author?.id?.let(onFollowing) },
                    onHeaderClick = {
                        state.profile?.headerUrl?.takeIf(String::isNotBlank)?.let { url ->
                            onMediaClick(MediaAttachment("profile-header", "image", url, url, "ヘッダー画像"))
                        }
                    },
                    onAvatarClick = {
                        state.profile?.author?.avatarUrl?.takeIf(String::isNotBlank)?.let { url ->
                            onMediaClick(MediaAttachment("profile-avatar", "image", url, url, "プロフィール画像"))
                        }
                    },
                    onEditProfile = { state.profile?.author?.id?.let(onEditProfile) },
                    onOpenLists = onOpenLists,
                    onOpenBookmarks = onOpenBookmarks,
                    onOpenFavourites = onOpenFavourites,
                    listState = profileListState,
                )
            }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
        ) { data ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    data.visuals.message,
                    Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }

    menuStatus?.let { status ->
        StatusMenuSheet(
            status = status,
            isOwnStatus = status.author.id == state.session?.accountId,
            onDismiss = { menuStatus = null },
            onOpenBrowser = {
                menuStatus = null
                status.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            },
            onPin = { menuStatus = null; viewModel.setPinned(status) },
            onEdit = { menuStatus = null; onEditStatus(status.statusId) },
            onDelete = { menuStatus = null; confirmation = "delete" to status },
            onAddToList = { menuStatus = null; listStatus = status; viewModel.loadLists() },
            onUnfollow = { menuStatus = null; confirmation = "unfollow" to status },
            onMute = { menuStatus = null; confirmation = "mute" to status },
            onBlock = { menuStatus = null; confirmation = "block" to status },
            onReport = { menuStatus = null; reportStatus = status },
        )
    }
    confirmation?.let { (action, status) ->
        ConfirmStatusActionDialog(
            action = action,
            status = status,
            onDismiss = { confirmation = null },
            onConfirm = {
                when (action) {
                    "delete" -> viewModel.deleteStatus(status)
                    "unfollow" -> viewModel.unfollow(status)
                    "mute" -> viewModel.mute(status)
                    "block" -> viewModel.block(status)
                }
                confirmation = null
            },
        )
    }
    reportStatus?.let { status ->
        StatusReportDialog(
            status = status,
            onDismiss = { reportStatus = null },
            onSubmit = { comment -> viewModel.report(status, comment); reportStatus = null },
        )
    }
    listStatus?.let { status ->
        ListPickerSheet(
            lists = state.lists,
            loading = state.isLoadingLists,
            onDismiss = { listStatus = null },
            onSelected = { listId -> viewModel.addToList(status, listId); listStatus = null },
        )
    }

    if (state.announcementsVisible) {
        AnnouncementsSheet(
            announcements = state.announcements,
            isLoading = state.isLoadingAnnouncements,
            errorMessage = state.announcementsError,
            onRetry = viewModel::showAnnouncements,
            onDismiss = viewModel::dismissAnnouncements,
            onOpenLink = onOpenLink,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StatusMenuSheet(
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(status.author.displayName, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        @Composable fun Action(label: String, action: () -> Unit) {
            TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
        }
        if (isOwnStatus) {
            Action(if (status.pinned) "プロフィールへの固定解除" else "プロフィールに固定", onPin)
            Action("ブラウザで開く", onOpenBrowser)
            Action("編集", onEdit)
            Action("削除", onDelete)
        } else {
            Action("ブラウザで開く", onOpenBrowser)
            Action("リストに追加", onAddToList)
            Action("フォロー解除　${status.author.displayName}さん", onUnfollow)
            Action("ミュート　${status.author.displayName}さん", onMute)
            Action("ブロック　${status.author.displayName}さん", onBlock)
            Action("報告　${status.author.displayName}さん", onReport)
        }
        Action("閉じる", onDismiss)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun ConfirmStatusActionDialog(action: String, status: TimelineStatus, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, message) = when (action) {
        "delete" -> "投稿を削除" to "この投稿を削除します。この操作は元に戻せません。"
        "unfollow" -> "フォロー解除" to "${status.author.displayName}さんのフォローを解除しますか？"
        "mute" -> "ミュート" to "${status.author.displayName}さんをミュートしますか？"
        else -> "ブロック" to "${status.author.displayName}さんをブロックしますか？"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("実行") } },
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TimelineTopBar(
    selectedFeed: TimelineFeed,
    onFeedSelected: (TimelineFeed) -> Unit,
    onAnnouncements: () -> Unit,
    onLogout: () -> Unit,
    activeSession: AccountSession?,
    sessions: List<AccountSession>,
    onAccountSelected: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var feedMenuOpen by remember { mutableStateOf(false) }
    var accountSheetOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Box {
                Row(
                    modifier = Modifier.clickable { feedMenuOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (selectedFeed) {
                            TimelineFeed.Home -> "ホーム"
                            TimelineFeed.Local -> "ローカル"
                            TimelineFeed.Federated -> "連合"
                        },
                    )
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = "フィードを切り替える")
                }
                DropdownMenu(expanded = feedMenuOpen, onDismissRequest = { feedMenuOpen = false }) {
                    TimelineFeed.entries.forEach { feed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (feed) {
                                        TimelineFeed.Home -> "ホーム"
                                        TimelineFeed.Local -> "ローカル"
                                        TimelineFeed.Federated -> "連合"
                                    },
                                )
                            },
                            onClick = {
                                feedMenuOpen = false
                                onFeedSelected(feed)
                            },
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = { accountSheetOpen = true }) {
                AsyncImage(
                    model = activeSession?.avatarUrl,
                    contentDescription = "アカウントを切り替える",
                    modifier = Modifier.size(30.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentScale = ContentScale.Crop,
                )
            }
            IconButton(onClick = onAnnouncements) {
                Icon(
                    Icons.Outlined.Campaign,
                    contentDescription = "サーバーからのお知らせ",
                    modifier = Modifier.testTag("server_announcements"),
                )
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "設定") }
        },
    )

    if (accountSheetOpen) {
        ModalBottomSheet(onDismissRequest = { accountSheetOpen = false }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("アカウントを切り替える", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { accountSheetOpen = false }) {
                    Icon(Icons.Outlined.Close, contentDescription = "アカウント切替を閉じる")
                }
            }
            sessions.forEach { session ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        accountSheetOpen = false
                        onAccountSelected(session.sessionId)
                    }.then(
                        if (session.sessionId == activeSession?.sessionId) Modifier.testTag("active_account_switch") else Modifier,
                    ).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = session.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.displayName.ifBlank { session.username }, fontWeight = FontWeight.SemiBold)
                        Text(
                            "@${session.username} · ${session.instanceUrl.removePrefix("https://")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (session.sessionId == activeSession?.sessionId) {
                        Text("選択中", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AnnouncementsSheet(
    announcements: List<ServerAnnouncement>,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "サーバーからのお知らせ",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            errorMessage != null -> Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("再試行") }
            }
            announcements.isEmpty() -> Text(
                "現在のお知らせはありません",
                modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 48.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(announcements, key = ServerAnnouncement::id) { announcement ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                        StatusContentText(
                            contentHtml = announcement.contentHtml,
                            onLinkClick = onOpenLink,
                        )
                        val date = announcement.updatedAt ?: announcement.publishedAt
                        if (date != null) {
                            Text(
                                relativeTime(date),
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun TimelineContent(
    state: TimelineUiState,
    padding: PaddingValues,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onStatusClick: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (MediaAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit,
    onReact: (TimelineStatus, String?) -> Unit,
    onMoreClick: (TimelineStatus) -> Unit,
    onUnavailableAction: (String) -> Unit,
    preferences: AppPreferences,
) {
    LaunchedEffect(listState, state.statuses.size, state.nextMaxId) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null && lastVisibleIndex >= state.statuses.lastIndex - 3) {
                    onLoadMore()
                }
            }
    }
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
        else -> PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("timeline_list"),
            ) {
                itemsIndexed(
                    items = state.statuses,
                    key = { _, status -> status.timelineId },
                ) { _, status ->
                    StatusCard(
                        status = status,
                        onStatusClick = onStatusClick,
                        onAuthorClick = onAccountClick,
                        onMediaClick = onMediaClick,
                        onOpenLink = onOpenLink,
                        onReply = { onReply(status) },
                        onBoost = { onBoost(status) },
                        onFavourite = { onFavourite(status) },
                        onBookmark = { onBookmark(status) },
                        onReact = { emoji -> onReact(status, emoji) },
                        onMoreClick = onMoreClick,
                        onUnavailableAction = onUnavailableAction,
                        displayPreferences = preferences.timelineDisplay,
                        gifAutoplay = preferences.gifAutoplay,
                        videoAutoplay = preferences.videoAutoplay,
                    )
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
}

@Composable
internal fun StatusCard(
    status: TimelineStatus,
    onStatusClick: ((String) -> Unit)?,
    onAuthorClick: ((String) -> Unit)? = null,
    onMediaClick: ((MediaAttachment) -> Unit)? = null,
    onOpenLink: (String) -> Unit = {},
    onReply: () -> Unit = {},
    onBoost: () -> Unit = {},
    onFavourite: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onReact: ((String?) -> Unit)? = null,
    onMoreClick: ((TimelineStatus) -> Unit)? = null,
    onUnavailableAction: (String) -> Unit,
    displayPreferences: TimelineDisplayPreferences = TimelineDisplayPreferences(),
    gifAutoplay: AutoplayPolicy = AutoplayPolicy.Always,
    videoAutoplay: AutoplayPolicy = AutoplayPolicy.Never,
) {
    var contentExpanded by rememberSaveable(status.statusId) {
        mutableStateOf(status.spoilerText.isBlank())
    }
    var mediaRevealed by rememberSaveable(status.statusId) { mutableStateOf(!status.sensitive) }
    var reactionPickerOpen by rememberSaveable(status.statusId) { mutableStateOf(false) }
    val context = LocalContext.current
    val avatarSize = when (displayPreferences.avatarIconSize) {
        AvatarIconSize.Small -> 34.dp
        AvatarIconSize.Standard -> 40.dp
        AvatarIconSize.Large -> 48.dp
    }
    val contentStart = avatarSize + 8.dp

    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (onStatusClick == null) Modifier else Modifier.clickable { onStatusClick(status.statusId) })
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp)
            .testTag("timeline_status"),
    ) {
        status.boostedBy?.let {
            Row(
                modifier = Modifier.padding(start = contentStart, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "${it.displayName}さんがブーストしました",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = status.author.avatarUrl,
                contentDescription = "${status.author.displayName}のプロフィール画像",
                modifier = Modifier.size(avatarSize).clip(CircleShape)
                    .testTag("status_author_avatar")
                    .then(
                        if (onAuthorClick == null) Modifier else Modifier.clickable {
                            onAuthorClick(status.author.id)
                        },
                    )
                    .background(MaterialTheme.colorScheme.surface),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status.author.displayName,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        relativeTime(status.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onMoreClick != null) {
                        IconButton(
                            onClick = { onMoreClick(status) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = "投稿メニュー",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Text(
                    "@${status.author.accountName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(modifier = Modifier.padding(start = contentStart)) {
            if (status.spoilerText.isNotBlank()) {
                Text(status.spoilerText, modifier = Modifier.padding(top = 8.dp))
                TextButton(
                    onClick = { contentExpanded = !contentExpanded },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(if (contentExpanded) "隠す" else "表示する")
                }
            }
            if (contentExpanded && status.contentHtml.isNotBlank()) {
                StatusContentText(
                    contentHtml = status.contentHtml,
                    modifier = Modifier.padding(top = if (status.spoilerText.isBlank()) 8.dp else 0.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = displayPreferences.fontSize.spValue(),
                        lineHeight = displayPreferences.lineHeightSp().sp,
                    ),
                    onLinkClick = onOpenLink,
                    onNonLinkClick = onStatusClick?.let { { it(status.statusId) } },
                )
            }
            if (status.mediaAttachments.isNotEmpty() && contentExpanded) {
                Spacer(Modifier.height(8.dp))
                if (mediaRevealed) {
                    MediaGrid(
                        status.mediaAttachments,
                        onMediaClick,
                        displayPreferences.thumbnailSize,
                        gifAutoplay,
                        videoAutoplay,
                    )
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
            status.previewCard?.let { card ->
                Spacer(Modifier.height(8.dp))
                PreviewCardView(
                    card = card,
                    thumbnailSize = displayPreferences.thumbnailSize,
                    onClick = { onOpenLink(card.url) },
                )
            }
            if (status.reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    status.reactions.forEach { reaction ->
                        Surface(
                            modifier = Modifier.testTag("displayed_reaction").then(
                                if (onReact == null || reaction.imageUrl != null) {
                                    Modifier
                                } else {
                                    Modifier.clickable {
                                        onReact(if (reaction.reactedByMe) null else reaction.name)
                                    }
                                },
                            ),
                            shape = RoundedCornerShape(16.dp),
                            color = if (reaction.reactedByMe) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (reaction.imageUrl != null) {
                                    AsyncImage(
                                        model = reaction.imageUrl,
                                        contentDescription = reaction.name,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Text(reaction.name)
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(reaction.count.toString(), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            StatusActionRow(
                status = status,
                onReply = onReply,
                onBoost = onBoost,
                onFavourite = onFavourite,
                onReaction = onReact?.let { { reactionPickerOpen = true } },
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
                onBookmark = onBookmark,
                preferences = displayPreferences,
            )
        }
    }

    if (reactionPickerOpen) {
        AlertDialog(
            onDismissRequest = { reactionPickerOpen = false },
            title = { Text("リアクション") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("👍", "❤️", "🎉", "😂", "😮").forEach { emoji ->
                        TextButton(onClick = {
                            reactionPickerOpen = false
                            onReact?.invoke(emoji)
                        }) { Text(emoji) }
                    }
                }
            },
            confirmButton = {
                if (status.reactions.any { it.reactedByMe }) {
                    TextButton(onClick = {
                        reactionPickerOpen = false
                        onReact?.invoke(null)
                    }) { Text("取り消す") }
                }
            },
            dismissButton = {
                TextButton(onClick = { reactionPickerOpen = false }) { Text("閉じる") }
            },
        )
    }
}

@Composable
private fun MediaGrid(
    attachments: List<MediaAttachment>,
    onMediaClick: ((MediaAttachment) -> Unit)?,
    thumbnailSize: ThumbnailSize = ThumbnailSize.Standard,
    gifAutoplay: AutoplayPolicy = AutoplayPolicy.Always,
    videoAutoplay: AutoplayPolicy = AutoplayPolicy.Never,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        attachments.take(4).chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rowItems.forEach { media ->
                    Box(
                        modifier = Modifier.weight(1f)
                            .aspectRatio(
                                if (attachments.size == 1) {
                                    when (thumbnailSize) {
                                        ThumbnailSize.Compact -> 2.1f
                                        ThumbnailSize.Standard -> 1.6f
                                        ThumbnailSize.Large -> 1.2f
                                    }
                                } else {
                                    when (thumbnailSize) {
                                        ThumbnailSize.Compact -> 1.45f
                                        ThumbnailSize.Standard -> 1f
                                        ThumbnailSize.Large -> 0.8f
                                    }
                                },
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .testTag("media_attachment")
                            .then(
                                if (onMediaClick == null || media.url == null) Modifier else {
                                    Modifier.clickable { onMediaClick(media) }
                                },
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        val autoplay = when (media.type) {
                            "gifv" -> shouldAutoplay(context, gifAutoplay)
                            "video" -> shouldAutoplay(context, videoAutoplay)
                            else -> false
                        }
                        if (autoplay && media.url != null) {
                            InlineVideo(media.url, loop = media.type == "gifv")
                        } else {
                            AsyncImage(
                                model = if (media.type == "image") {
                                    media.url ?: media.previewUrl
                                } else {
                                    media.previewUrl ?: media.url
                                },
                                contentDescription = media.description ?: "添付メディア",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        if (!autoplay && (media.type == "video" || media.type == "gifv")) {
                            Icon(
                                Icons.Outlined.PlayCircle,
                                contentDescription = "動画",
                                modifier = Modifier.size(48.dp),
                                tint = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                    }
                }
                if (rowItems.size == 1 && attachments.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PreviewCardView(
    card: PreviewCard,
    thumbnailSize: ThumbnailSize = ThumbnailSize.Standard,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            card.imageUrl?.let { imageUrl ->
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.width(
                        when (thumbnailSize) {
                            ThumbnailSize.Compact -> 72.dp
                            ThumbnailSize.Standard -> 96.dp
                            ThumbnailSize.Large -> 128.dp
                        },
                    ).height(
                        when (thumbnailSize) {
                            ThumbnailSize.Compact -> 68.dp
                            ThumbnailSize.Standard -> 88.dp
                            ThumbnailSize.Large -> 108.dp
                        },
                    ),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (card.byline.isNotBlank()) {
                    Text(card.byline, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    card.title.ifBlank { card.url },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.description.isNotBlank()) {
                    Text(
                        card.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineVideo(url: String, loop: Boolean) {
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(url) {
        onDispose { videoView?.stopPlayback() }
    }
    AndroidView(
        factory = { context ->
            VideoView(context).also { view ->
                view.setVideoURI(Uri.parse(url))
                view.setOnPreparedListener { player ->
                    player.isLooping = loop
                    player.setVolume(0f, 0f)
                    view.start()
                }
                videoView = view
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun shouldAutoplay(context: Context, policy: AutoplayPolicy): Boolean = when (policy) {
    AutoplayPolicy.Always -> true
    AutoplayPolicy.Never -> false
    AutoplayPolicy.WifiOnly -> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}

@Composable
private fun StatusActionRow(
    status: TimelineStatus,
    onReply: () -> Unit,
    onBoost: () -> Unit,
    onFavourite: () -> Unit,
    onReaction: (() -> Unit)?,
    onShare: () -> Unit,
    onBookmark: () -> Unit = {},
    preferences: TimelineDisplayPreferences = TimelineDisplayPreferences(),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        preferences.actionOrder.filterNot { it in preferences.hiddenActions }.forEach { action ->
            when (action) {
                StatusAction.Reply -> StatusActionButton(Icons.Outlined.ChatBubbleOutline, "返信", status.repliesCount, preferences, onReply)
                StatusAction.Boost -> StatusActionButton(Icons.Outlined.Repeat, "ブースト", status.boostsCount, preferences, onBoost)
                StatusAction.Favourite -> StatusActionButton(
                    if (status.favourited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    "お気に入り", status.favouritesCount, preferences, onFavourite,
                )
                StatusAction.Reaction -> if (onReaction != null && status.supportsEmojiReactions) {
                    StatusActionButton(Icons.Outlined.SentimentSatisfiedAlt, "リアクション", null, preferences, onReaction)
                }
                StatusAction.Share -> StatusActionButton(Icons.Outlined.Share, "共有", null, preferences, onShare)
                StatusAction.Bookmark -> StatusActionButton(
                    if (status.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    "ブックマーク", null, preferences, onBookmark,
                )
            }
        }
    }
}

@Composable
private fun StatusActionButton(
    icon: ImageVector,
    label: String,
    count: Long?,
    preferences: TimelineDisplayPreferences,
    onClick: () -> Unit,
) {
    val iconSize = when (preferences.actionIconSize) {
        ActionIconSize.Small -> 18.dp
        ActionIconSize.Standard -> 21.dp
        ActionIconSize.Large -> 24.dp
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f))
        }
        if (preferences.showCounts && count != null && count > 0) {
            Text(
                compactCount(count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun FontSizePreset.spValue() = when (this) {
    FontSizePreset.Small -> 14.sp
    FontSizePreset.Standard -> 16.sp
    FontSizePreset.Large -> 18.sp
    FontSizePreset.ExtraLarge -> 20.sp
}

internal fun TimelineDisplayPreferences.lineHeightSp(): Int {
    val base = when (fontSize) {
        FontSizePreset.Small -> 18
        FontSizePreset.Standard -> 22
        FontSizePreset.Large -> 26
        FontSizePreset.ExtraLarge -> 30
    }
    return when (lineSpacing) {
        LineSpacingPreset.Compact -> base - 2
        LineSpacingPreset.Standard -> base
        LineSpacingPreset.Relaxed -> base + 4
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
