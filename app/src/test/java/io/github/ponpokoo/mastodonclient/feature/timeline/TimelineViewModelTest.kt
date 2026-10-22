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
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(setOf("new"), viewModel.uiState.value.pendingNewStatusIds)
        viewModel.updateViewport(true)
        assertTrue(viewModel.uiState.value.pendingNewStatusIds.isEmpty())
        assertEquals(listOf("new"), viewModel.uiState.value.statuses.map { it.timelineId })
    }

    @Test fun refreshPendingIdsSurviveAnotherRefreshAndFailureAcrossAllFeeds() = runTest(dispatcher) {
        for (feed in TimelineFeed.entries) {
            var rows = listOf(status("old"))
            var fail = false
            val repository = object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    if (fail) Result.failure(java.io.IOException("offline")) else Result.success(TimelinePage(rows, null, true))
                override suspend fun getTimeline(session: AccountSession, feed: TimelineFeed, maxId: String?, limit: Int) =
                    getHomeTimeline(session, maxId, limit)
            }
            val model = createTimeline(repository, FakeAuthRepository(SESSION))
            advanceUntilIdle()
            if (feed != TimelineFeed.Home) { model.selectFeed(feed); advanceUntilIdle() }
            rows = listOf(status("new-a"), status("old"))
            model.refresh(); advanceUntilIdle(); model.consumeRefreshResult()
            rows = listOf(status("new-b"), status("old"))
            model.refresh(); advanceUntilIdle()
            assertEquals(setOf("new-a", "new-b"), model.uiState.value.pendingNewStatusIds)
            fail = true
            model.refresh(); advanceUntilIdle()
            assertEquals(setOf("new-a", "new-b"), model.uiState.value.pendingNewStatusIds)
            mainViewModel.browsing.publish(mainViewModel.browsing.snapshot.value,
                BrowsingSession.Change.StatusDeleted("new-a"))
            advanceUntilIdle()
            assertEquals(setOf("new-b"), model.uiState.value.pendingNewStatusIds)
            model.updateViewport(true)
            assertTrue(model.uiState.value.pendingNewStatusIds.isEmpty())
        }
    }

    @Test fun refreshAtVisibleTopReportsPassiveResultUnlessWindowModeKeepsTheOldAnchor() = runTest(dispatcher) {
        for (keepPosition in listOf(false, true)) {
            var calls = 0
            val repository = object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(if (++calls == 1) listOf(status("first"))
                        else listOf(status("new"), status("first")), null, true))
            }
            val model = createTimeline(repository, FakeAuthRepository(SESSION))
            model.setKeepPositionOnRefresh(keepPosition)
            advanceUntilIdle()
            model.updateViewport(true)
            model.refresh(); advanceUntilIdle()
            assertEquals(1, model.uiState.value.newItemsNoticeResult?.count)
            assertEquals(if (keepPosition) setOf("new") else emptySet<String>(), model.uiState.value.pendingNewStatusIds)
            model.consumeRefreshResult()
            assertEquals(1, model.uiState.value.newItemsNoticeResult?.count)
        }
    }

    @Test
    fun refreshShowsOwnActivityWithoutCountingItAsNew() = runTest(dispatcher) {
        val me = StatusAuthor("me", "Me", "me@example.social", "")
        val other = StatusAuthor("other", "Other", "other@example.social", "")
        var calls = 0
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                val statuses = if (++calls == 1) listOf(status("first")) else listOf(
                    status("mine").copy(author = me),
                    status("other-boost").copy(author = me, boostedBy = other),
                    status("my-boost").copy(boostedBy = me),
                    status("other"),
                    status("first"),
                )
                return Result.success(TimelinePage(statuses, null, true))
            }
        }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()

        model.refresh()
        advanceUntilIdle()

        assertEquals(2, model.uiState.value.refreshNewStatusCount)
        assertEquals(listOf("mine", "other-boost", "my-boost", "other", "first"),
            model.uiState.value.statuses.map { it.timelineId })
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
    fun ownStreamActivityAppearsWithoutIncreasingNewCount() = runTest(dispatcher) {
        val model = createTimeline(FakeTimelineRepository(), FakeAuthRepository(SESSION))
        advanceUntilIdle()
        val me = StatusAuthor("me", "Me", "me@example.social", "")
        val other = StatusAuthor("other", "Other", "other@example.social", "")
        suspend fun emit(post: TimelineStatus) {
            mainViewModel.browsing.publish(mainViewModel.browsing.snapshot.value,
                io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession.Change.Stream(
                    io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent.StatusAdded(post)))
            advanceUntilIdle()
        }

        model.updateViewport(false)
        emit(status("mine").copy(author = me))
        emit(status("my-boost").copy(boostedBy = me))
        assertEquals(listOf("my-boost", "mine", "first"), model.uiState.value.statuses.map { it.timelineId })
        assertTrue(model.uiState.value.unseenStreamIds.isEmpty())

        emit(status("other-boost").copy(author = me, boostedBy = other))
        assertEquals(setOf("other-boost"), model.uiState.value.unseenStreamIds)

        model.updateViewport(true)
        emit(status("mine-at-top").copy(author = me))
        assertEquals("mine-at-top", model.uiState.value.statuses.first().timelineId)
        assertEquals(null, model.uiState.value.streamAtTopCount)
        val scrollRequest = model.uiState.value.streamAutoScrollId
        assertTrue(scrollRequest > 0)
        model.consumeStreamNotice(scrollRequest)
        assertEquals(0L, model.uiState.value.streamAutoScrollId)

        emit(status("other-at-top"))
        emit(status("mine-again").copy(author = me))
        assertEquals(1, model.uiState.value.streamAtTopCount)
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
        assertEquals(setOf("live", "http"), model.uiState.value.pendingNewStatusIds)
        model.selectFeed(TimelineFeed.Local)
        advanceUntilIdle()
        assertTrue(model.uiState.value.unseenStreamIds.isEmpty())
        assertEquals(null, model.uiState.value.streamAtTopCount)
    }
    @Test
    fun refreshUsesApiOrderForPreviouslyUnreadStreamingRows() =
        assertRefreshUsesApiOrder(arrivesDuringRefresh = false, followingTop = false)

    @Test
    fun refreshUsesApiOrderForUnreadStreamingRowsArrivingDuringRequest() =
        assertRefreshUsesApiOrder(arrivesDuringRefresh = true, followingTop = false)

    @Test
    fun refreshUsesApiOrderForReadStreamingRowsArrivingDuringRequest() =
        assertRefreshUsesApiOrder(arrivesDuringRefresh = true, followingTop = true)

    private fun assertRefreshUsesApiOrder(arrivesDuringRefresh: Boolean, followingTop: Boolean) = runTest(dispatcher) {
        val initial = status("initial").copy(createdAt = "2026-10-16T11:57:00Z")
        val older = status("older").copy(createdAt = "2026-10-16T11:58:00Z")
        val newer = status("newer").copy(createdAt = "2026-10-16T11:59:00Z")
        val pending = CompletableDeferred<Result<TimelinePage>>()
        var calls = 0
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> =
                if (++calls == 1) Result.success(TimelinePage(listOf(initial), "initial", false)) else pending.await()
        }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        model.updateViewport(followingTop)
        if (arrivesDuringRefresh) {
            model.refresh()
            advanceUntilIdle()
        }
        mainViewModel.browsing.publish(mainViewModel.browsing.snapshot.value,
            BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(older)))
        advanceUntilIdle()
        assertEquals(if (followingTop) emptySet<String>() else setOf("older"), model.uiState.value.unseenStreamIds)
        if (!arrivesDuringRefresh) {
            model.refresh()
            advanceUntilIdle()
        }

        pending.complete(Result.success(TimelinePage(listOf(newer, older, initial), "initial", false)))
        advanceUntilIdle()

        assertEquals(listOf("newer", "older", "initial"), model.uiState.value.statuses.map { it.timelineId })
        assertEquals("initial", model.uiState.value.nextMaxId)
        assertEquals(1, model.uiState.value.refreshNewStatusCount)
        assertEquals(if (followingTop) emptySet<String>() else setOf("older", "newer"),
            model.uiState.value.pendingNewStatusIds)
    }

    @Test
    fun refreshKeepsPreviouslyUnreadStreamingRowMissingFromResponseAndDistinctBoostRow() = runTest(dispatcher) {
        val original = status("original")
        val boost = original.copy(timelineId = "boost", boostedBy = StatusAuthor("booster", "Booster", "booster@example.social", ""))
        var calls = 0
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> =
                Result.success(TimelinePage(if (++calls == 1) listOf(original) else listOf(boost, original), "original", false))
        }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        model.updateViewport(false)
        mainViewModel.browsing.publish(mainViewModel.browsing.snapshot.value,
            BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(status("unseen"))))
        advanceUntilIdle()

        model.refresh()
        advanceUntilIdle()

        assertEquals(listOf("unseen", "boost", "original"), model.uiState.value.statuses.map { it.timelineId })
        assertEquals(listOf("original", "original"), model.uiState.value.statuses.drop(1).map { it.statusId })
        assertEquals(boost.boostedBy, model.uiState.value.statuses[1].boostedBy)
        assertEquals(listOf(original.createdAt, original.createdAt), model.uiState.value.statuses.drop(1).map { it.createdAt })
        assertEquals(setOf("unseen"), model.uiState.value.unseenStreamIds)
        assertEquals("original", model.uiState.value.nextMaxId)
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

    @Test fun cachedHomeAppearsBeforeSingleNetworkRequestAndGetsFreshCounts() = runTest(dispatcher) {
        val pending = CompletableDeferred<Result<TimelinePage>>()
        val repository = CachedHomeRepository()
        repository.network = { _, _ -> pending.await() }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        assertEquals(listOf("cached"), model.uiState.value.statuses.map { it.timelineId })
        assertFalse(model.uiState.value.isInitialLoading)
        assertTrue(model.uiState.value.isHomeSyncing)
        assertEquals(listOf(null to 20), repository.requests)
        pending.complete(Result.success(TimelinePage(listOf(status("cached").copy(favouritesCount = 9)), "cached", false)))
        advanceUntilIdle()
        assertEquals(9L, model.uiState.value.statuses.single().favouritesCount)
        assertFalse(model.uiState.value.isShowingSavedStatuses)
        assertEquals(1, repository.writes.size)
    }

    @Test fun offlineHomePagesUseCacheAndStopAtCacheBoundaryWithoutTimeoutOrRepeatedRequests() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        repository.online = false
        repository.pages["cached"] = TimelinePage(listOf(status("older")), "older", false)
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        model.loadNextPage()
        advanceUntilIdle()
        assertEquals(listOf("cached", "older"), model.uiState.value.statuses.map { it.timelineId })
        repeat(3) { model.loadNextPage(); advanceUntilIdle() }
        assertTrue(repository.requests.isEmpty())
        assertEquals(null, model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.endReached)
        assertTrue(model.uiState.value.isShowingSavedStatuses)
        model.refresh()
        advanceUntilIdle()
        assertEquals(1, repository.requests.size)
        assertEquals("timeout", model.uiState.value.errorMessage)
    }

    @Test fun automaticFailureKeepsCachedHomeButEmptyCacheShowsRetryError() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        assertEquals("cached", model.uiState.value.statuses.single().timelineId)
        assertEquals(null, model.uiState.value.errorMessage)
        assertTrue(model.uiState.value.isShowingSavedStatuses)
        repository.pages.clear()
        val emptyModel = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        assertEquals("timeout", emptyModel.uiState.value.errorMessage)
        assertFalse(emptyModel.uiState.value.isInitialLoading)
    }

    @Test fun cachedNextPageRevalidatesSameCursorAndReplacesCountsWithoutDuplicates() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        val pending = CompletableDeferred<Result<TimelinePage>>()
        repository.pages["cached"] = TimelinePage(listOf(status("older")), "older", false)
        repository.network = { cursor, _ -> if (cursor == null) Result.failure(java.io.IOException("timeout")) else pending.await() }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        model.loadNextPage()
        advanceUntilIdle()
        assertEquals(listOf("cached", "older"), model.uiState.value.statuses.map { it.timelineId })
        pending.complete(Result.success(TimelinePage(listOf(status("older").copy(favouritesCount = 7)), "older", false)))
        advanceUntilIdle()
        assertEquals(listOf(null to 20, "cached" to 20), repository.requests)
        assertEquals(7L, model.uiState.value.statuses.last().favouritesCount)
        assertFalse(model.uiState.value.endReached)
    }

    @Test fun pendingRevalidationCannotUndoActionDeletionOrStreamingArrival() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        val pending = CompletableDeferred<Result<TimelinePage>>()
        repository.pages[null] = TimelinePage(listOf(status("cached"), status("deleted")), "deleted", false)
        repository.network = { _, _ -> pending.await() }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        val votedPoll = io.github.ponpokoo.mastodonclient.domain.model.StatusPoll("poll", null, false, false, 1, 1, true, setOf(0),
            listOf(io.github.ponpokoo.mastodonclient.domain.model.PollOption("yes", 1)))
        for (change in listOf(
            BrowsingSession.Change.StatusUpdated(status("cached").copy(favourited = true, favouritesCount = 1, poll = votedPoll)),
            BrowsingSession.Change.StatusDeleted("deleted"),
            BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(status("live"))),
        )) mainViewModel.browsing.publish(mainViewModel.browsing.snapshot.value, change)
        advanceUntilIdle()
        pending.complete(Result.success(TimelinePage(listOf(status("cached"), status("deleted")), "deleted", false)))
        advanceUntilIdle()
        assertEquals(listOf("live", "cached"), model.uiState.value.statuses.map { it.timelineId })
        assertTrue(model.uiState.value.statuses.last().favourited)
        assertEquals(votedPoll, model.uiState.value.statuses.last().poll)
        assertEquals(3, repository.changes.size)
        assertEquals(3, repository.writeChanges.single().size)
    }

    @Test fun switchingToPublicFeedDoesNotReadWriteOrApplyLateHomeResponse() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        val pending = CompletableDeferred<Result<TimelinePage>>()
        repository.network = { _, _ -> withContext(NonCancellable) { pending.await() } }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        model.selectFeed(TimelineFeed.Local)
        advanceUntilIdle()
        model.selectFeed(TimelineFeed.Federated)
        advanceUntilIdle()
        pending.complete(Result.success(TimelinePage(listOf(status("late")), null, true)))
        advanceUntilIdle()
        assertEquals(TimelineFeed.Federated, model.uiState.value.selectedFeed)
        assertEquals("Federated", model.uiState.value.statuses.single().timelineId)
        assertEquals(1, repository.cacheReads)
        assertTrue(repository.writes.isEmpty())
        assertFalse(model.uiState.value.isShowingSavedStatuses)
    }

    @Test fun savedHomeViewportAndOffsetRestoreOfflineFromCache() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        repository.online = false
        val saved = SavedStateHandle(mapOf(
            "timeline_viewport_session" to SESSION.sessionId, "timeline_viewport_feed" to "Home",
            "timeline_viewport_anchor" to "anchor", "timeline_viewport_before" to "before", "timeline_viewport_offset" to 17,
        ))
        repository.pages["before"] = TimelinePage(listOf(status("anchor"), status("older")), "older", false)
        val model = createTimeline(repository, FakeAuthRepository(SESSION), saved)
        advanceUntilIdle()
        assertEquals("anchor", model.uiState.value.resumeAnchorId)
        assertEquals(17, model.uiState.value.resumeOffset)
        assertTrue(model.uiState.value.isResumedWindow)
        assertTrue(repository.requests.isEmpty())
    }

    @Test fun homeRevalidationDoesNotRestoreConsumedAnchorAfterReaderScrolls() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        val pending = CompletableDeferred<Result<TimelinePage>>()
        repository.network = { _, _ -> pending.await() }
        repository.pages["before"] = TimelinePage(listOf(status("anchor"), status("older")), "older", false)
        val saved = SavedStateHandle(mapOf(
            "timeline_viewport_session" to SESSION.sessionId, "timeline_viewport_feed" to "Home",
            "timeline_viewport_anchor" to "anchor", "timeline_viewport_before" to "before", "timeline_viewport_offset" to 17,
        ))
        val model = createTimeline(repository, FakeAuthRepository(SESSION), saved)
        advanceUntilIdle()
        model.consumeResumeAnchor()
        model.saveViewport("older", "anchor", 33)
        pending.complete(Result.success(TimelinePage(listOf(status("anchor"), status("older")), "older", false)))
        advanceUntilIdle()
        assertEquals(null, model.uiState.value.resumeAnchorId)
        assertEquals(0, model.uiState.value.resumeOffset)
        assertEquals("older", saved.get<String>("timeline_viewport_anchor"))
        assertEquals(33, saved.get<Int>("timeline_viewport_offset"))
    }

    @Test fun offlineViewportOutsideCacheFallsBackToSavedLatestHome() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        repository.online = false
        val saved = SavedStateHandle(mapOf(
            "timeline_viewport_session" to SESSION.sessionId, "timeline_viewport_feed" to "Home",
            "timeline_viewport_anchor" to "outside", "timeline_viewport_before" to "missing", "timeline_viewport_offset" to 17,
        ))
        val model = createTimeline(repository, FakeAuthRepository(SESSION), saved)
        advanceUntilIdle()
        assertEquals("cached", model.uiState.value.statuses.single().timelineId)
        assertFalse(model.uiState.value.isResumedWindow)
        assertEquals(null, model.uiState.value.errorMessage)
        assertTrue(model.uiState.value.isShowingSavedStatuses)
        assertTrue(repository.requests.isEmpty())
    }

    @Test fun cacheReadAndWriteFailuresDoNotPreventNetworkDisplay() = runTest(dispatcher) {
        val repository = object : TimelineRepository {
            override suspend fun getCachedHomeTimeline(session: AccountSession, maxId: String?, limit: Int, anchorId: String?): Result<TimelinePage?> = Result.failure(java.io.IOException("disk"))
            override suspend fun cacheHomeTimeline(session: AccountSession, maxId: String?, page: TimelinePage, changes: List<BrowsingSession.Change>): Result<Unit> = Result.failure(java.io.IOException("disk"))
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(listOf(status("network")), null, true))
        }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        assertEquals("network", model.uiState.value.statuses.single().timelineId)
        assertEquals(null, model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.isHomeSyncing)
    }

    @Test fun nextPageRevalidationStillUpdatesCachedRowsWhenCursorIsDeletedDuringRequest() = runTest(dispatcher) {
        val repository = CachedHomeRepository()
        val pending = CompletableDeferred<Result<TimelinePage>>()
        repository.pages["cached"] = TimelinePage(listOf(status("older")), "older", false)
        repository.network = { cursor, _ -> if (cursor == null) Result.failure(java.io.IOException("timeout")) else pending.await() }
        val model = createTimeline(repository, FakeAuthRepository(SESSION))
        advanceUntilIdle()
        model.loadNextPage()
        advanceUntilIdle()
        mainViewModel.browsing.publish(mainViewModel.browsing.snapshot.value, BrowsingSession.Change.StatusDeleted("cached"))
        advanceUntilIdle()
        pending.complete(Result.success(TimelinePage(listOf(status("older").copy(favouritesCount = 7)), "older", false)))
        advanceUntilIdle()
        assertEquals(listOf("older"), model.uiState.value.statuses.map { it.timelineId })
        assertEquals(7L, model.uiState.value.statuses.single().favouritesCount)
    }

    @Test fun publicFeedRefreshRetainsDisconnectedViewportAndLoadsGapFromSameFeed() = runTest(dispatcher) {
        for (feed in listOf(TimelineFeed.Local, TimelineFeed.Federated)) {
            val requests = mutableListOf<Pair<TimelineFeed, String?>>()
            var latest = false
            val newIds = (1..20).map { "new-$it" }
            val repository = object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(emptyList(), null, true))
                override suspend fun getTimeline(session: AccountSession, feed: TimelineFeed, maxId: String?, limit: Int): Result<TimelinePage> {
                    requests += feed to maxId
                    val ids = when (maxId) {
                        "new-20" -> listOf("middle", "old")
                        "older" -> listOf("oldest")
                        else -> if (latest) newIds else listOf("old", "older")
                    }
                    return Result.success(TimelinePage(ids.map(::status), ids.last(), false))
                }
            }
            val model = createTimeline(repository, FakeAuthRepository(SESSION))
            model.setKeepPositionOnRefresh(true)
            advanceUntilIdle()
            model.selectFeed(feed); advanceUntilIdle()
            latest = true
            model.refresh(); advanceUntilIdle()
            assertEquals(newIds + listOf("old", "older"), model.uiState.value.statuses.map { it.timelineId })
            assertEquals(newIds.toSet(), model.uiState.value.pendingNewStatusIds)
            model.consumeRefreshResult()
            assertEquals(newIds.toSet(), model.uiState.value.pendingNewStatusIds)
            model.updateViewport(true)
            assertTrue(model.uiState.value.pendingNewStatusIds.isEmpty())
            assertEquals("older", model.uiState.value.nextMaxId)
            assertEquals("old", model.uiState.value.homeIndex!!.gaps.single().beforeId)
            model.loadGap("old"); advanceUntilIdle()
            assertEquals(feed to "new-20", requests.last())
            assertTrue(model.uiState.value.homeIndex!!.gaps.isEmpty())
            model.loadNextPage(); advanceUntilIdle()
            assertEquals(feed to "older", requests.last())
            assertEquals(newIds + listOf("middle", "old", "older", "oldest"), model.uiState.value.statuses.map { it.timelineId })
            model.setKeepPositionOnRefresh(false)
            model.refresh(); advanceUntilIdle()
            assertEquals(newIds, model.uiState.value.statuses.map { it.timelineId })
            assertNull(model.uiState.value.homeIndex)
        }
    }

    @Test fun automaticCacheRevalidationProbesShortDisconnectedPageOnce() = runTest(dispatcher) {
        for (connects in listOf(true, false)) {
            val requests = mutableListOf<String?>()
            val repository = object : TimelineRepository {
                override suspend fun getCachedHomeWindow(session: AccountSession) =
                    Result.success(TimelinePage(listOf(status("old")), "old", false))
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                    requests += maxId
                    val ids = if (maxId == null) listOf("new") else if (connects) listOf("middle", "old") else listOf("middle")
                    return Result.success(TimelinePage(ids.map(::status), ids.last(), false))
                }
            }
            val model = createTimeline(repository, FakeAuthRepository(SESSION))
            model.setKeepPositionOnRefresh(true); advanceUntilIdle()
            assertEquals(listOf(null, "new"), requests)
            assertEquals(connects, model.uiState.value.homeIndex!!.gaps.isEmpty())
            assertEquals(listOf("new", "middle", "old"), model.uiState.value.statuses.map { it.timelineId })
        }
    }

    @Test fun streamingProbesOnlyNewBoundaryAndRetainsGapOnFailureOrDisconnection() = runTest(dispatcher) {
        for (outcome in listOf("connected", "disconnected", "failure")) {
            val requests = mutableListOf<String?>()
            val repository = object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                    requests += maxId
                    if (maxId != null && outcome == "failure") return Result.failure(java.io.IOException("offline"))
                    val ids = if (maxId == null) listOf("old") else if (outcome == "connected") listOf("middle", "old") else listOf("middle")
                    return Result.success(TimelinePage(ids.map(::status), ids.last(), false))
                }
            }
            val main = MainSessionViewModel(FakeAuthRepository(SESSION), repository)
            val model = TimelineViewModel(repository, main.browsing)
            model.setKeepPositionOnRefresh(true); advanceUntilIdle()
            suspend fun emit(event: TimelineStreamEvent) = main.browsing.publish(main.browsing.snapshot.value, BrowsingSession.Change.Stream(event))
            emit(TimelineStreamEvent.Connected); advanceUntilIdle()
            assertEquals(listOf<String?>(null), requests)
            emit(TimelineStreamEvent.StatusAdded(status("new"))); advanceUntilIdle()
            assertEquals(listOf(null, "new"), requests)
            assertEquals(outcome == "connected", model.uiState.value.homeIndex!!.gaps.isEmpty())
            emit(TimelineStreamEvent.StatusAdded(status("newer"))); advanceUntilIdle()
            emit(TimelineStreamEvent.StatusAdded(status("newer"))); advanceUntilIdle()
            assertEquals(listOf(null, "new"), requests)
            assertTrue(model.uiState.value.statuses.any { it.timelineId == "old" })
            if (outcome == "failure") assertEquals("offline", model.uiState.value.gapErrors["old"])
        }
    }

    @Test fun automaticGapStaysHiddenUntilCheckFinishesButManualRetryStaysVisible() = runTest(dispatcher) {
        for (outcome in listOf("connected", "disconnected", "failure")) {
            var pending = CompletableDeferred<Result<TimelinePage>>()
            val repository = object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                    if (maxId != null) return pending.await()
                    return Result.success(TimelinePage(listOf(status("old")), "old", false))
                }
            }
            val main = MainSessionViewModel(FakeAuthRepository(SESSION), repository)
            val model = TimelineViewModel(repository, main.browsing)
            model.setKeepPositionOnRefresh(true); advanceUntilIdle()
            main.browsing.publish(main.browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.Connected))
            main.browsing.publish(main.browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(status("new"))))
            advanceUntilIdle()
            assertEquals("old", model.uiState.value.loadingGapId)
            assertEquals(1, model.uiState.value.homeIndex!!.gaps.size)
            assertTrue(model.uiState.value.displayRows().none { it is TimelineRow.Gap })
            val ids = if (outcome == "connected") listOf("middle", "old") else listOf("middle")
            pending.complete(if (outcome == "failure") Result.failure(java.io.IOException("offline"))
                else Result.success(TimelinePage(ids.map(::status), ids.last(), false)))
            advanceUntilIdle()
            assertNull(model.uiState.value.automaticGapCheck)
            assertEquals(if (outcome == "connected") 0 else 1,
                model.uiState.value.displayRows().count { it is TimelineRow.Gap })
            if (outcome != "connected") {
                pending = CompletableDeferred()
                model.loadGap("old"); advanceUntilIdle()
                assertEquals("old", model.uiState.value.loadingGapId)
                assertEquals(1, model.uiState.value.displayRows().count { it is TimelineRow.Gap })
                pending.complete(Result.success(TimelinePage(listOf(status("old")), "old", false)))
                advanceUntilIdle()
            }
        }
    }

    @Test fun streamingProbeRejectsResponseAfterFeedSwitch() = runTest(dispatcher) {
        val pending = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                if (maxId != null) return withContext(NonCancellable) { pending.await() }
                return Result.success(TimelinePage(listOf(status("old")), "old", false))
            }
            override suspend fun getTimeline(session: AccountSession, feed: TimelineFeed, maxId: String?, limit: Int) =
                if (feed == TimelineFeed.Home) getHomeTimeline(session, maxId, limit)
                else Result.success(TimelinePage(listOf(status("local")), "local", false))
        }
        val main = MainSessionViewModel(FakeAuthRepository(SESSION), repository)
        val model = TimelineViewModel(repository, main.browsing)
        model.setKeepPositionOnRefresh(true); advanceUntilIdle()
        main.browsing.publish(main.browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.Connected))
        main.browsing.publish(main.browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(status("new"))))
        advanceUntilIdle()
        assertEquals("old", model.uiState.value.loadingGapId)
        model.selectFeed(TimelineFeed.Local); advanceUntilIdle()
        pending.complete(Result.success(TimelinePage(listOf(status("late")), "late", false)))
        advanceUntilIdle()
        assertEquals(listOf("local"), model.uiState.value.statuses.map { it.timelineId })
        assertNull(model.uiState.value.loadingGapId)
        assertNull(model.uiState.value.automaticGapCheck)
    }

    @Test fun streamingWaitsForRefreshAndSkipsGapAlreadyConnectedByItsResponse() = runTest(dispatcher) {
        val pending = CompletableDeferred<Result<TimelinePage>>()
        val requests = mutableListOf<String?>()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                requests += maxId
                if (requests.size > 1) return pending.await()
                return Result.success(TimelinePage(listOf(status("old")), "old", false))
            }
        }
        val main = MainSessionViewModel(FakeAuthRepository(SESSION), repository)
        val model = TimelineViewModel(repository, main.browsing)
        model.setKeepPositionOnRefresh(true); advanceUntilIdle()
        model.refresh(); advanceUntilIdle()
        main.browsing.publish(main.browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.Connected))
        main.browsing.publish(main.browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(status("new"))))
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertNull(model.uiState.value.loadingGapId)
        pending.complete(Result.success(TimelinePage(listOf(status("new"), status("old")), "old", false)))
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertTrue(model.uiState.value.homeIndex!!.gaps.isEmpty())
    }

    @Test fun shortRefreshProbesOnlyOnceAndKeepsUnresolvedGapsForEveryFeed() = runTest(dispatcher) {
        for (feed in TimelineFeed.entries) for (outcome in listOf("connected", "disconnected", "failure", "empty", "overlap", "full-hidden", "zero")) {
            var latest = false
            val requests = mutableListOf<String?>()
            var savedWindow: TimelinePage? = null
            val repository = object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(listOf(status("old")), "old", false))
                override suspend fun getTimeline(session: AccountSession, feed: TimelineFeed, maxId: String?, limit: Int): Result<TimelinePage> {
                    requests += maxId
                    if (!latest) return Result.success(TimelinePage(listOf(status("old")), "old", false))
                    if (maxId != null && outcome == "failure") return Result.failure(java.io.IOException("offline"))
                    val ids = if (maxId == null) when (outcome) {
                        "overlap" -> listOf("new", "old")
                        "full-hidden" -> (1..20).map { "new-$it" }
                        "zero" -> emptyList()
                        else -> listOf("new")
                    } else when (outcome) {
                        "connected" -> listOf("middle", "old")
                        "empty" -> emptyList()
                        else -> listOf("middle")
                    }
                    return Result.success(TimelinePage(
                        (if (outcome == "full-hidden") ids.take(1) else ids).map(::status), ids.lastOrNull(), ids.isEmpty(),
                        io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineIndex(ids)))
                }
                override suspend fun saveHomeWindow(session: AccountSession, page: TimelinePage, anchorId: String?, changes: List<BrowsingSession.Change>): Result<Unit> {
                    savedWindow = page
                    return Result.success(Unit)
                }
            }
            val model = createTimeline(repository, FakeAuthRepository(SESSION))
            model.setKeepPositionOnRefresh(true); advanceUntilIdle()
            model.selectFeed(feed); advanceUntilIdle()
            requests.clear()
            latest = true
            model.refresh(); advanceUntilIdle()
            val probes = outcome !in listOf("overlap", "full-hidden", "zero")
            assertEquals("$feed $outcome", if (probes) listOf(null, "new") else listOf<String?>(null), requests)
            val state = model.uiState.value
            assertTrue(state.statuses.any { it.timelineId == "old" })
            assertFalse(state.isRefreshing)
            assertNull(state.errorMessage)
            assertEquals(outcome in listOf("connected", "overlap", "zero"), state.homeIndex!!.gaps.isEmpty())
            if (outcome == "disconnected") assertEquals("middle", state.homeIndex.gaps.single().maxId)
            if (outcome == "failure") assertFalse(state.homeIndex.gaps.single().unavailable)
            if (feed == TimelineFeed.Home) assertEquals(state.homeIndex, savedWindow!!.homeIndex)
        }
    }

    @Test fun switchingFeedCancelsShortRefreshProbeAndRejectsLateResponse() = runTest(dispatcher) {
        for (feed in TimelineFeed.entries) {
            val pending = CompletableDeferred<Result<TimelinePage>>()
            var latest = false
            var probes = 0
            val repository = object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(emptyList(), null, true))
                override suspend fun getTimeline(session: AccountSession, feed: TimelineFeed, maxId: String?, limit: Int): Result<TimelinePage> {
                    if (maxId != null) {
                        probes++
                        return withContext(NonCancellable) { pending.await() }
                    }
                    val id = if (latest) "new-$feed" else "old-$feed"
                    return Result.success(TimelinePage(listOf(status(id)), id, false))
                }
            }
            val model = createTimeline(repository, FakeAuthRepository(SESSION))
            model.setKeepPositionOnRefresh(true); advanceUntilIdle()
            model.selectFeed(feed); advanceUntilIdle()
            latest = true
            model.refresh(); advanceUntilIdle()
            assertTrue(model.uiState.value.isRefreshing)
            model.refresh(); model.loadNextPage(); advanceUntilIdle()
            assertEquals(1, probes)
            val replacement = if (feed == TimelineFeed.Home) TimelineFeed.Local else TimelineFeed.Home
            model.selectFeed(replacement); advanceUntilIdle()
            pending.complete(Result.success(TimelinePage(listOf(status("late")), "late", false)))
            advanceUntilIdle()
            assertEquals(replacement, model.uiState.value.selectedFeed)
            assertEquals(listOf("new-$replacement"), model.uiState.value.statuses.map { it.timelineId })
            assertFalse(model.uiState.value.isRefreshing)
        }
    }

    @Test fun keepPositionRefreshAndGapLoadingRetainOldRowsAndUseIndependentCursors() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<String?, Int>>()
        var latest = false
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                requests += maxId to limit
                val ids = when (maxId) {
                    "new" -> listOf("middle")
                    "middle" -> listOf("near-old", "old")
                    else -> if (latest) listOf("new") else listOf("old", "older")
                }
                return Result.success(TimelinePage(ids.map(::status), ids.last(), false))
            }
        }
        val saved = SavedStateHandle()
        val model = createTimeline(repository, FakeAuthRepository(SESSION), saved)
        model.setKeepPositionOnRefresh(true)
        advanceUntilIdle()
        model.saveViewport("old", null, 17)
        latest = true
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf("new", "middle", "old", "older"), model.uiState.value.statuses.map { it.timelineId })
        assertEquals("older", model.uiState.value.nextMaxId)
        assertNull(model.uiState.value.resumeAnchorId)
        model.saveViewport("old", "new", 17)
        assertEquals("", saved.get<String>("timeline_viewport_before"))
        assertEquals("middle", model.uiState.value.homeIndex!!.gaps.single().maxId)
        assertEquals(3, requests.size)
        model.loadGap("old")
        advanceUntilIdle()
        assertTrue(model.uiState.value.homeIndex!!.gaps.isEmpty())
        assertEquals(listOf(null to 20, null to 20, "new" to 20, "middle" to 20), requests)
        assertEquals(listOf("new", "middle", "near-old", "old", "older"), model.uiState.value.statuses.map { it.timelineId })
    }

    @Test fun disablingPositionRetentionReturnsToContiguousLatestRange() = runTest(dispatcher) {
        val model = createTimeline(FakeTimelineRepository(), FakeAuthRepository(SESSION))
        model.setKeepPositionOnRefresh(true)
        advanceUntilIdle()
        model.refresh(); advanceUntilIdle()
        assertEquals(1, model.uiState.value.homeIndex!!.gaps.size)
        model.setKeepPositionOnRefresh(false)
        assertNull(model.uiState.value.homeIndex)
        assertEquals(listOf("new", "second"), model.uiState.value.statuses.map { it.timelineId })
        assertEquals("second", model.uiState.value.nextMaxId)
        assertFalse(model.uiState.value.endReached)
    }

    @Test fun gapFailureIsRetryableAndAccountSwitchRejectsLateGapResponse() = runTest(dispatcher) {
        val pending = CompletableDeferred<Result<TimelinePage>>()
        val second = SESSION.copy(sessionId = "second")
        var latest = false
        var fail = true
        var gapCalls = 0
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                if (maxId != null) {
                    gapCalls++
                    if (fail) return Result.failure(java.io.IOException("offline"))
                    return withContext(NonCancellable) { pending.await() }
                }
                val id = if (session.sessionId == "second") "second" else if (latest) "new" else "old"
                return Result.success(TimelinePage(listOf(status(id)), id, false))
            }
        }
        val auth = object : AuthRepository by FakeAuthRepository(SESSION) {
            override suspend fun switchSession(sessionId: String) = second
            override suspend fun getSessions() = listOf(SESSION, second)
        }
        val model = createTimeline(repository, auth)
        model.setKeepPositionOnRefresh(true)
        advanceUntilIdle()
        latest = true
        model.refresh(); advanceUntilIdle()
        model.loadGap("old"); advanceUntilIdle()
        assertEquals("offline", model.uiState.value.gapErrors["old"])
        assertEquals(1, model.uiState.value.homeIndex!!.gaps.size)
        fail = false
        model.loadGap("old"); advanceUntilIdle()
        model.loadGap("old"); advanceUntilIdle()
        assertEquals(3, gapCalls) // One automatic probe, one failed tap, one pending retry.
        mainViewModel.switchAccount("second"); advanceUntilIdle()
        pending.complete(Result.success(TimelinePage(listOf(status("late")), "late", false)))
        advanceUntilIdle()
        assertEquals(listOf("second"), model.uiState.value.statuses.map { it.timelineId })
        assertNull(model.uiState.value.loadingGapId)
        assertTrue(model.uiState.value.gapErrors.isEmpty())
    }

    @Test fun restoredHomeRefreshGetsLatestWhileKeepingAnchorAndExistingGap() = runTest(dispatcher) {
        val cached = TimelinePage(listOf(status("new"), status("old")), "old", false,
            io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineIndex(listOf("new", "old"),
                listOf(io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineGap("old", "new"))))
        var online = false
        val requests = mutableListOf<String?>()
        val repository = object : TimelineRepository {
            override fun isNetworkAvailable() = online
            override suspend fun getCachedHomeWindow(session: AccountSession) = Result.success(cached)
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                requests += maxId
                return Result.success(TimelinePage(listOf(status("latest"), status("new")), "new", false))
            }
        }
        val saved = SavedStateHandle(mapOf("timeline_viewport_session" to SESSION.sessionId,
            "timeline_viewport_feed" to "Home", "timeline_viewport_anchor" to "old", "timeline_viewport_offset" to 23))
        val model = createTimeline(repository, FakeAuthRepository(SESSION), saved)
        advanceUntilIdle()
        assertEquals("old", model.uiState.value.resumeAnchorId)
        assertEquals(23, model.uiState.value.resumeOffset)
        assertEquals(1, model.uiState.value.homeIndex!!.gaps.size)
        model.consumeResumeAnchor()
        online = true
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf<String?>(null), requests)
        assertEquals(listOf("latest", "new", "old"), model.uiState.value.statuses.map { it.timelineId })
        assertNull(model.uiState.value.resumeAnchorId)
        assertFalse(model.uiState.value.isResumedWindow)
        assertEquals(1, model.uiState.value.homeIndex!!.gaps.size)
    }

    private class CachedHomeRepository : TimelineRepository {
        val pages = mutableMapOf<String?, TimelinePage>(null to TimelinePage(listOf(status("cached")), "cached", false))
        val requests = mutableListOf<Pair<String?, Int>>()
        val writes = mutableListOf<TimelinePage>()
        val writeChanges = mutableListOf<List<BrowsingSession.Change>>()
        val changes = mutableListOf<BrowsingSession.Change>()
        var cacheReads = 0
        var online = true
        var network: suspend (String?, Int) -> Result<TimelinePage> = { _, _ -> Result.failure(java.io.IOException("timeout")) }
        override fun isNetworkAvailable() = online
        override suspend fun getCachedHomeTimeline(session: AccountSession, maxId: String?, limit: Int, anchorId: String?): Result<TimelinePage?> {
            cacheReads++
            return Result.success(pages[maxId])
        }
        override suspend fun cacheHomeTimeline(session: AccountSession, maxId: String?, page: TimelinePage, changes: List<BrowsingSession.Change>): Result<Unit> {
            writes += page
            writeChanges += changes
            return Result.success(Unit)
        }
        override suspend fun updateHomeTimelineCache(session: AccountSession, change: BrowsingSession.Change): Result<Unit> {
            changes += change
            return Result.success(Unit)
        }
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
            requests += maxId to limit
            return network(maxId, limit)
        }
        override suspend fun getTimeline(session: AccountSession, feed: TimelineFeed, maxId: String?, limit: Int): Result<TimelinePage> =
            if (feed == TimelineFeed.Home) getHomeTimeline(session, maxId, limit)
            else Result.success(TimelinePage(listOf(status(feed.name)), null, true))
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
