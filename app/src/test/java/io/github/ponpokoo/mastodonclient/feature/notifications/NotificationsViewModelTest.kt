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
        assertTrue(requireNotNull(viewModel.uiState.value.refreshResult).hasNewNotifications)
    }

    @Test fun initialLoadWithoutMarkerDoesNotTreatExistingNotificationsAsNew() = runTest(dispatcher) {
        val repository = ScreenRepositoryFake()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertFalse(requireNotNull(viewModel.uiState.value.refreshResult).hasNewNotifications)
        assertEquals(0, viewModel.uiState.value.unreadNotifications)

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertFalse(requireNotNull(viewModel.uiState.value.refreshResult).hasNewNotifications)
    }

    @Test fun refreshKeepsStreamNotificationReceivedWhileRequestIsInFlight() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<NotificationPage>>()
        val old = testNotification("old").copy(createdAt = "2026-09-08T00:00:00Z")
        var requests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                if (++requests == 1) Result.success(NotificationPage(listOf(old), null, true)) else delayed.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        val live = testNotification("live").copy(createdAt = "2026-09-09T00:00:00Z")
        browsing.publish(
            browsing.snapshot.value,
            BrowsingSession.Change.Stream(
                TimelineStreamEvent.NotificationReceived(live),
            ),
        )
        advanceUntilIdle()
        delayed.complete(
            Result.success(
                NotificationPage(
                    listOf(live, old),
                    null,
                    true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("live", "old"), viewModel.uiState.value.notifications.map(TimelineNotification::id))
        assertTrue(requireNotNull(viewModel.uiState.value.refreshResult).hasNewNotifications)
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

    @Test fun refreshSupersedesPaginationAndDoesNotResaveAnUnchangedMarker() = runTest(dispatcher) {
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
        assertTrue(repository.markers.isEmpty())
        viewModel.onLatestNotificationsShown(setOf("notification"), allNotificationsShown = true)
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
        viewModel.onLatestNotificationsShown(setOf("notification"), allNotificationsShown = true)
        advanceUntilIdle()
        assertEquals(listOf("one" to "notification"), repository.markers)
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
        viewModel.onLatestNotificationsShown(setOf("second"), allNotificationsShown = true)
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
        assertEquals(1, viewModel.uiState.value.unreadNotifications)
        assertTrue(repository.markers.isEmpty())
        viewModel.onLatestNotificationsShown(setOf("notification"), allNotificationsShown = true)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.unreadNotifications)
        assertEquals(1, repository.markers.size)
    }

    @Test fun unreadNotificationIsAcknowledgedOnlyWhenTheNewestRowIsShown() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession) = Result.success("old")
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification("new"), testNotification("old")), null, true))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertEquals(setOf("new"), viewModel.uiState.value.pendingNewNotificationIds)
        assertTrue(repository.markers.isEmpty())

        viewModel.onLatestNotificationsShown(setOf("old"), allNotificationsShown = true)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.unreadNotifications)
        assertTrue(repository.markers.isEmpty())

        viewModel.onLatestNotificationsShown(setOf("new"), allNotificationsShown = true)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.unreadNotifications)
        assertEquals(1, viewModel.uiState.value.shownNewNotice?.count)
        assertEquals(listOf("one" to "new"), repository.markers)
    }

    @Test fun failedRefreshPreservesUnreadAndHighlightUntilTheTabIsLeft() = runTest(dispatcher) {
        var requests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession) = Result.success("old")
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                if (++requests == 1) Result.success(NotificationPage(
                    listOf(testNotification("new"), testNotification("old")), null, true,
                )) else Result.failure(IllegalStateException("offline"))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertEquals(setOf("new"), viewModel.uiState.value.highlightedNotificationIds)
        viewModel.refreshNotifications()
        advanceUntilIdle()
        assertEquals(setOf("new"), viewModel.uiState.value.pendingNewNotificationIds)
        assertEquals(setOf("new"), viewModel.uiState.value.highlightedNotificationIds)
        viewModel.onLatestNotificationsShown(setOf("new"), allNotificationsShown = true)
        advanceUntilIdle()
        assertTrue(repository.markers.isEmpty())

        viewModel.onNotificationsHidden()
        assertTrue(viewModel.uiState.value.highlightedNotificationIds.isEmpty())
        assertEquals(1, viewModel.uiState.value.unreadNotifications)
    }
}
