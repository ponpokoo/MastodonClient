package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.notifications.*
import io.github.ponpokoo.mastodonclient.feature.profile.*
import io.github.ponpokoo.mastodonclient.feature.search.SearchTabState
import io.github.ponpokoo.mastodonclient.feature.search.SearchUiState
import io.github.ponpokoo.mastodonclient.feature.search.ExploreUiState
import io.github.ponpokoo.mastodonclient.feature.search.ExploreTabState
import io.github.ponpokoo.mastodonclient.feature.timeline.ExploreContent
import io.github.ponpokoo.mastodonclient.feature.timeline.SearchContent
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineContent
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProcessingIndicatorsDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun timelineInitialLoadingUsesOneIndicatorAndStopsOnFailureOrLoadedContent() {
        val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")
        val post = TimelineStatus("post", "post", "2026-10-13T00:00:00Z", author, null,
            "<p>取得済みの投稿</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
        val state = mutableStateOf(TimelineUiState(isInitialLoading = true, isRefreshing = true))
        rule.setContent { MaterialTheme {
            TimelineContent(state.value, PaddingValues(), rememberLazyListState(), onRefresh = {}, onRetry = {},
                onLoadMore = {}, onStatusClick = {}, onAccountClick = {}, onMediaClick = { _, _ -> },
                onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
                onBookmark = {}, onReact = { _, _ -> }, onVotePoll = { _, _ -> }, onMoreClick = {},
                onUnavailableAction = {}, preferences = AppPreferences())
        } }
        for (feed in TimelineFeed.entries) {
            rule.runOnIdle { state.value = TimelineUiState(selectedFeed = feed, isInitialLoading = true, isRefreshing = true) }
            rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
            rule.onAllNodes(progress).assertCountEquals(1)
            rule.runOnIdle { state.value = state.value.copy(isInitialLoading = false, isRefreshing = false, errorMessage = "取得失敗") }
            rule.onNodeWithTag("list_initial_loading").assertDoesNotExist()
            rule.onAllNodes(progress).assertCountEquals(0)
            rule.onNodeWithText("再試行").assertIsDisplayed()
        }
        rule.runOnIdle { state.value = TimelineUiState(isInitialLoading = false, statuses = listOf(post), isHomeSyncing = true) }
        rule.onNodeWithTag("list_initial_loading").assertDoesNotExist()
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.onNodeWithText("取得済みの投稿").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(isRefreshing = true) }
        rule.onAllNodes(progress).assertCountEquals(1)
        rule.onNodeWithText("取得済みの投稿").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(isRefreshing = false) }
        rule.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun cancelledSearchReentryDoesNotShowProgressUntilResubmitted() {
        val state = mutableStateOf(SearchUiState(searchQuery = "hello", submittedQuery = "hello",
            isSearchActive = true, tabs = mapOf(SearchTarget.Posts to SearchTabState(isSearching = true))))
        rule.setContent { MaterialTheme {
            SearchContent(state.value, PaddingValues(), onQueryChanged = {}, onSearch = {}, onEnterSearch = {},
                onBack = {}, onClear = {}, onSelectTarget = {}, onLoadMore = {}, onRetry = {},
                onStatusClick = {}, onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                onFavourite = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> }, onMoreClick = {})
        } }
        rule.onAllNodes(progress).assertCountEquals(1)
        rule.runOnIdle { state.value = state.value.copy(isSearchActive = false, tabs = emptyMap()) }
        rule.runOnIdle { state.value = state.value.copy(isSearchActive = true) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.runOnIdle { state.value = state.value.copy(tabs = mapOf(SearchTarget.Posts to SearchTabState(isSearching = true))) }
        rule.onAllNodes(progress).assertCountEquals(1)
    }

    @Test fun notificationsInitialLoadAndPullRefreshEachShowOneIndicatorAndFinish() {
        val state = mutableStateOf(NotificationsUiState(isLoadingNotifications = true))
        showNotifications(state)
        rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
        capture("notifications-initial")
        rule.onAllNodes(progress).assertCountEquals(1)
        rule.runOnIdle { state.value = state.value.copy(isPullRefreshingNotifications = true) }
        rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
        rule.onAllNodes(progress).assertCountEquals(1)
        rule.runOnIdle { state.value = state.value.copy(isInitialPageLoaded = true) }
        rule.onNodeWithTag("list_initial_loading").assertDoesNotExist()
        rule.onAllNodes(progress).assertCountEquals(1)
        capture("notifications-refresh")
        rule.runOnIdle { state.value = state.value.copy(isLoadingNotifications = false,
            isPullRefreshingNotifications = false, notificationsEndReached = true) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.runOnIdle { state.value = state.value.copy(isLoadingNotifications = true) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.runOnIdle { state.value = state.value.copy(isLoadingNotifications = false, isLoadingMoreNotifications = true) }
        rule.onNodeWithTag("list_loading_more").assertIsDisplayed()
        rule.onAllNodes(progress).assertCountEquals(1)
        capture("notifications-more")
        rule.runOnIdle { state.value = state.value.copy(isLoadingMoreNotifications = false) }
        rule.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun capabilitiesAndFirstReactionPageUseOneIndicatorAndFailureStopsIt() {
        val state = mutableStateOf(NotificationsUiState(isCheckingCapabilities = true))
        showNotifications(state, NotificationCategory.Reactions)
        rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
        rule.onAllNodes(progress).assertCountEquals(1)
        val loadingId = rule.onNodeWithTag("list_initial_loading").fetchSemanticsNode().id
        rule.runOnIdle { state.value = state.value.copy(isCheckingCapabilities = false,
            capabilities = NotificationCapabilities(supportsEmojiReactions = true)) }
        rule.onAllNodes(progress).assertCountEquals(1)
        assertEquals(loadingId, rule.onNodeWithTag("list_initial_loading").fetchSemanticsNode().id)
        rule.runOnIdle { state.value = state.value.withList(NotificationCategory.Reactions,
            NotificationListState(error = "通知取得失敗")) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.onNodeWithText("通知取得失敗").assertIsDisplayed()
        rule.onNodeWithText("再試行").assertIsDisplayed()
    }

    private fun showNotifications(state: MutableState<NotificationsUiState>, category: NotificationCategory = NotificationCategory.All) {
        rule.setContent { MaterialTheme {
            NotificationsContent(state.value, PaddingValues(), onRefresh = {}, onLoadMore = {},
                onStatusClick = {}, onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {},
                onBookmark = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                preferences = AppPreferences(), listStates = List(NotificationCategory.entries.size) { rememberLazyListState() },
                selectedFilter = category, onSelectFilter = {}, newNoticeMessage = null, onNewNoticeClick = null)
        } }
    }

    @Test fun exploreInitialRefreshAndPaginationUseOneIndicatorAndPreserveLoadedItems() {
        val state = mutableStateOf(ExploreUiState(tabs = mapOf(ExploreFeed.Posts to ExploreTabState(loading = true))))
        val page = ExplorePage(news = listOf(ExploreNews("https://example.test/news", "取得済みの記事", "", "テスト", null)), nextCursor = "next")
        rule.setContent { MaterialTheme {
            ExploreContent(state.value, onSelect = {}, onRefresh = {}, onLoadMore = {}, onRetry = {},
                onOpenTag = { _, _ -> }, onOpenLink = {}, onUnfollow = {}, post = { Text(it.contentHtml) }, modifier = Modifier.fillMaxSize())
        } }
        rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
        rule.onAllNodes(progress).assertCountEquals(1)
        capture("explore-initial")
        rule.runOnIdle { state.value = state.value.copy(tabs = mapOf(ExploreFeed.Posts to ExploreTabState(page = page))) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.onNodeWithText("取得済みの記事").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(tabs = mapOf(ExploreFeed.Posts to ExploreTabState(page = page, loadingMore = true))) }
        rule.onNodeWithTag("list_loading_more").assertIsDisplayed()
        rule.onAllNodes(progress).assertCountEquals(1)
        capture("explore-more")
        rule.runOnIdle { state.value = state.value.copy(tabs = mapOf(ExploreFeed.Posts to ExploreTabState(page = page, loading = true, loadingMore = true))) }
        rule.onNodeWithTag("list_loading_more").assertDoesNotExist()
        rule.onAllNodes(progress).assertCountEquals(1)
        rule.onNodeWithText("取得済みの記事").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(tabs = mapOf(ExploreFeed.Posts to ExploreTabState(page = page, error = "更新失敗"))) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.onNodeWithText("取得済みの記事").assertIsDisplayed()
        rule.onNodeWithText("再試行").assertIsDisplayed()
    }

    @Test fun profilePostsInitialRefreshAndPaginationKeepTheHeaderAndUseOneIndicator() {
        val headerState = LazyListState()
        val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")
        val post = TimelineStatus("post", "post", "2026-10-13T00:00:00Z", author, null,
            "<p>取得済みの投稿</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
        val state = mutableStateOf(ProfileUiState(profile = UserProfile(author, "", "", 0, 0, 1, emptyList(), endReached = true),
            isLoadingMoreProfile = true, profileTabs = mapOf(ProfileStatusTab.Posts to ProfileTabUiState(isLoadingMore = true))))
        rule.setContent { MaterialTheme {
            ProfileContent(state.value, PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
                onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> }, isLoadingMore = state.value.isLoadingMoreProfile,
                headerListState = headerState)
        } }
        rule.onNodeWithTag("profile_header").assertIsDisplayed()
        rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
        rule.onAllNodes(progress).assertCountEquals(1)
        capture("profile-initial")
        val labelBounds = rule.onNodeWithText("読み込み中…").fetchSemanticsNode().boundsInRoot
        assertTrue("Loading label must fit the visible viewport: $labelBounds; items=${headerState.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) }}",
            labelBounds.bottom <= rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        val listTop = rule.onNodeWithTag("profile_status_tab_posts").fetchSemanticsNode().boundsInRoot.bottom
        val viewportBottom = rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Loading label must stay near the center of visible posts: $labelBounds; top=$listTop; bottom=$viewportBottom; items=${headerState.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) }}",
            labelBounds.center.y < listTop + (viewportBottom - listTop) * 0.75f)
        val loaded = ProfileTabUiState(statuses = listOf(post), isLoaded = true, endReached = true)
        rule.runOnIdle { state.value = state.value.copy(isLoadingMoreProfile = false, profileTabs = mapOf(ProfileStatusTab.Posts to loaded)) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.onNodeWithText("取得済みの投稿").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(isRefreshingProfile = true,
            profileTabs = mapOf(ProfileStatusTab.Posts to loaded.copy(isRefreshing = true))) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.runOnIdle { state.value = state.value.copy(profileTabs = mapOf(ProfileStatusTab.Posts to loaded.copy(isRefreshing = true, isPullRefreshing = true))) }
        rule.onAllNodes(progress).assertCountEquals(1)
        rule.onNodeWithTag("list_initial_loading").assertDoesNotExist()
        rule.onNodeWithText("取得済みの投稿").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(isRefreshingProfile = false, isLoadingMoreProfile = true,
            profileTabs = mapOf(ProfileStatusTab.Posts to loaded.copy(isLoadingMore = true))) }
        rule.onNodeWithTag("list_loading_more").assertIsDisplayed()
        rule.onAllNodes(progress).assertCountEquals(1)
        capture("profile-more")
        rule.runOnIdle { state.value = state.value.copy(isLoadingMoreProfile = false, profileTabs = mapOf(ProfileStatusTab.Posts to loaded)) }
        rule.onAllNodes(progress).assertCountEquals(0)
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "processing-$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun profilePostsFailureOffersRetryWithoutAutomaticallyRequestingAgain() {
        val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")
        val state = mutableStateOf(ProfileUiState(profile = UserProfile(author, "", "", 0, 0, 0, emptyList()),
            profileTabs = mapOf(ProfileStatusTab.Posts to ProfileTabUiState(error = "投稿取得失敗"))))
        var retries = 0
        var automaticRequests = 0
        rule.setContent { MaterialTheme {
            ProfileContent(state.value, PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
                onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                onAutoLoadMore = { automaticRequests++ }, onRetryPosts = {
                    retries++
                    state.value = state.value.copy(profileTabs = mapOf(ProfileStatusTab.Posts to ProfileTabUiState(isRefreshing = true)))
                })
        } }
        rule.onNodeWithText("投稿取得失敗").assertIsDisplayed()
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.runOnIdle { assertEquals(0, automaticRequests) }
        rule.onNodeWithText("再試行").performClick()
        rule.runOnIdle { assertEquals(1, retries) }
        rule.onNodeWithText("投稿取得失敗").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(profileTabs = mapOf(ProfileStatusTab.Posts to
            ProfileTabUiState(isLoaded = true, endReached = true))) }
        rule.onAllNodes(progress).assertCountEquals(0)
        rule.runOnIdle { assertEquals(0, automaticRequests) }
    }
}
