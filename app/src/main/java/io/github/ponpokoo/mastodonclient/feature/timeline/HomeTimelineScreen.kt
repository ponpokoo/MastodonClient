package io.github.ponpokoo.mastodonclient.feature.timeline

import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
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
import coil3.request.ImageRequest
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.mentionedAccountIdFor
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.OwnProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.EditProfileDialog
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.feature.common.AccountSwitchDialog
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
    mainViewModel: MainSessionViewModel,
    searchViewModel: SearchViewModel,
    notificationsViewModel: NotificationsViewModel,
    profileViewModel: OwnProfileViewModel,
    actionsViewModel: StatusActionsViewModel,
    onLoggedOut: () -> Unit,
    onStatusClick: (String) -> Unit,
    onCompose: (String?) -> Unit,
    onQuote: (TimelineStatus) -> Unit,
    onOpenLink: (String) -> Unit,
    openLinksInApp: Boolean,
    onOpenLinksInAppChange: (Boolean) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onSettings: () -> Unit,
    onFollowers: (String) -> Unit,
    onFollowing: (String) -> Unit,
    onEditStatus: (String) -> Unit,
    onOpenLists: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenFavourites: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val notificationsState by notificationsViewModel.uiState.collectAsStateWithLifecycle()
    val profileState by profileViewModel.uiState.collectAsStateWithLifecycle()
    val actionsState by actionsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val destinations = MainDestination.entries
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val timelineListState = rememberLazyListState()
    val notificationListState = rememberLazyListState()
    val profileListState = rememberLazyListState()
    var notificationFilter by rememberSaveable { mutableStateOf(NotificationFilter.All) }
    var menuStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    var confirmation by remember(mainState.session?.sessionId) { mutableStateOf<Pair<String, TimelineStatus>?>(null) }
    var reportStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    var listStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    var editProfileOpen by remember(mainState.session?.sessionId) { mutableStateOf(false) }
    var scrollHomeAfterRefresh by remember { mutableStateOf(false) }
    var homeRefreshStarted by remember { mutableStateOf(false) }
    var scrollNotificationsAfterRefresh by remember { mutableStateOf(false) }
    var notificationsRefreshStarted by remember { mutableStateOf(false) }
    var scrollProfileAfterRefresh by remember { mutableStateOf(false) }
    var profileRefreshStarted by remember { mutableStateOf(false) }
    val destination = destinations[pagerState.currentPage]
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mainViewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> mainViewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(mainState.requiresLogin) {
        if (mainState.requiresLogin) onLoggedOut()
    }
    LaunchedEffect(mainState.errorMessage) {
        mainState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
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
    LaunchedEffect(actionsState.actionMessage) {
        actionsState.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            actionsViewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(profileState.editMessage) {
        profileState.editMessage?.let {
            snackbarHostState.showSnackbar(it)
            profileViewModel.clearEditMessage()
        }
    }
    LaunchedEffect(state.isRefreshing) {
        if (state.isRefreshing) {
            homeRefreshStarted = true
        } else if (homeRefreshStarted) {
            if (scrollHomeAfterRefresh) timelineListState.animateScrollToItem(0)
            homeRefreshStarted = false
            scrollHomeAfterRefresh = false
        }
    }
    LaunchedEffect(notificationsState.isLoadingNotifications) {
        if (notificationsState.isLoadingNotifications && scrollNotificationsAfterRefresh) {
            notificationsRefreshStarted = true
        } else if (!notificationsState.isLoadingNotifications && notificationsRefreshStarted) {
            notificationListState.animateScrollToItem(0)
            notificationsRefreshStarted = false
            scrollNotificationsAfterRefresh = false
        }
    }
    LaunchedEffect(profileState.isRefreshingProfile) {
        if (profileState.isRefreshingProfile) {
            profileRefreshStarted = true
        } else if (profileRefreshStarted) {
            if (scrollProfileAfterRefresh) profileListState.animateScrollToItem(0)
            profileRefreshStarted = false
            scrollProfileAfterRefresh = false
        }
    }
    LaunchedEffect(destination) {
        when (destination) {
            MainDestination.Notifications -> notificationsViewModel.loadNotifications()
            MainDestination.Profile -> profileViewModel.loadProfile()
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
                    onLogout = mainViewModel::logout,
                    activeSession = mainState.session,
                    sessions = mainState.sessions,
                    onAccountSelected = mainViewModel::switchAccount,
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
                            if (item == MainDestination.Notifications && notificationsState.unreadNotifications > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge { Text(notificationsState.unreadNotifications.coerceAtMost(99).toString()) }
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
                    onRefresh = {
                        scrollHomeAfterRefresh = !mainState.preferences.keepPositionOnPullRefresh
                        homeRefreshStarted = false
                        viewModel.refresh()
                    },
                    onRetry = viewModel::retry,
                    onLoadMore = viewModel::loadNextPage,
                    onStatusClick = onStatusClick,
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = actionsViewModel::toggleReblog,
                    onQuote = onQuote,
                    onFavourite = actionsViewModel::toggleFavourite,
                    onBookmark = actionsViewModel::toggleBookmark,
                    onReact = actionsViewModel::setReaction,
                    onMoreClick = { menuStatus = it },
                    onUnavailableAction = { label ->
                        scope.launch { snackbarHostState.showSnackbar("$label は次の実装で追加します") }
                    },
                    preferences = mainState.preferences,
                )
                MainDestination.Explore -> SearchContent(
                    state = searchState,
                    padding = padding,
                    onQueryChanged = searchViewModel::onQueryChanged,
                    onSearch = searchViewModel::search,
                    onStatusClick = onStatusClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = actionsViewModel::toggleReblog,
                    onQuote = onQuote,
                    onFavourite = actionsViewModel::toggleFavourite,
                    onBookmark = actionsViewModel::toggleBookmark,
                    onReact = actionsViewModel::setReaction,
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    preferences = mainState.preferences,
                )
                MainDestination.Notifications -> NotificationsContent(
                    state = notificationsState,
                    padding = padding,
                    onRefresh = {
                        scrollNotificationsAfterRefresh = !mainState.preferences.keepPositionOnPullRefresh
                        notificationsRefreshStarted = false
                        notificationsViewModel.loadNotifications(force = true)
                    },
                    onLoadMore = notificationsViewModel::loadNextNotifications,
                    onStatusClick = onStatusClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = actionsViewModel::toggleReblog,
                    onQuote = onQuote,
                    onFavourite = actionsViewModel::toggleFavourite,
                    onBookmark = actionsViewModel::toggleBookmark,
                    onReact = actionsViewModel::setReaction,
                    onMoreClick = { menuStatus = it },
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    preferences = mainState.preferences,
                    listState = notificationListState,
                    selectedFilter = notificationFilter,
                    onSelectFilter = { notificationFilter = it },
                )
                MainDestination.Profile -> ProfileContent(
                    state = profileState,
                    padding = padding,
                    onRetry = profileViewModel::loadProfile,
                    onRefresh = {
                        scrollProfileAfterRefresh = !mainState.preferences.keepPositionOnPullRefresh
                        profileRefreshStarted = false
                        profileViewModel.refreshProfile()
                    },
                    onStatusClick = onStatusClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = actionsViewModel::toggleReblog,
                    onQuote = onQuote,
                    onFavourite = actionsViewModel::toggleFavourite,
                    onBookmark = actionsViewModel::toggleBookmark,
                    onReact = actionsViewModel::setReaction,
                    onMoreClick = { menuStatus = it },
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    preferences = mainState.preferences,
                    selectedTab = profileState.profileSelectedTab,
                    isLoadingMore = profileState.isLoadingMoreProfile,
                    onSelectTab = profileViewModel::selectProfileTab,
                    onLoadMore = profileViewModel::loadMoreProfile,
                    onFollowers = { profileState.profile?.author?.id?.let(onFollowers) },
                    onFollowing = { profileState.profile?.author?.id?.let(onFollowing) },
                    onHeaderClick = {
                        profileState.profile?.headerUrl?.takeIf(String::isNotBlank)?.let { url ->
                            onMediaClick(listOf(MediaAttachment("profile-header", "image", url, url, "ヘッダー画像")), 0)
                        }
                    },
                    onAvatarClick = {
                        profileState.profile?.author?.avatarUrl?.takeIf(String::isNotBlank)?.let { url ->
                            onMediaClick(listOf(MediaAttachment("profile-avatar", "image", url, url, "プロフィール画像")), 0)
                        }
                    },
                    onEditProfile = { editProfileOpen = true },
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

    if (editProfileOpen) {
        profileState.profile?.let { profile ->
            EditProfileDialog(profile, onDismiss = { editProfileOpen = false }) { request ->
                profileViewModel.updateProfile(request)
                editProfileOpen = false
            }
        }
    }
    menuStatus?.let { status ->
        StatusMenuDialog(
            status = status,
            isOwnStatus = status.author.id == mainState.session?.accountId,
            onDismiss = { menuStatus = null },
            onOpenBrowser = {
                menuStatus = null
                status.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            },
            onPin = { menuStatus = null; actionsViewModel.setPinned(status) },
            onEdit = { menuStatus = null; onEditStatus(status.statusId) },
            onDelete = { menuStatus = null; confirmation = "delete" to status },
            onAddToList = { menuStatus = null; listStatus = status; actionsViewModel.loadLists() },
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
                    "delete" -> actionsViewModel.deleteStatus(status)
                    "unfollow" -> actionsViewModel.unfollow(status)
                    "mute" -> actionsViewModel.mute(status)
                    "block" -> actionsViewModel.block(status)
                }
                confirmation = null
            },
        )
    }
    reportStatus?.let { status ->
        StatusReportDialog(
            status = status,
            onDismiss = { reportStatus = null },
            onSubmit = { comment -> actionsViewModel.report(status, comment); reportStatus = null },
        )
    }
    listStatus?.let { status ->
        ListPickerSheet(
            lists = actionsState.lists,
            loading = actionsState.isLoadingLists,
            onDismiss = { listStatus = null },
            onSelected = { listId -> actionsViewModel.addToList(status, listId); listStatus = null },
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
                    action: () -> Unit,
                ) {
                    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(onClick = action)
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
                    Action("ミュート", Icons.Outlined.VolumeOff, action = onMute)
                    Action("ブロック", Icons.Outlined.Block, action = onBlock)
                    Action("報告", Icons.Outlined.Flag, action = onReport)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
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
    var accountDialogOpen by remember { mutableStateOf(false) }

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
            IconButton(onClick = { accountDialogOpen = true }) {
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

    if (accountDialogOpen) {
        AccountSwitchDialog(
            title = "アカウントを切り替える",
            sessions = sessions,
            selectedSessionId = activeSession?.sessionId,
            onSelected = { sessionId ->
                accountDialogOpen = false
                onAccountSelected(sessionId)
            },
            onDismiss = { accountDialogOpen = false },
        )
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
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus) -> Unit,
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
                        onQuote = { onQuote(status) },
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

internal val LocalReactionListOpener = staticCompositionLocalOf<((String, EmojiReaction) -> Unit)?> { null }

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun StatusCard(
    status: TimelineStatus,
    onStatusClick: ((String) -> Unit)?,
    onAuthorClick: ((String) -> Unit)? = null,
    onMediaClick: ((List<MediaAttachment>, Int) -> Unit)? = null,
    onOpenLink: (String) -> Unit = {},
    onReply: () -> Unit = {},
    onBoost: () -> Unit = {},
    onQuote: (() -> Unit)? = null,
    onFavourite: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onReact: ((String?) -> Unit)? = null,
    onReactionLongPress: ((EmojiReaction) -> Unit)? = null,
    onMoreClick: ((TimelineStatus) -> Unit)? = null,
    onUnavailableAction: (String) -> Unit,
    displayPreferences: TimelineDisplayPreferences = TimelineDisplayPreferences(),
    gifAutoplay: AutoplayPolicy = AutoplayPolicy.Always,
    videoAutoplay: AutoplayPolicy = AutoplayPolicy.Never,
    fullWidthContent: Boolean = false,
    afterActions: (@Composable () -> Unit)? = null,
) {
    var contentExpanded by rememberSaveable(status.statusId) {
        mutableStateOf(status.spoilerText.isBlank())
    }
    var mediaRevealed by rememberSaveable(status.statusId) { mutableStateOf(!status.sensitive) }
    var reactionPickerOpen by rememberSaveable(status.statusId) { mutableStateOf(false) }
    val reactionListOpener = LocalReactionListOpener.current
    val context = LocalContext.current
    val avatarSize = when (displayPreferences.avatarIconSize) {
        AvatarIconSize.Small -> 40.dp
        AvatarIconSize.Standard -> 44.dp
        AvatarIconSize.Large -> 56.dp
    }
    val contentStart = avatarSize + 8.dp
    val headerEdgeShift = if (onMoreClick == null) 0.dp else 16.dp

    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (onStatusClick == null) Modifier else Modifier.clickable { onStatusClick(status.statusId) })
            .padding(start = 8.dp, top = 12.dp, end = 16.dp, bottom = 2.dp)
            .testTag("timeline_status"),
    ) {
        status.boostedBy?.let {
            Row(
                modifier = Modifier
                    .padding(start = contentStart, bottom = 6.dp)
                    .then(if (onAuthorClick == null) Modifier else Modifier.clickable { onAuthorClick(it.id) })
                    .testTag("status_booster"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                CustomEmojiText(
                    text = "${it.displayName}さんがブーストしました",
                    emojis = it.customEmojis,
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
                CustomEmojiText(
                    text = status.author.displayName,
                    emojis = status.author.customEmojis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "@${status.author.accountName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.height(48.dp).offset(x = headerEdgeShift),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (visibilityIcon, visibilityLabel) = statusVisibility(status.visibility)
                Icon(
                    visibilityIcon,
                    contentDescription = visibilityLabel,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    relativeTime(status.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (onMoreClick != null) {
                IconButton(
                    onClick = { onMoreClick(status) },
                    modifier = Modifier.size(48.dp).offset(x = headerEdgeShift),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "投稿メニュー",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(start = if (fullWidthContent) 8.dp else contentStart)) {
            if (status.spoilerText.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "CW",
                                modifier = Modifier.clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "内容警告",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { contentExpanded = !contentExpanded }) {
                                Text(if (contentExpanded) "内容を隠す" else "内容を表示")
                            }
                        }
                        CustomEmojiText(
                            text = status.spoilerText,
                            emojis = status.customEmojis,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = displayPreferences.fontSize.spValue(),
                                lineHeight = displayPreferences.lineHeightSp().sp,
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            if (contentExpanded && status.contentHtml.isNotBlank()) {
                StatusContentText(
                    contentHtml = status.contentHtml,
                    customEmojis = status.customEmojis,
                    modifier = Modifier.padding(top = if (status.spoilerText.isBlank()) 8.dp else 0.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = displayPreferences.fontSize.spValue(),
                        lineHeight = displayPreferences.lineHeightSp().sp,
                    ),
                    onLinkClick = { link ->
                        val accountId = status.mentionedAccountIdFor(link)
                        if (accountId != null && onAuthorClick != null) onAuthorClick(accountId)
                        else onOpenLink(link)
                    },
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
            if (contentExpanded) {
                status.previewCard?.let { card ->
                    Spacer(Modifier.height(8.dp))
                    PreviewCardView(
                        card = card,
                        thumbnailSize = displayPreferences.thumbnailSize,
                        onClick = { onOpenLink(card.url) },
                    )
                }
            }
            if (status.reactions.isNotEmpty()) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val availableWidth = maxWidth
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        status.reactions.forEach { reaction ->
                            val maxImageWidth = (availableWidth - (22 + reaction.count.toString().length * 10).dp)
                                .coerceAtLeast(48.dp)
                            Surface(
                                modifier = Modifier.testTag("displayed_reaction").then(
                                    if (onReact == null && onReactionLongPress == null && reactionListOpener == null) Modifier
                                    else Modifier.combinedClickable(
                                        onClick = {
                                            onReact?.invoke(if (reaction.reactedByMe) null else reaction.apiName)
                                        },
                                        onLongClick = {
                                            if (onReactionLongPress != null) onReactionLongPress(reaction)
                                            else reactionListOpener?.invoke(status.statusId, reaction)
                                        },
                                    ),
                                ),
                                shape = RoundedCornerShape(16.dp),
                                color = if (reaction.reactedByMe) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (reaction.imageUrl != null) {
                                        var imageRatio by remember(reaction.imageUrl) { mutableStateOf(1f) }
                                        val tall = imageRatio < 0.65f
                                        val imageRequest = remember(reaction.imageUrl) {
                                            ImageRequest.Builder(context).data(reaction.imageUrl)
                                                .size(1024, 128).build()
                                        }
                                        AsyncImage(
                                            model = imageRequest,
                                            contentDescription = reaction.name,
                                            onSuccess = { result ->
                                                val size = result.painter.intrinsicSize
                                                if (size.width.isFinite() && size.height.isFinite() && size.height > 0f) {
                                                    imageRatio = size.width / size.height
                                                }
                                            },
                                            modifier = Modifier.width((24f * imageRatio).dp.coerceIn(8.dp, maxImageWidth))
                                                .height(24.dp),
                                            contentScale = ContentScale.Fit,
                                        )
                                        if (tall) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(reaction.name, modifier = Modifier.widthIn(max = 80.dp),
                                                maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                        }
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
            }
            Spacer(Modifier.height(if (fullWidthContent) 8.dp else 12.dp))
            StatusActionRow(
                status = status,
                onReply = onReply,
                onBoost = onBoost,
                onQuote = onQuote,
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
            afterActions?.let { content ->
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }

    if (reactionPickerOpen) {
        ReactionPickerSheet(
            canUndo = status.reactions.any { it.reactedByMe },
            onDismiss = { reactionPickerOpen = false },
            onSelected = { emoji ->
                reactionPickerOpen = false
                onReact?.invoke(emoji)
            },
        )
    }
}

@Composable
private fun MediaGrid(
    attachments: List<MediaAttachment>,
    onMediaClick: ((List<MediaAttachment>, Int) -> Unit)?,
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
                                    Modifier.clickable { onMediaClick(attachments, attachments.indexOf(media)) }
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
    onQuote: (() -> Unit)?,
    onFavourite: () -> Unit,
    onReaction: (() -> Unit)?,
    onShare: () -> Unit,
    onBookmark: () -> Unit = {},
    preferences: TimelineDisplayPreferences = TimelineDisplayPreferences(),
) {
    var boostMenuExpanded by remember(status.statusId) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        preferences.actionOrder.filterNot { it in preferences.hiddenActions }.forEach { action ->
            when (action) {
                StatusAction.Reply -> StatusActionButton(Icons.Outlined.ChatBubbleOutline, "返信", status.repliesCount, preferences, onReply)
                StatusAction.Boost -> {
                    val canBoost = status.visibility.lowercase() !in setOf("private", "direct", "followers", "followers_only")
                    Box {
                        StatusActionButton(
                            icon = Icons.Outlined.Repeat,
                            label = if (canBoost) "ブースト（長押しで引用を選択）" else "この公開範囲ではブーストできません",
                            count = status.boostsCount,
                            preferences = preferences,
                            onClick = onBoost,
                            onLongClick = onQuote?.let { { boostMenuExpanded = true } },
                            enabled = canBoost,
                            crossedOut = !canBoost,
                        )
                        DropdownMenu(expanded = boostMenuExpanded, onDismissRequest = { boostMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text(if (status.reblogged) "ブースト解除" else "ブースト") }, onClick = {
                                boostMenuExpanded = false
                                onBoost()
                            })
                            DropdownMenuItem(
                                text = { Text(when (status.quoteApproval) {
                                    null -> "引用（リンク）"
                                    "manual" -> "引用（承認申請）"
                                    else -> "引用"
                                }) },
                                enabled = onQuote != null &&
                                    status.quoteApproval !in setOf("denied", "unknown") && status.url != null,
                                onClick = {
                                    boostMenuExpanded = false
                                    onQuote?.invoke()
                                },
                            )
                        }
                    }
                }
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
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    crossedOut: Boolean = false,
) {
    val iconSize = when (preferences.actionIconSize) {
        ActionIconSize.Small -> 18.dp
        ActionIconSize.Standard -> 21.dp
        ActionIconSize.Large -> 24.dp
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        @Composable fun ButtonIcon() {
            val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.68f else 0.35f)
            Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.matchParentSize(), tint = tint)
                if (crossedOut) {
                    Canvas(Modifier.matchParentSize()) {
                        drawLine(
                            color = tint,
                            start = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.1f),
                            end = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.9f),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
        if (onLongClick == null) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { ButtonIcon() }
        } else {
            @OptIn(ExperimentalFoundationApi::class)
            Box(
                modifier = Modifier.size(48.dp).combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
                contentAlignment = Alignment.Center,
            ) { ButtonIcon() }
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
        duration.toMinutes() < 60 -> "${duration.toMinutes()}分前"
        duration.toHours() < 24 -> "${duration.toHours()}時間前"
        duration.toDays() < 7 -> "${duration.toDays()}日前"
        else -> DateTimeFormatter.ofPattern("M月d日")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
}.getOrDefault("")

private fun statusVisibility(visibility: String): Pair<ImageVector, String> = when (visibility) {
    "unlisted" -> Icons.Outlined.Group to "ひかえめな公開"
    "private" -> Icons.Outlined.Lock to "フォロワー限定"
    "direct" -> Icons.Outlined.AlternateEmail to "指定した相手のみ"
    else -> Icons.Outlined.Public to "公開"
}

private fun compactCount(count: Long): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0).replace(".0K", "K")
    else -> String.format(Locale.US, "%.1fM", count / 1_000_000.0).replace(".0M", "M")
}
