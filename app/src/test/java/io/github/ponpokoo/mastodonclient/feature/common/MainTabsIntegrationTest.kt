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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainTabsIntegrationTest : ScreenViewModelTestBase() {
    @Test fun notificationAccountSelectionWaitsForStartupRestore() = runTest(dispatcher) {
        val restoreStarted = CompletableDeferred<Unit>()
        val auth = object : AuthRepository {
            override suspend fun restoreSession(): AccountSession? {
                restoreStarted.complete(Unit)
                awaitCancellation()
            }
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun switchSession(sessionId: String) =
                listOf(testAccount, secondAccount).firstOrNull { it.sessionId == sessionId }
            override suspend fun logout() = Unit
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.failure<String>(UnsupportedOperationException())
            override suspend fun completeAuthorization(callbackUrl: String) = Result.failure<AccountSession>(UnsupportedOperationException())
        }
        val main = own(MainSessionViewModel(auth, ScreenRepositoryFake()))
        runCurrent()
        restoreStarted.await()

        val selected = async { main.switchAccountAndWait(secondAccount.sessionId) }
        advanceUntilIdle()

        assertTrue(selected.await())
        assertEquals(secondAccount.sessionId, main.uiState.value.session?.sessionId)
    }

    @Test fun notificationAccountSelectionWaitsForAnotherAccountChange() = runTest(dispatcher) {
        val switchStarted = CompletableDeferred<Unit>()
        val releaseSwitch = CompletableDeferred<Unit>()
        val auth = object : AuthRepository {
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun switchSession(sessionId: String): AccountSession? {
                if (sessionId == secondAccount.sessionId) {
                    switchStarted.complete(Unit)
                    releaseSwitch.await()
                }
                return listOf(testAccount, secondAccount).firstOrNull { it.sessionId == sessionId }
            }
            override suspend fun logout() = Unit
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.failure<String>(UnsupportedOperationException())
            override suspend fun completeAuthorization(callbackUrl: String) = Result.failure<AccountSession>(UnsupportedOperationException())
        }
        val main = own(MainSessionViewModel(auth, ScreenRepositoryFake()))
        advanceUntilIdle()

        main.switchAccount(secondAccount.sessionId)
        runCurrent()
        switchStarted.await()
        val selected = async { main.switchAccountAndWait(testAccount.sessionId) }
        runCurrent()
        assertFalse(selected.isCompleted)

        releaseSwitch.complete(Unit)
        advanceUntilIdle()
        assertTrue(selected.await())
        assertEquals(testAccount.sessionId, main.uiState.value.session?.sessionId)
    }

    @Test fun cancellationRollsBackAndReleasesPendingAction() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean): Result<TimelineStatus> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val manager = StatusActionManager(repository)
        val pending = requireNotNull(manager.beginFavourite(testAccount, testStatus()))
        val request = launch { manager.complete(pending) }
        runCurrent()
        assertTrue(started.isCompleted)

        request.cancelAndJoin()

        assertNotNull(manager.beginFavourite(testAccount, testStatus()))
    }

    @Test fun reblogUpdatesImmediatelyAndUsesServerResult() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun setReblogged(session: AccountSession, statusId: String, reblogged: Boolean) =
                delayed.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing))
        val actions = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()

        actions.toggleReblog(timeline.uiState.value.statuses.single())
        advanceUntilIdle()
        assertTrue(timeline.uiState.value.statuses.single().reblogged)
        assertEquals(1L, timeline.uiState.value.statuses.single().boostsCount)

        delayed.complete(Result.success(testStatus().copy(reblogged = true, boostsCount = 3)))
        advanceUntilIdle()
        assertTrue(timeline.uiState.value.statuses.single().reblogged)
        assertEquals(3L, timeline.uiState.value.statuses.single().boostsCount)
    }

    @Test fun favouriteRemovalUpdatesImmediatelyAndDoesNotMakeCountNegative() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<TimelineStatus>>()
        val initial = testStatus().copy(favourited = true, favouritesCount = 1)
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(listOf(initial), null, true))
            override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean) =
                delayed.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing))
        val actions = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()

        actions.toggleFavourite(timeline.uiState.value.statuses.single())
        advanceUntilIdle()
        assertFalse(timeline.uiState.value.statuses.single().favourited)
        assertEquals(0L, timeline.uiState.value.statuses.single().favouritesCount)

        delayed.complete(Result.success(initial.copy(favourited = false, favouritesCount = 0)))
        advanceUntilIdle()
        assertFalse(timeline.uiState.value.statuses.single().favourited)
        assertEquals(0L, timeline.uiState.value.statuses.single().favouritesCount)
    }

    @Test fun favouriteUpdatesImmediatelyBlocksRepeatedTapAndRollsBackOnFailure() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            var requests = 0
            override suspend fun setFavourite(session: AccountSession, statusId: String, favourite: Boolean): Result<TimelineStatus> {
                requests++
                return delayed.await()
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing))
        val actions = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()

        actions.toggleFavourite(timeline.uiState.value.statuses.single())
        advanceUntilIdle()
        assertTrue(timeline.uiState.value.statuses.single().favourited)
        assertEquals(1L, timeline.uiState.value.statuses.single().favouritesCount)

        actions.toggleFavourite(timeline.uiState.value.statuses.single())
        advanceUntilIdle()
        assertEquals(1, repository.requests)

        delayed.complete(Result.failure(IllegalStateException("network failed")))
        advanceUntilIdle()
        assertFalse(timeline.uiState.value.statuses.single().favourited)
        assertEquals(0L, timeline.uiState.value.statuses.single().favouritesCount)
        assertEquals("network failed", actions.uiState.value.actionMessage)
    }

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

    @Test fun systemNotificationsDoNotBlockStreamAndFinishAfterMovingToBackground() = runTest(dispatcher) {
        val stream = MutableSharedFlow<TimelineStreamEvent>()
        val repository = object : ScreenRepositoryFake() {
            override fun observeUserStream(session: AccountSession) = stream
        }
        val auth = object : AuthRepository {
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun switchSession(sessionId: String) = secondAccount
            override suspend fun logout() = Unit
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.failure<String>(UnsupportedOperationException())
            override suspend fun completeAuthorization(callbackUrl: String) = Result.failure<AccountSession>(UnsupportedOperationException())
        }
        val delivered = mutableListOf<String>()
        val pending = CompletableDeferred<Unit>()
        val stale = CompletableDeferred<Unit>()
        val main = own(MainSessionViewModel(auth, repository,
            systemNotifications = io.github.ponpokoo.mastodonclient.domain.repository.SystemNotificationRepository { session, notification, valid ->
                if (notification.id == "pending") {
                    withContext(NonCancellable) { pending.await() }
                }
                if (notification.id == "stale") {
                    withContext(NonCancellable) { stale.await() }
                }
                if (valid()) delivered += "${session.sessionId}:${notification.id}"
            },
        ))
        val notifications = own(NotificationsViewModel(repository, main.browsing))
        advanceUntilIdle()
        stream.emit(TimelineStreamEvent.NotificationReceived(testNotification("first")))
        advanceUntilIdle()
        assertEquals(listOf("${testAccount.sessionId}:first"), delivered)
        stream.emit(TimelineStreamEvent.NotificationReceived(testNotification("pending")))
        advanceUntilIdle()
        stream.emit(TimelineStreamEvent.NotificationReceived(testNotification("next")))
        advanceUntilIdle()
        assertEquals(3, notifications.uiState.value.unreadNotifications)
        assertTrue(delivered.contains("${testAccount.sessionId}:next"))
        main.setForeground(false)
        pending.complete(Unit)
        advanceUntilIdle()
        assertTrue(delivered.any { it.endsWith(":pending") })
        main.setForeground(true)
        advanceUntilIdle()
        stream.emit(TimelineStreamEvent.NotificationReceived(testNotification("stale")))
        advanceUntilIdle()
        main.switchAccount(secondAccount.sessionId)
        advanceUntilIdle()
        stale.complete(Unit)
        advanceUntilIdle()
        assertFalse(delivered.contains("${testAccount.sessionId}:stale"))
        stream.emit(TimelineStreamEvent.NotificationReceived(testNotification("second")))
        advanceUntilIdle()
        assertTrue(delivered.contains("${secondAccount.sessionId}:second"))
    }

    @Test fun systemNotificationFailureDoesNotStopStreaming() = runTest(dispatcher) {
        val stream = MutableSharedFlow<TimelineStreamEvent>()
        val repository = object : ScreenRepositoryFake() {
            override fun observeUserStream(session: AccountSession) = stream
        }
        val auth = object : AuthRepository {
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount)
            override suspend fun logout() = Unit
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.failure<String>(UnsupportedOperationException())
            override suspend fun completeAuthorization(callbackUrl: String) = Result.failure<AccountSession>(UnsupportedOperationException())
        }
        var attempts = 0
        val main = own(MainSessionViewModel(auth, repository,
            systemNotifications = io.github.ponpokoo.mastodonclient.domain.repository.SystemNotificationRepository { _, _, _ ->
                attempts++
                error("notification unavailable")
            },
        ))
        val notifications = own(NotificationsViewModel(repository, main.browsing))
        advanceUntilIdle()
        stream.emit(TimelineStreamEvent.NotificationReceived(testNotification("one")))
        advanceUntilIdle()
        stream.emit(TimelineStreamEvent.NotificationReceived(testNotification("two")))
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(2, notifications.uiState.value.unreadNotifications)
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
