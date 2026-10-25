package io.github.ponpokoo.mastodonclient.core.security

import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.secondAccount
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReauthorizationStoreTest {
    @Test fun replacingInactiveAccountPreservesSelectionOrderAndSurvivesRestart() = runTest {
        val data = MemoryAuthPreferences()
        val store = memoryAuthStore(data)
        store.saveSession(testAccount); store.saveSession(secondAccount)
        val updated = testAccount.copy(accessToken = "new", scopes = "read write push")
        assertTrue(store.replaceSession(testAccount, updated))
        val restored = memoryAuthStore(data)
        assertEquals(listOf(updated, secondAccount), restored.getSessions())
        assertEquals(secondAccount, restored.getSession())
        val activeUpdated = secondAccount.copy(accessToken = "also-new")
        assertTrue(restored.replaceSession(secondAccount, activeUpdated))
        assertEquals(activeUpdated, restored.getSession())
    }

    @Test fun removedOrReplacedCredentialsCannotBeRestoredByLateReauthorization() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount); store.saveSession(secondAccount)
        val updated = testAccount.copy(accessToken = "new")
        assertTrue(store.replaceSession(testAccount, updated))
        assertFalse(store.replaceSession(testAccount, testAccount.copy(accessToken = "stale")))
        assertEquals(updated, store.getSessions().first())
        store.removeSession(testAccount.sessionId)
        assertFalse(store.replaceSession(updated, testAccount))
        assertEquals(listOf(secondAccount), store.getSessions())
        assertEquals(secondAccount, store.getSession())
    }
}
