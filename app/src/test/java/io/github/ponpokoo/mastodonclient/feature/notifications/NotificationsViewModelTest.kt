package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest : ScreenViewModelTestBase() {
    @Test fun streamReceivedDuringDiskReadIsPersistedEvenWhenNetworkRefreshFails() = runTest(dispatcher) {
        val disk = CompletableDeferred<Result<CachedNotifications>>()
        var saved = emptyList<TimelineNotification>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getCachedNotifications(session: AccountSession) = disk.await()
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> = Result.failure(IllegalStateException("offline"))
            override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>): Result<Unit> {
                saved = notifications
                return Result.success(Unit)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(testNotification("live"))))
        advanceUntilIdle()
        disk.complete(Result.success(CachedNotifications(listOf(testNotification("live"), testNotification("old")))))
        advanceUntilIdle()
        assertEquals(listOf("live", "old"), saved.map { it.id })
        assertEquals(saved, viewModel.uiState.value.notifications)
        assertEquals(setOf("live"), viewModel.uiState.value.pendingNewNotificationIds)
    }

    @Test fun cachedNotificationsAreVisibleBeforeNetworkAndSurviveOfflineRefreshWithoutAcknowledgement() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<NotificationPage>>()
        var cacheWrites = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getCachedNotifications(session: AccountSession) =
                Result.success(CachedNotifications(listOf(testNotification("cached")), "older"))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) = response.await()
            override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>): Result<Unit> {
                cacheWrites++
                return Result.success(Unit)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertEquals(listOf("cached"), viewModel.uiState.value.notifications.map { it.id })
        assertTrue(viewModel.uiState.value.isLoadingNotifications)
        viewModel.onLatestNotificationsShown(setOf("cached"), true)
        advanceUntilIdle()
        assertTrue(repository.markers.isEmpty())

        response.complete(Result.failure(IllegalStateException("offline")))
        advanceUntilIdle()
        assertEquals(listOf("cached"), viewModel.uiState.value.notifications.map { it.id })
        assertEquals("offline", viewModel.uiState.value.notificationsError)
        viewModel.onLatestNotificationsShown(setOf("cached"), true)
        advanceUntilIdle()
        assertTrue(repository.markers.isEmpty())
        assertEquals(0, cacheWrites)
    }

    @Test fun freshPageReplacesDiskHistoryKeepsLiveEventsAndPersistsPaginationAndDeletion() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<NotificationPage>>()
        val saved = mutableListOf<List<TimelineNotification>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getCachedNotifications(session: AccountSession) =
                Result.success(CachedNotifications(listOf(testNotification("stale")), "read"))
            override suspend fun getNotificationMarker(session: AccountSession): Result<String?> = Result.failure(IllegalStateException("marker offline"))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                if (maxId == null) response.await() else Result.success(NotificationPage(listOf(testNotification("older")), "older", false))
            override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>): Result<Unit> {
                saved += notifications
                return Result.success(Unit)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(
            TimelineStreamEvent.NotificationReceived(testNotification("live").copy(createdAt = "2026-09-09T00:00:00Z")),
        ))
        advanceUntilIdle()
        response.complete(Result.success(NotificationPage(listOf(testNotification("new"), testNotification("read")), "read", false)))
        advanceUntilIdle()
        assertEquals(listOf("live", "new", "read"), viewModel.uiState.value.notifications.map { it.id })
        assertEquals(setOf("live", "new"), viewModel.uiState.value.pendingNewNotificationIds)
        assertEquals(viewModel.uiState.value.notifications, saved.last())
        viewModel.loadNextNotifications()
        advanceUntilIdle()
        assertEquals(listOf("live", "new", "read", "older"), saved.last().map { it.id })

        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusDeleted("post"))
        advanceUntilIdle()
        assertTrue(saved.last().all { it.status == null })
    }

    @Test fun delayedDiskReadCannotRestorePreviousAccount() = runTest(dispatcher) {
        val disk = CompletableDeferred<Result<CachedNotifications>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getCachedNotifications(session: AccountSession) =
                if (session == testAccount) withContext(NonCancellable) { disk.await() }
                else Result.success(CachedNotifications())
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification(session.sessionId)), null, true))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        disk.complete(Result.success(CachedNotifications(listOf(testNotification("old-account")))))
        advanceUntilIdle()
        assertEquals(listOf("two"), viewModel.uiState.value.notifications.map { it.id })
    }

    @Test fun unavailableCacheDoesNotFailSuccessfulNetworkLoad() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getCachedNotifications(session: AccountSession): Result<CachedNotifications> = Result.failure(IllegalStateException("disk"))
            override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>): Result<Unit> = Result.failure(IllegalStateException("disk full"))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertEquals(listOf("notification"), viewModel.uiState.value.notifications.map { it.id })
        assertNull(viewModel.uiState.value.notificationsError)
        assertFalse(viewModel.uiState.value.isLoadingNotifications)
    }

    @Test fun initialLoadOverlapsMarkerAndPageRequestsAndPreservesUnreadDetection() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession): Result<String?> {
                delay(1_000)
                return Result.success("old")
            }
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                delay(1_000)
                return Result.success(NotificationPage(listOf(testNotification("new"), testNotification("old")), null, true))
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()

        val startedAt = currentTime
        viewModel.onNotificationsVisible()
        advanceUntilIdle()

        assertEquals(1_000L, currentTime - startedAt)
        assertEquals(listOf("new", "old"), viewModel.uiState.value.notifications.map(TimelineNotification::id))
        assertEquals(setOf("new"), viewModel.uiState.value.pendingNewNotificationIds)
        assertFalse(viewModel.uiState.value.isLoadingNotifications)
        assertTrue(repository.markers.isEmpty())
    }

    @Test fun failedInitialMarkerStillDisplaysNotificationsWithoutMarkingHistoryUnread() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession): Result<String?> {
                delay(1_000)
                return Result.failure(IllegalStateException("marker unavailable"))
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()

        assertEquals(listOf("notification"), viewModel.uiState.value.notifications.map(TimelineNotification::id))
        assertTrue(viewModel.uiState.value.pendingNewNotificationIds.isEmpty())
        assertNull(viewModel.uiState.value.notificationsError)
        assertFalse(viewModel.uiState.value.isLoadingNotifications)
    }

    @Test fun failedSystemDismissalDoesNotPreventReadMarker() = runTest(dispatcher) {
        val system = object : io.github.ponpokoo.mastodonclient.domain.repository.SystemNotificationRepository {
            override suspend fun show(session: AccountSession, notification: TimelineNotification, isCurrent: () -> Boolean) = Unit
            override suspend fun dismissForAccount(sessionId: String) = Unit
            override suspend fun dismissRead(sessionId: String, notificationIds: Set<String>) = error("OS unavailable")
        }
        val repository = ScreenRepositoryFake()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing, system))
        advanceUntilIdle()
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        viewModel.onLatestNotificationsShown(setOf("notification"), true)
        advanceUntilIdle()
        assertEquals(listOf(testAccount.sessionId to "notification"), repository.markers)
    }
    @Test fun readDismissalUsesLoadedFilterIdsAndAccountEvenWithoutUnreadMarker() = runTest(dispatcher) {
        val dismissed = mutableListOf<Pair<String, Set<String>>>()
        val system = object : io.github.ponpokoo.mastodonclient.domain.repository.SystemNotificationRepository {
            override suspend fun show(session: AccountSession, notification: TimelineNotification, isCurrent: () -> Boolean) = Unit
            override suspend fun dismissForAccount(sessionId: String) = error("Must not dismiss an entire account")
            override suspend fun dismissRead(sessionId: String, notificationIds: Set<String>) { dismissed += sessionId to notificationIds }
        }
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification("read"), testNotification("unread")), null, true))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(NotificationsViewModel(repository, browsing, system))
        advanceUntilIdle()
        viewModel.onLatestNotificationsShown(setOf("read"), false)
        advanceUntilIdle()
        assertTrue(dismissed.isEmpty())
        viewModel.onNotificationsVisible()
        advanceUntilIdle()
        assertTrue(dismissed.isEmpty())
        viewModel.onLatestNotificationsShown(setOf("read", "unknown"), false)
        advanceUntilIdle()
        assertEquals(listOf(testAccount.sessionId to setOf("read")), dismissed)
        browsing.activate(secondAccount)
        viewModel.onLatestNotificationsShown(setOf("read"), false)
        advanceUntilIdle()
        assertEquals(1, dismissed.size)
    }
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
