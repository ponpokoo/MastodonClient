package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.LocalServerCapabilities
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailScreen
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationActionsDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val store = ViewModelStore()
    private val account = AccountSession("session", "https://example.test", "me", "me", "Me", "", "test-token")
    private val replies = mutableListOf<String>()
    private val quotes = mutableListOf<Pair<String, QuoteMode>>()
    private lateinit var model: StatusDetailViewModel

    @After fun clearModels() { rule.runOnIdle { store.clear() } }

    private fun show(repository: Repository) {
        val auth = object : AuthRepository {
            override suspend fun restoreSession() = account
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(account)
            override suspend fun logout() = Unit
        }
        val browsing = BrowsingSession().apply { activate(account) }
        rule.runOnIdle {
            model = StatusDetailViewModel("selected", repository, auth, browsing = browsing)
            store.put("detail", model)
        }
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalServerCapabilities provides ServerCapabilities(reactionApi = EmojiReactionApi.Fedibird)) {
                StatusDetailScreen(model, AppPreferences(timelineDisplay = TimelineDisplayPreferences(hiddenActions = emptySet())),
                    {}, { replies += it }, { post, mode -> quotes += post.statusId to mode }, {}, {}, {}, { _, _ -> }, {})
            }
        } }
    }

    private fun action(id: String, label: String): SemanticsNodeInteraction {
        val card = hasTestTag("timeline_status") and (hasText("Author $id") or hasAnyDescendant(hasText("Author $id")))
        return rule.onNode(hasContentDescription(label) and hasAnyAncestor(card))
    }

    private fun showPost(id: String) {
        rule.onNodeWithTag("status_detail").performScrollToNode(hasText("Author $id"))
    }

    @Test fun allActionsOnAncestorsAndRepliesUseTheirOwnPost() {
        val repository = Repository()
        show(repository)
        for (id in listOf("before", "after")) {
            showPost(id)
            action(id, "お気に入り").performScrollTo().performClick()
            action(id, "ブックマーク").performScrollTo().performClick()
            action(id, "ブースト（長押しで引用を選択）").performScrollTo().performClick()
            action(id, "返信").performScrollTo().performClick()
            action(id, "ブースト（長押しで引用を選択）").performScrollTo().performTouchInput { longClick() }
            rule.onNodeWithText("引用（リンク）").performClick()
            val card = hasTestTag("timeline_status") and (hasText("Author $id") or hasAnyDescendant(hasText("Author $id")))
            rule.onNode(hasText("👍") and hasAnyAncestor(card)).performScrollTo().performClick()
            rule.onNodeWithText("$id option 1").performScrollTo().performClick()
            rule.onNode(hasText("投票する") and hasAnyAncestor(card)).performScrollTo().performClick()
            rule.waitUntil { repository.votes.any { it.first == id } }
            // A success snackbar can cover the next card's actions after scrolling.
            rule.waitUntil(timeoutMillis = 6_000) { rule.onAllNodesWithText("投票しました").fetchSemanticsNodes().isEmpty() }
        }
        rule.runOnIdle {
            for (operation in listOf("favourite", "bookmark", "boost", "reaction")) {
                assertEquals(listOf(operation to "before", operation to "after"), repository.calls.filter { it.first == operation })
            }
            assertEquals(listOf("before", "after"), replies)
            assertEquals(listOf("before" to QuoteMode.Link, "after" to QuoteMode.Link), quotes)
            assertEquals(listOf(Triple("before", "before-poll", setOf(1)), Triple("after", "after-poll", setOf(1))), repository.votes)
            assertFalse(model.uiState.value.detail!!.status.favourited)
            assertFalse(model.uiState.value.detail!!.status.bookmarked)
            assertEquals("boost-before", model.uiState.value.detail!!.ancestors.single().timelineId)
        }
    }

    @Test fun votingOnOneConversationPostDoesNotDisableAnotherPoll() {
        val repository = Repository().apply { holdVotes = true }
        show(repository)
        for (id in listOf("before", "after")) {
            showPost(id)
            rule.onNodeWithText("$id option 0").performScrollTo().performClick()
            val card = hasTestTag("timeline_status") and (hasText("Author $id") or hasAnyDescendant(hasText("Author $id")))
            rule.onNode(hasText("投票する") and hasAnyAncestor(card))
                .performScrollTo().assertIsEnabled().performClick()
        }
        rule.runOnIdle { assertEquals(setOf("before-poll", "after-poll"), model.uiState.value.votingPollIds) }
        val afterCard = hasTestTag("timeline_status") and (hasText("Author after") or hasAnyDescendant(hasText("Author after")))
        rule.onNode(hasText("投票中…") and hasAnyAncestor(afterCard)).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            repository.pending.getValue("after").complete(Result.success(repository.post("after").copy(
                poll = repository.post("after").poll!!.copy(voted = true, ownVotes = setOf(0)))))
        }
        rule.waitUntil { model.uiState.value.votingPollIds == setOf("before-poll") }
        rule.runOnIdle { repository.pending.getValue("before").complete(Result.failure(IllegalStateException("offline"))) }
        rule.waitUntil { model.uiState.value.votingPollIds.isEmpty() }
    }

    private class Repository : TimelineRepository {
        val calls = mutableListOf<Pair<String, String>>()
        val votes = mutableListOf<Triple<String, String, Set<Int>>>()
        val pending = mutableMapOf<String, CompletableDeferred<Result<TimelineStatus>>>()
        var holdVotes = false
        private val posts = listOf("before", "selected", "after").associateWith { id -> TimelineStatus(
            timelineId = if (id == "before") "boost-before" else id, statusId = id,
            createdAt = "2026-11-02T00:00:00Z", author = StatusAuthor("author-$id", "Author $id", "author-$id@example.test", ""),
            boostedBy = null, contentHtml = "$id body", spoilerText = "", sensitive = false, visibility = "public",
            url = "https://example.test/@author/$id", repliesCount = 0, boostsCount = 0, favouritesCount = 0,
            reactions = listOf(EmojiReaction("👍", 1, false, null, emptySet())), mediaAttachments = emptyList(),
            poll = StatusPoll("$id-poll", null, false, false, 0, null, false, emptySet(),
                listOf(PollOption("$id option 0", null), PollOption("$id option 1", null))),
        ) }.toMutableMap()
        fun post(id: String) = posts.getValue(id)
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
            Result.success(TimelinePage(posts.values.toList(), null, true))
        override suspend fun getStatusDetail(session: AccountSession, statusId: String) =
            Result.success(StatusDetail(post("selected"), listOf(post("before")), listOf(post("after"))))
        override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean): Result<TimelineStatus> {
            calls += "favourite" to statusId
            return Result.success(post(statusId).copy(favourited = favourite, favouritesCount = 1).also { posts[statusId] = it })
        }
        override suspend fun setReblogged(session: AccountSession, statusId: String, reblogged: Boolean): Result<TimelineStatus> {
            calls += "boost" to statusId
            return Result.success(post(statusId).copy(reblogged = reblogged, boostsCount = 1).also { posts[statusId] = it })
        }
        override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> {
            calls += "bookmark" to statusId
            return Result.success(post(statusId).copy(bookmarked = bookmarked).also { posts[statusId] = it })
        }
        override suspend fun setFedibirdReaction(session: AccountSession, statusId: String, emoji: String?): Result<TimelineStatus> {
            calls += "reaction" to statusId
            return Result.success(post(statusId).copy(reactions = listOf(EmojiReaction("👍", 2, true, null, emptySet()))).also { posts[statusId] = it })
        }
        override suspend fun votePoll(session: AccountSession, statusId: String, pollId: String, choices: Set<Int>): Result<TimelineStatus> {
            votes += Triple(statusId, pollId, choices)
            if (holdVotes) return CompletableDeferred<Result<TimelineStatus>>().also { pending[statusId] = it }.await()
            return Result.success(post(statusId).copy(poll = post(statusId).poll!!.copy(voted = true, ownVotes = choices)).also { posts[statusId] = it })
        }
    }
}
