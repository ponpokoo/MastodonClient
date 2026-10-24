package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.ServerAnnouncement
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAnnouncementsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val browsing = BrowsingSession()
    private val repository = Repository()
    private var now = 0L

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun create(): TimelineViewModel {
        browsing.activate(SESSION)
        return TimelineViewModel(repository, browsing, announcementTimeMillis = { now })
    }

    @Test fun checksOnActivationAndThrottlesResumeWithoutOpeningSheet() = runTest(dispatcher) {
        val viewModel = create()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasUnreadAnnouncements)
        assertFalse(viewModel.uiState.value.announcementsVisible)
        viewModel.refreshAnnouncementsIfNeeded()
        now = 29 * 60 * 1_000L
        viewModel.refreshAnnouncementsIfNeeded()
        advanceUntilIdle()
        assertEquals(1, repository.requests.size)
        now = 30 * 60 * 1_000L
        repository.notices = listOf(notice("read", true), notice("unknown", null))
        viewModel.refreshAnnouncementsIfNeeded()
        advanceUntilIdle()
        assertEquals(2, repository.requests.size)
        assertFalse(viewModel.uiState.value.hasUnreadAnnouncements)
    }

    @Test fun coalescesRequestsAndManualOpenBypassesInterval() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<List<ServerAnnouncement>>>()
        repository.fetch = { response.await() }
        val viewModel = create()
        runCurrent()
        viewModel.showAnnouncements()
        viewModel.showAnnouncements()
        viewModel.refreshAnnouncementsIfNeeded()
        runCurrent()
        assertEquals(1, repository.requests.size)
        assertTrue(viewModel.uiState.value.announcementsVisible)
        response.complete(Result.success(repository.notices))
        advanceUntilIdle()
        repository.fetch = { Result.success(repository.notices) }
        viewModel.dismissAnnouncements()
        viewModel.showAnnouncements()
        advanceUntilIdle()
        assertEquals(2, repository.requests.size)
    }

    @Test fun backgroundFailurePreservesBadgeAndIsThrottledButManualRetryWorks() = runTest(dispatcher) {
        val viewModel = create()
        advanceUntilIdle()
        repository.fetch = { Result.failure(IllegalStateException("offline")) }
        now = 30 * 60 * 1_000L
        viewModel.refreshAnnouncementsIfNeeded()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasUnreadAnnouncements)
        assertNull(viewModel.uiState.value.announcementsError)
        viewModel.refreshAnnouncementsIfNeeded()
        advanceUntilIdle()
        assertEquals(2, repository.requests.size)
        viewModel.showAnnouncements()
        advanceUntilIdle()
        assertEquals("offline", viewModel.uiState.value.announcementsError)
        repository.fetch = { Result.success(emptyList()) }
        viewModel.showAnnouncements()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasUnreadAnnouncements)
        assertNull(viewModel.uiState.value.announcementsError)
    }

    @Test fun onlyVisibleUnreadItemsAreDismissedAndBadgeClearsAfterSuccess() = runTest(dispatcher) {
        repository.notices = listOf(notice("one"), notice("two"), notice("read", true), notice("unknown", null))
        val readResult = CompletableDeferred<Result<Unit>>()
        repository.dismiss = { readResult.await() }
        val viewModel = create()
        advanceUntilIdle()
        viewModel.onAnnouncementsShown(setOf("one"))
        advanceUntilIdle()
        assertTrue(repository.dismissed.isEmpty())
        viewModel.showAnnouncements()
        advanceUntilIdle()
        viewModel.onAnnouncementsShown(setOf("one", "read", "unknown", "missing"))
        viewModel.onAnnouncementsShown(setOf("one"))
        runCurrent()
        assertEquals(listOf("one"), repository.dismissed.map { it.second })
        assertTrue(viewModel.uiState.value.hasUnreadAnnouncements)
        readResult.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.announcements.first().read)
        assertTrue(viewModel.uiState.value.hasUnreadAnnouncements)
        viewModel.onAnnouncementsShown(setOf("two"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasUnreadAnnouncements)
    }

    @Test fun failedDismissStaysUnreadAndCanBeRetried() = runTest(dispatcher) {
        val viewModel = create()
        advanceUntilIdle()
        viewModel.showAnnouncements()
        advanceUntilIdle()
        repository.dismiss = { Result.failure(IllegalStateException("denied")) }
        viewModel.onAnnouncementsShown(setOf("one"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasUnreadAnnouncements)
        repository.dismiss = { Result.success(Unit) }
        viewModel.onAnnouncementsShown(setOf("one"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasUnreadAnnouncements)
    }

    @Test fun accountSwitchRejectsLateFetchAndChecksNewAccountImmediately() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<List<ServerAnnouncement>>>()
        repository.fetch = { withContext(NonCancellable) { response.await() } }
        val viewModel = create()
        runCurrent()
        repository.fetch = { Result.success(emptyList()) }
        val other = SESSION.copy(sessionId = "other", instanceUrl = "https://other.example")
        browsing.activate(other)
        runCurrent()
        response.complete(Result.success(listOf(notice("old"))))
        advanceUntilIdle()
        assertEquals(listOf(SESSION, other), repository.requests)
        assertTrue(viewModel.uiState.value.announcements.isEmpty())
        assertFalse(viewModel.uiState.value.hasUnreadAnnouncements)
        assertFalse(viewModel.uiState.value.announcementsVisible)
    }

    @Test fun accountSwitchRejectsLateDismissEvenWhenIdsMatch() = runTest(dispatcher) {
        val viewModel = create()
        advanceUntilIdle()
        viewModel.showAnnouncements()
        advanceUntilIdle()
        val response = CompletableDeferred<Result<Unit>>()
        repository.dismiss = { withContext(NonCancellable) { response.await() } }
        viewModel.onAnnouncementsShown(setOf("one"))
        runCurrent()
        browsing.activate(SESSION.copy(sessionId = "other"))
        runCurrent()
        response.complete(Result.success(Unit))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasUnreadAnnouncements)
        assertEquals(SESSION, repository.dismissed.single().first)
    }

    @Test fun concurrentFetchCannotUndoSuccessfulReadButEditedNoticeBecomesUnread() = runTest(dispatcher) {
        val viewModel = create()
        advanceUntilIdle()
        viewModel.showAnnouncements()
        advanceUntilIdle()
        val read = CompletableDeferred<Result<Unit>>()
        repository.dismiss = { read.await() }
        viewModel.onAnnouncementsShown(setOf("one"))
        runCurrent()
        val fetch = CompletableDeferred<Result<List<ServerAnnouncement>>>()
        repository.fetch = { fetch.await() }
        viewModel.showAnnouncements()
        runCurrent()
        read.complete(Result.success(Unit))
        runCurrent()
        fetch.complete(Result.success(repository.notices))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasUnreadAnnouncements)
        repository.fetch = { Result.success(listOf(notice("one").copy(updatedAt = "later"))) }
        viewModel.showAnnouncements()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasUnreadAnnouncements)
    }

    private class Repository : TimelineRepository {
        var notices = listOf(notice("one"))
        var fetch: suspend () -> Result<List<ServerAnnouncement>> = { Result.success(notices) }
        var dismiss: suspend () -> Result<Unit> = { Result.success(Unit) }
        val requests = mutableListOf<AccountSession>()
        val dismissed = mutableListOf<Pair<AccountSession, String>>()
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
            Result.success(TimelinePage(emptyList(), null, true))
        override suspend fun getAnnouncements(session: AccountSession): Result<List<ServerAnnouncement>> {
            requests += session
            return fetch()
        }
        override suspend fun dismissAnnouncement(session: AccountSession, announcementId: String): Result<Unit> {
            dismissed += session to announcementId
            return dismiss()
        }
    }

    private companion object {
        val SESSION = AccountSession("session", "https://example.social", "me", "me", "Me", "", "token")
        fun notice(id: String, read: Boolean? = false) = ServerAnnouncement(id, "<p>Notice</p>", null, "v1", read)
    }
}
