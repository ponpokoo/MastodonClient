package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.lifecycle.SavedStateHandle
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun restoresSessionThenLoadsAndAppendsTimelinePages() = runTest(dispatcher) {
        val repository = FakeTimelineRepository()
        val viewModel = createTimeline(repository, FakeAuthRepository(SESSION))

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertEquals(listOf("first"), viewModel.uiState.value.statuses.map { it.timelineId })

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertEquals(listOf(null, "first"), repository.requestedMaxIds)
        assertEquals(
            listOf("first", "second"),
            viewModel.uiState.value.statuses.map { it.timelineId },
        )
    }

    @Test
    fun refreshReportsHowManyNewStatusesWereFetched() = runTest(dispatcher) {
        val repository = FakeTimelineRepository()
        val viewModel = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.refreshNewStatusCount)
        assertEquals(listOf("new"), viewModel.uiState.value.statuses.map { it.timelineId })
    }

    @Test
    fun accountSwitchIgnoresOldRefreshEvenWhenCancellationIsIgnored() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<TimelinePage>>()
        var calls = 0
        val second = SESSION.copy(sessionId = "second", instanceUrl = "https://other.social")
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                calls++
                if (calls == 2) return withContext(NonCancellable) { delayed.await() }
                return Result.success(TimelinePage(listOf(status(session.sessionId)), null, true))
            }
        }
        val auth = object : AuthRepository by FakeAuthRepository(SESSION) {
            override suspend fun switchSession(sessionId: String) = second
            override suspend fun getSessions() = listOf(SESSION, second)
        }
        val viewModel = createTimeline(repository, auth)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()
        mainViewModel.switchAccount(second.sessionId)
        advanceUntilIdle()
        delayed.complete(Result.success(TimelinePage(listOf(status("old")), null, true)))
        advanceUntilIdle()
        assertEquals(second, mainViewModel.uiState.value.session)
        assertEquals(listOf("second"), viewModel.uiState.value.statuses.map { it.statusId })
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun feedSwitchIgnoresFailureFromPreviousPage() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(listOf(status("home")), "cursor", false))
            override suspend fun getTimeline(session: AccountSession, feed: TimelineFeed, maxId: String?, limit: Int): Result<TimelinePage> {
                if (maxId != null) return withContext(NonCancellable) { delayed.await() }
                return Result.success(TimelinePage(listOf(status(if (feed == TimelineFeed.Home) "home" else "local")), "cursor", false))
            }
        }
        val viewModel = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        viewModel.loadNextPage()
        advanceUntilIdle()
        viewModel.selectFeed(TimelineFeed.Local)
        advanceUntilIdle()
        delayed.complete(Result.failure(IllegalStateException("old failure")))
        advanceUntilIdle()
        assertEquals(TimelineFeed.Local, viewModel.uiState.value.selectedFeed)
        assertEquals(listOf("local"), viewModel.uiState.value.statuses.map { it.statusId })
        assertEquals(null, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoadingMore)
    }

    @Test
    fun logoutIgnoresPendingRefreshFailure() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<TimelinePage>>()
        var calls = 0
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                if (++calls > 1) return withContext(NonCancellable) { delayed.await() }
                return Result.success(TimelinePage(listOf(status("home")), null, true))
            }
        }
        val auth = object : AuthRepository by FakeAuthRepository(SESSION) {
            var loggedOut = false
            override suspend fun restoreSession() = if (loggedOut) null else SESSION
            override suspend fun getSessions() = listOfNotNull(restoreSession())
            override suspend fun logout() { loggedOut = true }
        }
        val viewModel = createTimeline(repository, auth)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()
        mainViewModel.logout()
        advanceUntilIdle()
        delayed.complete(Result.failure(IllegalStateException("stale error")))
        advanceUntilIdle()
        assertTrue(mainViewModel.uiState.value.requiresLogin)
        assertTrue(viewModel.uiState.value.statuses.isEmpty())
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun cancelledSessionRestoreCannotReturnToLoginAfterSwitch() = runTest(dispatcher) {
        val delayed = CompletableDeferred<AccountSession?>()
        val second = SESSION.copy(sessionId = "second")
        val auth = object : AuthRepository by FakeAuthRepository(SESSION) {
            override suspend fun getSessions() = listOf(SESSION, second)
            override suspend fun restoreSession() = withContext(NonCancellable) { delayed.await() }
            override suspend fun switchSession(sessionId: String) = second
        }
        val viewModel = createTimeline(FakeTimelineRepository(), auth)
        advanceUntilIdle()
        mainViewModel.switchAccount(second.sessionId)
        advanceUntilIdle()
        delayed.complete(null)
        advanceUntilIdle()
        assertEquals(second, mainViewModel.uiState.value.session)
        assertFalse(mainViewModel.uiState.value.requiresLogin)
        assertFalse(viewModel.uiState.value.statuses.isEmpty())
    }

    @Test
    fun streamCountsUniqueUnreadPostsAndDoesNotCountEdits() = runTest(dispatcher) {
        val model = createTimeline(FakeTimelineRepository(), FakeAuthRepository(SESSION))
        advanceUntilIdle()
        model.updateViewport(false)
        suspend fun emit(event: io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent) {
            mainViewModel.browsing.publish(mainViewModel.browsing.snapshot.value,
                io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession.Change.Stream(event))
            advanceUntilIdle()
        }
        emit(io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.StatusAdded(status("live")))
        emit(io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.StatusAdded(status("live")))
        emit(io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.StatusAdded(status("first").copy(contentHtml = "edited"), isEdit = true))
        emit(io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.StatusAdded(status("unseen edit"), isEdit = true))
        assertEquals(setOf("live"), model.uiState.value.unseenStreamIds)
        assertEquals(listOf("live", "first"), model.uiState.value.statuses.map { it.timelineId })
        assertEquals("edited", model.uiState.value.statuses.last().contentHtml)
        model.updateViewport(true)
        assertTrue(model.uiState.value.unseenStreamIds.isEmpty())
        emit(io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.StatusAdded(status("top")))
        assertTrue(model.uiState.value.unseenStreamIds.isEmpty())
        assertEquals(1, model.uiState.value.streamAtTopCount)
        val firstRequest = model.uiState.value.streamAutoScrollId
        emit(io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.StatusAdded(status("top2")))
        model.consumeStreamNotice(firstRequest)
        assertEquals(2, model.uiState.value.streamAtTopCount)
        model.consumeStreamNotice(model.uiState.value.streamAutoScrollId)
        assertEquals(null, model.uiState.value.streamAtTopCount)
    }

    @Test
    fun refreshKeepsStreamingArrivalAndFeedChangeClearsUnreadCount() = runTest(dispatcher) {
        val pending = CompletableDeferred<Result<TimelinePage>>()
        var calls = 0
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                if (++calls == 2) return pending.await()
                return Result.success(TimelinePage(listOf(status("first")), null, true))
            }
        }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        model.updateViewport(false)
        model.refresh()
        advanceUntilIdle()
        mainViewModel.browsing.publish(mainViewModel.browsing.snapshot.value,
            io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession.Change.Stream(
                io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.StatusAdded(status("live"))))
        advanceUntilIdle()
        pending.complete(Result.success(TimelinePage(listOf(status("http"), status("first")), null, true)))
        advanceUntilIdle()
        assertEquals(listOf("live", "http", "first"), model.uiState.value.statuses.map { it.timelineId })
        assertEquals(setOf("live"), model.uiState.value.unseenStreamIds)
        assertEquals(1, model.uiState.value.refreshNewStatusCount)
        model.selectFeed(TimelineFeed.Local)
        advanceUntilIdle()
        assertTrue(model.uiState.value.unseenStreamIds.isEmpty())
        assertEquals(null, model.uiState.value.streamAtTopCount)
    }
    @Test
    fun savedViewportRestoresNearbyStatusesAndCanReturnToLatest() = runTest(dispatcher) {
        val requestedMaxIds = mutableListOf<String?>()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                requestedMaxIds += maxId
                val statuses = if (maxId == "before") listOf(status("anchor"), status("older"))
                    else listOf(status("latest"), status("anchor"), status("before"))
                return Result.success(TimelinePage(statuses, null, true))
            }
        }
        val savedState = SavedStateHandle()
        val first = createTimeline(repository, FakeAuthRepository(SESSION), savedState)
        advanceUntilIdle()
        first.saveViewport("anchor", "before", 18)

        val restored = createTimeline(repository, FakeAuthRepository(SESSION), savedState)
        advanceUntilIdle()
        assertEquals(listOf(null, "before"), requestedMaxIds)
        assertEquals(listOf("anchor", "older"), restored.uiState.value.statuses.map { it.timelineId })
        assertTrue(restored.uiState.value.isResumedWindow)
        assertEquals("anchor", restored.uiState.value.resumeAnchorId)
        assertEquals(18, restored.uiState.value.resumeOffset)

        restored.consumeResumeAnchor()
        restored.goToLatest()
        advanceUntilIdle()
        assertEquals(listOf(null, "before", null), requestedMaxIds)
        assertFalse(restored.uiState.value.isResumedWindow)
        assertEquals("latest", restored.uiState.value.resumeAnchorId)
    }

    @Test
    fun savedViewportIsNotUsedForAnotherAccount() = runTest(dispatcher) {
        val repository = FakeTimelineRepository()
        val savedState = SavedStateHandle()
        val first = createTimeline(repository, FakeAuthRepository(SESSION), savedState)
        advanceUntilIdle()
        first.saveViewport("first", null, 12)

        val other = SESSION.copy(sessionId = "other", instanceUrl = "https://other.social")
        val restored = createTimeline(repository, FakeAuthRepository(other), savedState)
        advanceUntilIdle()

        assertEquals(listOf(null, null), repository.requestedMaxIds)
        assertFalse(restored.uiState.value.isResumedWindow)
        assertEquals(null, restored.uiState.value.resumeAnchorId)
    }

    private lateinit var mainViewModel: MainSessionViewModel
    private fun createTimeline(
        repository: TimelineRepository,
        auth: AuthRepository,
        savedState: SavedStateHandle = SavedStateHandle(),
    ): TimelineViewModel {
        mainViewModel = MainSessionViewModel(auth, repository)
        return TimelineViewModel(repository, mainViewModel.browsing, savedState)
    }

    private class FakeTimelineRepository : TimelineRepository {
        val requestedMaxIds = mutableListOf<String?>()

        override suspend fun getHomeTimeline(
            session: AccountSession,
            maxId: String?,
            limit: Int,
        ): Result<TimelinePage> {
            requestedMaxIds += maxId
            return if (maxId == null) {
                val id = if (requestedMaxIds.count { it == null } == 1) "first" else "new"
                Result.success(TimelinePage(listOf(status(id)), id, endReached = false))
            } else {
                Result.success(TimelinePage(listOf(status("second")), "second", endReached = true))
            }
        }
    }

    private class FakeAuthRepository(
        private val session: AccountSession?,
    ) : AuthRepository {
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.failure<String>(
            UnsupportedOperationException(),
        )

        override suspend fun completeAuthorization(callbackUrl: String) =
            Result.failure<AccountSession>(UnsupportedOperationException())

        override suspend fun restoreSession(): AccountSession? = session
        override suspend fun logout() = Unit
    }

    private companion object {
        val SESSION = AccountSession(
            sessionId = "session",
            instanceUrl = "https://example.social",
            accountId = "me",
            username = "me",
            displayName = "Me",
            avatarUrl = "",
            accessToken = "token",
        )

        fun status(id: String) = TimelineStatus(
            timelineId = id,
            statusId = id,
            createdAt = "2026-09-08T00:00:00Z",
            author = StatusAuthor("author", "Author", "author@example.social", ""),
            boostedBy = null,
            contentHtml = "<p>Hello</p>",
            spoilerText = "",
            sensitive = false,
            visibility = "public",
            url = null,
            repliesCount = 0,
            boostsCount = 0,
            favouritesCount = 0,
            mediaAttachments = emptyList(),
        )
    }
}
