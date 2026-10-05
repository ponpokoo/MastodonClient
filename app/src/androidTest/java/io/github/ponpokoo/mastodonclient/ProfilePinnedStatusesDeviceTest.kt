package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.timeline.ProfileContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfilePinnedStatusesDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")
    private val posts = List(20) { status("post-$it") }
    private val pinned = status("pinned")
    private val profile = mutableStateOf(UserProfile(author, "", "", 0, 0, 21, posts, endReached = true))
    private lateinit var listState: LazyListState

    @Test fun delayedPinnedPostAppearsFirstWhenProfileIsStillAtTop() {
        showProfile()
        rule.runOnIdle { assertEquals("post-0", firstVisibleKey()) }
        rule.runOnIdle { profile.value = profile.value.copy(pinnedStatuses = listOf(pinned)) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("pinned", firstVisibleKey())
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
        rule.onNodeWithText("固定された投稿").assertIsDisplayed()
    }

    @Test fun delayedPinnedPostPreservesThePostBeingReadAfterScrolling() {
        showProfile()
        rule.runOnIdle { runBlocking { listState.scrollToItem(5, 24) } }
        rule.waitForIdle()
        var originalKey: Any? = null
        var originalOffset = 0
        rule.runOnIdle {
            originalKey = firstVisibleKey()
            originalOffset = listState.firstVisibleItemScrollOffset
            profile.value = profile.value.copy(pinnedStatuses = listOf(pinned))
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(originalKey, firstVisibleKey())
            assertEquals(originalOffset, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test fun delayedPinnedPostPreservesPartialScrollWithinTheFirstPost() {
        showProfile()
        rule.runOnIdle { runBlocking { listState.scrollToItem(0, 24) } }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(24, listState.firstVisibleItemScrollOffset)
            profile.value = profile.value.copy(pinnedStatuses = listOf(pinned))
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("post-0", firstVisibleKey())
            assertEquals(24, listState.firstVisibleItemScrollOffset)
        }
    }

    private fun firstVisibleKey() = listState.layoutInfo.visibleItemsInfo.first().key

    private fun showProfile() {
        rule.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                val listStates = listOf(listState, rememberLazyListState(), rememberLazyListState())
                ProfileContent(
                    state = ProfileUiState(profile = profile.value),
                    padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                    onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                    onFavourite = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                    listStates = listStates,
                )
            }
        }
    }

    private fun status(id: String) = TimelineStatus(
        timelineId = id, statusId = id, createdAt = "2026-10-05T00:00:00Z", author = author,
        boostedBy = null, contentHtml = "<p>$id</p>", spoilerText = "", sensitive = false,
        visibility = "public", url = null, repliesCount = 0, boostsCount = 0, favouritesCount = 0,
        mediaAttachments = emptyList(),
    )
}
