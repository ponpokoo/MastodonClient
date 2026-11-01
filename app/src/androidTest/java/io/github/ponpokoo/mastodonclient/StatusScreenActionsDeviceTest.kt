package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailScreen
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatusScreenActionsDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val store = ViewModelStore()
    private val account = AccountSession("session", "https://example.test", "me", "me", "Me", "", "test-token")
    private val preferences = AppPreferences(timelineDisplay = TimelineDisplayPreferences(hiddenActions = emptySet()))

    @After fun clearModels() { rule.runOnIdle { store.clear() } }

    private fun <T : ViewModel> own(key: String, create: () -> T): T {
        lateinit var result: T
        rule.runOnIdle { result = create(); store.put(key, result) }
        return result
    }

    @Test fun tagBookmarkAndVoteReachTheOriginalPostAndRefreshItsCard() {
        val repository = Repository()
        val browsing = BrowsingSession().apply { activate(account) }
        val tag = own("tag") { HashtagTimelineViewModel("test", repository, browsing) }
        val actions = own("actions") { StatusActionsViewModel(repository, browsing) }
        rule.setContent { MaterialTheme {
            HashtagTimelineScreen("test", tag, actions, account.accountId, preferences,
                {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
        } }
        rule.onNodeWithContentDescription("ブックマーク").performScrollTo().performClick()
        rule.waitUntil { tag.uiState.value.statuses.singleOrNull()?.bookmarked == true }
        rule.onNodeWithText("second").performScrollTo().performClick()
        rule.onNodeWithText("投票する").performScrollTo().performClick()
        rule.waitUntil { tag.uiState.value.statuses.singleOrNull()?.poll?.voted == true }
        rule.runOnIdle {
            assertEquals(listOf("original" to true), repository.bookmarks)
            assertEquals(listOf(Triple("original", "poll", setOf(1))), repository.votes)
        }
    }

    @Test fun detailBookmarkButtonUpdatesTheSelectedPost() {
        val repository = Repository()
        val auth = object : AuthRepository {
            override suspend fun restoreSession() = account
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(account)
            override suspend fun logout() = Unit
        }
        val detail = own("detail") { StatusDetailViewModel("original", repository, auth) }
        rule.setContent { MaterialTheme {
            StatusDetailScreen(detail, preferences, {}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {})
        } }
        rule.onNodeWithContentDescription("ブックマーク").performScrollTo().performClick()
        rule.waitUntil { detail.uiState.value.detail?.status?.bookmarked == true }
        rule.onNodeWithContentDescription("ブックマーク").performScrollTo().performClick()
        rule.waitUntil { detail.uiState.value.detail?.status?.bookmarked == false }
        rule.runOnIdle { assertEquals(listOf("original" to true, "original" to false), repository.bookmarks) }
    }

    private class Repository : TimelineRepository {
        val bookmarks = mutableListOf<Pair<String, Boolean>>()
        val votes = mutableListOf<Triple<String, String, Set<Int>>>()
        private var post = TimelineStatus(
            timelineId = "boost-wrapper", statusId = "original", createdAt = "2026-11-02T00:00:00Z",
            author = StatusAuthor("author", "Author", "author@example.test", ""), boostedBy = null,
            contentHtml = "Hello", spoilerText = "", sensitive = false, visibility = "public",
            url = "https://example.test/@author/original", repliesCount = 0, boostsCount = 0, favouritesCount = 0,
            mediaAttachments = emptyList(),
            poll = StatusPoll("poll", null, false, false, 0, null, false, emptySet(),
                listOf(PollOption("first", null), PollOption("second", null))),
        )
        override suspend fun getTag(session: AccountSession, name: String) =
            Result.success(SearchTag(name, "https://example.test/tags/test", following = false))
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
            Result.success(TimelinePage(listOf(post), null, true))
        override suspend fun getHashtagTimeline(session: AccountSession, hashtag: String, maxId: String?, limit: Int) =
            Result.success(TimelinePage(listOf(post), null, true))
        override suspend fun getStatusDetail(session: AccountSession, statusId: String) =
            Result.success(StatusDetail(post, emptyList(), emptyList()))
        override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> {
            bookmarks += statusId to bookmarked
            post = post.copy(bookmarked = bookmarked)
            return Result.success(post)
        }
        override suspend fun votePoll(session: AccountSession, statusId: String, pollId: String, choices: Set<Int>): Result<TimelineStatus> {
            votes += Triple(statusId, pollId, choices)
            post = post.copy(poll = post.poll!!.copy(voted = true, ownVotes = choices))
            return Result.success(post)
        }
    }
}
