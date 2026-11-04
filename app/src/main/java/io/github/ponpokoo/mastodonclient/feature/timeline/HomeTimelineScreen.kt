package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.feature.emoji.EmojiPickerSheetMotion
import io.github.ponpokoo.mastodonclient.feature.timeline.components.DraggableComposeFab
import io.github.ponpokoo.mastodonclient.core.preferences.FabPositionStore
import io.github.ponpokoo.mastodonclient.feature.media.LocalMediaSourceIsHome
import io.github.ponpokoo.mastodonclient.feature.media.mediaContentInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.CompositionLocalProvider

import io.github.ponpokoo.mastodonclient.feature.common.NewItemsNoticeState
import io.github.ponpokoo.mastodonclient.feature.common.NewItemsNoticeEffect
import io.github.ponpokoo.mastodonclient.feature.common.NotificationNoticeEffect
import io.github.ponpokoo.mastodonclient.feature.common.TimelineNoticeStack
import io.github.ponpokoo.mastodonclient.feature.common.withoutBottomPadding
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import io.github.ponpokoo.mastodonclient.feature.status.StatusActions
import io.github.ponpokoo.mastodonclient.feature.status.toStatusDisplayPreferences
import io.github.ponpokoo.mastodonclient.feature.status.StatusMenuDialog
import io.github.ponpokoo.mastodonclient.feature.status.ConfirmStatusActionDialog
import io.github.ponpokoo.mastodonclient.feature.status.StatusReportDialog
import io.github.ponpokoo.mastodonclient.feature.status.ListPickerDialog
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsContent
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationFilter
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationDisplaySettings
import io.github.ponpokoo.mastodonclient.feature.notifications.rememberNotificationDisplayRows
import io.github.ponpokoo.mastodonclient.feature.notifications.notificationRowsForCategory
import io.github.ponpokoo.mastodonclient.feature.notifications.visibleNotificationIds
import io.github.ponpokoo.mastodonclient.feature.common.LocalReadingSettings
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmation
import io.github.ponpokoo.mastodonclient.feature.common.StatusConfirmationAction
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import io.github.ponpokoo.mastodonclient.feature.common.AppDropdownMenu
import io.github.ponpokoo.mastodonclient.feature.common.AppDropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
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
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileScreen
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.AppPullToRefreshBox
import io.github.ponpokoo.mastodonclient.feature.common.AppTopBarHeight
import io.github.ponpokoo.mastodonclient.feature.common.AppTopBarShadowElevation
import io.github.ponpokoo.mastodonclient.feature.common.ListLoadingContent
import io.github.ponpokoo.mastodonclient.feature.common.AccountSwitchDialog
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.notification.NotificationOpenRequest
import io.github.ponpokoo.mastodonclient.ui.theme.MainTopBarTitleStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

