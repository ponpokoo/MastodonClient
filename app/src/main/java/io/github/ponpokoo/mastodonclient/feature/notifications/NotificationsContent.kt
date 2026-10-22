package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.feature.common.TimelineNotice
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import io.github.ponpokoo.mastodonclient.feature.status.spValue
import io.github.ponpokoo.mastodonclient.feature.status.lineHeightSp
import io.github.ponpokoo.mastodonclient.feature.common.MessageContent
import io.github.ponpokoo.mastodonclient.feature.common.ListLoadingContent
import io.github.ponpokoo.mastodonclient.feature.common.ListLoadingMoreIndicator
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsUiState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.CustomEmojiText
import io.github.ponpokoo.mastodonclient.feature.common.ContentWarning
import io.github.ponpokoo.mastodonclient.feature.common.FilterWarning
import io.github.ponpokoo.mastodonclient.feature.common.AppPullToRefreshBox
import io.github.ponpokoo.mastodonclient.feature.common.rememberSwipeTabs
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconShape
import io.github.ponpokoo.mastodonclient.feature.common.toShape
import io.github.ponpokoo.mastodonclient.feature.common.AppTabRow
import io.github.ponpokoo.mastodonclient.feature.common.AppTabText
import io.github.ponpokoo.mastodonclient.feature.common.AppTab
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
    noticeContent: (@Composable () -> Unit)? = null,
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
    val canPullRefresh = activeState.isInitialPageLoaded || activeState.notifications.isNotEmpty()
    val isPullRefreshing = canPullRefresh && activeState.isPullRefreshingNotifications
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
            isRefreshing = isPullRefreshing,
            enabled = canPullRefresh,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("notifications_screen"),
        ) {
            Column(Modifier.fillMaxSize()) {
                AppTabRow(selectedTabIndex = filterTabs.selectedPage) {
                    NotificationFilter.entries.forEach { filter ->
                        AppTab(
                            modifier = Modifier.testTag("notification_filter_${filter.name.lowercase()}"),
                            selected = filterTabs.selectedPage == filter.ordinal,
                            onClick = { filterTabs.selectPage(filter.ordinal) },
                            text = { AppTabText(filter.label, selected = filterTabs.selectedPage == filter.ordinal) },
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
                    val checkingCapabilities = !supported && state.isCheckingCapabilities
                    val initialLoading = supported && !pageState.isInitialPageLoaded &&
                        filteredNotifications.isEmpty() && pageState.notificationsError == null
                    Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state = listStates[page]) {
                    if (!supported && !checkingCapabilities) item {
                        MessageContent("現在サポートしていません")
                    }
                    else if (supported && !initialLoading && filteredNotifications.isEmpty() && pageState.notificationsError == null) item {
                        MessageContent(when {
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
                    if (supported && pageState.isLoadingMoreNotifications && !initialLoading && !isPullRefreshing) {
                        item { ListLoadingMoreIndicator() }
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
                    if ((checkingCapabilities && !isPullRefreshing) || initialLoading) {
                        ListLoadingContent(label = if (checkingCapabilities) "対応状況を確認中…" else "読み込み中…")
                    }
                    }
                }
                if (noticeContent != null || newNoticeMessage != null) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                        if (noticeContent != null) noticeContent()
                        else newNoticeMessage?.let { TimelineNotice(it, onNewNoticeClick) }
                    }
                }
                }
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
        "favourite" -> Icons.Filled.Star to "お気に入りにしました"
        "follow" -> Icons.Filled.PersonAdd to "フォローしました"
        "follow_request" -> Icons.Filled.HowToReg to "フォローをリクエストしました"
        "poll" -> Icons.Filled.Poll to "アンケートが終了しました"
        "status" -> Icons.Filled.Campaign to "新しい投稿があります"
        "update" -> Icons.Filled.Edit to "投稿を編集しました"
        "emoji_reaction", "pleroma:emoji_reaction", "reaction" -> Icons.Filled.AutoAwesome to "リアクションしました"
        else -> Icons.Filled.Notifications to "通知"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onAccountClick(notification.account.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (notification.isEmojiReaction) {
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
    if (status.filterDisplay.hidden) return
    var filterRevealed by androidx.compose.runtime.saveable.rememberSaveable(status.filterScope, status.statusId, status.filterDisplay.warnings) { androidx.compose.runtime.mutableStateOf(false) }
    val reading = io.github.ponpokoo.mastodonclient.feature.common.LocalReadingSettings.current
    var cwOverride by androidx.compose.runtime.saveable.rememberSaveable(reading.accountKey, status.filterScope, status.statusId, status.spoilerText) { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    val cwRevealed = cwOverride ?: reading.preferences.expandSpoilers
    val visible = status.filterDisplay.warnings.isEmpty() || filterRevealed
    val plainContent = remember(status.contentHtml) {
        androidx.core.text.HtmlCompat.fromHtml(
            status.contentHtml,
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY,
        ).toString().trim()
    }
    Surface(
        onClick = { if (visible) onStatusClick(status.statusId) },
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
            if (status.filterDisplay.warnings.isNotEmpty()) FilterWarning(
                status.filterDisplay.warnings, filterRevealed, { filterRevealed = !filterRevealed },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = preferences.fontSize.spValue(),
                    lineHeight = preferences.lineHeightSp().sp,
                ),
            )
            if (!visible) return@Column
            if (status.spoilerText.isNotBlank()) {
                ContentWarning(expanded = cwRevealed, onToggle = { cwOverride = !cwRevealed }) {
                    CustomEmojiText(
                        text = status.spoilerText,
                        emojis = status.customEmojis,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = preferences.fontSize.spValue(),
                            lineHeight = preferences.lineHeightSp().sp,
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (!cwRevealed) return@Column
            }
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
        reaction == null || imageLoadFailed || imageUrl == null && reaction.name.all { it.code < 128 } ->
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
