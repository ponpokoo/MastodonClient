package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.core.security.memoryAuthStore
import io.github.ponpokoo.mastodonclient.data.local.AccountDataLocalDataSource
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.secondAccount
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountCleanupRetryTest {
    @Test fun failureKeepsDurableCleanupAcrossRestartWithoutRestoringCredentials() = runTest {
        val data = MemoryAuthPreferences()
        val auth = memoryAuthStore(data)
        auth.saveSession(secondAccount); auth.saveSession(testAccount)
        var fail = true
        val cleaned = mutableListOf<String>()
        val cleanup = AccountDataLocalDataSource { id ->
            assertTrue(auth.getSessions().none { it.sessionId == id })
            if (fail) throw java.io.IOException("disk unavailable")
            cleaned += id
        }
        val repository = DefaultAuthRepository(ApiClientFactory(), auth, accountData = cleanup)
        assertTrue(repository.logout(testAccount))
        assertEquals(listOf(secondAccount), auth.getSessions())
        assertEquals(setOf(testAccount.sessionId), memoryAuthStore(data).pendingAccountDeletions())
        fail = false
        val restarted = DefaultAuthRepository(ApiClientFactory(), memoryAuthStore(data), accountData = cleanup)
        assertEquals(secondAccount, restarted.restoreSession())
        assertEquals(listOf(testAccount.sessionId), cleaned)
        assertTrue(auth.pendingAccountDeletions().isEmpty())
        restarted.retryAccountCleanup()
        assertEquals(1, cleaned.size)
    }

    @Test fun removalJournalIsCommittedEvenIfProcessStopsBeforeCleanup() = runTest {
        val data = MemoryAuthPreferences()
        val auth = memoryAuthStore(data)
        auth.saveSession(testAccount)
        auth.removeSession(testAccount.sessionId)
        val recreated = memoryAuthStore(data)
        assertNull(recreated.getSession())
        assertEquals(setOf(testAccount.sessionId), recreated.pendingAccountDeletions())
    }
}