private const val MainNavigationBarAlpha = 0.94f
private val MainNavigationMaxWidth = 352.dp
private val MainNavigationHorizontalMargin = 16.dp
private val MainNavigationInnerHorizontalPadding = 8.dp
private val MainNavigationBottomGap = 8.dp
private val MainNavigationContentGap = 8.dp
private val MainNavigationShape = RoundedCornerShape(28.dp)

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
    profileViewModel: ProfileViewModel,
    actionsViewModel: StatusActionsViewModel,
    fabPositionStore: FabPositionStore,
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
    onOpenReactions: () -> Unit = {},
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
    val snackbarHostState = remember(mainState.session?.sessionId) { SnackbarHostState() }
    LaunchedEffect(exploreState.actionMessage) {
        exploreState.actionMessage?.let { snackbarHostState.showSnackbar(it); searchViewModel.consumeExploreMessage() }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preferencesStore = remember(context) { UserPreferencesStore(context) }
    val allNotificationRows = rememberNotificationDisplayRows(
        notificationsState.notifications,
        mainState.preferences.groupNotifications,
        LocalReadingSettings.current.transientSessionKey,
    )
    val destinations = MainDestination.entries
    // Keep an off-screen page's viewport when another tab has a different top bar.
    val savedPagePadding = remember { mutableStateMapOf<MainDestination, PaddingValues>() }
    val layoutDirection = LocalLayoutDirection.current
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
    val (profileListStates, profileHeaderListState) = key(profileState.imageSessionKey) {
        List(ProfileStatusTab.entries.size) { rememberLazyListState() } to rememberLazyListState()
    }
    val profileListState = profileListStates[profileState.profileSelectedTab.ordinal]
    // NavHost can recreate this destination after a detail route is popped.
    var hasObservedTimelineContext by rememberSaveable { mutableStateOf(false) }
    var observedTimelineSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var observedTimelineFeed by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationFilter by rememberSaveable { mutableStateOf(NotificationFilter.All) }
    var menuStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    val openStatusMenu: (TimelineStatus) -> Unit = {
        if (it.author.id != mainState.session?.accountId) actionsViewModel.loadModerationMenu(it.author.id)
        menuStatus = it
    }
    var confirmation by remember(mainState.session?.sessionId) { mutableStateOf<StatusConfirmation?>(null) }
    var reportStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    var listStatus by remember(mainState.session?.sessionId) { mutableStateOf<TimelineStatus?>(null) }
    var scrollHomeAfterRefresh by remember { mutableStateOf(false) }
    var homeRefreshStarted by remember { mutableStateOf(false) }
    val destination = destinations[pagerState.currentPage]
    val topBarStates = destinations.map { tab ->
        key(mainState.session?.sessionId, tab, state.selectedFeed.takeIf { tab == MainDestination.Home }) {
            rememberTopAppBarState()
        }
    }
    val navigationScrollStates = destinations.map { tab ->
        key(mainState.session?.sessionId, tab, state.selectedFeed.takeIf { tab == MainDestination.Home }) {
            val header = topBarStates[tab.ordinal]
            remember(header) { MainNavigationScrollState(header) }
        }
    }
    val topMenus = remember(mainState.session?.sessionId) { mutableStateMapOf<MainDestination, Boolean>() }
    var searchEditing by remember(mainState.session?.sessionId) { mutableStateOf(false) }
    val topBarHeights = remember { mutableStateMapOf<MainDestination, Int>() }
    val topInsetPx = mediaContentInsets().getTop(LocalDensity.current)
    val canScrollBars = destinations.map { tab ->
        { destination == tab && topMenus[tab] != true && (tab != MainDestination.Explore || !searchEditing) }
    }
    val scrollBehaviors = destinations.map { tab ->
        TopAppBarDefaults.enterAlwaysScrollBehavior(
            state = topBarStates[tab.ordinal],
            canScroll = canScrollBars[tab.ordinal],
            flingAnimationSpec = null,
        )
    }
    val homeTopBarState = topBarStates[MainDestination.Home.ordinal]
    val currentTopBarState = topBarStates[destination.ordinal]
    val currentNavigationScrollState = navigationScrollStates[destination.ordinal]
    val scrollConnections = scrollBehaviors.mapIndexed { index, behavior ->
        val navigationState = navigationScrollStates[index]
        remember(behavior, navigationState, scope) {
            navigationState.connection(scope, canScrollBars[index])
        }
    }
    LaunchedEffect(topMenus[destination], searchEditing, destination, currentNavigationScrollState) {
        if (topMenus[destination] == true || (destination == MainDestination.Explore && searchEditing)) {
            currentTopBarState.heightOffset = 0f
            currentTopBarState.contentOffset = 0f
            currentNavigationScrollState.show()
        }
    }
    val topScrollScope = key(
        mainState.session?.sessionId, destination, state.selectedFeed,
        notificationFilter, profileState.profileSelectedTab,
    ) { rememberCoroutineScope() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    // STARTED also includes an uncommitted predictive-back preview.
    val isScreenResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val hasBeenResumed = remember(lifecycleOwner) { mutableStateOf(false) }
    val homeNotice = key(mainState.session?.sessionId, state.selectedFeed) {
        rememberSaveable(saver = NewItemsNoticeState.Saver) { NewItemsNoticeState() }
    }
    val notificationNotice = key(mainState.session?.sessionId) {
        rememberSaveable(saver = NewItemsNoticeState.Saver) { NewItemsNoticeState() }
    }
    val isHomeVisible = destination == MainDestination.Home
    val homeNewestId = state.statuses.firstOrNull()?.timelineId
    val homeAtTop by remember(isHomeVisible, homeNewestId, lifecycleState) {
        derivedStateOf {
            isHomeVisible && isScreenResumed &&
                timelineListState.firstVisibleItemIndex == 0 && timelineListState.firstVisibleItemScrollOffset == 0 &&
                !timelineListState.isScrollInProgress && homeNewestId != null &&
                timelineListState.layoutInfo.visibleItemsInfo.any { it.key == homeNewestId }
        }
    }
    val notificationsVisible = destination == MainDestination.Notifications
    LaunchedEffect(homeNotice, state.pendingNewStatusIds, homeAtTop, state.newItemsNoticeResult, state.isResumedWindow) {
        homeNotice.update(state.pendingNewStatusIds.size, homeAtTop,
            resultKey = state.newItemsNoticeResult?.id, resultCount = state.newItemsNoticeResult?.count,
            isRestored = state.isResumedWindow)
    }
    LaunchedEffect(notificationNotice, notificationsState.pendingNewNotificationIds, notificationsState.canShowNewNotice) {
        if (notificationsState.canShowNewNotice) {
            notificationNotice.updateNotificationCount(notificationsState.pendingNewNotificationIds)
        }
    }
    NewItemsNoticeEffect(homeNotice, isHomeVisible && isScreenResumed)
    NotificationNoticeEffect(notificationNotice, notificationsVisible && isScreenResumed)

    LaunchedEffect(mainState.preferencesLoaded, mainState.preferences.keepPositionOnPullRefresh,
        mainState.session?.sessionId, state.isResumedWindow, state.isHomeSyncing, isScreenResumed) {
        if (!isScreenResumed) return@LaunchedEffect
        if (mainState.preferencesLoaded) viewModel.setKeepPositionOnRefresh(mainState.preferences.keepPositionOnPullRefresh)
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
    LaunchedEffect(state.resumeAnchorId, state.statuses, isScreenResumed) {
        val anchorId = state.resumeAnchorId ?: return@LaunchedEffect
        val index = state.displayRows().indexOfFirst { it is TimelineRow.Post && it.status.timelineId == anchorId }
        if (index >= 0) timelineListState.scrollToItem(index, state.resumeOffset)
        else timelineListState.scrollToItem(0)
        if (isScreenResumed) viewModel.consumeResumeAnchor()
    }
    LaunchedEffect(mainState.session?.sessionId, mainState.preferencesLoaded,
        mainState.preferences.keepPositionOnPullRefresh, state.selectedFeed,
        state.statuses, state.isInitialLoading, state.isRefreshing, state.resumeAnchorId, isScreenResumed) {
        if (!isScreenResumed || !mainState.preferencesLoaded || !mainState.preferences.keepPositionOnPullRefresh ||
            mainState.session == null || state.isInitialLoading || state.isRefreshing ||
            state.resumeAnchorId != null || state.statuses.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            if (timelineListState.isScrollInProgress) null else {
                val visible = timelineListState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    state.statuses.any { it.timelineId == item.key }
                }
                val visibleId = visible?.key as? String
                val index = state.statuses.indexOfFirst { it.timelineId == visibleId }
                if (index < 0) null else Triple(
                    state.statuses[index].timelineId,
                    state.statuses.getOrNull(index - 1)?.timelineId,
                    -(visible?.offset ?: 0),
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

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAnnouncementsIfNeeded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(lifecycleOwner, destination, homeAtTop) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasBeenResumed.value = true
                    viewModel.updateViewport(homeAtTop)
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.updateViewport(false)
                Lifecycle.Event.ON_STOP -> {
                    viewModel.updateViewport(false)
                    if (hasBeenResumed.value) viewModel.consumeStreamNotice(viewModel.uiState.value.streamAutoScrollId)
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
    LaunchedEffect(homeAtTop, state.pendingNewStatusIds, state.selectedFeed, mainState.session?.sessionId) {
        viewModel.updateViewport(homeAtTop)
    }
    DisposableEffect(viewModel, destination) {
        onDispose {
            viewModel.updateViewport(false)
            if (hasBeenResumed.value) viewModel.consumeStreamNotice(viewModel.uiState.value.streamAutoScrollId)
        }
    }
    LaunchedEffect(state.refreshNewStatusCount, mainState.session?.sessionId, state.selectedFeed, isScreenResumed) {
        if (isScreenResumed && state.refreshNewStatusCount != null) viewModel.consumeRefreshResult()
    }
    LaunchedEffect(state.streamAutoScrollId, isHomeVisible, mainState.session?.sessionId, state.selectedFeed, isScreenResumed) {
        if (isHomeVisible && isScreenResumed && state.streamAutoScrollId > 0) {
            val requestId = state.streamAutoScrollId
            timelineListState.scrollToItem(0)
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
    LaunchedEffect(state.isRefreshing) {
        if (state.isRefreshing) {
            homeRefreshStarted = true
        } else if (homeRefreshStarted) {
            if (scrollHomeAfterRefresh) {
                navigationScrollStates[MainDestination.Home.ordinal].show()
                timelineListState.animateToTimelineTop()
            }
            homeRefreshStarted = false
            scrollHomeAfterRefresh = false
        }
    }
    LaunchedEffect(notificationsState.refreshResult, isScreenResumed) {
        notificationsState.refreshResult?.takeIf { isScreenResumed }?.let { result ->
            notificationsViewModel.consumeRefreshResult(result.id)
        }
    }
    NotificationsLifecycleEffect(notificationsViewModel, destination == MainDestination.Notifications)
    LaunchedEffect(notificationFilter) { notificationsViewModel.selectCategory(notificationFilter) }
    LaunchedEffect(destination, isScreenResumed) {
        if (isScreenResumed && destination == MainDestination.Profile) profileViewModel.loadProfile()
    }
    LaunchedEffect(
        destination, notificationFilter, notificationsState.list(notificationFilter), lifecycleState, allNotificationRows,
    ) {
        val notificationTabState = notificationsState.forCategory(notificationFilter)
        if (destination == MainDestination.Notifications &&
            notificationTabState.isInitialPageLoaded && !notificationTabState.isLoadingNotifications &&
            (notificationTabState.notificationsError == null || notificationTabState.notificationsErrorIsPagination) &&
            notificationsState.canExplore(notificationFilter)
        ) {
            val shown = notificationRowsForCategory(notificationTabState.notifications, notificationFilter, allNotificationRows)
            val newestKey = shown.firstOrNull()?.key
            if (newestKey != null) {
                val listState = notificationListStates[notificationFilter.ordinal]
                snapshotFlow {
                    lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
                        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 &&
                        !listState.isScrollInProgress &&
                        listState.layoutInfo.visibleItemsInfo.any { it.key == newestKey }
                }.distinctUntilChanged().collect { newestIsShown ->
                    if (newestIsShown) notificationsViewModel.onLatestNotificationsShown(
                        visibleNotificationIds(shown, listState.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.key }),
                        notificationFilter == NotificationFilter.All,
                    )
                }
            }
        }
    }
    val navigationHidden = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = mediaContentInsets(),
        topBar = {
            if (destination == MainDestination.Home) {
                TimelineTopBar(
                    selectedFeed = state.selectedFeed,
                    onFeedSelected = viewModel::selectFeed,
                    onAnnouncements = viewModel::showAnnouncements,
                    hasUnreadAnnouncements = state.hasUnreadAnnouncements,
                    onLogout = mainViewModel::logout,
                    activeSession = mainState.session,
                    sessions = mainState.sessions,
                    onAccountSelected = mainViewModel::switchAccount,
                    onSettings = onSettings,
                    scrollBehavior = scrollBehaviors[MainDestination.Home.ordinal],
                    onMenuVisibilityChanged = { topMenus[MainDestination.Home] = it },
                    modifier = Modifier.onSizeChanged { topBarHeights[MainDestination.Home] = it.height },
                )
            } else if (destination != MainDestination.Explore) {
                TopAppBar(
                    expandedHeight = AppTopBarHeight,
                    modifier = Modifier.testTag("main_top_bar_${destination.name.lowercase()}")
                        .onSizeChanged { topBarHeights[destination] = it.height },
                    scrollBehavior = scrollBehaviors[destination.ordinal],
                    colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surface),
                    title = { Text(destination.label, style = MainTopBarTitleStyle) },
                    actions = {
                        if (destination == MainDestination.Notifications) NotificationDisplaySettings(
                            grouped = mainState.preferences.groupNotifications,
                            onGroupedChange = { enabled -> scope.launch { preferencesStore.setGroupNotifications(enabled) } },
                            onMenuVisibilityChanged = { topMenus[MainDestination.Notifications] = it },
                        )
                    },
                    windowInsets = mediaContentInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                )
            }
        },
        bottomBar = {
            // Keep navigation fixed while the header retreats independently.
            // Controls covered by the IME must not remain actionable or in accessibility traversal.
            Box(
                Modifier.fillMaxWidth()
                    .graphicsLayer {
                        alpha = if (navigationHidden) 0f else 1f
                    }
                    .then(if (navigationHidden) Modifier.clearAndSetSemantics {} else Modifier)
                    .windowInsetsPadding(mediaContentInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(start = MainNavigationHorizontalMargin, end = MainNavigationHorizontalMargin,
                        bottom = MainNavigationBottomGap),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    // Keep the minimum loose so the bar can measure its four items separately.
                    modifier = Modifier.widthIn(max = MainNavigationMaxWidth).testTag("main_navigation_bar"),
                    shape = MainNavigationShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = MainNavigationBarAlpha),
                    tonalElevation = 0.dp,
                    shadowElevation = 4.dp,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    ShortNavigationBar(
                        // Keep the end labels inside the rounded surface, including wrapped labels.
                        modifier = Modifier.padding(horizontal = MainNavigationInnerHorizontalPadding),
                        containerColor = Color.Transparent,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        MainDestination.entries.forEach { item ->
                            val page = destinations.indexOf(item)
                            ShortNavigationBarItem(
                                // The item already has 6 dp above its indicator; add 6 dp for a 12 dp top gap.
                                modifier = Modifier.padding(top = 6.dp).testTag("main_tab_${item.name.lowercase()}"),
                                selected = destination == item,
                                enabled = !navigationHidden,
                                onClick = {
                                    if (pagerState.currentPage == page) {
                                        topScrollScope.launch {
                                            currentTopBarState.heightOffset = 0f
                                            currentTopBarState.contentOffset = 0f
                                            currentNavigationScrollState.show()
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
                                label = {
                                    Text(item.label, modifier = Modifier.padding(horizontal = 4.dp),
                                        textAlign = TextAlign.Center)
                                },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = {},
    ) { padding ->
        val measuredPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            top = with(LocalDensity.current) { topInsetPx.toDp() },
            end = padding.calculateEndPadding(layoutDirection),
            bottom = padding.calculateBottomPadding() + MainNavigationContentGap,
        )
        SideEffect { savedPagePadding[destination] = measuredPadding }
        Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { destinations[it] },
            userScrollEnabled = false,
        ) { page ->
            val pageDensity = LocalDensity.current
            // Match Material's pixel rounding so the following tabs meet the bar during a drag.
            val expandedTopBarHeight = with(pageDensity) {
                (-topBarStates[page].heightOffsetLimit).takeIf { it < 1_000_000f }?.toDp() ?: AppTopBarHeight
            }
            val pagePadding = if (destinations[page] == destination) measuredPadding
                else savedPagePadding[destinations[page]] ?: measuredPadding
            val pageModifier = if (destinations[page] == destination)
                Modifier.nestedScroll(scrollConnections[page]) else Modifier
            CompositionLocalProvider(
                LocalMediaSourceIsHome provides (destinations[page] == MainDestination.Home),
                io.github.ponpokoo.mastodonclient.feature.status.LocalPollVoteState provides
                    io.github.ponpokoo.mastodonclient.feature.status.PollVoteUiState(
                        mainState.session?.let { "${it.instanceUrl}:${it.sessionId}" }, actionsState.votingPollIds),
            ) {
            when (val pageDestination = destinations[page]) {
                MainDestination.Home -> TimelineContent(
                    state = state,
                    padding = pagePadding,
                    drawBehindBottomBar = true,
                    topContentPadding = expandedTopBarHeight,
                    headerCollapseOffset = { topBarStates[page].heightOffset.roundToInt() },
                    modifier = pageModifier,
                    listState = timelineListState,
                    onRefresh = {
                        viewModel.setKeepPositionOnRefresh(mainState.preferences.keepPositionOnPullRefresh)
                        scrollHomeAfterRefresh = !mainState.preferences.keepPositionOnPullRefresh
                        homeRefreshStarted = false
                        viewModel.refresh()
                    },
                    onRetry = viewModel::retry,
                    onLoadMore = viewModel::loadNextPage,
                    onLoadGap = viewModel::loadGap,
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
                    onMoreClick = openStatusMenu,
                    onUnavailableAction = { label ->
                        scope.launch { snackbarHostState.showSnackbar("${label}は現在利用できません") }
                    },
                    preferences = mainState.preferences,
                )
                MainDestination.Explore -> SearchContent(
                    state = searchState,
                    modifier = pageModifier,
                    drawBehindBottomBar = true,
                    searchBarModifier = Modifier.testTag("main_search_header")
                        .onSizeChanged { topBarHeights[MainDestination.Explore] = topInsetPx + it.height }
                        .collapsingSearchHeader(topBarStates[MainDestination.Explore.ordinal]),
                    headerCollapseOffset = { topBarStates[page].heightOffset.roundToInt() },
                    onSearchEditingChanged = { searchEditing = it },
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
                    padding = pagePadding,
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
                    onMoreClick = openStatusMenu,
                    preferences = mainState.preferences,
                )
                MainDestination.Notifications -> NotificationsContent(
                    state = notificationsState,
                    modifier = pageModifier,
                    drawBehindBottomBar = true,
                    topContentPadding = expandedTopBarHeight,
                    headerCollapseOffset = { topBarStates[page].heightOffset.roundToInt() },
                    allDisplayRows = allNotificationRows,
                    padding = pagePadding,
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
                    onMoreClick = openStatusMenu,
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    preferences = mainState.preferences,
                    listStates = notificationListStates,
                    selectedFilter = notificationFilter,
                    onSelectFilter = { notificationFilter = it },
                    newNoticeMessage = null,
                    onNewNoticeClick = null,
                    noticeContent = {
                        TimelineNoticeStack(notificationNotice.notice, snackbarHostState, onClick = {})
                    },
                )
                MainDestination.Profile -> ProfileScreen(
                    viewModel = profileViewModel,
                    modifier = pageModifier,
                    drawBehindBottomBar = true,
                    topContentPadding = expandedTopBarHeight,
                    topBarVisibleHeight = {
                        (with(pageDensity) { expandedTopBarHeight.roundToPx() } +
                            topBarStates[page].heightOffset.roundToInt()).coerceAtLeast(0).toFloat()
                    },
                    preferences = mainState.preferences,
                    padding = pagePadding,
                    snackbarHostState = snackbarHostState,
                    onStatusClick = onStatusClick,
                    onReply = { onCompose(it.statusId) },
                    onQuote = onQuote,
                    onOpenLink = onOpenLink,
                    onAccountClick = onAccountClick,
                    onMediaClick = onMediaClick,
                    onFollowers = onFollowers,
                    onFollowing = onFollowing,
                    onOpenLists = onOpenLists,
                    onOpenBookmarks = onOpenBookmarks,
                    onOpenFavourites = onOpenFavourites,
                    onOpenReactions = onOpenReactions,
                    onEditStatus = onEditStatus,
                    profileListStates = profileListStates,
                    profileHeaderListState = profileHeaderListState,
                )
            }
            }
        }
        DraggableComposeFab(
            store = fabPositionStore,
            onCompose = { onCompose(null) },
            visible = destination == MainDestination.Home,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
        }
    }
        if (destination != MainDestination.Notifications) {
            TimelineNoticeStack(
                notice = homeNotice.notice.takeIf { destination == MainDestination.Home },
                snackbarHostState = snackbarHostState,
                onClick = {
                    homeTopBarState.heightOffset = 0f
                    homeTopBarState.contentOffset = 0f
                    navigationScrollStates[MainDestination.Home.ordinal].show()
                    if (state.isResumedWindow) viewModel.goToLatest()
                    else topScrollScope.launch { timelineListState.animateToTimelineTop() }
                },
                modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, topBarHeights[destination] ?: topInsetPx) },
            )
        }
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
                status.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
            },
            onPin = { menuStatus = null; actionsViewModel.setPinned(status) },
            onEdit = { menuStatus = null; onEditStatus(status.statusId) },
            onDelete = { menuStatus = null; confirmation = StatusConfirmation(StatusConfirmationAction.Delete, status) },
            onAddToList = { menuStatus = null; listStatus = status; actionsViewModel.loadLists() },
            onFollow = { menuStatus = null; actionsViewModel.follow(status) },
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
        ListPickerDialog(
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
            onShown = viewModel::onAnnouncementsShown,
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
    modifier: Modifier = Modifier,
    hasUnreadAnnouncements: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onMenuVisibilityChanged: (Boolean) -> Unit = {},
) {
    var feedMenuOpen by remember { mutableStateOf(false) }
    var accountDialogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(feedMenuOpen, accountDialogOpen) {
        onMenuVisibilityChanged(feedMenuOpen || accountDialogOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onMenuVisibilityChanged(false) }
    }

    TopAppBar(
        expandedHeight = AppTopBarHeight,
        modifier = modifier.testTag("home_top_bar").graphicsLayer {
            // Fade the shadow with the toolbar so it does not remain under the status bar.
            shadowElevation = AppTopBarShadowElevation.toPx() * (1f - (scrollBehavior?.state?.collapsedFraction ?: 0f))
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        windowInsets = mediaContentInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
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
                        style = MainTopBarTitleStyle,
                    )
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = "フィードを切り替える")
                }
                AppDropdownMenu(expanded = feedMenuOpen, onDismissRequest = { feedMenuOpen = false }) {
                    TimelineFeed.entries.forEach { feed ->
                        AppDropdownMenuItem(
                            label = when (feed) {
                                TimelineFeed.Home -> "ホーム"
                                TimelineFeed.Local -> "ローカル"
                                TimelineFeed.Federated -> "連合"
                            },
                            isSelected = feed == selectedFeed,
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
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentScale = ContentScale.Crop,
                )
            }
            IconButton(onClick = onAnnouncements) {
                BadgedBox(badge = {
                    if (hasUnreadAnnouncements) Badge(Modifier.testTag("server_announcements_unread"))
                }) {
                    Icon(
                        Icons.Outlined.Campaign,
                        contentDescription = if (hasUnreadAnnouncements)
                            "サーバーからのお知らせ（未読あり）" else "サーバーからのお知らせ",
                        modifier = Modifier.testTag("server_announcements"),
                    )
                }
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
    onShown: (Set<String>) -> Unit,
) {
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.CREATED,
    )
    LaunchedEffect(listState, isLoading, errorMessage, announcements, lifecycleState) {
        if (isLoading || errorMessage != null || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()
        }.distinctUntilChanged().collect { onShown(it) }
    }
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
            else -> LazyColumn(Modifier.fillMaxWidth(), state = listState) {
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
internal fun TimelineContent(
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
    modifier: Modifier = Modifier,
    onLoadGap: (String) -> Unit = {},
    drawBehindBottomBar: Boolean = false,
    topContentPadding: Dp = 0.dp,
    headerCollapseOffset: () -> Int = { 0 },
) {
    val viewportPadding = if (drawBehindBottomBar) padding.withoutBottomPadding() else padding
    val contentDensity = LocalDensity.current
    val rows = remember(state.statuses, state.homeIndex, state.automaticGapCheck) { state.displayRows() }
    val rowKeys = remember(rows) { rows.map { it.key } }
    var previousKeys by remember(listState) { mutableStateOf<List<Any>>(emptyList()) }
    // Explicitly preserve the visible post, including index zero. Capture the viewport
    // at application time, not request time, so scrolling during HTTP is respected.
    SideEffect {
        if (previousKeys != rowKeys) {
            val anchor = if (preferences.keepPositionOnPullRefresh &&
                state.resumeAnchorId == null && state.streamAutoScrollId == 0L &&
                previousKeys.isNotEmpty() && !listState.isScrollInProgress) {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    item.key is String && item.key in previousKeys && item.key in rowKeys
                }?.let { rowKeys.indexOf(it.key) to -it.offset }
            } else null
            if (anchor != null && !listState.isScrollInProgress) listState.requestScrollToItem(anchor.first, anchor.second)
            previousKeys = rowKeys
        }
    }
    LaunchedEffect(listState, rows.size, state.nextMaxId, state.resumeAnchorId, state.isHomeSyncing) {
        if (state.resumeAnchorId != null) return@LaunchedEffect
        if (state.isHomeSyncing) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                val remaining = if (state.selectedFeed == TimelineFeed.Home) 5 else 3
                if (lastVisibleIndex != null && lastVisibleIndex >= rows.lastIndex - remaining) {
                    onLoadMore()
                }
            }
    }
    when {
        state.isInitialLoading -> ListLoadingContent(Modifier.padding(padding))
        rows.isEmpty() && state.errorMessage != null -> CenteredMessage(padding) {
            Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("再試行") }
        }
        rows.isEmpty() -> CenteredMessage(padding) {
            Text("投稿がありません")
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("再読み込み") }
        }
        else -> AppPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize().padding(viewportPadding),
            indicatorModifier = Modifier.offset {
                IntOffset(0, (with(contentDensity) { topContentPadding.roundToPx() } + headerCollapseOffset()).coerceAtLeast(0))
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("timeline_list"),
                contentPadding = PaddingValues(top = topContentPadding,
                    bottom = if (drawBehindBottomBar) padding.calculateBottomPadding() else 0.dp),
            ) {
                items(items = rows, key = { it.key }) { row ->
                    if (row is TimelineRow.Gap) {
                        TimelineGapRow(
                            loading = state.loadingGapId == row.gap.beforeId,
                            unavailable = row.gap.unavailable,
                            error = state.gapErrors[row.gap.beforeId],
                            enabled = !state.isRefreshing && !state.isHomeSyncing && state.loadingGapId == null,
                            onClick = { onLoadGap(row.gap.beforeId) },
                        )
                        HorizontalDivider()
                        return@items
                    }
                    val status = (row as TimelineRow.Post).status
                    StatusCard(
                        status = status,
                        onStatusClick = onStatusClick,
                        onAuthorClick = onAccountClick,
                        onMediaClick = onMediaClick,
                        onOpenLink = onOpenLink,
                        actions = StatusActions(
                            onReply = onReply,
                            onBoost = onBoost,
                            onQuote = onQuote,
                            onFavourite = onFavourite,
                            onBookmark = onBookmark,
                            onReact = onReact,
                            onVotePoll = onVotePoll,
                        ),
                        onMoreClick = onMoreClick,
                        onUnavailableAction = onUnavailableAction,
                        preferences = preferences.toStatusDisplayPreferences(),
                        useTimelineImagePreviews = true,
                        reactionPickerMotion = EmojiPickerSheetMotion.Standard,
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
