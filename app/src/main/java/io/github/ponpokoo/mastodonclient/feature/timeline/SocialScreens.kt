package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsUiState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.profile.profileWebLinks
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.feature.common.AppPullToRefreshBox
import io.github.ponpokoo.mastodonclient.feature.common.rememberSwipeTabs
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconShape
import io.github.ponpokoo.mastodonclient.feature.common.toShape
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.feature.profile.withTab
import androidx.compose.material3.Button
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

internal typealias NotificationFilter = io.github.ponpokoo.mastodonclient.domain.model.NotificationCategory

@Composable
internal fun NotificationsContent(
    state: NotificationsUiState,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit = { _, _ -> },
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit,
    onReact: (TimelineStatus, String?) -> Unit,
    onVotePoll: (TimelineStatus, Set<Int>) -> Unit = { _, _ -> },
    onMoreClick: (TimelineStatus) -> Unit = {},
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    preferences: AppPreferences,
    listStates: List<LazyListState>,
    selectedFilter: NotificationFilter,
    onSelectFilter: (NotificationFilter) -> Unit,
    newNoticeMessage: String?,
    onNewNoticeClick: (() -> Unit)?,
    onAutoLoadMore: () -> Unit = onLoadMore,
) {
    check(listStates.size == NotificationFilter.entries.size)
    // Keep new rows close to the quoted post's surface instead of using a full accent fill.
    val highlightedBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        .compositeOver(MaterialTheme.colorScheme.surface)
    val filterTabs = rememberSwipeTabs(selectedFilter.ordinal, NotificationFilter.entries.size) {
        onSelectFilter(NotificationFilter.entries[it])
    }
    val filterPagerState = filterTabs.pagerState
    val selectedListState = listStates[selectedFilter.ordinal]
    val activeState = state.forCategory(selectedFilter)
    val activeList = state.list(selectedFilter)
    LaunchedEffect(selectedListState, activeList, selectedFilter, state.capabilities, state.isCheckingCapabilities) {
        if (state.canExplore(selectedFilter) && activeList.canAutoLoad) {
            snapshotFlow {
                if (filterTabs.isMoving || filterPagerState.settledPage != selectedFilter.ordinal) null
                else selectedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            }
                .distinctUntilChanged().collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= selectedListState.layoutInfo.totalItemsCount - 4) onAutoLoadMore()
                }
        }
    }
    AppPullToRefreshBox(
            isRefreshing = activeState.isPullRefreshingNotifications,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("notifications_screen"),
        ) {
            Column(Modifier.fillMaxSize()) {
                SecondaryTabRow(selectedTabIndex = filterTabs.selectedPage) {
                    NotificationFilter.entries.forEach { filter ->
                        Tab(
                            modifier = Modifier.testTag("notification_filter_${filter.name.lowercase()}"),
                            selected = filterTabs.selectedPage == filter.ordinal,
                            onClick = { filterTabs.selectPage(filter.ordinal) },
                            text = { Text(filter.label) },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                HorizontalPager(
                    state = filterPagerState,
                    modifier = Modifier.fillMaxSize().testTag("notification_filter_pager"),
                    key = { NotificationFilter.entries[it] },
                ) { page ->
                    val filter = NotificationFilter.entries[page]
                    val pageState = state.forCategory(filter)
                    val filteredNotifications = pageState.notifications.filter(filter::includes)
                    val supported = state.canExplore(filter)
                    LazyColumn(Modifier.fillMaxSize(), state = listStates[page]) {
                    if (!supported) item {
                        MessageContent(if (state.isCheckingCapabilities) "対応状況を確認しています" else "現在サポートしていません")
                    }
                    else if (filteredNotifications.isEmpty() && pageState.notificationsError == null) item {
                        MessageContent(when {
                            !pageState.isInitialPageLoaded || pageState.isLoadingNotifications -> "通知を読み込んでいます"
                            pageState.notificationsEndReached -> "該当する通知はありません"
                            else -> "読み込んだ範囲に該当する通知はありません"
                        })
                    }
                    items(if (supported) filteredNotifications else emptyList(), key = TimelineNotification::id) { notification ->
                        Column(Modifier.fillMaxWidth().background(
                            if (notification.id in state.highlightedNotificationIds) highlightedBackground
                            else MaterialTheme.colorScheme.surface,
                        )) {
                        val status = notification.status
                        val isReply = notification.type.equals("mention", ignoreCase = true) ||
                            notification.type.equals("reply", ignoreCase = true)
                        if (isReply && status != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NotificationTypeIcon(Icons.AutoMirrored.Filled.Reply, "返信", notification.type)
                                Spacer(Modifier.width(8.dp))
                                Text("返信", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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
                                onReact = { onReact(status, it) },
                                onVotePoll = { choices -> onVotePoll(status, choices) },
                                onMoreClick = onMoreClick,
                                onUnavailableAction = {},
                                displayPreferences = preferences.timelineDisplay,
                                gifAutoplay = preferences.gifAutoplay,
                                videoAutoplay = preferences.videoAutoplay,
                            )
                        } else {
                            NotificationHeader(notification, onAccountClick, preferences.timelineDisplay.avatarIconShape)
                            status?.let {
                                NotificationStatusQuote(
                                    status = it,
                                    onStatusClick = onStatusClick,
                                    onMoreClick = onMoreClick,
                                    preferences = preferences.timelineDisplay,
                                )
                            }
                        }
                        HorizontalDivider()
                        }
                    }
                    if (supported && pageState.isLoadingMoreNotifications) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                    } else if (supported && pageState.notificationsError != null) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(pageState.notificationsError!!, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = if (pageState.notificationsErrorIsPagination) onLoadMore else onRefresh) {
                                    Text("再試行")
                                }
                            }
                        }
                    } else if (supported && !pageState.isLoadingNotifications && !pageState.notificationsEndReached && pageState.notificationsNextMaxId != null) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = onLoadMore) { Text("さらに読み込む") }
                            }
                        }
                    }
                    }
                }
                if (newNoticeMessage != null) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                        TimelineNotice(newNoticeMessage, onNewNoticeClick)
                    }
                }
                }
            }
        }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ProfileContent(
    state: ProfileUiState,
    padding: PaddingValues,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit = {},
    onReact: (TimelineStatus, String?) -> Unit,
    onVotePoll: (TimelineStatus, Set<Int>) -> Unit = { _, _ -> },
    onMoreClick: (TimelineStatus) -> Unit = {},
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    preferences: AppPreferences = AppPreferences(),
    relationship: AccountRelationship? = null,
    selectedTab: ProfileStatusTab = ProfileStatusTab.Posts,
    isLoadingMore: Boolean = false,
    onSelectTab: (ProfileStatusTab) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onFollowers: () -> Unit = {},
    onFollowing: () -> Unit = {},
    onHeaderClick: () -> Unit = {},
    onAvatarClick: () -> Unit = {},
    onEditProfile: () -> Unit = {},
    onOpenLists: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenFavourites: () -> Unit = {},
    onToggleFollow: () -> Unit = {},
    onShareProfile: (() -> Unit)? = null,
    instanceUrl: String? = null,
    onAddProfileToList: (() -> Unit)? = null,
    onCopyProfileUrl: (() -> Unit)? = null,
    onShowProfileQr: (() -> Unit)? = null,
    onOpenFollowedTags: (() -> Unit)? = null,
    onMuteProfile: (() -> Unit)? = null,
    onBlockProfile: (() -> Unit)? = null,
    onReportProfile: (() -> Unit)? = null,
    onProfileMenuOpen: () -> Unit = {},
    moderationReady: Boolean = true,
    moderationError: String? = null,
    onRetryModeration: () -> Unit = {},
    isProfileMuted: Boolean = false,
    isProfileBlocked: Boolean = false,
    listStates: List<LazyListState>? = null,
    headerListState: LazyListState? = null,
) {
    val profile = state.profile
    val webLinks = profile?.let { profileWebLinks(it.url, instanceUrl) }
    val avatarShape = preferences.timelineDisplay.avatarIconShape.toShape()
    val resolvedListStates = listStates ?: List(ProfileStatusTab.entries.size) { rememberLazyListState() }
    check(resolvedListStates.size == ProfileStatusTab.entries.size)
    val resolvedListState = resolvedListStates[selectedTab.ordinal]
    val resolvedHeaderListState = headerListState ?: rememberLazyListState()
    val headerScrollConnection = remember(resolvedHeaderListState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scroll the shared header away before scrolling the selected post list.
                if (available.y >= 0f) return Offset.Zero
                return Offset(0f, -resolvedHeaderListState.dispatchRawDelta(-available.y))
            }
        }
    }
    val density = LocalDensity.current
    var tabRowHeight by remember { mutableStateOf(48.dp) }
    val profileTabs = key(profile?.url, profile?.author?.id) {
        rememberSwipeTabs(selectedTab.ordinal, ProfileStatusTab.entries.size) {
            onSelectTab(ProfileStatusTab.entries[it])
        }
    }
    val tabPagerState = profileTabs.pagerState
    val postsScrollConnection = remember(resolvedHeaderListState, resolvedListState, profileTabs) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // A gesture starting on the header belongs to the outer list. Keep its
                // drag/fling running by consuming the remainder in the selected post list.
                // Child-owned gestures already scroll posts through their own scroll scope.
                if (available.y >= 0f || !resolvedHeaderListState.isScrollInProgress ||
                    resolvedListState.isScrollInProgress || profileTabs.isMoving) return Offset.Zero
                return Offset(0f, -resolvedListState.dispatchRawDelta(-available.y))
            }
        }
    }
    LaunchedEffect(resolvedListState, profile?.statuses?.size, profile?.nextMaxId, selectedTab,
        state.isLoadingProfile, state.isRefreshingProfile, isLoadingMore) {
        if (!state.isLoadingProfile && !state.isRefreshingProfile && !isLoadingMore &&
            profile?.nextMaxId != null && !profile.endReached) {
            snapshotFlow {
                if (profileTabs.isMoving || tabPagerState.settledPage != selectedTab.ordinal) null
                else resolvedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            }
                .distinctUntilChanged().collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= resolvedListState.layoutInfo.totalItemsCount - 4) onLoadMore()
                }
        }
    }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    when {
        state.isLoadingProfile && profile == null -> LoadingContent(
            "プロフィールを読み込んでいます", Modifier.padding(padding),
        )
        profile == null -> Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.profileError ?: "プロフィールを表示できませんでした")
            TextButton(onClick = onRetry) { Text("再試行") }
        }
        else -> AppPullToRefreshBox(
            isRefreshing = state.isRefreshingProfile,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("profile_screen"),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val pagerHeight = (maxHeight - tabRowHeight).coerceAtLeast(0.dp)
            LazyColumn(
                modifier = Modifier.fillMaxSize().nestedScroll(postsScrollConnection),
                state = resolvedHeaderListState,
            ) {
            item(key = "profile_header") {
                Column(Modifier.fillMaxWidth().testTag("profile_header")) {
                Box(Modifier.fillMaxWidth().height(184.dp)) {
                    AsyncImage(
                        model = profile.headerUrl, contentDescription = "ヘッダー画像",
                        modifier = Modifier.fillMaxWidth().height(132.dp).clickable(onClick = onHeaderClick)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    AsyncImage(
                        model = profile.author.avatarUrl, contentDescription = "プロフィール画像",
                        modifier = Modifier.padding(start = 16.dp).align(Alignment.BottomStart).size(84.dp)
                            .clip(avatarShape).clickable(onClick = onAvatarClick)
                            .testTag("profile_avatar")
                            .background(MaterialTheme.colorScheme.surface)
                            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, avatarShape),
                        contentScale = ContentScale.Crop,
                    )
                    Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (profile.isOwnProfile) {
                                Button(onClick = onEditProfile) { Text("プロフィールを編集") }
                            } else {
                                Button(onClick = onToggleFollow,
                                    enabled = relationship != null && !relationship.requested) {
                                    Text(when {
                                        relationship?.requested == true -> "リクエスト中"
                                        relationship?.following == true -> "フォロー解除"
                                        profile.locked -> "リクエスト"
                                        else -> "フォロー"
                                    })
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Box {
                            OutlinedIconButton(
                                onClick = { onProfileMenuOpen(); profileMenuExpanded = true },
                                modifier = Modifier.size(48.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = "プロフィールのその他メニュー",
                                )
                            }
                        DropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false },
                        ) {
                            if (profile.isOwnProfile) {
                                onShareProfile?.let { action -> DropdownMenuItem(text = { Text("共有") }, onClick = { profileMenuExpanded = false; action() }) }
                                onCopyProfileUrl?.let { action -> DropdownMenuItem(text = { Text("リンクをコピー") }, onClick = { profileMenuExpanded = false; action() }) }
                                onShowProfileQr?.let { action -> DropdownMenuItem(text = { Text("QRコードを表示") }, onClick = { profileMenuExpanded = false; action() }) }
                                webLinks?.profile?.takeIf(String::isNotBlank)?.let { url -> DropdownMenuItem(text = { Text("ブラウザで開く") }, onClick = { profileMenuExpanded = false; onOpenLink(url) }) }
                                DropdownMenuItem(text = { Text("リスト") }, onClick = { profileMenuExpanded = false; onOpenLists() })
                                DropdownMenuItem(text = { Text("ブックマーク") }, onClick = { profileMenuExpanded = false; onOpenBookmarks() })
                                DropdownMenuItem(text = { Text("お気に入り") }, onClick = { profileMenuExpanded = false; onOpenFavourites() })
                                onOpenFollowedTags?.let { action -> DropdownMenuItem(text = { Text("フォロー中のハッシュタグ") }, onClick = { profileMenuExpanded = false; action() }) }
                                webLinks?.settings?.let { url -> DropdownMenuItem(text = { Text("マストドンの設定") }, onClick = { profileMenuExpanded = false; onOpenLink(url) }) }
                            } else {
                                onShareProfile?.let { action -> DropdownMenuItem(text = { Text("共有") }, onClick = { profileMenuExpanded = false; action() }) }
                                webLinks?.profile?.takeIf(String::isNotBlank)?.let { url -> DropdownMenuItem(text = { Text("ブラウザで開く") }, onClick = { profileMenuExpanded = false; onOpenLink(url) }) }
                                onAddProfileToList?.let { action -> DropdownMenuItem(text = { Text("リストに追加") }, onClick = { profileMenuExpanded = false; action() }) }
                                if (onMuteProfile != null || onBlockProfile != null || onReportProfile != null) HorizontalDivider()
                                onMuteProfile?.let { action -> DropdownMenuItem(enabled = moderationReady, text = { Text(if (isProfileMuted) "ミュート（解除）" else "ミュート", color = MaterialTheme.colorScheme.error) }, onClick = { profileMenuExpanded = false; action() }) }
                                onBlockProfile?.let { action -> DropdownMenuItem(enabled = moderationReady, text = { Text(if (isProfileBlocked) "ブロック（解除）" else "ブロック", color = MaterialTheme.colorScheme.error) }, onClick = { profileMenuExpanded = false; action() }) }
                                onReportProfile?.let { action -> DropdownMenuItem(text = { Text("報告", color = MaterialTheme.colorScheme.error) }, onClick = { profileMenuExpanded = false; action() }) }
                                moderationError?.let { error -> DropdownMenuItem(text = { Text("$error・再試行", color = MaterialTheme.colorScheme.error) }, onClick = onRetryModeration) }

                            }
                            webLinks?.about?.let { url ->
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("このサーバーについて") },
                                    onClick = { profileMenuExpanded = false; onOpenLink(url) },
                                )
                            }
                        }
                    }
                }
                }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        val handle = profileAccountHandle(profile.author.accountName, profile.url)
                        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val badgeStyle = MaterialTheme.typography.labelSmall
                        val handleStyle = MaterialTheme.typography.bodyLarge
                        val badgeWidth = measurer.measure("フォローされています", badgeStyle).size.width
                        val handleWidth = measurer.measure(handle, handleStyle).size.width
                        val extraWidth = with(density) { (if (profile.locked) 28.dp else 8.dp).roundToPx() }
                        val badgeById = handleWidth + badgeWidth + extraWidth <= constraints.maxWidth
                        val followed = relationship?.followedBy == true
                        Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CustomEmojiText(
                                text = profile.author.displayName, emojis = profile.customEmojis,
                                modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            )
                            if (followed && !badgeById) {
                                Text("フォローされています", Modifier.padding(start = 8.dp),
                                    style = badgeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(handle, Modifier.weight(1f, fill = false), style = handleStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (profile.locked) Icon(Icons.Outlined.Lock, "非公開アカウント", Modifier.padding(start = 4.dp).size(16.dp))
                            if (followed && badgeById) Text("フォローされています", Modifier.padding(start = 8.dp),
                                style = badgeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.TopCenter) { ProfileCount("投稿", profile.statusesCount) }
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onFollowing), contentAlignment = Alignment.TopCenter) { ProfileCount("フォロー", profile.followingCount) }
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onFollowers), contentAlignment = Alignment.TopCenter) { ProfileCount("フォロワー", profile.followersCount) }
                        Box(Modifier.weight(1.3f), contentAlignment = Alignment.TopCenter) { ProfileRegistrationDate(profile.createdAt) }
                    }
                    if (profile.noteHtml.isNotBlank()) {
                        StatusContentText(
                            contentHtml = profile.noteHtml,
                            customEmojis = profile.customEmojis,
                            onLinkClick = onOpenLink,
                        )
                    }
                    if (profile.fields.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column {
                                profile.fields.forEachIndexed { index, field ->
                                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                                    Row(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            CustomEmojiText(
                                                text = field.name, emojis = profile.customEmojis,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            StatusContentText(field.valueHtml, customEmojis = profile.customEmojis, onLinkClick = onOpenLink)
                                        }
                                        if (field.verifiedAt != null) {
                                            Icon(Icons.Outlined.Check, contentDescription = "リンク確認済み",
                                                modifier = Modifier.padding(start = 8.dp).size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                }
            }
            stickyHeader(key = "profile_tabs") {
                SecondaryTabRow(selectedTabIndex = profileTabs.selectedPage,
                    modifier = Modifier.onSizeChanged { tabRowHeight = with(density) { it.height.toDp() } }) {
                    listOf("投稿", "投稿と返信", "メディア").forEachIndexed { index, label ->
                        Tab(modifier = Modifier.testTag("profile_status_tab_${ProfileStatusTab.entries[index].name.lowercase()}"),
                            selected = profileTabs.selectedPage == index,
                            onClick = { profileTabs.selectPage(index) }, text = { Text(label) })
                    }
                }
            }
            item(key = "profile_posts") {
            HorizontalPager(
                state = tabPagerState,
                modifier = Modifier.fillMaxWidth().height(pagerHeight)
                    .nestedScroll(headerScrollConnection).testTag("profile_status_pager"),
                key = { ProfileStatusTab.entries[it] },
            ) { page ->
            val pageTab = ProfileStatusTab.entries[page]
            val isSelectedPage = pageTab == selectedTab
            val pageState = state.profileTabs[pageTab]
            val pageProfile = if (pageState != null) profile.withTab(pageState) else if (isSelectedPage) profile else null
            val postListState = resolvedListStates[page]
            val pinnedStatusIds = pageProfile?.pinnedStatuses?.map { it.statusId }.orEmpty()
            if (pageTab == ProfileStatusTab.Posts) {
                // Capture the position before LazyColumn preserves the old first item's key.
                val wasAtTop = remember(postListState, pinnedStatusIds) {
                    postListState.firstVisibleItemIndex == 0 &&
                        postListState.firstVisibleItemScrollOffset == 0 && !postListState.isScrollInProgress
                }
                LaunchedEffect(postListState, pinnedStatusIds) {
                    if (pinnedStatusIds.isNotEmpty() && wasAtTop) postListState.requestScrollToItem(0)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = postListState) {
            val statuses = if (pageProfile == null) {
                emptyList()
            } else if (pageTab == ProfileStatusTab.Posts) {
                (pageProfile.pinnedStatuses + pageProfile.statuses).distinctBy { it.statusId }
            } else {
                pageProfile.statuses
            }
            items(statuses, key = { it.timelineId }) { status ->
                SocialStatus(
                    status, onStatusClick, onOpenLink, onReply,
                    onBoost, onQuote, onFavourite, onBookmark, onReact, onVotePoll, onAccountClick, onMediaClick, preferences,
                    onMoreClick = onMoreClick,
                    isPinned = pageTab == ProfileStatusTab.Posts && status.statusId in pinnedStatusIds,
                )
            }
                if (statuses.isEmpty() && relationship?.let { it.muting || it.blocking } == true) {
                    item { Text("ミュートまたはブロック中のため、投稿を表示していません", modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (pageProfile == null || pageState?.isLoading == true || pageState?.isLoadingMore == true ||
                    (pageState == null && isSelectedPage && (state.isLoadingProfile || isLoadingMore))) {
                    item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (isSelectedPage && !profile.endReached) {
                    item { TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") } }
                }
            }
            }
            }
            }
            }
        }
    }
}

@Composable
internal fun SocialStatus(
    status: TimelineStatus,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit,
    onReact: (TimelineStatus, String?) -> Unit,
    onVotePoll: (TimelineStatus, Set<Int>) -> Unit = { _, _ -> },
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    preferences: AppPreferences,
    onMoreClick: (TimelineStatus) -> Unit = {},
    isPinned: Boolean = false,
) {
    if (isPinned) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 64.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text("固定された投稿", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
        onReact = { onReact(status, it) },
        onVotePoll = { choices -> onVotePoll(status, choices) },
        onMoreClick = onMoreClick,
        onUnavailableAction = {},
        displayPreferences = preferences.timelineDisplay,
        gifAutoplay = preferences.gifAutoplay,
        videoAutoplay = preferences.videoAutoplay,
    )
    HorizontalDivider()
}

@Composable
internal fun AccountResult(account: StatusAuthor, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick(account.id) }
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
            CustomEmojiText(account.displayName, account.customEmojis, fontWeight = FontWeight.SemiBold)
            Text("@${account.accountName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NotificationHeader(
    notification: TimelineNotification,
    onAccountClick: (String) -> Unit,
    avatarIconShape: AvatarIconShape,
) {
    val (icon, action) = when (notification.type) {
        "mention" -> Icons.AutoMirrored.Filled.Reply to "返信"
        "reply" -> Icons.AutoMirrored.Filled.Reply to "返信しました"
        "reblog" -> Icons.Filled.Repeat to "ブーストしました"
        "favourite" -> Icons.Filled.Star to "お気に入りしました"
        "follow" -> Icons.Filled.PersonAdd to "フォローしました"
        "follow_request" -> Icons.Filled.HowToReg to "フォローをリクエストしました"
        "poll" -> Icons.Filled.Poll to "アンケートが終了しました"
        "status" -> Icons.Filled.Campaign to "新しい投稿があります"
        "update" -> Icons.Filled.Edit to "投稿を編集しました"
        "emoji_reaction", "reaction" -> Icons.Filled.AutoAwesome to "リアクションしました"
        else -> Icons.Filled.Notifications to "通知"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onAccountClick(notification.account.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (notification.type == "emoji_reaction" || notification.type == "reaction") {
            NotificationReactionIcon(notification)
        } else {
            NotificationTypeIcon(icon, action, notification.type)
        }
        Spacer(Modifier.width(8.dp))
        AsyncImage(
            model = notification.account.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(34.dp).clip(avatarIconShape.toShape())
                .testTag("notification_actor_avatar"),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            CustomEmojiText(
                notification.account.displayName,
                notification.account.customEmojis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text("@${notification.account.accountName} · $action", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NotificationStatusQuote(
    status: TimelineStatus,
    onStatusClick: (String) -> Unit,
    onMoreClick: (TimelineStatus) -> Unit,
    preferences: io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences,
) {
    val plainContent = remember(status.contentHtml) {
        androidx.core.text.HtmlCompat.fromHtml(
            status.contentHtml,
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY,
        ).toString().trim()
    }
    Surface(
        onClick = { onStatusClick(status.statusId) },
        modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, bottom = 10.dp)
            .testTag("notification_status_quote"),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = status.author.avatarUrl,
                    contentDescription = "${status.author.displayName}のプロフィール画像",
                    modifier = Modifier.size(28.dp).clip(preferences.avatarIconShape.toShape())
                        .testTag("notification_status_author_avatar"),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(8.dp))
                CustomEmojiText(
                    text = status.author.displayName,
                    emojis = status.author.customEmojis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { onMoreClick(status) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "投稿メニュー",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            CustomEmojiText(
                text = plainContent,
                emojis = status.customEmojis,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = preferences.fontSize.spValue(),
                    lineHeight = preferences.lineHeightSp().sp,
                ),
            )
            if (status.mediaAttachments.isNotEmpty()) {
                Text(
                    "添付 ${status.mediaAttachments.size}件",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NotificationTypeIcon(icon: ImageVector, action: String, type: String) {
    val tint = when (type) {
        "mention", "reply" -> Color(0xFF2686C4)
        "favourite" -> Color(0xFFD4A017)
        else -> MaterialTheme.colorScheme.primary
    }
    Icon(
        imageVector = icon,
        contentDescription = action,
        modifier = Modifier.size(20.dp).testTag("notification_type_icon"),
        tint = tint,
    )
}

@Composable
private fun NotificationReactionIcon(notification: TimelineNotification) {
    val reaction = notification.matchingReaction
    val imageUrl = reaction?.imageUrl
    var imageLoadFailed by remember(imageUrl) { mutableStateOf(false) }
    when {
        reaction == null || imageLoadFailed ->
            NotificationTypeIcon(Icons.Filled.AutoAwesome, "リアクション", notification.type)
        imageUrl != null -> AsyncImage(
            model = imageUrl,
            contentDescription = "${reaction.name}のリアクション",
            onError = { imageLoadFailed = true },
            modifier = Modifier.size(20.dp).testTag("notification_type_icon"),
            contentScale = ContentScale.Fit,
        )
        else -> Box(
            Modifier.size(20.dp).testTag("notification_type_icon"),
            contentAlignment = Alignment.Center,
        ) {
            Text(reaction.name, fontSize = 18.sp, lineHeight = 20.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ProfileCount(label: String, count: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileRegistrationDate(createdAt: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(formatProfileDate(createdAt), fontWeight = FontWeight.Bold)
        Text("登録日", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun profileAccountHandle(accountName: String, profileUrl: String): String {
    if ('@' in accountName) return "@$accountName"
    val host = runCatching { URI(profileUrl).host }.getOrNull().orEmpty()
    return if (host.isBlank()) "@$accountName" else "@$accountName@$host"
}

private fun formatProfileDate(createdAt: String?): String {
    if (createdAt.isNullOrBlank()) return "—"
    return runCatching {
        LocalDate.parse(createdAt.take(10)).format(DateTimeFormatter.ofPattern("yyyy/M/d", Locale.JAPAN))
    }.getOrDefault(createdAt.take(10))
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun LoadingContent(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label)
        }
    }
}

@Composable
internal fun MessageContent(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
