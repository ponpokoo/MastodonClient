package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.memoryAuthStore
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAuthRepository
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.PushSettingsViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushReauthorizationViewModelTest : ScreenViewModelTestBase() {
    private class Push : PushControlRepository {
        override val states = MutableStateFlow<Map<String, PushControlState>>(emptyMap())
        var refreshes = 0
        override suspend fun refresh() { refreshes++ }
        override suspend fun setEnabled(sessionId: String, enabled: Boolean) = Unit
        override suspend fun tokenChanged(token: String?) = Unit
    }

    @Test fun activeReauthorizationReplacesStreamAndRejectsOldTimelineEvenWhenCancellationIsIgnored() = runTest(dispatcher) {
        val store = memoryAuthStore(); store.saveSession(testAccount)
        val delegate = DefaultAuthRepository(ApiClientFactory(), store)
        val auth = object : AuthRepository by delegate {
            override suspend fun refreshAccountDisplay(session: AccountSession) = Result.success(Unit)
        }
        val oldResponse = CompletableDeferred<TimelinePage>()
        val streams = mutableListOf<AccountSession>()
        var oldStreamCancelled = false
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                if (session.accessToken == testAccount.accessToken) Result.success(withContext(NonCancellable) { oldResponse.await() })
                else Result.success(TimelinePage(listOf(testStatus("new-page")), null, true))
            override fun observeUserStream(session: AccountSession) = flow<TimelineStreamEvent> {
                streams += session
                try { awaitCancellation() }
                finally { if (session.accessToken == testAccount.accessToken) oldStreamCancelled = true }
            }
        }
        val main = own(MainSessionViewModel(auth, repository))
        val timeline = own(TimelineViewModel(repository, main.browsing))
        runCurrent()
        val before = main.browsing.snapshot.value
        val updated = testAccount.copy(accessToken = "new", scopes = "read write push")
        assertTrue(store.replaceSession(testAccount, updated)); runCurrent()
        assertEquals(updated, main.uiState.value.session)
        assertEquals(updated, main.browsing.snapshot.value.account)
        assertTrue(main.browsing.snapshot.value.generation > before.generation)
        assertTrue(oldStreamCancelled)
        assertEquals(listOf(testAccount, updated), streams)
        oldResponse.complete(TimelinePage(listOf(testStatus("old-page")), null, true)); runCurrent()
        assertEquals(listOf("new-page"), timeline.uiState.value.statuses.map { it.statusId })
    }

    @Test fun inactiveReauthorizationKeepsBrowsingAndDoesNotRequestNavigation() = runTest(dispatcher) {
        val store = memoryAuthStore(); store.saveSession(testAccount); store.saveSession(secondAccount)
        val updated = testAccount.copy(accessToken = "new", scopes = "read write push")
        val delegate = DefaultAuthRepository(ApiClientFactory(), store)
        val auth = object : AuthRepository by delegate {
            override suspend fun refreshAccountDisplay(session: AccountSession) = Result.success(Unit)
            override suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession> {
                check(store.replaceSession(testAccount, updated))
                return Result.success(updated)
            }
        }
        val main = own(MainSessionViewModel(auth, ScreenRepositoryFake())); runCurrent()
        val before = main.browsing.snapshot.value
        val push = Push()
        val settings = own(PushSettingsViewModel(push, auth))
        settings.complete("callback"); runCurrent()
        assertEquals(before, main.browsing.snapshot.value)
        assertEquals(secondAccount, main.uiState.value.session)
        assertEquals(secondAccount, store.getSession())
        assertEquals(listOf(updated, secondAccount), main.uiState.value.sessions)
        assertEquals(0, settings.accountRemoved.value)
        assertEquals(1, push.refreshes)
        assertNull(settings.error.value)
        assertFalse(settings.busy.value)
    }

    @Test fun pendingRestoreCannotOverwriteReauthorizedCredentials() = runTest(dispatcher) {
        val store = memoryAuthStore(); store.saveSession(testAccount)
        val delegate = DefaultAuthRepository(ApiClientFactory(), store)
        val gate = CompletableDeferred<Unit>()
        var delayRestore = false
        val auth = object : AuthRepository by delegate {
            override suspend fun refreshAccountDisplay(session: AccountSession) = Result.success(Unit)
            override suspend fun restoreSession(): AccountSession? {
                val account = delegate.restoreSession()
                if (delayRestore) gate.await()
                return account
            }
        }
        val main = own(MainSessionViewModel(auth, ScreenRepositoryFake())); runCurrent()
        delayRestore = true
        main.restore(); runCurrent()
        val updated = testAccount.copy(accessToken = "new", scopes = "read write push")
        assertTrue(store.replaceSession(testAccount, updated)); runCurrent()
        val current = main.browsing.snapshot.value
        gate.complete(Unit); runCurrent()
        assertEquals(updated, main.uiState.value.session)
        assertEquals(current, main.browsing.snapshot.value)
    }

    @Test fun failedOrDeniedReauthorizationKeepsCredentialsAndAllowsRetryWithoutNavigation() = runTest(dispatcher) {
        val store = memoryAuthStore(); store.saveSession(testAccount)
        val delegate = DefaultAuthRepository(ApiClientFactory(), store)
        var succeed = false
        val auth = object : AuthRepository by delegate {
            override suspend fun completeAuthorization(callbackUrl: String) =
                if (succeed) Result.success(testAccount) else Result.failure<AccountSession>(IllegalArgumentException("consent denied"))
        }
        val settings = own(PushSettingsViewModel(Push(), auth))
        settings.complete("callback"); runCurrent()
        assertNotNull(settings.error.value)
        assertFalse(settings.busy.value)
        assertEquals(testAccount, store.getSession())
        assertEquals(0, settings.accountRemoved.value)
        succeed = true
        settings.complete("retry"); runCurrent()
        assertNull(settings.error.value)
        assertEquals(0, settings.accountRemoved.value)
    }
}
