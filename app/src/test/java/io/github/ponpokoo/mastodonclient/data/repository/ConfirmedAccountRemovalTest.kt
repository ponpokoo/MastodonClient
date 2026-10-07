package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.memoryAuthStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.PushAuthLifecycle
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.secondAccount
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmedAccountRemovalTest {
    private open class Lifecycle : PushAuthLifecycle {
        val removed = mutableListOf<AccountSession>()
        override suspend fun beforeLogout(session: AccountSession) { removed += session }
        override suspend fun beforeReauthorization(session: AccountSession) { }
        override suspend fun afterAuthorization(session: AccountSession) { }
    }

    @Test fun staleConfirmationCannotDeleteSwitchedOrReauthorizedAccount() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount); store.saveSession(secondAccount)
        val lifecycle = Lifecycle()
        val repository = DefaultAuthRepository(ApiClientFactory(), store, lifecycle)
        assertFalse(repository.logout(testAccount))
        store.setActiveSession(testAccount.sessionId)
        val replacement = testAccount.copy(accessToken = "new-test-token")
        store.saveSession(replacement)
        assertFalse(repository.logout(testAccount))
        assertEquals(listOf(replacement, secondAccount), store.getSessions())
        assertTrue(lifecycle.removed.isEmpty())
    }

    @Test fun metadataRefreshKeepsConfirmationValidAndRemainingOrderSelectsFallback() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount); store.saveSession(secondAccount)
        store.moveSession(secondAccount.sessionId, testAccount.sessionId)
        val renamed = testAccount.copy(displayName = "Fresh", avatarRevision = 1)
        store.saveSession(renamed)
        val lifecycle = Lifecycle()
        val repository = DefaultAuthRepository(ApiClientFactory(), store, lifecycle)
        assertTrue(repository.logout(testAccount))
        assertEquals(listOf(renamed), lifecycle.removed)
        assertEquals(listOf(secondAccount), store.getSessions())
        assertEquals(secondAccount, repository.restoreSession())
        assertTrue(repository.logout(secondAccount))
        assertNull(repository.restoreSession())
        assertTrue(store.getSessions().isEmpty())
    }

    @Test fun switchDuringPushCleanupWaitsForRemovalAndCannotChangeTarget() = runTest {
        val store = memoryAuthStore()
        store.saveSession(secondAccount); store.saveSession(testAccount)
        val gate = CompletableDeferred<Unit>()
        val lifecycle = object : Lifecycle() {
            override suspend fun beforeLogout(session: AccountSession) { super.beforeLogout(session); gate.await() }
        }
        val repository = DefaultAuthRepository(ApiClientFactory(), store, lifecycle)
        val removal = async { repository.logout(testAccount) }
        runCurrent()
        val switch = async { repository.switchSession(secondAccount.sessionId) }
        runCurrent()
        assertFalse(switch.isCompleted)
        gate.complete(Unit)
        assertTrue(removal.await())
        assertEquals(secondAccount, switch.await())
        assertEquals(listOf(testAccount), lifecycle.removed)
        assertEquals(listOf(secondAccount), store.getSessions())
    }

    @Test fun cleanupPersistenceFailureRetainsRegistrationForRetry() = runTest {
        val store = memoryAuthStore(); store.saveSession(testAccount)
        val lifecycle = object : Lifecycle() {
            override suspend fun beforeLogout(session: AccountSession) { throw java.io.IOException("disk") }
        }
        val repository = DefaultAuthRepository(ApiClientFactory(), store, lifecycle)
        assertTrue(runCatching { repository.logout(testAccount) }.isFailure)
        assertEquals(testAccount, store.getSession())
    }
}
