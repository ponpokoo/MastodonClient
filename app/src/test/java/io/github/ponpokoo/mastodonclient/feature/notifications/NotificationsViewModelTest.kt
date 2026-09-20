package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest : ScreenViewModelTestBase() {
    @Test fun automaticRefreshDoesNotShowPullRefreshIndicator() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<NotificationPage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) = delayed.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.onNotificationsVisible()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isLoadingNotifications)
        assertFalse(viewModel.uiState.value.isPullRefreshingNotifications)
        delayed.complete(Result.success(NotificationPage(listOf(testNotification()), null, true)))
        advanceUntilIdle()
    }

    @Test fun manualRefreshShowsPullRefreshIndicatorUntilRequestCompletes() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<NotificationPage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) = delayed.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.refreshNotifications()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPullRefreshingNotifications)
        delayed.complete(Result.success(NotificationPage(listOf(testNotification()), null, true)))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isPullRefreshingNotifications)
    }

    @Test fun becomingVisibleRefreshesAnAlreadyLoadedNotificationList() = runTest(dispatcher) {
        var requests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                requests++
                return Result.success(NotificationPage(listOf(testNotification("notification-$requests")), null, true))
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()

        assertEquals(2, requests)
        assertEquals(
            listOf("notification-2", "notification-1"),
            viewModel.uiState.value.notifications.map(TimelineNotification::id),
        )
        assertTrue(viewModel.uiState.value.refreshAddedNewNotifications)
    }

    @Test fun refreshReportsWhenNoNewNotificationWasAdded() = runTest(dispatcher) {
        val repository = ScreenRepositoryFake()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.refreshAddedNewNotifications)

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.refreshAddedNewNotifications)
    }

    @Test fun refreshKeepsStreamNotificationReceivedWhileRequestIsInFlight() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<NotificationPage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) = delayed.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        browsing.publish(
            browsing.snapshot.value,
            BrowsingSession.Change.Stream(
                TimelineStreamEvent.NotificationReceived(
                    testNotification("live").copy(createdAt = "2026-09-09T00:00:00Z"),
                ),
            ),
        )
        advanceUntilIdle()
        delayed.complete(
            Result.success(
                NotificationPage(
                    listOf(testNotification("fetched").copy(createdAt = "2026-09-08T00:00:00Z")),
                    null,
                    true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("live", "fetched"), viewModel.uiState.value.notifications.map(TimelineNotification::id))
    }

    @Test fun shortNotificationPageStillLoadsOlderHistory() = runTest(dispatcher) {
        val requestedCursors = mutableListOf<String?>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                requestedCursors += maxId
                return Result.success(when (maxId) {
                    null -> NotificationPage(listOf(testNotification("new")), "new", false)
                    "new" -> NotificationPage(listOf(testNotification("old")), "old", false)
                    else -> NotificationPage(emptyList(), null, true)
                })
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.loadNotifications()
        advanceUntilIdle()
        viewModel.loadNextNotifications()
        advanceUntilIdle()

        assertEquals(listOf(null, "new"), requestedCursors)
        assertEquals(listOf("new", "old"), viewModel.uiState.value.notifications.map { it.id })
        assertFalse(viewModel.uiState.value.notificationsEndReached)
    }

    @Test fun refreshSupersedesPaginationAndSavesMarker() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<NotificationPage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                if (maxId != null) withContext(NonCancellable) { delayed.await() } else super.getNotifications(session, maxId, limit)
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadNotifications()
        advanceUntilIdle()
        viewModel.loadNextNotifications()
        advanceUntilIdle()
        viewModel.loadNotifications(force = true)
        advanceUntilIdle()
        delayed.complete(Result.failure(IllegalStateException("old page")))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.notificationsError)
        assertFalse(viewModel.uiState.value.isLoadingMoreNotifications)
        assertEquals(listOf("notification"), viewModel.uiState.value.notifications.map { it.id })
        assertEquals(listOf("one" to "notification", "one" to "notification"), repository.markers)
    }

    @Test fun accountSwitchRejectsOldPageAndOldMarker() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<NotificationPage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                if (session == testAccount) withContext(NonCancellable) { delayed.await() }
                else Result.success(NotificationPage(listOf(testNotification("second")), null, true))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadNotifications()
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        delayed.complete(Result.success(NotificationPage(listOf(testNotification("old")), null, true)))
        advanceUntilIdle()
        assertEquals(listOf("second"), viewModel.uiState.value.notifications.map { it.id })
        assertEquals(listOf("two" to "second"), repository.markers)
    }

    @Test fun duplicateStreamEventDoesNotIncreaseUnreadTwiceOrPreventInitialFetch() = runTest(dispatcher) {
        val repository = ScreenRepositoryFake()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        val event = BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(testNotification()))
        repeat(2) { browsing.publish(browsing.snapshot.value, event) }
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.unreadNotifications)
        viewModel.loadNotifications()
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.unreadNotifications)
        assertEquals(1, repository.markers.size)
    }
}
