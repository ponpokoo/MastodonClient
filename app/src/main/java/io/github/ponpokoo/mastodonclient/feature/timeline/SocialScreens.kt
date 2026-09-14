package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.feature.search.SearchUiState
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsUiState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import androidx.compose.material3.Button
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class NotificationFilter(val label: String) {
    All("すべて"),
    Mentions("メンション"),
    Reactions("リアクション"),
}

@Composable
internal fun SearchContent(
    state: SearchUiState,
    padding: PaddingValues,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit = {},
    onReact: (TimelineStatus, String?) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    preferences: AppPreferences = AppPreferences(),
    listState: LazyListState? = null,
) {
    val resolvedListState = listState ?: rememberLazyListState()
    Column(Modifier.fillMaxSize().padding(padding).testTag("search_screen")) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            placeholder = { Text("アカウント、投稿、ハッシュタグを検索") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onSearch, enabled = state.searchQuery.isNotBlank()) {
                    Icon(Icons.Outlined.Search, contentDescription = "検索")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        when {
            state.isSearching -> LoadingContent("検索しています")
            state.searchError != null -> MessageContent(state.searchError)
            state.searchResults == null -> MessageContent("検索語を入力してください")
            else -> {
                val results = state.searchResults
                LazyColumn(Modifier.fillMaxSize(), state = resolvedListState) {
                    if (results.accounts.isNotEmpty()) {
                        item { SectionTitle("アカウント") }
                        items(results.accounts, key = StatusAuthor::id) { AccountResult(it, onAccountClick) }
                    }
                    if (results.hashtags.isNotEmpty()) {
                        item { SectionTitle("ハッシュタグ") }
                        items(results.hashtags, key = { it.url }) { tag ->
                            Text(
                                "#${tag.name}",
                                modifier = Modifier.fillMaxWidth().clickable { onOpenLink(tag.url) }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (results.statuses.isNotEmpty()) {
                        item { SectionTitle("投稿") }
                        items(results.statuses, key = { it.timelineId }) { status ->
                            SocialStatus(
                                status, onStatusClick, onOpenLink, onReply,
                                onBoost, onQuote, onFavourite, onBookmark, onReact, onAccountClick, onMediaClick, preferences,
                            )
                        }
                    }
                    if (results.accounts.isEmpty() && results.hashtags.isEmpty() && results.statuses.isEmpty()) {
                        item { MessageContent("検索結果はありません") }
                    }
                }
            }
        }
    }
}

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
    onQuote: (TimelineStatus) -> Unit = {},
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit,
    onReact: (TimelineStatus, String?) -> Unit,
    onMoreClick: (TimelineStatus) -> Unit = {},
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    preferences: AppPreferences,
    listStates: List<LazyListState>,
    selectedFilter: NotificationFilter,
    onSelectFilter: (NotificationFilter) -> Unit,
) {
    check(listStates.size == NotificationFilter.entries.size)
    val filterPagerState = rememberPagerState(
        initialPage = selectedFilter.ordinal,
        pageCount = { NotificationFilter.entries.size },
    )
    LaunchedEffect(selectedFilter) {
        if (filterPagerState.currentPage != selectedFilter.ordinal) {
            filterPagerState.animateScrollToPage(selectedFilter.ordinal)
        }
    }
    LaunchedEffect(filterPagerState.currentPage) {
        NotificationFilter.entries.getOrNull(filterPagerState.currentPage)?.let { filter ->
            if (filter != selectedFilter) onSelectFilter(filter)
        }
    }
    val selectedListState = listStates[selectedFilter.ordinal]
    LaunchedEffect(selectedListState, state.notifications.size, state.notificationsNextMaxId, selectedFilter) {
        if (state.notificationsNextMaxId != null && !state.notificationsEndReached) {
            snapshotFlow { selectedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .distinctUntilChanged().collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= selectedListState.layoutInfo.totalItemsCount - 4) onLoadMore()
                }
        }
    }
    when {
        state.isLoadingNotifications && state.notifications.isEmpty() -> LoadingContent(
            "通知を読み込んでいます", Modifier.padding(padding),
        )
        state.notificationsError != null && state.notifications.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.notificationsError, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh) { Text("再試行") }
        }
        else -> PullToRefreshBox(
            isRefreshing = state.isLoadingNotifications,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("notifications_screen"),
        ) {
            Column(Modifier.fillMaxSize()) {
                SecondaryTabRow(selectedTabIndex = selectedFilter.ordinal) {
                    NotificationFilter.entries.forEach { filter ->
                        Tab(
                            selected = selectedFilter == filter,
                            onClick = { onSelectFilter(filter) },
                            text = { Text(filter.label) },
                        )
                    }
                }
                HorizontalPager(
                    state = filterPagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("notification_filter_pager"),
                    key = { NotificationFilter.entries[it] },
                ) { page ->
                    val filter = NotificationFilter.entries[page]
                    val filteredNotifications = state.notifications.filter { notification ->
                        when (filter) {
                            NotificationFilter.All -> true
                            NotificationFilter.Mentions -> notification.type.equals("mention", ignoreCase = true) ||
                                notification.type.equals("reply", ignoreCase = true)
                            NotificationFilter.Reactions -> notification.type.contains("reaction", ignoreCase = true)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = listStates[page]) {
                    if (filteredNotifications.isEmpty()) item { MessageContent("該当する通知はありません") }
                    items(filteredNotifications, key = TimelineNotification::id) { notification ->
                        val status = notification.status
                        val isReply = notification.type.equals("mention", ignoreCase = true) ||
                            notification.type.equals("reply", ignoreCase = true)
                        if (isReply && status != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NotificationTypeIcon(Icons.Outlined.ChatBubbleOutline, "返信", notification.type)
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
                                onQuote = { onQuote(status) },
                                onFavourite = { onFavourite(status) },
                                onBookmark = { onBookmark(status) },
                                onReact = { onReact(status, it) },
                                onMoreClick = onMoreClick,
                                onUnavailableAction = {},
                                displayPreferences = preferences.timelineDisplay,
                                gifAutoplay = preferences.gifAutoplay,
                                videoAutoplay = preferences.videoAutoplay,
                            )
                        } else {
                            NotificationHeader(notification, onAccountClick)
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
                    if (state.isLoadingMoreNotifications) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                    } else if (state.notificationsError != null) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(state.notificationsError, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = if (state.notificationsErrorIsPagination) onLoadMore else onRefresh) {
                                    Text("再試行")
                                }
                            }
                        }
                    } else if (!state.notificationsEndReached && state.notificationsNextMaxId != null) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = onLoadMore) { Text("さらに読み込む") }
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
internal fun ProfileContent(
    state: ProfileUiState,
    padding: PaddingValues,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit = {},
    onReact: (TimelineStatus, String?) -> Unit,
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
    onOpenProfileBrowser: (() -> Unit)? = null,
    onAddProfileToList: (() -> Unit)? = null,
    onCopyProfileUrl: (() -> Unit)? = null,
    onShowProfileQr: (() -> Unit)? = null,
    onOpenFollowedTags: (() -> Unit)? = null,
    onOpenAccountSettings: (() -> Unit)? = null,
    onMuteProfile: (() -> Unit)? = null,
    onBlockProfile: (() -> Unit)? = null,
    onReportProfile: (() -> Unit)? = null,
    isProfileMuted: Boolean = false,
    isProfileBlocked: Boolean = false,
    listState: LazyListState? = null,
) {
    val profile = state.profile
    val resolvedListState = listState ?: rememberLazyListState()
    LaunchedEffect(resolvedListState, profile?.statuses?.size, profile?.nextMaxId, selectedTab) {
        if (profile?.nextMaxId != null && !profile.endReached) {
            snapshotFlow { resolvedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
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
        else -> PullToRefreshBox(
            isRefreshing = state.isRefreshingProfile,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("profile_screen"),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = resolvedListState,
            ) {
            item {
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
                            .clip(CircleShape).clickable(onClick = onAvatarClick)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(2.dp, Color.Black, CircleShape),
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
                            OutlinedIconButton(
                                onClick = { profileMenuExpanded = true },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "プロフィールのその他メニュー")
                            }
                        }
                        DropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false },
                        ) {
                            if (profile.isOwnProfile) {
                                onShareProfile?.let { action -> DropdownMenuItem(text = { Text("共有") }, onClick = { profileMenuExpanded = false; action() }) }
                                onCopyProfileUrl?.let { action -> DropdownMenuItem(text = { Text("リンクをコピー") }, onClick = { profileMenuExpanded = false; action() }) }
                                onShowProfileQr?.let { action -> DropdownMenuItem(text = { Text("QRコードを表示") }, onClick = { profileMenuExpanded = false; action() }) }
                                onOpenProfileBrowser?.let { action -> DropdownMenuItem(text = { Text("ブラウザで開く") }, onClick = { profileMenuExpanded = false; action() }) }
                                DropdownMenuItem(text = { Text("リスト") }, onClick = { profileMenuExpanded = false; onOpenLists() })
                                DropdownMenuItem(text = { Text("ブックマーク") }, onClick = { profileMenuExpanded = false; onOpenBookmarks() })
                                DropdownMenuItem(text = { Text("お気に入り") }, onClick = { profileMenuExpanded = false; onOpenFavourites() })
                                onOpenFollowedTags?.let { action -> DropdownMenuItem(text = { Text("フォロー中のハッシュタグ") }, onClick = { profileMenuExpanded = false; action() }) }
                                onOpenAccountSettings?.let { action -> DropdownMenuItem(text = { Text("アカウント設定") }, onClick = { profileMenuExpanded = false; action() }) }
                            } else {
                                onShareProfile?.let { action -> DropdownMenuItem(text = { Text("共有") }, onClick = { profileMenuExpanded = false; action() }) }
                                onOpenProfileBrowser?.let { action -> DropdownMenuItem(text = { Text("ブラウザで開く") }, onClick = { profileMenuExpanded = false; action() }) }
                                onAddProfileToList?.let { action -> DropdownMenuItem(text = { Text("リストに追加") }, onClick = { profileMenuExpanded = false; action() }) }
                                if (onMuteProfile != null || onBlockProfile != null || onReportProfile != null) HorizontalDivider()
                                onMuteProfile?.let { action -> DropdownMenuItem(text = { Text(if (isProfileMuted) "ミュート解除" else "ミュート") }, onClick = { profileMenuExpanded = false; action() }) }
                                onBlockProfile?.let { action -> DropdownMenuItem(text = { Text(if (isProfileBlocked) "ブロック解除" else "ブロック") }, onClick = { profileMenuExpanded = false; action() }) }
                                onReportProfile?.let { action -> DropdownMenuItem(text = { Text("通報") }, onClick = { profileMenuExpanded = false; action() }) }
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    CustomEmojiText(
                        text = profile.author.displayName,
                        emojis = profile.customEmojis,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            profileAccountHandle(profile.author.accountName, profile.url),
                            modifier = Modifier.weight(1f, fill = profile.isOwnProfile),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (profile.locked) {
                            Icon(Icons.Outlined.Lock, contentDescription = "非公開アカウント",
                                modifier = Modifier.padding(start = 4.dp).size(16.dp))
                        }
                        if (relationship?.followedBy == true) {
                            Text("フォローされてます", modifier = Modifier.padding(start = 6.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        ProfileCount("投稿", profile.statusesCount)
                        Box(Modifier.clickable(onClick = onFollowing)) { ProfileCount("フォロー", profile.followingCount) }
                        Box(Modifier.clickable(onClick = onFollowers)) { ProfileCount("フォロワー", profile.followersCount) }
                        ProfileRegistrationDate(profile.createdAt)
                    }
                    if (profile.noteHtml.isNotBlank()) {
                        StatusContentText(
                            contentHtml = profile.noteHtml,
                            customEmojis = profile.customEmojis,
                            onLinkClick = onOpenLink,
                        )
                    }
                    profile.fields.forEach { field ->
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            CustomEmojiText(
                                text = field.name,
                                emojis = profile.customEmojis,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                                StatusContentText(
                                    contentHtml = field.valueHtml,
                                    customEmojis = profile.customEmojis,
                                    onLinkClick = onOpenLink,
                                )
                            }
                            if (field.verifiedAt != null) {
                                Text("✓ 認証済み", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            item {
                SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    listOf("投稿", "投稿と返信", "メディア").forEachIndexed { index, label ->
                        Tab(selected = selectedTab.ordinal == index, onClick = { onSelectTab(ProfileStatusTab.entries[index]) }, text = { Text(label) })
                    }
                }
            }
            val statuses = if (selectedTab == ProfileStatusTab.Posts) {
                (profile.pinnedStatuses + profile.statuses).distinctBy { it.statusId }
            } else {
                profile.statuses
            }
            val pinnedStatusIds = profile.pinnedStatuses.mapTo(mutableSetOf()) { it.statusId }
            items(statuses, key = { it.timelineId }) { status ->
                SocialStatus(
                    status, onStatusClick, onOpenLink, onReply,
                    onBoost, onQuote, onFavourite, onBookmark, onReact, onAccountClick, onMediaClick, preferences,
                    onMoreClick = onMoreClick,
                    isPinned = selectedTab == ProfileStatusTab.Posts && status.statusId in pinnedStatusIds,
                )
            }
                if (isLoadingMore) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                else if (!profile.endReached) item { TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") } }
            }
        }
    }
}

@Composable
private fun SocialStatus(
    status: TimelineStatus,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit,
    onReact: (TimelineStatus, String?) -> Unit,
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
        onQuote = { onQuote(status) },
        onFavourite = { onFavourite(status) },
        onBookmark = { onBookmark(status) },
        onReact = { onReact(status, it) },
        onMoreClick = onMoreClick,
        onUnavailableAction = {},
        displayPreferences = preferences.timelineDisplay,
        gifAutoplay = preferences.gifAutoplay,
        videoAutoplay = preferences.videoAutoplay,
    )
    HorizontalDivider()
}

@Composable
private fun AccountResult(account: StatusAuthor, onClick: (String) -> Unit) {
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
) {
    val (icon, action) = when (notification.type) {
        "mention" -> Icons.Outlined.ChatBubbleOutline to "返信"
        "reply" -> Icons.Outlined.AlternateEmail to "返信しました"
        "reblog" -> Icons.Outlined.Repeat to "ブーストしました"
        "favourite" -> Icons.Outlined.FavoriteBorder to "お気に入りしました"
        "follow" -> Icons.Outlined.PersonAdd to "フォローしました"
        "follow_request" -> Icons.Outlined.HowToReg to "フォローをリクエストしました"
        "poll" -> Icons.Outlined.Poll to "アンケートが終了しました"
        "status" -> Icons.Outlined.Campaign to "新しい投稿があります"
        "update" -> Icons.Outlined.Edit to "投稿を編集しました"
        "emoji_reaction", "reaction" -> Icons.Outlined.SentimentSatisfiedAlt to "リアクションしました"
        else -> Icons.Outlined.NotificationsNone to "通知"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onAccountClick(notification.account.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationTypeIcon(icon, action, notification.type)
        Spacer(Modifier.width(8.dp))
        AsyncImage(
            model = notification.account.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(34.dp).clip(CircleShape),
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
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = status.author.avatarUrl,
                    contentDescription = "${status.author.displayName}のプロフィール画像",
                    modifier = Modifier.size(28.dp).clip(CircleShape)
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
        "favourite" -> Color(0xFFE46487)
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
private fun LoadingContent(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label)
        }
    }
}

@Composable
private fun MessageContent(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
