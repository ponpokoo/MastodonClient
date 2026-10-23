package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationOrderingTest : ScreenViewModelTestBase() {
    private fun event(id: String, time: String) = testNotification(id).copy(
        type = "favourite", createdAt = time,
        account = StatusAuthor(id, id, id, ""),
    )

    private class OrderingRepository : ScreenRepositoryFake() {
        var cached = emptyList<TimelineNotification>()
        var page = NotificationPage(emptyList(), "cursor", false)
        var nextPage = NotificationPage(emptyList(), null, true)
        var saved = emptyList<TimelineNotification>()
        var pendingPage: CompletableDeferred<Result<NotificationPage>>? = null
        val cursors = mutableListOf<String?>()

        override suspend fun getCachedNotifications(session: AccountSession) = Result.success(CachedNotifications(cached))
        override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
            cursors += maxId
            return if (maxId == null) pendingPage?.await() ?: Result.success(page) else Result.success(nextPage)
        }
        override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>): Result<Unit> {
            saved = notifications
            return Result.success(Unit)
        }
    }

    @Test fun delayedStreamKeepsChronologicalActorOrderThroughRefreshAndRecreation() = runTest(dispatcher) {
        val older = event("z-older", "2026-10-23T00:00:00Z")
        val middle = event("a-middle", "2026-10-23T00:00:00.900Z")
        val newest = event("m-newest", "2026-10-23T00:00:01Z")
        val repository = OrderingRepository().apply { page = NotificationPage(listOf(newest, middle), "cursor", false) }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(older)))
        advanceUntilIdle()

        val expected = listOf(newest.id, middle.id, older.id)
        fun actors() = NotificationRowGrouper().rows(vm.uiState.value.notifications, true).single().accounts.map { it.id }
        assertEquals(expected, vm.uiState.value.notifications.map { it.id })
        assertEquals(expected, actors())
        repository.page = NotificationPage(listOf(newest, middle, older), "cursor", false)
        vm.refreshNotifications()
        advanceUntilIdle()
        assertEquals(expected, actors())
        assertEquals(expected, repository.saved.map { it.id })

        val restoredRepository = OrderingRepository().apply { cached = repository.saved; pendingPage = CompletableDeferred() }
        val restored = own(NotificationsViewModel(restoredRepository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        assertEquals(expected, NotificationRowGrouper().rows(restored.uiState.value.notifications, true).single().accounts.map { it.id })
        restoredRepository.pendingPage!!.complete(Result.success(repository.page))
        advanceUntilIdle()
        assertEquals(expected, restored.uiState.value.notifications.map { it.id })
    }

    @Test fun refreshComparesInstantsAcrossOffsetsAndFractionalSecondFormats() = runTest(dispatcher) {
        val latest = event("opaque-latest", "2026-10-22T20:00:01-04:00")
        val fractional = event("opaque-fractional", "2026-10-23T00:00:00.900Z")
        val second = event("opaque-second", "2026-10-23T00:00:00Z")
        val repository = OrderingRepository().apply { page = NotificationPage(listOf(latest, fractional, second), "cursor", false) }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        assertEquals(listOf(latest.id, fractional.id, second.id), vm.uiState.value.notifications.map { it.id })
    }

    @Test fun equalInstantsUseServerOrderOnRefreshAndKeepTheServerPagingCursor() = runTest(dispatcher) {
        val first = event("z-first", "2026-10-23T00:00:00Z")
        val live = event("a-live", "2026-10-23T09:00:00+09:00")
        val repository = OrderingRepository().apply { page = NotificationPage(listOf(first), "server-cursor", false) }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(live)))
        advanceUntilIdle()
        assertEquals(listOf(live.id, first.id), vm.uiState.value.notifications.map { it.id })
        repository.page = NotificationPage(listOf(first, live), "server-cursor", false)
        vm.refreshNotifications()
        advanceUntilIdle()
        assertEquals(listOf(first.id, live.id), vm.uiState.value.notifications.map { it.id })
        vm.loadNextNotifications()
        advanceUntilIdle()
        assertEquals(listOf(null, null, "server-cursor"), repository.cursors)
    }

    @Test fun allAndCategoryCachesAreOrderedBeforeNetworkResultsAndPaginationUsesTheSameOrder() = runTest(dispatcher) {
        val older = event("old", "2026-10-23T00:00:00Z").copy(type = "mention")
        val newest = event("new", "2026-10-23T00:00:02Z").copy(type = "mention")
        val middle = event("middle", "2026-10-23T00:00:01Z").copy(type = "mention")
        val pending = CompletableDeferred<Result<NotificationPage>>()
        val repository = OrderingRepository().apply { cached = listOf(older, newest); pendingPage = pending }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        assertEquals(listOf(newest.id, older.id), vm.uiState.value.notifications.map { it.id })
        vm.selectCategory(NotificationCategory.Mentions)
        runCurrent()
        assertEquals(listOf(newest.id, older.id), vm.uiState.value.list(NotificationCategory.Mentions).notifications.map { it.id })
        pending.complete(Result.success(NotificationPage(listOf(newest, older), "next", false)))
        advanceUntilIdle()
        repository.nextPage = NotificationPage(listOf(middle, older), null, true)
        vm.loadNextNotifications()
        advanceUntilIdle()
        assertEquals(listOf(newest.id, middle.id, older.id), vm.uiState.value.list(NotificationCategory.Mentions).notifications.map { it.id })
    }

    @Test fun invalidDatesRemainVisibleAfterDatedNotificationsWithoutChangingTheirRelativeOrder() = runTest(dispatcher) {
        val invalid = event("invalid", "invalid")
        val missing = event("missing", "")
        val valid = event("valid", "2026-10-23T00:00:00Z")
        val repository = OrderingRepository().apply { page = NotificationPage(listOf(invalid, valid, missing), "cursor", false) }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        assertEquals(listOf(valid.id, invalid.id, missing.id), vm.uiState.value.notifications.map { it.id })
    }
}
