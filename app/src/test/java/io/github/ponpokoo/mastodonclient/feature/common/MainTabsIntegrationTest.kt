package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.OwnProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainTabsIntegrationTest : ScreenViewModelTestBase() {
    @Test fun statusActionsUpdateAllTabsAndKeepBoostIdentity() = runTest(dispatcher) {
        val repository = ScreenRepositoryFake()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing))
        val search = own(SearchViewModel(repository, browsing))
        val notifications = own(NotificationsViewModel(repository, browsing))
        val profile = own(OwnProfileViewModel(repository, browsing))
        val actions = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()
        search.onQueryChanged("post")
        search.search()
        notifications.loadNotifications()
        profile.loadProfile()
        advanceUntilIdle()
        actions.toggleFavourite(testStatus())
        advanceUntilIdle()
        assertTrue(timeline.uiState.value.statuses.single().favourited)
        assertEquals("boost-wrapper", timeline.uiState.value.statuses.single().timelineId)
        assertTrue(search.uiState.value.searchResults!!.statuses.single().favourited)
        assertTrue(notifications.uiState.value.notifications.single().status!!.favourited)
        assertTrue(profile.uiState.value.profile!!.statuses.single().favourited)
        actions.deleteStatus(testStatus())
        advanceUntilIdle()
        assertTrue(timeline.uiState.value.statuses.isEmpty())
        assertTrue(search.uiState.value.searchResults!!.statuses.isEmpty())
        assertNull(notifications.uiState.value.notifications.single().status)
        assertTrue(profile.uiState.value.profile!!.statuses.isEmpty())
    }

    @Test fun oldAccountMutationCannotChangeNewAccountWithSameStatusId() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) =
                withContext(NonCancellable) { delayed.await() }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing))
        val actions = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()
        actions.toggleFavourite(testStatus())
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        delayed.complete(Result.success(testStatus().copy(favourited = true)))
        advanceUntilIdle()
        assertFalse(timeline.uiState.value.statuses.single().favourited)
        assertNull(actions.uiState.value.actionMessage)
    }

    @Test fun allTabsShareOneStreamAndBackgroundStopsIt() = runTest(dispatcher) {
        val stream = MutableSharedFlow<TimelineStreamEvent>()
        val repository = object : ScreenRepositoryFake() {
            var connections = 0
            override fun observeUserStream(session: AccountSession) = flow {
                connections++
                try { emitAll(stream) } finally { connections-- }
            }
        }
        val auth = object : AuthRepository {
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun switchSession(sessionId: String) = secondAccount
            override suspend fun logout() = Unit
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.failure<String>(UnsupportedOperationException())
            override suspend fun completeAuthorization(callbackUrl: String) = Result.failure<AccountSession>(UnsupportedOperationException())
        }
        val main = own(MainSessionViewModel(auth, repository))
        val timeline = own(TimelineViewModel(repository, main.browsing))
        val notifications = own(NotificationsViewModel(repository, main.browsing))
        own(SearchViewModel(repository, main.browsing))
        own(OwnProfileViewModel(repository, main.browsing))
        advanceUntilIdle()
        assertEquals(1, repository.connections)
        stream.emit(TimelineStreamEvent.StatusAdded(testStatus("live")))
        stream.emit(TimelineStreamEvent.NotificationReceived(testNotification("live notification")))
        advanceUntilIdle()
        assertEquals("live", timeline.uiState.value.statuses.first().statusId)
        assertEquals(1, notifications.uiState.value.unreadNotifications)
        main.setForeground(false)
        advanceUntilIdle()
        assertEquals(0, repository.connections)
        main.setForeground(true)
        advanceUntilIdle()
        assertEquals(1, repository.connections)
        val oldSnapshot = main.browsing.snapshot.value
        main.switchAccount(secondAccount.sessionId)
        advanceUntilIdle()
        main.browsing.publish(oldSnapshot, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(testStatus("old"))))
        advanceUntilIdle()
        assertEquals(1, repository.connections)
        assertEquals(0, notifications.uiState.value.unreadNotifications)
        assertFalse(timeline.uiState.value.statuses.any { it.statusId == "old" })
        main.setForeground(false)
        advanceUntilIdle()
    }
}
