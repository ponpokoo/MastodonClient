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
