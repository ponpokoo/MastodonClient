package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPrefetchRefreshTest : ScreenViewModelTestBase() {
    @Test fun tabOpenShowsPrefetchedRowsWhileFetchingNotificationsThatArrivedLater() = runTest(dispatcher) {
        val refresh = CompletableDeferred<Result<NotificationPage>>()
        var requests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                if (++requests == 1) Result.success(NotificationPage(listOf(testNotification("old")), null, true))
                else refresh.await()
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        advanceUntilIdle()
        vm.onNotificationsVisible()
        runCurrent()
        assertEquals(2, requests)
        assertEquals(listOf("old"), vm.uiState.value.notifications.map { it.id })
        assertFalse(vm.uiState.value.isPullRefreshingNotifications)
        refresh.complete(Result.success(NotificationPage(listOf(testNotification("new"), testNotification("old")), null, true)))
        advanceUntilIdle()
        assertEquals(setOf("new"), vm.uiState.value.pendingNewNotificationIds)
        assertEquals(setOf("new"), vm.uiState.value.highlightedNotificationIds)
        assertTrue(repository.markers.isEmpty())
    }

    @Test fun lateMarkerHighlightsViewedRowsWithoutRestoringUnreadOrRollingBackMarker() = runTest(dispatcher) {
        checkLateMarker(leaveBeforeMarker = false)
    }

    @Test fun leavingBeforeMarkerArrivesDoesNotRestoreViewedRowHighlights() = runTest(dispatcher) {
        checkLateMarker(leaveBeforeMarker = true)
    }

    private suspend fun TestScope.checkLateMarker(leaveBeforeMarker: Boolean) {
        val marker = CompletableDeferred<Result<String?>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession) = marker.await()
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification("new"), testNotification("old")), null, true))
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        vm.onSystemNotificationOpened()
        runCurrent()
        vm.onLatestNotificationsShown(setOf("new"), true)
        runCurrent()
        assertTrue(repository.markers.isEmpty())
        if (leaveBeforeMarker) vm.onNotificationsHidden()
        marker.complete(Result.success("old"))
        runCurrent()
        assertTrue(vm.uiState.value.pendingNewNotificationIds.isEmpty())
        assertEquals(if (leaveBeforeMarker) emptySet<String>() else setOf("new"), vm.uiState.value.highlightedNotificationIds)
        assertEquals(listOf(testAccount.sessionId to "new"), repository.markers)
        vm.onNotificationsVisible()
        runCurrent()
        assertEquals(if (leaveBeforeMarker) emptySet<String>() else setOf("new"), vm.uiState.value.highlightedNotificationIds)
        vm.refreshNotifications()
        runCurrent()
        assertTrue(vm.uiState.value.highlightedNotificationIds.isEmpty())
    }

    @Test fun previousAccountsLateMarkerCannotHighlightCurrentAccountsRows() = runTest(dispatcher) {
        val marker = CompletableDeferred<Result<String?>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession): Result<String?> =
                if (session == testAccount) withContext(NonCancellable) { marker.await() } else Result.success("new")
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification("new"), testNotification("old")), null, true))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        runCurrent()
        vm.onLatestNotificationsShown(setOf("new"), true)
        browsing.activate(secondAccount)
        runCurrent()
        marker.complete(Result.success("old"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pendingNewNotificationIds.isEmpty())
        assertTrue(vm.uiState.value.highlightedNotificationIds.isEmpty())
        assertTrue(repository.markers.isEmpty())
    }

    @Test fun systemNotificationAndForegroundDuringPrefetchQueueOneFreshRequest() = runTest(dispatcher) {
        val prefetch = CompletableDeferred<Result<NotificationPage>>()
        var requests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                if (++requests == 1) prefetch.await()
                else Result.success(NotificationPage(listOf(testNotification("arrived-later")), null, true))
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        vm.onAppForeground()
        vm.onSystemNotificationOpened()
        runCurrent()
        assertEquals(1, requests)
        prefetch.complete(Result.success(NotificationPage(listOf(testNotification("startup")), null, true)))
        advanceUntilIdle()
        assertEquals(2, requests)
        assertTrue(vm.uiState.value.notifications.any { it.id == "arrived-later" })
        assertTrue(repository.markers.isEmpty())
    }

    @Test fun manualRefreshDuringPrefetchSurvivesAnInitialFailure() = runTest(dispatcher) {
        val prefetch = CompletableDeferred<Result<NotificationPage>>()
        var requests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                if (++requests == 1) prefetch.await()
                else Result.success(NotificationPage(listOf(testNotification("retried")), null, true))
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent()
        vm.refreshNotifications()
        runCurrent()
        assertTrue(vm.uiState.value.isPullRefreshingNotifications)
        prefetch.complete(Result.failure(IllegalStateException("offline")))
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals("retried", vm.uiState.value.notifications.single().id)
        assertNull(vm.uiState.value.notificationsError)
        assertFalse(vm.uiState.value.isPullRefreshingNotifications)
    }

    @Test fun accountSwitchDiscardsTheOldQueuedRefreshAndUncancellablePrefetch() = runTest(dispatcher) {
        val prefetch = CompletableDeferred<Result<NotificationPage>>()
        val requests = mutableListOf<String>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                requests += session.sessionId
                return if (session == testAccount) withContext(NonCancellable) { prefetch.await() }
                else Result.success(NotificationPage(listOf(testNotification("second")), null, true))
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        runCurrent()
        vm.onSystemNotificationOpened()
        browsing.activate(secondAccount)
        runCurrent()
        prefetch.complete(Result.success(NotificationPage(listOf(testNotification("old-account")), null, true)))
        advanceUntilIdle()
        assertEquals(listOf(testAccount.sessionId, secondAccount.sessionId), requests)
        assertEquals("second", vm.uiState.value.notifications.single().id)
        assertTrue(repository.markers.isEmpty())
    }
}
