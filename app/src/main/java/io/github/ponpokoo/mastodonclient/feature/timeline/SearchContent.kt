package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.QuoteMode
import io.github.ponpokoo.mastodonclient.domain.model.SearchTarget
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.search.SearchBar
import io.github.ponpokoo.mastodonclient.feature.common.rememberSwipeTabs
import io.github.ponpokoo.mastodonclient.feature.search.SearchTabState
import io.github.ponpokoo.mastodonclient.feature.search.SearchUiState
import io.github.ponpokoo.mastodonclient.feature.search.message

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun SearchContent(
    state: SearchUiState,
    padding: PaddingValues,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onEnterSearch: () -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onSelectTarget: (SearchTarget) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onStatusClick: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onReply: (TimelineStatus) -> Unit,
    onBoost: (TimelineStatus) -> Unit,
    onQuote: (TimelineStatus, QuoteMode) -> Unit,
    onFavourite: (TimelineStatus) -> Unit,
    onBookmark: (TimelineStatus) -> Unit = {},
    onReact: (TimelineStatus, String?) -> Unit,
    onVotePoll: (TimelineStatus, Set<Int>) -> Unit = { _, _ -> },
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onMoreClick: (TimelineStatus) -> Unit,
    preferences: AppPreferences = AppPreferences(),
    isVisible: Boolean = true,
    exploreState: io.github.ponpokoo.mastodonclient.feature.search.ExploreUiState = io.github.ponpokoo.mastodonclient.feature.search.ExploreUiState(),
    onSelectExploreFeed: (io.github.ponpokoo.mastodonclient.domain.model.ExploreFeed) -> Unit = {},
    onEnsureExploreLoaded: () -> Unit = {},
    onRefreshExplore: () -> Unit = {},
    onLoadMoreExplore: () -> Unit = {},
    onRetryExplore: () -> Unit = {},
    onUnfollowTag: (io.github.ponpokoo.mastodonclient.domain.model.SearchTag) -> Unit = {},
    onOpenTag: (String, Boolean) -> Unit = { _, _ -> },
    scrollToTopRequest: Long = 0L,
) {
    val searchFocusRequester = remember(state.sessionKey) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(isVisible, state.isSearchActive, state.sessionKey) {
        if (isVisible && !state.isSearchActive) onEnsureExploreLoaded()
    }
    val listStates = SearchTarget.entries.map { target -> key(state.sessionKey, state.searchQuery, target) { rememberLazyListState() } }
    var handledScrollRequest by remember(state.sessionKey) { mutableLongStateOf(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (handledScrollRequest != scrollToTopRequest) {
            handledScrollRequest = scrollToTopRequest
            if (state.isSearchActive && state.searchQuery.isNotBlank()) {
                listStates[state.selectedTarget.ordinal].animateToTimelineTop()
            }
        }
    }
    val tabs = key(state.sessionKey) {
        rememberSwipeTabs(state.selectedTarget.ordinal, SearchTarget.entries.size) { onSelectTarget(SearchTarget.entries[it]) }
    }
    val pager = tabs.pagerState
    Column(Modifier.fillMaxSize().padding(padding).testTag("search_screen")) {
        SearchBar(
            query = state.searchQuery, active = state.isSearchActive, sessionKey = state.sessionKey,
            onQueryChanged = onQueryChanged, onEnterSearch = onEnterSearch,
            onBack = onBack, onClear = onClear, onSearch = onSearch,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            isVisible = isVisible,
            focusRequester = searchFocusRequester,
        )
        if (state.isSearchActive && state.searchQuery.isNotBlank()) {
            SecondaryTabRow(selectedTabIndex = tabs.selectedPage) {
                SearchTarget.entries.forEach { target -> Tab(
                    selected = target.ordinal == tabs.selectedPage, onClick = { tabs.selectPage(target.ordinal) },
                    text = { Text(target.searchLabel()) }, modifier = Modifier.testTag("search_target_${target.name.lowercase()}"),
                ) }
            }
            HorizontalPager(state = pager, key = { SearchTarget.entries[it] },
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search_target_pager")) { page ->
                val target = SearchTarget.entries[page]
                val tab = state.tabs[target] ?: SearchTabState()
                val results = tab.results
                LazyColumn(Modifier.fillMaxSize().testTag("search_results_${target.name.lowercase()}"), state = listStates[page]) {
                    if (tab.isSearching) item { LoadingContent("${target.searchLabel()}を検索しています") }
                    if (results != null) {
                        when (target) {
                            SearchTarget.Accounts -> items(results.accounts, key = StatusAuthor::id) { AccountResult(it, onAccountClick) }
                            SearchTarget.Hashtags -> items(results.hashtags, key = { it.url }) { tag ->
                                Column(Modifier.fillMaxWidth().clickable { onOpenTag(tag.name, false) }
                                    .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.Center) {
                                    Text("#${tag.name}", color = MaterialTheme.colorScheme.primary)
                                    tag.postingAccounts?.let { Text("${java.text.NumberFormat.getIntegerInstance().format(it)}人が投稿",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                HorizontalDivider()
                            }
                            SearchTarget.Posts -> items(results.statuses, key = { it.timelineId }) { status -> SocialStatus(
                                status, onStatusClick, onOpenLink, onReply, onBoost, onQuote, onFavourite,
                                onBookmark, onReact, onVotePoll, onAccountClick, onMediaClick, preferences,
                                onMoreClick = onMoreClick,
                            ) }
                        }
                        if (results.count(target) == 0 && !tab.isSearching) item { MessageContent("${target.searchLabel()}が見つかりませんでした") }
                    }
                    if (tab.error != null) item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tab.error.message(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text("再試行") }
                        }
                    } else if (tab.isLoadingMore) item { LoadingContent("続きを取得しています") }
                    else if (results != null && !tab.isSearching && !tab.endReached && tab.nextOffset != null) item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore) { Text("さらに読み込む") }
                        }
                    }
                    if (results == null && !tab.isSearching && tab.error == null) item {
                        if (state.submittedQuery == state.searchQuery.trim()) LoadingContent("${target.searchLabel()}を検索しています")
                        else MessageContent("対象を選び、キーボードの検索キーで検索してください")
                    }
                }
            }
        } else if (state.isSearchActive) MessageContent("キーワード、アカウントID、URLを入力してください")
        else ExploreContent(exploreState, onSelectExploreFeed, onRefreshExplore, onLoadMoreExplore, onRetryExplore,
            onOpenTag, onOpenLink, onUnfollowTag, modifier = Modifier.fillMaxWidth().weight(1f), post = { status ->
                SocialStatus(status, onStatusClick, onOpenLink, onReply, onBoost, onQuote, onFavourite,
                    onBookmark, onReact, onVotePoll, onAccountClick, onMediaClick, preferences,
                    onMoreClick = onMoreClick)
            }, onFindTags = {
                onSelectTarget(SearchTarget.Hashtags)
                onEnterSearch()
                searchFocusRequester.requestFocus()
                keyboard?.show()
            }, scrollToTopRequest = scrollToTopRequest)
    }
}

private fun SearchTarget.searchLabel(): String = when (this) {
    SearchTarget.Posts -> "投稿"
    SearchTarget.Accounts -> "アカウント"
    SearchTarget.Hashtags -> "ハッシュタグ"
}
