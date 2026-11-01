package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PollVotingTest : ScreenViewModelTestBase() {
    private val poll = StatusPoll("poll", null, false, true, 0, null, false, emptySet(),
        listOf(PollOption("a", null), PollOption("b", null)))
    private val status = testStatus().copy(poll = poll)

    @Test fun repeatedPressesSendOnceAndFailureAllowsRetry() = runTest(dispatcher) {
        for (profile in listOf(false, true)) {
            var calls = 0
            var reply = CompletableDeferred<Result<TimelineStatus>>()
            val repository = object : ScreenRepositoryFake() {
                override suspend fun votePoll(session: AccountSession, statusId: String, pollId: String, choices: Set<Int>): Result<TimelineStatus> {
                    calls++
                    return reply.await()
                }
            }
            val browsing = BrowsingSession().apply { activate(testAccount) }
            val actions = own(StatusActionsViewModel(repository, browsing))
            val model = own(ProfileViewModel(repository, browsing))
            advanceUntilIdle()
            fun vote() { if (profile) model.votePoll(status, setOf(0)) else actions.votePoll(status, setOf(0)) }
            fun pending() = if (profile) model.uiState.value.votingPollIds else actions.uiState.value.votingPollIds
            vote(); vote()
            runCurrent()
            assertEquals(1, calls)
            assertEquals(setOf("poll"), pending())
            reply.complete(Result.failure(IllegalStateException("offline")))
            advanceUntilIdle()
            assertTrue(pending().isEmpty())
            reply = CompletableDeferred()
            vote(); runCurrent()
            assertEquals(2, calls)
            reply.complete(Result.success(status.copy(poll = poll.copy(voted = true, ownVotes = setOf(0)))))
            advanceUntilIdle()
            assertTrue(pending().isEmpty())
        }
    }

    @Test fun accountSwitchRejectsAnOldVoteResponseAndDoesNotClearNewPendingState() = runTest(dispatcher) {
        val oldReply = CompletableDeferred<Result<TimelineStatus>>()
        val newReply = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun votePoll(session: AccountSession, statusId: String, pollId: String, choices: Set<Int>) =
                if (session == testAccount) withContext(NonCancellable) { oldReply.await() } else newReply.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()
        model.votePoll(status, setOf(0)); runCurrent()
        browsing.activate(secondAccount); runCurrent()
        assertTrue(model.uiState.value.votingPollIds.isEmpty())
        model.votePoll(status, setOf(1)); runCurrent()
        oldReply.complete(Result.success(status)); runCurrent()
        assertEquals(setOf("poll"), model.uiState.value.votingPollIds)
        assertNull(model.uiState.value.actionMessage)
        newReply.complete(Result.failure(IllegalStateException("offline"))); advanceUntilIdle()
        assertTrue(model.uiState.value.votingPollIds.isEmpty())
    }
}
