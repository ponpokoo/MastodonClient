package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.memoryAuthStore
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAuthRepository
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.PushSettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagementViewModelTest : ScreenViewModelTestBase() {
    private class Push : PushControlRepository {
        override val states = MutableStateFlow<Map<String, PushControlState>>(emptyMap())
        override suspend fun refresh() { }
        override suspend fun setEnabled(sessionId: String, enabled: Boolean) { }
        override suspend fun tokenChanged(token: String?) { }
    }

    @Test fun reorderPreservesBrowsingGenerationStreamAndSelectionAndRetriesFailedSave() = runTest(dispatcher) {
        val store = memoryAuthStore(); store.saveSession(testAccount); store.saveSession(secondAccount)
        var fail = true
        val delegate = DefaultAuthRepository(ApiClientFactory(), store)
        val auth = object : AuthRepository by delegate {
            override suspend fun refreshAccountDisplay(session: AccountSession) = Result.success(Unit)
            override suspend fun moveAccount(sessionId: String, beforeSessionId: String?): Result<Unit> =
                if (fail) Result.failure(java.io.IOException("disk")) else delegate.moveAccount(sessionId, beforeSessionId)
        }
        var connections = 0
        val repository = object : ScreenRepositoryFake() {
            override fun observeUserStream(session: AccountSession) = flow<TimelineStreamEvent> { connections++; awaitCancellation() }
        }
        val model = own(MainSessionViewModel(auth, repository)); runCurrent()
        val snapshot = model.browsing.snapshot.value
        model.moveAccount(secondAccount.sessionId, testAccount.sessionId); runCurrent()
        assertEquals(listOf(testAccount, secondAccount), model.uiState.value.sessions)
        assertNotNull(model.uiState.value.accountOrderError)
        assertFalse(model.uiState.value.changingAccountOrder)
        fail = false
        model.moveAccount(secondAccount.sessionId, testAccount.sessionId); runCurrent()
        assertEquals(listOf(secondAccount, testAccount), model.uiState.value.sessions)
        assertNull(model.uiState.value.accountOrderError)
        assertEquals(snapshot, model.browsing.snapshot.value)
        assertEquals(secondAccount, model.uiState.value.session)
        assertEquals(1, connections)
    }

    @Test fun confirmedRemovalIsSingleFlightAndNavigatesToRemainingAccount() = runTest(dispatcher) {
        val store = memoryAuthStore(); store.saveSession(testAccount); store.saveSession(secondAccount)
        val delegate = DefaultAuthRepository(ApiClientFactory(), store)
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val auth = object : AuthRepository by delegate {
            override suspend fun logout(expected: AccountSession): Boolean { calls++; gate.await(); return delegate.logout(expected) }
        }
        val model = own(PushSettingsViewModel(Push(), auth))
        model.logout(secondAccount); model.logout(secondAccount); runCurrent()
        assertTrue(model.busy.value); assertEquals(1, calls)
        assertEquals(0, model.authenticated.value)
        gate.complete(Unit); runCurrent()
        assertFalse(model.busy.value); assertFalse(model.requiresLogin)
        assertEquals(1, model.authenticated.value)
        assertEquals(testAccount, store.getSession())
        model.navigationHandled(); model.logout(testAccount); runCurrent()
        assertTrue(model.requiresLogin); assertEquals(1, model.authenticated.value)
    }

    @Test fun staleRemovalOrFailureShowsRetryErrorWithoutNavigationOrDeletion() = runTest(dispatcher) {
        val store = memoryAuthStore(); store.saveSession(testAccount); store.saveSession(secondAccount)
        val delegate = DefaultAuthRepository(ApiClientFactory(), store)
        var diskFailure = false
        val auth = object : AuthRepository by delegate {
            override suspend fun logout(expected: AccountSession): Boolean {
                if (diskFailure) throw java.io.IOException("disk")
                return delegate.logout(expected)
            }
        }
        val model = own(PushSettingsViewModel(Push(), auth))
        model.logout(testAccount); runCurrent()
        assertNotNull(model.error.value); assertEquals(0, model.authenticated.value)
        assertEquals(listOf(testAccount, secondAccount), store.getSessions())
        diskFailure = true; model.logout(secondAccount); runCurrent()
        assertFalse(model.busy.value); assertNotNull(model.error.value)
        assertEquals(0, model.authenticated.value)
        diskFailure = false; model.logout(secondAccount); runCurrent()
        assertNull(model.error.value); assertEquals(1, model.authenticated.value)
    }
}
