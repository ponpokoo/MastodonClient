package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.feature.common.TimelineNotice
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import io.github.ponpokoo.mastodonclient.feature.status.StatusMenuDialog
import io.github.ponpokoo.mastodonclient.feature.status.ConfirmStatusActionDialog
import io.github.ponpokoo.mastodonclient.feature.status.StatusReportDialog
import io.github.ponpokoo.mastodonclient.feature.status.ListPickerSheet
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsContent
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationFilter
import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmation
import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmationAction
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsLifecycleEffect
import io.github.ponpokoo.mastodonclient.feature.profile.OwnProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.EditProfileDialog
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.AppPullToRefreshBox
import io.github.ponpokoo.mastodonclient.feature.common.AccountSwitchDialog
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.notification.NotificationOpenRequest
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
    notificationOpenRequest: NotificationOpenRequest? = null,
    onNotificationOpenHandled: (NotificationOpenRequest) -> Unit = {},
    onLoggedOut: () -> Unit,
    onStatusClick: (String) -> Unit,
    onCompose: (String?) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
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
    onOpenTag: (String, Boolean) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    var exploreScrollToTopRequest by remember(mainState.session?.sessionId) { mutableLongStateOf(0L) }
    val exploreState by searchViewModel.exploreState.collectAsStateWithLifecycle()
    val notificationsState by notificationsViewModel.uiState.collectAsStateWithLifecycle()
    val profileState by profileViewModel.uiState.collectAsStateWithLifecycle()
    val actionsState by actionsViewModel.uiState.collectAsStateWithLifecycle()
    val moderationMenu by actionsViewModel.moderationMenuState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(exploreState.actionMessage) {
        exploreState.actionMessage?.let { snackbarHostState.showSnackbar(it); searchViewModel.consumeExploreMessage() }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val destinations = MainDestination.entries
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val timelineListState = rememberLazyListState()
    val allNotificationsListState = rememberLazyListState()
    val mentionsNotificationsListState = rememberLazyListState()
    val reactionsNotificationsListState = rememberLazyListState()
    val notificationListStates = listOf(
        allNotificationsListState,
        mentionsNotificationsListState,
        reactionsNotificationsListState,
    )
    val profileListStates = List(ProfileStatusTab.entries.size) { rememberLazyListState() }
    val profileHeaderListState = rememberLazyListState()
    val profileListState = profileListStates[profileState.profileSelectedTab.ordinal]
    // NavHost can recreate this destination after a detail route is popped.
    var hasObservedTimelineContext by rememberSaveable { mutableStateOf(false) }
    var observedTimelineSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var observedTimelineFeed by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationFilter by rememberSaveable { mutableStateOf(NotificationFilter.All) }
    var menuStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    var confirmation by remember(mainState.session?.sessionId) { mutableStateOf<StatusConfirmation?>(null) }
    var reportStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    var listStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    val imageEdit by profileViewModel.profileImageEditState.collectAsStateWithLifecycle()
    DisposableEffect(profileViewModel) { onDispose { profileViewModel.dismissProfileEdit() } }
    var scrollHomeAfterRefresh by remember { mutableStateOf(false) }
    var homeRefreshStarted by remember { mutableStateOf(false) }
    var scrollProfileAfterRefresh by remember { mutableStateOf(false) }
    var profileRefreshStarted by remember { mutableStateOf(false) }
    val destination = destinations[pagerState.currentPage]
    val topScrollScope = key(
        mainState.session?.sessionId, destination, state.selectedFeed,
        notificationFilter, profileState.profileSelectedTab,
    ) { rememberCoroutineScope() }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(mainState.preferencesLoaded, mainState.preferences.keepPositionOnPullRefresh,
        mainState.session?.sessionId, state.isResumedWindow) {
        if (mainState.preferencesLoaded && !mainState.preferences.keepPositionOnPullRefresh) {
            viewModel.clearSavedViewport()
            if (viewModel.uiState.value.isResumedWindow) viewModel.goToLatest()
        }
    }
    LaunchedEffect(mainState.session?.sessionId, state.selectedFeed) {
        val sessionId = mainState.session?.sessionId
        val feed = state.selectedFeed.name
        val contextChanged = hasObservedTimelineContext &&
            (observedTimelineSessionId != sessionId || observedTimelineFeed != feed)
        if (contextChanged && state.resumeAnchorId == null) timelineListState.scrollToItem(0)
        observedTimelineSessionId = sessionId
        observedTimelineFeed = feed
        hasObservedTimelineContext = true
    }
    LaunchedEffect(state.resumeAnchorId, state.statuses) {
        val anchorId = state.resumeAnchorId ?: return@LaunchedEffect
        val index = state.statuses.indexOfFirst { it.timelineId == anchorId }
        if (index >= 0) timelineListState.scrollToItem(index, state.resumeOffset)
        else timelineListState.scrollToItem(0)
        viewModel.consumeResumeAnchor()
    }
    LaunchedEffect(mainState.session?.sessionId, mainState.preferencesLoaded,
        mainState.preferences.keepPositionOnPullRefresh, state.selectedFeed,
        state.statuses, state.isInitialLoading, state.isRefreshing, state.resumeAnchorId) {
        if (!mainState.preferencesLoaded || !mainState.preferences.keepPositionOnPullRefresh ||
            mainState.session == null || state.isInitialLoading || state.isRefreshing ||
            state.resumeAnchorId != null || state.statuses.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            if (timelineListState.isScrollInProgress) null else {
                val visibleId = timelineListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
                val index = state.statuses.indexOfFirst { it.timelineId == visibleId }
                if (index < 0) null else Triple(
                    state.statuses[index].timelineId,
                    state.statuses.getOrNull(index - 1)?.timelineId,
                    timelineListState.firstVisibleItemScrollOffset,
                )
            }
        }.distinctUntilChanged().collect { viewport ->
            viewport?.let { (anchorId, beforeAnchorId, offset) ->
                viewModel.saveViewport(anchorId, beforeAnchorId, offset)
            }
        }
    }

    LaunchedEffect(notificationOpenRequest) {
        val request = notificationOpenRequest ?: return@LaunchedEffect
        if (!mainViewModel.switchAccountAndWait(request.sessionId)) {
            onNotificationOpenHandled(request)
            return@LaunchedEffect
        }
        notificationFilter = NotificationFilter.All
        pagerState.scrollToPage(MainDestination.Notifications.ordinal)
        allNotificationsListState.scrollToItem(0)
        notificationsViewModel.onSystemNotificationOpened()
        onNotificationOpenHandled(request)
    }

    DisposableEffect(lifecycleOwner, destination) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.updateViewport(destination == MainDestination.Home && timelineListState.firstVisibleItemIndex == 0 &&
                        timelineListState.firstVisibleItemScrollOffset == 0 && !timelineListState.isScrollInProgress)
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.updateViewport(false)
                    viewModel.consumeStreamNotice(viewModel.uiState.value.streamAutoScrollId)
                }
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
    var refreshNotice by remember(mainState.session?.sessionId, state.selectedFeed) { mutableStateOf<String?>(null) }
    val isHomeVisible = destination == MainDestination.Home
    LaunchedEffect(isHomeVisible, lifecycleOwner, state.selectedFeed, mainState.session?.sessionId) {
        snapshotFlow {
            isHomeVisible && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                timelineListState.firstVisibleItemIndex == 0 && timelineListState.firstVisibleItemScrollOffset == 0 &&
                !timelineListState.isScrollInProgress
        }.collect { viewModel.updateViewport(it) }
    }
    DisposableEffect(viewModel, destination) {
        onDispose {
            viewModel.updateViewport(false)
            viewModel.consumeStreamNotice(viewModel.uiState.value.streamAutoScrollId)
        }
    }
    LaunchedEffect(state.refreshNewStatusCount, mainState.session?.sessionId, state.selectedFeed) {
        state.refreshNewStatusCount?.let { count ->
            if (count > 0) {
                refreshNotice = "新着 ${count}件"
                delay(1_800)
                refreshNotice = null
            } else {
                refreshNotice = null
            }
            viewModel.consumeRefreshResult()
        }
    }
    LaunchedEffect(state.streamAutoScrollId, isHomeVisible, mainState.session?.sessionId, state.selectedFeed) {
        if (isHomeVisible && state.streamAutoScrollId > 0) {
            val requestId = state.streamAutoScrollId
            timelineListState.scrollToItem(0)
            viewModel.updateViewport(true)
            delay(1_800)
            viewModel.consumeStreamNotice(requestId)
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
            if (scrollHomeAfterRefresh) timelineListState.animateToTimelineTop()
            homeRefreshStarted = false
            scrollHomeAfterRefresh = false
        }
    }
    LaunchedEffect(notificationsState.refreshResult) {
        notificationsState.refreshResult?.let { result ->
            notificationsViewModel.consumeRefreshResult(result.id)
        }
    }
    LaunchedEffect(notificationsState.shownNewNotice?.id) {
        notificationsState.shownNewNotice?.let { notice ->
            delay(1_800)
            notificationsViewModel.consumeShownNewNotice(notice.id)
        }
    }
    LaunchedEffect(profileState.isRefreshingProfile, mainState.session?.sessionId, profileState.profileSelectedTab) {
        if (profileState.isRefreshingProfile) {
            profileRefreshStarted = true
        } else if (profileRefreshStarted) {
            val returnToTop = scrollProfileAfterRefresh
            profileRefreshStarted = false
            scrollProfileAfterRefresh = false
            if (returnToTop) profileListState.animateToTimelineTop(profileHeaderListState)
        }
    }
    NotificationsLifecycleEffect(notificationsViewModel, destination == MainDestination.Notifications)
    LaunchedEffect(notificationFilter) { notificationsViewModel.selectCategory(notificationFilter) }
    LaunchedEffect(destination) {
        if (destination == MainDestination.Profile) profileViewModel.loadProfile()
    }
    LaunchedEffect(
        destination, notificationFilter, notificationsState.list(notificationFilter), lifecycleOwner,
    ) {
        val notificationTabState = notificationsState.forCategory(notificationFilter)
        if (destination == MainDestination.Notifications &&
            notificationTabState.isInitialPageLoaded && !notificationTabState.isLoadingNotifications &&
            (notificationTabState.notificationsError == null || notificationTabState.notificationsErrorIsPagination) &&
            notificationsState.canExplore(notificationFilter)
        ) {
            val shown = notificationTabState.notifications.filter(notificationFilter::includes)
            val newestId = shown.firstOrNull()?.id
            if (newestId != null) {
                val listState = notificationListStates[notificationFilter.ordinal]
                val loadedIds = shown.mapTo(mutableSetOf(), io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification::id)
                snapshotFlow {
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 &&
                        !listState.isScrollInProgress &&
                        listState.layoutInfo.visibleItemsInfo.any { it.key == newestId }
                }.distinctUntilChanged().collect { newestIsShown ->
                    if (newestIsShown) notificationsViewModel.onLatestNotificationsShown(
                        listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.filterTo(mutableSetOf()) { it in loadedIds },
                        notificationFilter == NotificationFilter.All,
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
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
            } else if (destination != MainDestination.Explore) {
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
                            if (pagerState.currentPage == page) {
                                topScrollScope.launch {
                                    when (item) {
                                        MainDestination.Home -> {
                                            if (state.isResumedWindow) viewModel.goToLatest()
                                            else timelineListState.animateToTimelineTop()
                                        }
                                        MainDestination.Notifications -> notificationListStates[notificationFilter.ordinal].animateToTimelineTop()
                                        MainDestination.Profile -> {
                                            profileListState.animateToTimelineTop(profileHeaderListState)
                                        }
                                        MainDestination.Explore -> exploreScrollToTopRequest++
                                    }
                                }
                            } else {
                                scope.launch { pagerState.scrollToPage(page) }
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
            userScrollEnabled = false,
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
                    onVotePoll = actionsViewModel::votePoll,
                    onMoreClick = { menuStatus = it },
                    onUnavailableAction = { label ->
                        scope.launch { snackbarHostState.showSnackbar("$label は次の実装で追加します") }
                    },
                    preferences = mainState.preferences,
                )
                MainDestination.Explore -> SearchContent(
                    state = searchState,
                    scrollToTopRequest = exploreScrollToTopRequest,
                    exploreState = exploreState,
                    onSelectExploreFeed = searchViewModel::selectExploreFeed,
                    onEnsureExploreLoaded = searchViewModel::ensureExploreLoaded,
                    onRefreshExplore = searchViewModel::refreshExplore,
                    onLoadMoreExplore = searchViewModel::loadMoreExplore,
                    onRetryExplore = searchViewModel::retryExplore,
                    onUnfollowTag = { searchViewModel.setTagFollowing(it, false) },
                    onOpenTag = onOpenTag,
                    isVisible = destination == MainDestination.Explore,
                    padding = padding,
                    onQueryChanged = searchViewModel::onQueryChanged,
                    onSearch = searchViewModel::search,
                    onEnterSearch = searchViewModel::enterSearch,
                    onBack = { searchViewModel.returnToExplore() },
                    onClear = { searchViewModel.returnToExplore(clear = true) },
                    onSelectTarget = searchViewModel::selectTarget,
                    onLoadMore = searchViewModel::loadMore,
                    onRetry = searchViewModel::retry,
                    onStatusClick = onStatusClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = actionsViewModel::toggleReblog,
                    onQuote = onQuote,
                    onFavourite = actionsViewModel::toggleFavourite,
                    onBookmark = actionsViewModel::toggleBookmark,
                    onReact = actionsViewModel::setReaction,
                    onVotePoll = actionsViewModel::votePoll,
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    onMoreClick = { menuStatus = it },
                    preferences = mainState.preferences,
                )
                MainDestination.Notifications -> NotificationsContent(
                    state = notificationsState,
                    padding = padding,
                    onRefresh = notificationsViewModel::refreshNotifications,
                    onLoadMore = notificationsViewModel::loadNextNotifications,
                    onAutoLoadMore = notificationsViewModel::loadNextNotificationsAutomatically,
                    onStatusClick = onStatusClick,
                    onOpenLink = onOpenLink,
                    onReply = { onCompose(it.statusId) },
                    onBoost = actionsViewModel::toggleReblog,
                    onQuote = onQuote,
                    onFavourite = actionsViewModel::toggleFavourite,
                    onBookmark = actionsViewModel::toggleBookmark,
                    onReact = actionsViewModel::setReaction,
                    onVotePoll = actionsViewModel::votePoll,
                    onMoreClick = { menuStatus = it },
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    preferences = mainState.preferences,
                    listStates = notificationListStates,
                    selectedFilter = notificationFilter,
                    onSelectFilter = { notificationFilter = it },
                    newNoticeMessage = if (snackbarHostState.currentSnackbarData == null && notificationsState.canShowNewNotice) {
                        when {
                            notificationsState.pendingNewNotificationIds.isNotEmpty() ->
                                "新着 ${notificationsState.pendingNewNotificationIds.size}件"
                            notificationsState.shownNewNotice != null ->
                                "新着 ${notificationsState.shownNewNotice?.count}件"
                            else -> null
                        }
                    } else null,
                    onNewNoticeClick = if (notificationsState.pendingNewNotificationIds.isNotEmpty()) {
                        {
                            notificationFilter = NotificationFilter.All
                            scope.launch { allNotificationsListState.animateToTimelineTop() }
                            Unit
                        }
                    } else null,
                )
                MainDestination.Profile -> ProfileContent(
                    state = profileState,
                    instanceUrl = mainState.session?.instanceUrl,
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
                    onVotePoll = actionsViewModel::votePoll,
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
                            onMediaClick(listOf(MediaAttachment("profile-header", "image", url, url, "ヘッダー画像",
                                cacheRevision = profileState.imageRefreshRevision)), 0)
                        }
                    },
                    onAvatarClick = {
                        profileState.profile?.author?.avatarUrl?.takeIf(String::isNotBlank)?.let { url ->
                            onMediaClick(listOf(MediaAttachment("profile-avatar", "image", url, url, "プロフィール画像",
                                cacheRevision = profileState.imageRefreshRevision)), 0)
                        }
                    },
                    onEditProfile = profileViewModel::beginProfileEdit,
                    onOpenLists = onOpenLists,
                    onOpenBookmarks = onOpenBookmarks,
                    onOpenFavourites = onOpenFavourites,
                    listStates = profileListStates,
                    headerListState = profileHeaderListState,
                )
            }
        }
    }
        val newPostMessage = when {
            state.isResumedWindow -> "前回の位置を表示中・最新へ"
            state.unseenStreamIds.isNotEmpty() -> "新着 ${state.unseenStreamIds.size}件"
            state.streamAtTopCount != null -> "新着 ${state.streamAtTopCount}件"
            else -> refreshNotice
        }
        if (destination == MainDestination.Home && newPostMessage != null && snackbarHostState.currentSnackbarData == null) {
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)) {
                TimelineNotice(newPostMessage, onClick = if (state.isResumedWindow) {
                    { viewModel.goToLatest() }
                } else if (state.unseenStreamIds.isNotEmpty()) {
                    { scope.launch { timelineListState.animateToTimelineTop(); viewModel.updateViewport(true) }; Unit }
                } else null)
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
        ) { data ->
            TimelineNotice(data.visuals.message)
        }
    }

    if (imageEdit.isOpen) {
        profileState.profile?.let { profile ->
            EditProfileDialog(profile, imageEdit, profileViewModel::selectProfileImage,
                profileViewModel::dismissProfileEdit, profileViewModel::updateProfile)
        }
    }
    LaunchedEffect(menuStatus?.author?.id) {
        menuStatus?.author?.id?.let { actionsViewModel.loadModerationMenu(it) }
    }
    menuStatus?.let { status ->
        StatusMenuDialog(
            status = status,
            moderation = moderationMenu.takeIf { it.accountId == status.author.id },
            onRetryRelationship = { actionsViewModel.loadModerationMenu(status.author.id) },
            isOwnStatus = status.author.id == mainState.session?.accountId,
            onDismiss = { menuStatus = null },
            onOpenBrowser = {
                menuStatus = null
                status.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
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
        ConfirmStatusActionDialog(
            action = action,
            status = status,
            onDismiss = { confirmation = null },
            onConfirm = {
                when (action) {
                    StatusConfirmationAction.Delete -> actionsViewModel.deleteStatus(status)
                    StatusConfirmationAction.Unfollow -> actionsViewModel.unfollow(status)
                    StatusConfirmationAction.Mute -> actionsViewModel.mute(status)
                    StatusConfirmationAction.Unmute -> actionsViewModel.mute(status, false)
                    StatusConfirmationAction.Block -> actionsViewModel.block(status)
                    StatusConfirmationAction.Unblock -> actionsViewModel.block(status, false)
                }
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
internal fun TimelineTopBar(
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
                    modifier = Modifier.testTag("feed_selector").clickable { feedMenuOpen = true },
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
                io.github.ponpokoo.mastodonclient.feature.common.ProfileImage(
                    model = io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel(activeSession),
                    identity = listOf(activeSession?.sessionId, activeSession?.instanceUrl,
                        activeSession?.accountId, "timeline-account-switcher"),
                    retainPreviousImage = true,
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
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit,
    onReact: (TimelineStatus, String?) -> Unit,
    onVotePoll: (TimelineStatus, Set<Int>) -> Unit,
    onMoreClick: (TimelineStatus) -> Unit,
    onUnavailableAction: (String) -> Unit,
    preferences: AppPreferences,
) {
    LaunchedEffect(listState, state.statuses.size, state.nextMaxId, state.resumeAnchorId, state.isHomeSyncing) {
        if (state.resumeAnchorId != null) return@LaunchedEffect
        if (state.isHomeSyncing) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                val remaining = if (state.selectedFeed == TimelineFeed.Home) 5 else 3
                if (lastVisibleIndex != null && lastVisibleIndex >= state.statuses.lastIndex - remaining) {
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
        else -> AppPullToRefreshBox(
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
                        onQuote = { mode -> onQuote(status, mode) },
                        onFavourite = { onFavourite(status) },
                        onBookmark = { onBookmark(status) },
                        onReact = { emoji -> onReact(status, emoji) },
                        onVotePoll = { choices -> onVotePoll(status, choices) },
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
                if (state.isShowingSavedStatuses) {
                    item(key = "home_cache_notice") {
                        Text("保存済みのタイムラインを表示しています",
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
