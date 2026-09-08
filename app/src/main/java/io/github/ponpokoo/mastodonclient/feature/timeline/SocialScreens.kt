package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import androidx.compose.material3.Button
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab

@Composable
internal fun SearchContent(
    state: TimelineUiState,
    padding: PaddingValues,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit = {},
    onReact: (TimelineStatus, String?) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (MediaAttachment) -> Unit,
    preferences: AppPreferences = AppPreferences(),
) {
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
                LazyColumn(Modifier.fillMaxSize()) {
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
                                onBoost, onFavourite, onBookmark, onReact, onAccountClick, onMediaClick, preferences,
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
    state: TimelineUiState,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit,
    onReact: (TimelineStatus, String?) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (MediaAttachment) -> Unit,
    preferences: AppPreferences,
) {
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
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.notifications.isEmpty()) item { MessageContent("通知はありません") }
                items(state.notifications, key = TimelineNotification::id) { notification ->
                    NotificationHeader(notification, onAccountClick)
                    notification.status?.let { status ->
                        SocialStatus(
                            status, onStatusClick, onOpenLink, onReply,
                            onBoost, onFavourite, onBookmark, onReact, onAccountClick, onMediaClick, preferences,
                        )
                    } ?: run {
                        AccountResult(notification.account, onAccountClick)
                        HorizontalDivider()
                    }
                }
                if (state.isLoadingMoreNotifications) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
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

@Composable
internal fun ProfileContent(
    state: TimelineUiState,
    padding: PaddingValues,
    onRetry: () -> Unit,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit = {},
    onReact: (TimelineStatus, String?) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (MediaAttachment) -> Unit,
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
    onToggleFollow: () -> Unit = {},
) {
    val profile = state.profile
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
        else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("profile_screen")) {
            item {
                AsyncImage(
                    model = profile.headerUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(128.dp).clickable(onClick = onHeaderClick)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                Column(Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = profile.author.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).clip(CircleShape).clickable(onClick = onAvatarClick)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(profile.author.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (profile.customEmojis.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        profile.customEmojis.entries.take(8).forEach { (name, url) ->
                            AsyncImage(url, name, Modifier.size(24.dp), contentScale = ContentScale.Fit)
                        }
                    }
                    Text("@${profile.author.accountName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = if (profile.isOwnProfile) onEditProfile else onToggleFollow,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(if (profile.isOwnProfile) "プロフィールを編集" else when {
                            relationship?.requested == true -> "申請中"
                            relationship?.following == true -> "フォロー中"
                            profile.locked -> "フォロー申請"
                            else -> "フォロー"
                        })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        ProfileCount("投稿", profile.statusesCount)
                        Box(Modifier.clickable(onClick = onFollowing)) { ProfileCount("フォロー", profile.followingCount) }
                        Box(Modifier.clickable(onClick = onFollowers)) { ProfileCount("フォロワー", profile.followersCount) }
                    }
                    if (profile.noteHtml.isNotBlank()) {
                        StatusContentText(contentHtml = profile.noteHtml, onLinkClick = onOpenLink)
                    }
                    profile.fields.forEach { field ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(field.name, Modifier.weight(0.35f), fontWeight = FontWeight.SemiBold)
                            Column(Modifier.weight(0.65f)) {
                                StatusContentText(field.valueHtml, onLinkClick = onOpenLink)
                                if (field.verifiedAt != null) Text("✓ 認証済み", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                HorizontalDivider()
                if (profile.pinnedStatuses.isNotEmpty()) SectionTitle("固定された投稿")
            }
            items(profile.pinnedStatuses, key = { "pinned-${it.timelineId}" }) { status ->
                SocialStatus(status, onStatusClick, onOpenLink, onReply, onBoost, onFavourite, onBookmark, onReact, onAccountClick, onMediaClick, preferences)
            }
            item {
                SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    listOf("投稿", "投稿と返信", "メディア").forEachIndexed { index, label ->
                        Tab(selected = selectedTab.ordinal == index, onClick = { onSelectTab(ProfileStatusTab.entries[index]) }, text = { Text(label) })
                    }
                }
            }
            items(profile.statuses, key = { it.timelineId }) { status ->
                SocialStatus(
                    status, onStatusClick, onOpenLink, onReply,
                    onBoost, onFavourite, onBookmark, onReact, onAccountClick, onMediaClick, preferences,
                )
            }
            if (isLoadingMore) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (!profile.endReached) item { TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") } }
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
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit,
    onReact: (TimelineStatus, String?) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (MediaAttachment) -> Unit,
    preferences: AppPreferences,
) {
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
        onReact = { onReact(status, it) },
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
            Text(account.displayName, fontWeight = FontWeight.SemiBold)
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
        "mention" -> Icons.Outlined.AlternateEmail to "メンションしました"
        "reblog" -> Icons.Outlined.Repeat to "ブーストしました"
        "favourite" -> Icons.Outlined.FavoriteBorder to "お気に入りしました"
        "follow" -> Icons.Outlined.PersonAdd to "フォローしました"
        "follow_request" -> Icons.Outlined.HowToReg to "フォローをリクエストしました"
        "poll" -> Icons.Outlined.Poll to "アンケートが終了しました"
        "status" -> Icons.Outlined.Campaign to "新しい投稿があります"
        "update" -> Icons.Outlined.Edit to "投稿を編集しました"
        else -> Icons.Outlined.NotificationsNone to "通知"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onAccountClick(notification.account.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationTypeIcon(icon, action)
        Spacer(Modifier.width(8.dp))
        Text(
            "${notification.account.displayName}さんが$action",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NotificationTypeIcon(icon: ImageVector, action: String) {
    Icon(
        imageVector = icon,
        contentDescription = action,
        modifier = Modifier.size(20.dp).testTag("notification_type_icon"),
        tint = MaterialTheme.colorScheme.primary,
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
