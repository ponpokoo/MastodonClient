package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.feature.status.SocialStatus
import io.github.ponpokoo.mastodonclient.feature.common.LoadingContent
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.feature.common.AppPullToRefreshBox
import io.github.ponpokoo.mastodonclient.feature.common.rememberSwipeTabs
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
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
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // A reverse drag/fling owned by the outer list must return posts first.
                // Child-owned gestures consume their own deltas without raw dispatch.
                if (available.y <= 0f || resolvedListState.isScrollInProgress || profileTabs.isMoving) return Offset.Zero
                return Offset(0f, -resolvedListState.dispatchRawDelta(-available.y))
            }

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
                    io.github.ponpokoo.mastodonclient.feature.common.ProfileImage(
                        model = if (profile.isOwnProfile) io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel(
                            profile.headerUrl, state.imageRefreshRevision) else profile.headerUrl,
                        identity = listOf(state.imageSessionKey, instanceUrl, profile.author.id, "header"),
                        retainPreviousImage = profile.isOwnProfile, contentDescription = "ヘッダー画像",
                        modifier = Modifier.fillMaxWidth().height(132.dp).clickable(onClick = onHeaderClick)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    io.github.ponpokoo.mastodonclient.feature.common.ProfileImage(
                        model = io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel(profile.author.avatarUrl,
                            if (profile.isOwnProfile) state.imageRefreshRevision else profile.author.avatarRevision),
                        identity = listOf(state.imageSessionKey, instanceUrl, profile.author.id, "avatar"),
                        retainPreviousImage = profile.isOwnProfile, contentDescription = "プロフィール画像",
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
