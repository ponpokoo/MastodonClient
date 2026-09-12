package io.github.ponpokoo.mastodonclient.feature.timeline

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

    private lateinit var mainViewModel: MainSessionViewModel
    private fun createTimeline(repository: TimelineRepository, auth: AuthRepository): TimelineViewModel {
        mainViewModel = MainSessionViewModel(auth, repository)
        return TimelineViewModel(repository, mainViewModel.browsing)
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
