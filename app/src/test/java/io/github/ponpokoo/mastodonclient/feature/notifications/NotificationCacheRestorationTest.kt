package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCacheRestorationTest : ScreenViewModelTestBase() {
    private fun event(index: Int) = testNotification("opaque-$index").copy(
        type = "favourite",
        createdAt = Instant.parse("2026-10-23T12:00:00Z").minusSeconds(index.toLong()).toString(),
        account = StatusAuthor("actor-$index", "Actor $index", "actor-$index", ""),
    )

    private class SnapshotRepository(val cached: List<TimelineNotification>) : ScreenRepositoryFake() {
        val firstPage = CompletableDeferred<Result<NotificationPage>>()
        val cursors = mutableListOf<String?>()
        var olderPage = NotificationPage(emptyList(), null, true)
        var saved = emptyList<TimelineNotification>()

        override suspend fun getCachedNotifications(session: AccountSession) =
            Result.success(CachedNotifications(cached))

        override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
            cursors += maxId
            return if (maxId == null) firstPage.await() else Result.success(olderPage)
        }

        override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>): Result<Unit> {
            saved = notifications
            return Result.success(Unit)
        }
    }

    @Test fun overlappingInitialPageKeepsTwoHundredActorsAndPagesAfterTheRestoredTail() = runTest(dispatcher) {
        val cached = (0 until 200).map(::event)
        val repository = SnapshotRepository(cached)
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        assertEquals(200, NotificationRowGrouper().rows(vm.uiState.value.notifications, true).single().accounts.size)

        val updated = cached.first().copy(status = cached.first().status!!.copy(contentHtml = "fresh content"))
        repository.firstPage.complete(Result.success(NotificationPage(listOf(updated) + cached.subList(1, 40), cached[39].id, false)))
        advanceUntilIdle()

        assertEquals(cached.map { it.id }, vm.uiState.value.notifications.map { it.id })
        assertEquals(200, NotificationRowGrouper().rows(vm.uiState.value.notifications, true).single().accounts.size)
        assertEquals("fresh content", vm.uiState.value.notifications.first().status!!.contentHtml)
        assertEquals(cached.map { it.id }, repository.saved.map { it.id })
        assertEquals(cached.last().id, vm.uiState.value.notificationsNextMaxId)
        assertTrue(vm.uiState.value.pendingNewNotificationIds.isEmpty())
        assertTrue(repository.markers.isEmpty())
        assertEquals(listOf<String?>(null), repository.cursors)

        // Opening the tab after prefetch refreshes the head without rewinding pagination.
        vm.onNotificationsVisible()
        advanceUntilIdle()
        assertEquals(cached.last().id, vm.uiState.value.notificationsNextMaxId)
        val older = (200 until 240).map(::event)
        repository.olderPage = NotificationPage(older, older.last().id, false)
        vm.loadNextNotifications()
        advanceUntilIdle()
        assertEquals(listOf(null, null, cached.last().id), repository.cursors)
        assertEquals((cached + older).map { it.id }, vm.uiState.value.notifications.map { it.id })
        assertEquals(240, NotificationRowGrouper().rows(vm.uiState.value.notifications, true).single().accounts.size)
    }

    @Test fun restoredRowsOmittedWithinTheFreshPageAreNotBroughtBack() = runTest(dispatcher) {
        val cached = (0 until 200).map(::event)
        val repository = SnapshotRepository(cached)
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        // The server no longer includes one of the previously cached notifications.
        val fresh = cached.take(41).filterNot { it.id == cached[10].id }
        repository.firstPage.complete(Result.success(NotificationPage(fresh, fresh.last().id, false)))
        advanceUntilIdle()

        assertEquals((fresh + cached.drop(41)).map { it.id }, vm.uiState.value.notifications.map { it.id })
        assertFalse(repository.saved.any { it.id == cached[10].id })
        assertEquals(199, NotificationRowGrouper().rows(vm.uiState.value.notifications, true).single().accounts.size)
    }

    @Test fun anOverlapBeforeThePageTailDoesNotConnectUnverifiedHistory() = runTest(dispatcher) {
        val cached = (0 until 200).map(::event)
        val repository = SnapshotRepository(cached)
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        val fresh = listOf(cached.first(), event(400))
        repository.firstPage.complete(Result.success(NotificationPage(fresh, fresh.last().id, false)))
        advanceUntilIdle()

        assertEquals(fresh.map { it.id }, vm.uiState.value.notifications.map { it.id })
        assertEquals(fresh.last().id, vm.uiState.value.notificationsNextMaxId)
        assertEquals(fresh.map { it.id }, repository.saved.map { it.id })
    }

    @Test fun emptyServerHistoryDoesNotRestoreOldCachedActors() = runTest(dispatcher) {
        val repository = SnapshotRepository((0 until 200).map(::event))
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        repository.firstPage.complete(Result.success(NotificationPage(emptyList(), null, true)))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.notifications.isEmpty())
        assertTrue(repository.saved.isEmpty())
        assertTrue(vm.uiState.value.notificationsEndReached)
        assertNull(vm.uiState.value.notificationsNextMaxId)
    }

    @Test fun categoryCacheIsConnectedOnlyToThatCategorysFirstPage() = runTest(dispatcher) {
        val cached = (0 until 80).map { event(it).copy(type = "mention") }
        val cursors = mutableListOf<Pair<NotificationCategory, String?>>()
        val saved = mutableListOf<Pair<NotificationCategory, List<String>>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getCachedNotifications(session: AccountSession) = Result.success(CachedNotifications())
            override suspend fun getCachedNotificationCategory(session: AccountSession, category: NotificationCategory) =
                Result.success(CachedNotifications(cached))

            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory,
                maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> {
                cursors += category to maxId
                return Result.success(if (category == NotificationCategory.All) NotificationPage(emptyList(), null, true)
                    else NotificationPage(cached.take(40), cached[39].id, false))
            }

            override suspend fun cacheNotificationCategory(session: AccountSession, category: NotificationCategory,
                notifications: List<TimelineNotification>): Result<Unit> {
                saved += category to notifications.map { it.id }
                return Result.success(Unit)
            }
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        vm.selectCategory(NotificationCategory.Mentions)
        advanceUntilIdle()

        assertEquals(cached.map { it.id }, vm.uiState.value.list(NotificationCategory.Mentions).notifications.map { it.id })
        assertEquals(cached.last().id, vm.uiState.value.list(NotificationCategory.Mentions).nextMaxId)
        assertTrue(vm.uiState.value.notifications.isEmpty())
        assertEquals(NotificationCategory.Mentions to cached.map { it.id }, saved.last())
        assertEquals(listOf(NotificationCategory.All to null, NotificationCategory.Mentions to null), cursors)
    }

    @Test fun streamDuringInitialRefreshKeepsItsUnreadIdAlongsideTheRestoredActors() = runTest(dispatcher) {
        val cached = (0 until 200).map(::event)
        val repository = SnapshotRepository(cached)
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        runCurrent()
        val live = event(-1)
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(live)))
        runCurrent()
        repository.firstPage.complete(Result.success(NotificationPage(cached.take(40), cached[39].id, false)))
        advanceUntilIdle()

        val row = NotificationRowGrouper().rows(vm.uiState.value.notifications, true).single()
        assertEquals(201, row.accounts.size)
        assertEquals(live.id, row.latest.id)
        assertEquals(setOf(live.id), vm.uiState.value.pendingNewNotificationIds)
        assertEquals(cached.last().id, vm.uiState.value.notificationsNextMaxId)
        vm.onLatestNotificationsShown(row.notificationIds, true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pendingNewNotificationIds.isEmpty())
        assertEquals(listOf(testAccount.sessionId to live.id), repository.markers)
    }

    @Test fun switchingBackRestoresEachAccountsActorsAndRejectsTheOldSessionsResponse() = runTest(dispatcher) {
        val first = (0 until 200).map(::event)
        val second = (300 until 350).map(::event)
        val lateResponse = CompletableDeferred<Result<NotificationPage>>()
        var firstAccountRequests = 0
        val saved = mutableListOf<Pair<String, List<String>>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getCachedNotifications(session: AccountSession) =
                Result.success(CachedNotifications(if (session == testAccount) first else second))

            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                if (session == testAccount && ++firstAccountRequests == 1) {
                    return withContext(NonCancellable) { lateResponse.await() }
                }
                val cached = if (session == testAccount) first else second
                return Result.success(NotificationPage(cached.take(40), cached[39].id, false))
            }

            override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>): Result<Unit> {
                saved += session.sessionId to notifications.map { it.id }
                return Result.success(Unit)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        runCurrent()
        browsing.activate(secondAccount)
        runCurrent()
        assertEquals(second.map { it.id }, vm.uiState.value.notifications.map { it.id })
        browsing.activate(testAccount)
        runCurrent()
        lateResponse.complete(Result.success(NotificationPage(first.take(1), first.first().id, false)))
        advanceUntilIdle()

        assertEquals(first.map { it.id }, vm.uiState.value.notifications.map { it.id })
        assertEquals(first.last().id, vm.uiState.value.notificationsNextMaxId)
        assertEquals(listOf(secondAccount.sessionId to second.map { it.id }, testAccount.sessionId to first.map { it.id }), saved)
    }
}
