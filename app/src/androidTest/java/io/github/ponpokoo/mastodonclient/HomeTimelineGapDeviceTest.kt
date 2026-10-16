package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.*
import io.github.ponpokoo.mastodonclient.feature.timeline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeTimelineGapDeviceTest {
    @get:Rule val rule = createComposeRule()
    private fun status(id: String) = TimelineStatus(id, id, "2026-10-16T00:00:00Z",
        StatusAuthor("a", "Author", "a@example.test", ""), null, "<p>$id</p>", "", false,
        "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private fun page(vararg ids: String) = TimelinePage(ids.map(::status), ids.lastOrNull(), false)

    @Test fun localPullRefreshKeepsOriginalPost() = checkPublicPullRefresh(TimelineFeed.Local)

    @Test fun federatedPullRefreshKeepsOriginalPost() = checkPublicPullRefresh(TimelineFeed.Federated)

    private fun checkPublicPullRefresh(feed: TimelineFeed) {
        val original = page("old", *(1..20).map { "older-$it" }.toTypedArray())
        val state = mutableStateOf(TimelineUiState(statuses = original.statuses, isInitialLoading = false, selectedFeed = feed))
        lateinit var list: LazyListState
        var refreshes = 0
        rule.setContent { MaterialTheme {
            list = rememberLazyListState()
            TimelineContent(state.value, PaddingValues(), list, onRefresh = {
                refreshes++
                val refreshed = original.mergeHomePage(page(*(1..20).map { "new-$it" }.toTypedArray()))
                state.value = state.value.copy(statuses = refreshed.statuses, homeIndex = refreshed.homeIndex)
            }, onRetry = {}, onLoadMore = {}, onStatusClick = {}, onAccountClick = {}, onMediaClick = { _, _ -> },
                onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
                onBookmark = {}, onReact = { _, _ -> }, onVotePoll = { _, _ -> }, onMoreClick = {},
                onUnavailableAction = {}, preferences = AppPreferences(keepPositionOnPullRefresh = true))
        } }
        rule.onNodeWithTag("timeline_list").performTouchInput { swipeDown() }
        rule.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals("old", list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
    }

    @Test fun refreshKeepsOriginalTopPostAndGapTapKeepsVisiblePost() {
        var window = page("old", *(1..20).map { "older-$it" }.toTypedArray())
        val state = mutableStateOf(TimelineUiState(statuses = window.statuses, isInitialLoading = false))
        lateinit var list: LazyListState
        var taps = 0
        rule.setContent { MaterialTheme {
            list = rememberLazyListState()
            TimelineContent(state.value, PaddingValues(), list, onRefresh = {}, onRetry = {},
                onLoadMore = {}, onStatusClick = {}, onAccountClick = {}, onMediaClick = { _, _ -> },
                onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
                onBookmark = {}, onReact = { _, _ -> }, onVotePoll = { _, _ -> }, onMoreClick = {},
                onUnavailableAction = {}, preferences = AppPreferences(keepPositionOnPullRefresh = true), onLoadGap = { id ->
                    taps++
                    window = window.fillHomeGap(window.homeIndex().gaps.single { it.beforeId == id }, page("middle", "old"))
                    state.value = state.value.copy(statuses = window.statuses, homeIndex = window.homeIndex)
                })
        } }
        rule.runOnIdle {
            window = window.mergeHomePage(page("latest", "new"))
            state.value = state.value.copy(statuses = window.statuses, homeIndex = window.homeIndex)
        }
        rule.runOnIdle {
            assertEquals("old", list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
        // Reveal the boundary, then fill it while the newer post remains the viewport anchor.
        rule.onNodeWithTag("timeline_list").performScrollToIndex(1)
        rule.onNodeWithText("間の投稿を読み込む").performClick()
        rule.runOnIdle {
            assertEquals(1, taps)
            assertTrue(state.value.homeIndex!!.gaps.isEmpty())
            assertEquals("new", list.layoutInfo.visibleItemsInfo.first().key)
        }
        rule.runOnIdle { state.value = state.value.copy(isRefreshing = true) }
        rule.onNodeWithTag("timeline_list").performScrollToIndex(8)
        var readingKey: Any? = null
        var readingOffset = 0
        rule.runOnIdle {
            readingKey = list.layoutInfo.visibleItemsInfo.first().key
            readingOffset = list.firstVisibleItemScrollOffset
            window = window.mergeHomePage(page("newest", "latest"))
            state.value = state.value.copy(statuses = window.statuses, homeIndex = window.homeIndex, isRefreshing = false)
        }
        rule.runOnIdle {
            assertEquals(readingKey, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(readingOffset, list.firstVisibleItemScrollOffset)
        }
    }
}
