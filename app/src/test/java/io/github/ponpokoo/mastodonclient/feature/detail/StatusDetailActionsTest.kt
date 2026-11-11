package io.github.ponpokoo.mastodonclient.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionManager
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import kotlinx.coroutines.cancel
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

    @Test fun tappedBoostShowsItsOriginalReplyAndActionsWithoutWaitingForRoomOrNetwork() = runTest(dispatcher) {
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val boosted = testStatus("reply").copy(
            timelineId = "boost-wrapper", boostedBy = testStatus().author, inReplyToId = "parent",
            repliesCount = 3, favouritesCount = 4, favourited = true, boostsCount = 5, reblogged = true,
            reactions = listOf(EmojiReaction("👍", 2, true, null, emptySet())),
        )
        val pending = CompletableDeferred<Result<StatusDetail>>()
        val repository = object : Repository() {
            override suspend fun getCachedHomeStatus(session: AccountSession, statusId: String): TimelineStatus? =
                error("A tapped post must bypass Room")
            override suspend fun getStatusDetail(session: AccountSession, statusId: String) = pending.await()
        }
        val model = own(StatusDetailViewModel("reply", repository, auth, browsing = browsing,
            seed = StatusDetailSeed(browsing.snapshot.value, boosted)))
        runCurrent()
        assertEquals(boosted.copy(timelineId = "reply", boostedBy = null), model.uiState.value.detail?.status?.copy(filterScope = ""))
        assertFalse(model.uiState.value.showInitialLoadingIndicator)
        val fresh = boosted.copy(timelineId = "reply", boostedBy = null, favouritesCount = 9)
        pending.complete(Result.success(StatusDetail(fresh, listOf(testStatus("parent")), listOf(testStatus("child")))))
        advanceUntilIdle()
        assertEquals(fresh, model.uiState.value.detail?.status?.copy(filterScope = ""))
        assertEquals(listOf("parent"), model.uiState.value.detail?.ancestors?.map { it.statusId })
    }

    @Test fun detailFallsBackToMemoryThenRoomAndFetchesOnCacheMiss() = runTest(dispatcher) {
        val cached = testStatus("reply").copy(inReplyToId = "parent", repliesCount = 2)
        for (source in listOf("memory", "room", "miss")) {
            var roomReads = 0
            val pending = CompletableDeferred<Result<StatusDetail>>()
            val repository = object : Repository() {
                override fun getCachedStatus(session: AccountSession, statusId: String) = cached.takeIf { source == "memory" }
                override suspend fun getCachedHomeStatus(session: AccountSession, statusId: String): TimelineStatus? {
                    roomReads++
                    return cached.takeIf { source == "room" }
                }
                override suspend fun getStatusDetail(session: AccountSession, statusId: String) = pending.await()
            }
            val model = own(StatusDetailViewModel("reply", repository, auth))
            runCurrent()
            assertEquals(if (source == "memory") 0 else 1, roomReads)
            assertEquals(cached.takeUnless { source == "miss" }, model.uiState.value.detail?.status?.copy(filterScope = ""))
            assertEquals(source == "miss", model.uiState.value.showInitialLoadingIndicator)
            pending.complete(Result.success(StatusDetail(cached, emptyList(), emptyList())))
            advanceUntilIdle()
            assertEquals(cached, model.uiState.value.detail?.status?.copy(filterScope = ""))
        }
    }

    @Test fun restoredDetailUsesRoomAfterTheBrowsingSessionIsActivated() = runTest(dispatcher) {
        val browsing = BrowsingSession()
        val cached = testStatus("reply").copy(inReplyToId = "parent", repliesCount = 2)
        val pending = CompletableDeferred<Result<StatusDetail>>()
        val repository = object : Repository() {
            override suspend fun getCachedHomeStatus(session: AccountSession, statusId: String) = cached
            override suspend fun getStatusDetail(session: AccountSession, statusId: String) = pending.await()
        }
        val model = own(StatusDetailViewModel(cached.statusId, repository, auth, browsing = browsing))
        runCurrent()
        assertNull(model.uiState.value.detail)

        browsing.activate(testAccount)
        runCurrent()
        assertEquals(cached.statusId, model.uiState.value.detail?.status?.statusId)
        assertFalse(model.uiState.value.showInitialLoadingIndicator)
        pending.complete(Result.success(StatusDetail(cached, emptyList(), emptyList())))
        advanceUntilIdle()
    }

    @Test fun detailRefreshAndActionsUpdateBothHomeRowsEvenAfterClosingDetail() = runTest(dispatcher) {
        val initial = testStatus().copy(inReplyToId = "parent")
        val boost = initial.copy(timelineId = "boost-wrapper", boostedBy = initial.author)
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val detailResponse = CompletableDeferred<Result<StatusDetail>>()
        val favouriteResponse = CompletableDeferred<Result<TimelineStatus>>()
        val reaction = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : Repository() {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(listOf(boost, initial), null, true))
            override suspend fun getStatusDetail(session: AccountSession, statusId: String) = detailResponse.await()
            override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) =
                favouriteResponse.await()
            override suspend fun setFedibirdReaction(session: AccountSession, statusId: String, emoji: String?) = reaction.await()
        }
        val manager = StatusActionManager(repository)
        val actions = own(StatusActionsViewModel(repository, browsing, manager))
        val timeline = own(TimelineViewModel(repository, browsing, SavedStateHandle()))
        val model = own(StatusDetailViewModel(initial.statusId, repository, auth, manager, browsing,
            seed = StatusDetailSeed(browsing.snapshot.value, boost), actionOwner = actions))
        runCurrent()

        model.toggleFavourite(initial)
        runCurrent()
        assertTrue(timeline.uiState.value.statuses.all { it.favourited })
        favouriteResponse.complete(Result.failure(IllegalStateException("offline")))
        runCurrent()
        assertTrue(timeline.uiState.value.statuses.none { it.favourited })

        detailResponse.complete(Result.success(StatusDetail(initial.copy(boostsCount = 5), listOf(testStatus("parent")), emptyList())))
        runCurrent()
        assertEquals(listOf(5L, 5L), timeline.uiState.value.statuses.map { it.boostsCount })
        model.setReaction(initial, "👍")
        runCurrent()
        model.viewModelScope.cancel()
        val updatedReactions = listOf(EmojiReaction("👍", 1, true, null, emptySet()))
        reaction.complete(Result.success(initial.copy(reactions = updatedReactions)))
        advanceUntilIdle()
        val rows = timeline.uiState.value.statuses
        assertEquals(listOf("boost-wrapper", initial.timelineId), rows.map { it.timelineId })
        assertEquals(initial.author, rows.first().boostedBy)
        assertEquals(listOf(updatedReactions, updatedReactions), rows.map { it.reactions })
        assertEquals(listOf(5L, 5L), rows.map { it.boostsCount })
    }

    @Test fun allEntryPointsShowCachedConversationWhileFetchingAndKeepItOnFailure() = runTest(dispatcher) {
        var requests = 0
        var cacheReads = 0
        val cached = StatusDetail(testStatus("reply"), listOf(testStatus("root")), emptyList())
        val updated = cached.copy(descendants = listOf(testStatus("new-reply")))
        val initialResponse = CompletableDeferred<Result<StatusDetail>>()
        val repository = object : Repository() {
            override fun getCachedConversation(session: AccountSession, sourceStatusId: String, statusId: String): StatusDetail {
                assertEquals(testAccount, session)
                assertEquals(if (cacheReads == 0) "reply" else "root", sourceStatusId)
                assertEquals("reply", statusId)
                cacheReads++
                return cached
            }
            override suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> {
                return if (++requests <= 2) initialResponse.await() else Result.success(updated)
            }
        }
        val homeDetail = own(StatusDetailViewModel("reply", repository, auth))
        runCurrent()
        assertEquals(1, requests)
        assertEquals(1, cacheReads)
        assertEquals("reply", homeDetail.uiState.value.detail?.status?.statusId)
        assertEquals(listOf("root"), homeDetail.uiState.value.detail?.ancestors?.map { it.statusId })
        assertFalse(homeDetail.uiState.value.showInitialLoadingIndicator)
        val model = own(StatusDetailViewModel("reply", repository, auth, conversationSourceId = "root"))
        runCurrent()
        val displayedCache = model.uiState.value.detail
        assertEquals("reply", displayedCache?.status?.statusId)
        assertEquals(listOf("root"), displayedCache?.ancestors?.map { it.statusId })
        assertTrue(model.uiState.value.isLoading)
        assertFalse(model.uiState.value.showInitialLoadingIndicator)
        assertEquals(2, requests)
        assertEquals(2, cacheReads)

        initialResponse.complete(Result.failure(IllegalStateException("offline")))
        advanceUntilIdle()
        assertEquals(displayedCache, model.uiState.value.detail)
        assertEquals("offline", model.uiState.value.errorMessage)
        assertNotNull(model.uiState.value.actionMessage)
        assertFalse(model.uiState.value.isLoading)

        model.retry(); advanceUntilIdle()
        assertEquals(listOf("new-reply"), model.uiState.value.detail?.descendants?.map { it.statusId })
        assertEquals(3, requests)
        assertEquals(2, cacheReads)
        assertFalse(model.uiState.value.showInitialLoadingIndicator)
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

    @Test fun replySuccessReloadsTheOpenConversationAndSupersedesAnInFlightRefresh() = runTest(dispatcher) {
        val cached = StatusDetail(testStatus("root"), emptyList(), listOf(testStatus("parent")))
        val posted = testStatus("new-reply").copy(inReplyToId = "parent")
        val staleResponse = CompletableDeferred<Result<StatusDetail>>()
        var requests = 0
        val repository = object : Repository() {
            override fun getCachedConversation(session: AccountSession, sourceStatusId: String, statusId: String) = cached
            override suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> =
                if (++requests == 1) withContext(NonCancellable) { staleResponse.await() }
                else Result.success(cached.copy(descendants = cached.descendants + posted))
        }
        val model = own(StatusDetailViewModel("root", repository, auth))
        runCurrent()
        model.onReplyPosted("unrelated")
        runCurrent()
        assertEquals(1, requests)

        model.onReplyPosted("parent")
        runCurrent()
        staleResponse.complete(Result.success(cached))
        advanceUntilIdle()

        assertEquals(2, requests)
        assertEquals(listOf("parent", "new-reply"), model.uiState.value.detail!!.descendants.map { it.statusId })
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
