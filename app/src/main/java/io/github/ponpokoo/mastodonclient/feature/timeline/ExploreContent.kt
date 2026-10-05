package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.AppPullToRefreshBox
import io.github.ponpokoo.mastodonclient.feature.common.rememberSwipeTabs
import io.github.ponpokoo.mastodonclient.feature.search.ExploreUiState
import io.github.ponpokoo.mastodonclient.feature.search.ExploreTabState
import io.github.ponpokoo.mastodonclient.feature.tag.TagSubscriptionButton

@Composable
internal fun ExploreContent(state: ExploreUiState, onSelect: (ExploreFeed) -> Unit,
    onRefresh: () -> Unit, onLoadMore: () -> Unit, onRetry: () -> Unit,
    onOpenTag: (String, Boolean) -> Unit, onOpenLink: (String) -> Unit,
    onUnfollow: (SearchTag) -> Unit, post: @Composable (TimelineStatus) -> Unit,
    onFindTags: () -> Unit = {},
    scrollToTopRequest: Long = 0L,
    modifier: Modifier = Modifier) {
    val tabs = key(state.sessionKey) {
        rememberSwipeTabs(state.selectedFeed.ordinal, ExploreFeed.entries.size) { onSelect(ExploreFeed.entries[it]) }
    }
    val pager = tabs.pagerState
    val lists = ExploreFeed.entries.map { key(state.sessionKey, it) { rememberLazyListState() } }
    var handledScrollRequest by remember(state.sessionKey) { mutableLongStateOf(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (handledScrollRequest != scrollToTopRequest) {
            handledScrollRequest = scrollToTopRequest
            lists[state.selectedFeed.ordinal].animateToTimelineTop()
        }
    }
    var confirm by remember(state.sessionKey) { mutableStateOf<SearchTag?>(null) }
    Column(modifier) {
        SecondaryTabRow(selectedTabIndex = tabs.selectedPage) {
            ExploreFeed.entries.forEach { feed ->
                Tab(selected = feed.ordinal == tabs.selectedPage, onClick = { tabs.selectPage(feed.ordinal) },
                    modifier = Modifier.testTag("explore_tab_${feed.name.lowercase()}")) {
                    Text(feed.label(), modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        HorizontalPager(pager, key = { ExploreFeed.entries[it] }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("explore_pager")) { index ->
            val feed = ExploreFeed.entries[index]
            val tab = state.tabs[feed] ?: ExploreTabState()
            AppPullToRefreshBox(isRefreshing = tab.loading && tab.page != null, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = lists[index]) {
                    if (tab.page == null && tab.error == null) item { LoadingContent("読み込んでいます") }
                    tab.page?.let { page ->
                        items(page.statuses, key = { it.timelineId }) { post(it) }
                        items(page.tags, key = { it.name.lowercase(java.util.Locale.ROOT) }) { tag ->
                            Row(Modifier.fillMaxWidth().clickable { onOpenTag(tag.name, feed == ExploreFeed.Hashtags) }
                                .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("#${tag.name}", color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    tag.postingAccounts?.let { Text("${java.text.NumberFormat.getIntegerInstance().format(it)}人が投稿",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                if (feed == ExploreFeed.Followed) TagSubscriptionButton(true,
                                    tag.name.lowercase(java.util.Locale.ROOT) !in state.busyTags, { confirm = tag },
                                    Modifier.testTag("unfollow_${tag.name}"))
                            }
                            HorizontalDivider()
                        }
                        items(page.news, key = { it.url }) { NewsRow(it, onOpenLink) }
                        if (page.count() == 0 && !tab.loading && tab.error == null) item {
                            MessageContent(if (feed == ExploreFeed.Followed) "購読中のハッシュタグはありません" else "表示する${feed.label()}はありません")
                            if (feed == ExploreFeed.Followed) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = onFindTags) { Text("タグを検索") }
                            }
                        }
                    }
                    if (tab.error != null) item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tab.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text("再試行") }
                        }
                    } else if (tab.loadingMore) item { LoadingContent("続きを取得しています") }
                    else if (!tab.loading && tab.page?.nextCursor != null) item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = onLoadMore) { Text("さらに読み込む") } }
                    }
                }
            }
        }
    }
    confirm?.let { tag -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("購読を解除しますか？") },
        text = { Text("#${tag.name}") }, confirmButton = { TextButton(onClick = { confirm = null; onUnfollow(tag) }) { Text("解除する") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("キャンセル") } }) }
}

@Composable
private fun NewsRow(news: ExploreNews, onOpenLink: (String) -> Unit) {
    var imageFailed by remember(news.imageUrl) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { onOpenLink(news.url) }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(news.provider.ifBlank { runCatching { java.net.URI(news.url).host }.getOrNull().orEmpty() },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(news.title.ifBlank { news.url }, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (news.description.isNotBlank()) Text(news.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (news.imageUrl != null && !imageFailed) AsyncImage(news.imageUrl, contentDescription = null,
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop,
            onError = { imageFailed = true })
    }
    HorizontalDivider()
}

private fun ExploreFeed.label() = when (this) {
    ExploreFeed.Posts -> "投稿"
    ExploreFeed.Hashtags -> "ハッシュタグ"
    ExploreFeed.News -> "ニュース"
    ExploreFeed.Followed -> "購読中"
}
