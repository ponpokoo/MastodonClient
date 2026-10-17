package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SavedTimelinesViewModelTest : ScreenViewModelTestBase() {
    @Test fun listFilterRefreshIsSilentAndItsDisplayCheckIsCancelledOnAccountChange() = runTest(dispatcher) {
        val page = CompletableDeferred<Result<TimelinePage>>()
        var calls = 0
        val checks = mutableListOf<Boolean?>()
        val settings = ServerAccountSettings(testAccount.instanceUrl, testAccount.accountId, filters = emptyList())
        val repository = object : ScreenRepositoryFake() {
            override val serverSettings = MutableStateFlow(mapOf(testAccount.sessionId to settings))
            override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?): Result<TimelinePage> =
                if (++calls == 2) withContext(NonCancellable) { page.await() }
                else Result.success(TimelinePage(listOf(testStatus(session.sessionId)), null, true))
            override suspend fun updateFilterDisplay(session: AccountSession, screen: String, expectedFilters: List<ServerFilter>, success: Boolean?) { checks += success }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(SavedTimelinesViewModel(SavedTimelineKind.List, "friends", repository, browsing))
        advanceUntilIdle()
        repository.serverSettings.value = mapOf(testAccount.sessionId to settings.copy(filters = listOf(ServerFilter("filter", "test", setOf("home")))))
        runCurrent()
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.refreshing)
        assertEquals(testAccount.sessionId, model.state.value.statuses.single().statusId)
        browsing.activate(secondAccount); runCurrent()
        page.complete(Result.success(TimelinePage(emptyList(), null, true))); advanceUntilIdle()
        assertEquals(secondAccount.sessionId, model.state.value.statuses.single().statusId)
        assertEquals(listOf<Boolean?>(null), checks)
    }

    @Test fun refreshRetainsListsAndPostsWhileLoadingAndAfterFailure() = runTest(dispatcher) {
        for (kind in listOf(null, *SavedTimelineKind.entries.toTypedArray())) {
            val refresh = CompletableDeferred<Unit>()
            var calls = 0
            val repository = object : ScreenRepositoryFake() {
                private suspend fun waitForRefresh() { if (++calls > 1) refresh.await() }
                override suspend fun getLists(session: AccountSession): Result<List<MastodonList>> {
                    waitForRefresh()
                    return if (calls == 1) Result.success(listOf(MastodonList("friends", "友だち"))) else Result.failure(IOException("offline"))
                }
                override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?): Result<TimelinePage> {
                    waitForRefresh()
                    return if (calls == 1) Result.success(TimelinePage(listOf(testStatus()), "next", false)) else Result.failure(IOException("offline"))
                }
            }
            val model = own(SavedTimelinesViewModel(kind, "friends", repository, BrowsingSession().apply { activate(testAccount) }))
            advanceUntilIdle()
            model.refresh(); runCurrent()
            assertTrue(model.state.value.refreshing)
            assertEquals(1, if (kind == null) model.state.value.lists.size else model.state.value.statuses.size)
            refresh.complete(Unit); advanceUntilIdle()
            assertFalse(model.state.value.refreshing)
            assertEquals("offline", model.state.value.error)
            assertEquals(1, if (kind == null) model.state.value.lists.size else model.state.value.statuses.size)
        }
    }

    @Test fun appendFailureStopsAutomaticLoadingAndRetryUsesSameCursor() = runTest(dispatcher) {
        val cursors = mutableListOf<String?>()
        var fail = true
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?): Result<TimelinePage> {
                cursors += maxId
                return when {
                    maxId == null -> Result.success(TimelinePage(listOf(testStatus("one")), "relationship-cursor", false))
                    fail -> Result.failure(IOException("offline"))
                    else -> Result.success(TimelinePage(listOf(testStatus("one"), testStatus("two")), null, true))
                }
            }
        }
        val model = own(SavedTimelinesViewModel(SavedTimelineKind.Bookmarks, null, repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle(); model.loadMore(); advanceUntilIdle()
        model.loadMore(); advanceUntilIdle()
        assertEquals(listOf(null, "relationship-cursor"), cursors)
        assertEquals("one", model.state.value.statuses.single().statusId)
        fail = false; model.retry(); advanceUntilIdle()
        assertEquals(listOf(null, "relationship-cursor", "relationship-cursor"), cursors)
        assertEquals(listOf("one", "two"), model.state.value.statuses.map { it.statusId })
        assertTrue(model.state.value.endReached)
    }

    @Test fun switchingAwayAndBackRejectsOldResponseEvenWhenCancellationIsIgnored() = runTest(dispatcher) {
        val old = CompletableDeferred<Result<TimelinePage>>()
        var calls = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?): Result<TimelinePage> =
                if (++calls == 1) withContext(NonCancellable) { old.await() }
                else Result.success(TimelinePage(listOf(testStatus("new-$calls")), null, true))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(SavedTimelinesViewModel(SavedTimelineKind.Favourites, null, repository, browsing))
        runCurrent(); browsing.activate(secondAccount); advanceUntilIdle()
        browsing.activate(testAccount); advanceUntilIdle()
        old.complete(Result.success(TimelinePage(listOf(testStatus("old")), "old-cursor", false))); advanceUntilIdle()
        assertEquals("new-3", model.state.value.statuses.single().statusId)
        assertNull(model.state.value.nextMaxId)
    }

    @Test fun newRefreshRejectsCancelledAppendAndPreservesActionsAndDeletesDuringRequest() = runTest(dispatcher) {
        val append = CompletableDeferred<Result<TimelinePage>>()
        val refresh = CompletableDeferred<Result<TimelinePage>>()
        var calls = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?): Result<TimelinePage> =
                when (++calls) {
                    1 -> Result.success(TimelinePage(listOf(testStatus("one"), testStatus("deleted")), "next", false))
                    2 -> withContext(NonCancellable) { append.await() }
                    else -> refresh.await()
                }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(SavedTimelinesViewModel(SavedTimelineKind.List, "friends", repository, browsing))
        advanceUntilIdle(); model.loadMore(); runCurrent(); model.refresh(); runCurrent()
        val poll = StatusPoll("poll", null, false, false, 1, 1, true, setOf(0), listOf(PollOption("Yes", 1)))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusUpdated(testStatus("one").copy(bookmarked = true, poll = poll)))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusDeleted("deleted")); runCurrent()
        refresh.complete(Result.success(TimelinePage(listOf(testStatus("one"), testStatus("deleted")), null, true)))
        append.complete(Result.success(TimelinePage(listOf(testStatus("old-append")), "wrong", false))); advanceUntilIdle()
        assertEquals("one", model.state.value.statuses.single().statusId)
        assertTrue(model.state.value.statuses.single().bookmarked)
        assertEquals(poll, model.state.value.statuses.single().poll)
        assertNull(model.state.value.nextMaxId)
    }

    @Test fun commonActionsUpdateSavedRowsAndUnsaveKeepsRowUntilRefresh() = runTest(dispatcher) {
        var saved = true
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?) =
                Result.success(TimelinePage(if (saved) listOf(testStatus().copy(bookmarked = true)) else emptyList(), null, true))
            override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> {
                saved = bookmarked
                return Result.success(testStatus(statusId).copy(bookmarked = bookmarked))
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(SavedTimelinesViewModel(SavedTimelineKind.Bookmarks, null, repository, browsing))
        val actions = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()
        actions.toggleBookmark(model.state.value.statuses.single()); advanceUntilIdle()
        assertFalse(model.state.value.statuses.single().bookmarked)
        actions.toggleFavourite(model.state.value.statuses.single()); advanceUntilIdle()
        assertTrue(model.state.value.statuses.single().favourited)
        model.refresh(); advanceUntilIdle()
        assertTrue(model.state.value.statuses.isEmpty())
    }
}
