package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class NotificationPrefetchTest(private val openAfterMillis: Long) : ScreenViewModelTestBase() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "open after {0} ms")
        fun timings() = listOf(0L, 400L, 1_200L)
    }

    @Test fun openingDisplaysPrefetchWithoutWaitingForBackgroundRefreshOrAcknowledging() = runTest(dispatcher) {
        val requested = mutableListOf<NotificationCategory>()
        var savedReadState = NotificationReadState()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> {
                delay(3_000)
                return Result.success(NotificationCapabilities())
            }
            override suspend fun getNotificationMarker(session: AccountSession): Result<String?> {
                delay(4_000)
                return Result.success("old")
            }
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory,
                maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> {
                requested += category
                delay(1_000)
                return Result.success(NotificationPage(listOf(testNotification("new"), testNotification("old")), null, true))
            }
            override suspend fun saveNotificationReadState(session: AccountSession, state: NotificationReadState): Result<Unit> {
                savedReadState = state
                return Result.success(Unit)
            }
        }
        // Account restoration may finish after the ViewModel is created.
        val browsing = BrowsingSession()
        val vm = own(NotificationsViewModel(repository, browsing))
        runCurrent()
        assertTrue(requested.isEmpty())
        browsing.activate(testAccount)
        runCurrent()
        assertEquals(listOf(NotificationCategory.All), requested)

        advanceTimeBy(openAfterMillis)
        runCurrent()
        assertEquals(openAfterMillis >= 1_000, vm.uiState.value.isInitialPageLoaded)
        val openedAt = currentTime
        vm.onNotificationsVisible()
        runCurrent()
        val remainingMillis = (1_000L - openAfterMillis).coerceAtLeast(0)
        advanceTimeBy(remainingMillis)
        runCurrent()

        assertTrue(vm.uiState.value.isInitialPageLoaded)
        assertEquals(listOf("new", "old"), vm.uiState.value.notifications.map { it.id })
        assertEquals(remainingMillis, currentTime - openedAt)
        assertTrue(vm.uiState.value.isResolvingReadState)
        assertFalse(vm.uiState.value.isPullRefreshingNotifications)
        advanceUntilIdle()
        assertEquals(listOf(NotificationCategory.All, NotificationCategory.All), requested)
        assertEquals(setOf("new"), vm.uiState.value.pendingNewNotificationIds)
        assertTrue(repository.markers.isEmpty())
        assertTrue(savedReadState.viewedIds.isEmpty())
        assertNull(savedReadState.latestViewedAllId)
    }
}
