package io.github.ponpokoo.mastodonclient.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.secondAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class MemoryAuthPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    override val data = MutableStateFlow(initial)
    private val mutex = Mutex()
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = mutex.withLock {
        transform(data.value).also { data.value = it }
    }
}

fun memoryAuthStore(data: DataStore<Preferences> = MemoryAuthPreferences()) = SecureAuthStore(data, { it }, { it })

class AccountDisplayStoreTest {
    private val author = StatusAuthor(testAccount.accountId, "New name", "new-user", "https://one.example/avatar.png")

    @Test fun updatePreservesOrderSelectionCredentialsAndSurvivesStoreRecreation() = runTest {
        val data = MemoryAuthPreferences()
        val store = memoryAuthStore(data)
        store.saveSession(testAccount)
        store.saveSession(secondAccount)
        val updated = store.updateAccountDisplay(testAccount, author)!!
        assertEquals(listOf(testAccount.sessionId, secondAccount.sessionId), store.getSessions().map { it.sessionId })
        assertEquals(secondAccount, store.getSession())
        assertEquals(testAccount.copy(username = author.accountName, displayName = author.displayName,
            avatarUrl = author.avatarUrl, avatarRevision = 1), updated)
        val restored = memoryAuthStore(data)
        assertEquals(updated, restored.getSessions().first())
        assertEquals(store.getSessions(), restored.observeSessions().first())
    }

    @Test fun deletedAndReauthorizedAccountsRejectLateWritesInsideStore() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount)
        store.removeSession(testAccount.sessionId)
        assertNull(store.updateAccountDisplay(testAccount, author))
        assertTrue(store.getSessions().isEmpty())
        val reauthorized = testAccount.copy(accessToken = "replacement-token", scopes = "read write push")
        store.saveSession(reauthorized)
        assertNull(store.updateAccountDisplay(testAccount, author))
        assertEquals(reauthorized, store.getSession())
        assertNull(store.updateAccountDisplay(reauthorized, author.copy(id = "someone-else")))
        assertEquals(reauthorized, store.getSession())
    }

    @Test fun unchangedAvatarUrlStillAdvancesOnlyTheTargetRevision() = runTest {
        val store = memoryAuthStore()
        val original = testAccount.copy(avatarUrl = author.avatarUrl)
        store.saveSession(original)
        store.saveSession(secondAccount)
        store.updateAccountDisplay(original, author)
        store.updateAccountDisplay(original, author)
        assertEquals(2L, store.getSessions().first().avatarRevision)
        assertEquals(secondAccount, store.getSessions().last())
    }

    @Test fun legacySessionWithoutRevisionCanBeUpdatedAndRemoved() = runTest {
        val initial = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("account_session")] = """{"sessionId":"one","instanceUrl":"https://one.example","accountId":"me","username":"me","displayName":"Me","avatarUrl":"","accessToken":"test-token"}"""
        }
        val store = memoryAuthStore(MemoryAuthPreferences(initial))
        assertEquals(testAccount, store.getSession())
        assertEquals(testAccount, store.observeSessions().first().single())
        store.updateAccountDisplay(testAccount, author)
        assertEquals("New name", store.getSession()?.displayName)
        store.removeSession(testAccount.sessionId)
        assertTrue(store.observeSessions().first().isEmpty())
    }

    @Test fun queuedMetadataWriteRechecksDeletionAndReauthorizationAtCommit() = runTest {
        for (remove in listOf(false, true)) {
            val backing = MemoryAuthPreferences()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var delayNextWrite = false
            val data = object : DataStore<Preferences> {
                override val data = backing.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    if (delayNextWrite) {
                        delayNextWrite = false
                        started.complete(Unit)
                        release.await()
                    }
                    return backing.updateData(transform)
                }
            }
            val store = memoryAuthStore(data)
            store.saveSession(testAccount)
            delayNextWrite = true
            val pending = async { store.updateAccountDisplay(testAccount, author) }
            started.await()
            val reauthorized = testAccount.copy(accessToken = "replacement-token")
            if (remove) store.removeSession(testAccount.sessionId) else store.saveSession(reauthorized)
            release.complete(Unit)
            assertNull(pending.await())
            assertEquals(if (remove) emptyList() else listOf(reauthorized), store.getSessions())
        }
    }
}
