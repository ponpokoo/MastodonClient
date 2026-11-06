package io.github.ponpokoo.mastodonclient.feature.detail

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusDetail
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.StatusPoll
import io.github.ponpokoo.mastodonclient.domain.model.PollOption
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.feature.common.ScreenRepositoryFake
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelTestBase
import io.github.ponpokoo.mastodonclient.feature.common.secondAccount
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusDetailActionsTest : ScreenViewModelTestBase() {
    private var account: AccountSession? = testAccount
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = account
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
        override suspend fun logout() { account = null }
    }

    private open class Repository : ScreenRepositoryFake() {
        override suspend fun getStatusDetail(session: AccountSession, statusId: String) =
            Result.success(StatusDetail(testStatus(statusId), emptyList(), emptyList()))
    }

    @Test fun allEntryPointsReuseConversationAndExplicitRetryFetchesAgain() = runTest(dispatcher) {
        var requests = 0
        var cacheReads = 0
        val cached = StatusDetail(testStatus("reply"), listOf(testStatus("root")), emptyList())
        val repository = object : Repository() {
            override fun getCachedConversation(session: AccountSession, sourceStatusId: String, statusId: String): StatusDetail {
                assertEquals(testAccount, session)
                assertEquals(if (cacheReads == 0) "reply" else "root", sourceStatusId)
                assertEquals("reply", statusId)
                cacheReads++
                return cached
            }
            override suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> {
                requests++
                return Result.success(cached)
            }
        }
        val homeDetail = own(StatusDetailViewModel("reply", repository, auth))
        advanceUntilIdle()
        assertEquals(0, requests)
        assertEquals(1, cacheReads)
        assertFalse(homeDetail.uiState.value.showInitialLoadingIndicator)
        val model = own(StatusDetailViewModel("reply", repository, auth, conversationSourceId = "root"))
        advanceUntilIdle()
        assertEquals("reply", model.uiState.value.detail!!.status.statusId)
        assertEquals(listOf("root"), model.uiState.value.detail!!.ancestors.map { it.statusId })
        assertFalse(model.uiState.value.isLoading)
        assertFalse(model.uiState.value.showInitialLoadingIndicator)
        assertEquals(0, requests)
        assertEquals(2, cacheReads)
        model.retry(); advanceUntilIdle()
        assertEquals(1, requests)
        assertEquals(2, cacheReads)
        assertTrue(model.uiState.value.showInitialLoadingIndicator)
    }

    @Test fun cacheMissAndAccountSwitchFetchInsteadOfReusingTheOldConversation() = runTest(dispatcher) {
        val requests = mutableListOf<AccountSession>()
        var cacheReads = 0
        val repository = object : Repository() {
            override fun getCachedConversation(session: AccountSession, sourceStatusId: String, statusId: String): StatusDetail? {
                cacheReads++
                return null
            }
            override suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> {
                requests += session
                return Result.success(StatusDetail(testStatus(statusId), emptyList(), emptyList()))
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(StatusDetailViewModel("reply", repository, auth, browsing = browsing, conversationSourceId = "root"))
        advanceUntilIdle()
        assertEquals(listOf(testAccount), requests)
        assertEquals(1, cacheReads)
        account = secondAccount
        browsing.activate(secondAccount)
        advanceUntilIdle()
        assertEquals(listOf(testAccount, secondAccount), requests)
        assertEquals(1, cacheReads)
        assertFalse(model.uiState.value.isLoading)
    }

    private val before = testStatus("before").copy(timelineId = "boost-before", poll = poll("before-poll"))
    private val selected = testStatus("original").copy(poll = poll("selected-poll"))
    private val after = testStatus("after")
    private fun poll(id: String) = StatusPoll(id, null, false, false, 0, null, false, emptySet(),
        listOf(PollOption("a", null), PollOption("b", null)))
    private open inner class ConversationRepository : Repository() {
        override suspend fun getStatusDetail(session: AccountSession, statusId: String) =
            Result.success(StatusDetail(selected, listOf(before), listOf(after)))
    }

    @Test fun eachConversationActionUsesItsOwnPostAndPreservesTheConversation() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<String, String>>()
        val repository = object : ConversationRepository() {
            override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean): Result<TimelineStatus> {
                calls += "favourite" to statusId
                return Result.success(testStatus(statusId).copy(favourited = favourite, favouritesCount = 1))
            }
            override suspend fun setReblogged(session: AccountSession, statusId: String, reblogged: Boolean): Result<TimelineStatus> {
                calls += "boost" to statusId
                return Result.success(testStatus(statusId).copy(reblogged = reblogged, boostsCount = 1))
            }
            override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> {
                calls += "bookmark" to statusId
                return Result.success(testStatus(statusId).copy(bookmarked = bookmarked))
            }
            override suspend fun setFedibirdReaction(session: AccountSession, statusId: String, emoji: String?): Result<TimelineStatus> {
                calls += "reaction" to statusId
                return Result.success(testStatus(statusId).copy(reactions = listOf(EmojiReaction(emoji!!, 1, true, null, emptySet()))))
            }
        }
        val model = own(StatusDetailViewModel("original", repository, auth))
        advanceUntilIdle()
        val originalSelected = model.uiState.value.detail!!.status
        model.toggleFavourite(before); advanceUntilIdle()
        model.toggleReblog(after); advanceUntilIdle()
        model.toggleBookmark(before); advanceUntilIdle()
        model.setReaction(after, "👍"); advanceUntilIdle()
        val detail = model.uiState.value.detail!!
        assertEquals(originalSelected, detail.status)
        assertEquals("boost-before", detail.ancestors.single().timelineId)
        assertTrue(detail.ancestors.single().favourited)
        assertTrue(detail.ancestors.single().bookmarked)
        assertEquals(before.poll, detail.ancestors.single().poll)
        assertTrue(detail.descendants.single().reblogged)
        assertEquals("👍", detail.descendants.single().reactions.single().name)
        assertEquals(listOf("favourite" to "before", "boost" to "after", "bookmark" to "before", "reaction" to "after"), calls)
    }

    @Test fun optimisticFailureRollsBackOnlyThatActionAndPost() = runTest(dispatcher) {
        val reply = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ConversationRepository() {
            override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) = reply.await()
            override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean) =
                Result.success(before.copy(bookmarked = bookmarked))
        }
        val model = own(StatusDetailViewModel("original", repository, auth))
        advanceUntilIdle()
        val originalSelected = model.uiState.value.detail!!.status
        model.toggleFavourite(before); runCurrent()
        assertTrue(model.uiState.value.detail!!.ancestors.single().favourited)
        assertEquals(originalSelected, model.uiState.value.detail!!.status)
        model.toggleBookmark(before); runCurrent()
        reply.complete(Result.failure(IllegalStateException("offline"))); advanceUntilIdle()
        val updated = model.uiState.value.detail!!.ancestors.single()
        assertFalse(updated.favourited)
        assertTrue(updated.bookmarked)
        assertEquals("offline", model.uiState.value.actionMessage)
    }

    @Test fun separatePollsCanVoteConcurrentlyAndFailuresAllowRetryWithoutLosingOtherActions() = runTest(dispatcher) {
        val beforeReply = CompletableDeferred<Result<TimelineStatus>>()
        val selectedReply = CompletableDeferred<Result<TimelineStatus>>()
        val calls = mutableListOf<Triple<String, String, Set<Int>>>()
        var retry = false
        val repository = object : ConversationRepository() {
            override suspend fun votePoll(session: AccountSession, statusId: String, pollId: String, choices: Set<Int>): Result<TimelineStatus> {
                calls += Triple(statusId, pollId, choices)
                return when {
                    pollId == "selected-poll" -> selectedReply.await()
                    retry -> Result.success(before.copy(poll = before.poll!!.copy(voted = true, ownVotes = choices)))
                    else -> beforeReply.await()
                }
            }
            override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean) =
                Result.success(before.copy(bookmarked = bookmarked))
        }
        val model = own(StatusDetailViewModel("original", repository, auth))
        advanceUntilIdle()
        model.votePoll(before, setOf(0)); model.votePoll(before, setOf(1))
        model.votePoll(selected, setOf(1)); runCurrent()
        assertEquals(setOf("before-poll", "selected-poll"), model.uiState.value.votingPollIds)
        assertEquals(2, calls.size)
        selectedReply.complete(Result.success(selected.copy(poll = selected.poll!!.copy(voted = true, ownVotes = setOf(1)))))
        runCurrent()
        assertEquals(setOf("before-poll"), model.uiState.value.votingPollIds)
        beforeReply.complete(Result.failure(IllegalStateException("offline"))); runCurrent()
        assertTrue(model.uiState.value.votingPollIds.isEmpty())
        assertEquals(false, model.uiState.value.detail!!.ancestors.single().poll!!.voted)
        model.toggleBookmark(before); runCurrent()
        retry = true
        model.votePoll(before, setOf(0)); advanceUntilIdle()
        val detail = model.uiState.value.detail!!
        assertTrue(detail.ancestors.single().bookmarked)
        assertEquals(setOf(0), detail.ancestors.single().poll!!.ownVotes)
        assertEquals(setOf(1), detail.status.poll!!.ownVotes)
        assertEquals(listOf(Triple("before", "before-poll", setOf(0)), Triple("original", "selected-poll", setOf(1)),
            Triple("before", "before-poll", setOf(0))), calls)
    }

    @Test fun switchingAwayAndBackRejectsOldOptimisticResultsAndKeepsNewPollPending() = runTest(dispatcher) {
        for (favourite in listOf(true, false)) {
            val oldReply = CompletableDeferred<Result<TimelineStatus>>()
            val newReply = CompletableDeferred<Result<TimelineStatus>>()
            var votes = 0
            val repository = object : ConversationRepository() {
                override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) =
                    withContext(NonCancellable) { oldReply.await() }
                override suspend fun votePoll(session: AccountSession, statusId: String, pollId: String, choices: Set<Int>) =
                    if (++votes == 1) withContext(NonCancellable) { oldReply.await() } else newReply.await()
            }
            val browsing = BrowsingSession().apply { activate(testAccount) }
            val model = own(StatusDetailViewModel("original", repository, auth, browsing = browsing))
            advanceUntilIdle()
            if (favourite) model.toggleFavourite(before) else model.votePoll(before, setOf(0))
            runCurrent()
            browsing.activate(secondAccount); browsing.activate(testAccount); runCurrent()
            if (!favourite) { model.votePoll(before, setOf(1)); runCurrent() }
            oldReply.complete(Result.success(before.copy(favourited = true, favouritesCount = 1,
                poll = before.poll!!.copy(voted = true, ownVotes = setOf(0)))))
            runCurrent()
            val displayed = model.uiState.value.detail!!.ancestors.single()
            assertFalse(displayed.favourited)
            assertEquals(false, displayed.poll!!.voted)
            assertNull(model.uiState.value.actionMessage)
            if (!favourite) {
                assertEquals(setOf("before-poll"), model.uiState.value.votingPollIds)
                newReply.complete(Result.success(before.copy(poll = before.poll!!.copy(voted = true, ownVotes = setOf(1)))))
                advanceUntilIdle()
                assertEquals(setOf(1), model.uiState.value.detail!!.ancestors.single().poll!!.ownVotes)
            }
        }
    }

    @Test fun bookmarkTogglesTheOriginalPostAndUsesTheReturnedState() = runTest(dispatcher) {
        val calls = mutableListOf<Triple<AccountSession, String, Boolean>>()
        val repository = object : Repository() {
            override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> {
                calls += Triple(session, statusId, bookmarked)
                return Result.success(testStatus(statusId).copy(bookmarked = bookmarked))
            }
        }
        val model = own(StatusDetailViewModel("original", repository, auth))
        advanceUntilIdle()
        model.toggleBookmark(model.uiState.value.detail!!.status); advanceUntilIdle()
        assertTrue(model.uiState.value.detail!!.status.bookmarked)
        model.toggleBookmark(model.uiState.value.detail!!.status); advanceUntilIdle()
        assertFalse(model.uiState.value.detail!!.status.bookmarked)
        assertEquals(listOf(Triple(testAccount, "original", true), Triple(testAccount, "original", false)), calls)
    }

    @Test fun bookmarkFailureKeepsThePostAndCanBeRetried() = runTest(dispatcher) {
        var calls = 0
        val repository = object : Repository() {
            override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean) =
                if (++calls == 1) Result.failure(IllegalStateException("offline"))
                else Result.success(testStatus(statusId).copy(bookmarked = bookmarked))
        }
        val model = own(StatusDetailViewModel("original", repository, auth))
        advanceUntilIdle()
        model.toggleBookmark(model.uiState.value.detail!!.status); advanceUntilIdle()
        assertFalse(model.uiState.value.detail!!.status.bookmarked)
        assertEquals("offline", model.uiState.value.actionMessage)
        assertNull(model.uiState.value.errorMessage)
        model.consumeActionMessage()
        model.toggleBookmark(model.uiState.value.detail!!.status); advanceUntilIdle()
        assertTrue(model.uiState.value.detail!!.status.bookmarked)
    }

    @Test fun aStaleDetailCannotSendBookmarkAfterAccountSwitch() = runTest(dispatcher) {
        var calls = 0
        val repository = object : Repository() {
            override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> {
                calls++
                return Result.success(testStatus(statusId).copy(bookmarked = bookmarked))
            }
        }
        val model = own(StatusDetailViewModel("original", repository, auth))
        advanceUntilIdle()
        account = secondAccount
        model.toggleBookmark(model.uiState.value.detail!!.status); advanceUntilIdle()
        assertEquals(0, calls)
        assertFalse(model.uiState.value.detail!!.status.bookmarked)
    }

    @Test fun delayedBookmarkSuccessOrFailureIsDiscardedAfterCredentialsChange() = runTest(dispatcher) {
        for (replacement in listOf(secondAccount, testAccount.copy(accessToken = "replacement"))) {
            for (succeeds in listOf(true, false)) {
                account = testAccount
                val reply = CompletableDeferred<Result<TimelineStatus>>()
                val repository = object : Repository() {
                    override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean) =
                        withContext(NonCancellable) { reply.await() }
                }
                val model = own(StatusDetailViewModel("original", repository, auth))
                advanceUntilIdle()
                model.toggleBookmark(model.uiState.value.detail!!.status); runCurrent()
                account = replacement
                reply.complete(if (succeeds) Result.success(testStatus("original").copy(bookmarked = true))
                    else Result.failure(IllegalStateException("old request")))
                advanceUntilIdle()
                assertFalse(model.uiState.value.detail!!.status.bookmarked)
                assertNull(model.uiState.value.actionMessage)
                assertNull(model.uiState.value.errorMessage)
            }
        }
    }
}
