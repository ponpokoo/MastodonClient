package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.PushSyncRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PushRecoveryViewModelTest : ScreenViewModelTestBase() {
    @Test fun startupAndForegroundRecoverAllAccountsWithoutBlockingSessionRestore() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<String?, Boolean>>()
        val auth = object : AuthRepository {
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.failure<String>(UnsupportedOperationException())
            override suspend fun completeAuthorization(callbackUrl: String) = Result.failure<AccountSession>(UnsupportedOperationException())
        }
        val model = own(MainSessionViewModel(auth, ScreenRepositoryFake(), pushSync = PushSyncRepository { id, force ->
            requests += id to force
            throw IOException("offline")
        }))
        advanceUntilIdle()
        assertEquals(testAccount, model.uiState.value.session)
        assertNull(model.uiState.value.errorMessage)
        model.setForeground(false); advanceUntilIdle()
        assertEquals(1, requests.size)
        model.setForeground(true); advanceUntilIdle()
        assertEquals(listOf(null to false, null to false), requests)
    }

    @Test fun initialAndExplicitNotificationRefreshRecoverOnlyTheirCurrentAccount() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<String?, Boolean>>()
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val model = own(NotificationsViewModel(ScreenRepositoryFake(), browsing, pushSync = PushSyncRepository { id, force ->
            requests += id to force
            throw IOException("offline")
        }))
        advanceUntilIdle()
        model.loadNotifications(); advanceUntilIdle()
        assertTrue(model.uiState.value.isInitialPageLoaded)
        assertNull(model.uiState.value.notificationsError)
        model.loadNotifications(); advanceUntilIdle()
        assertEquals(listOf(testAccount.sessionId to false), requests)
        model.loadNotifications(force = true); advanceUntilIdle()
        assertEquals(listOf(testAccount.sessionId to false, testAccount.sessionId to false), requests)
        browsing.activate(secondAccount); advanceUntilIdle()
        assertEquals(secondAccount.sessionId to false, requests.last())
    }
}
