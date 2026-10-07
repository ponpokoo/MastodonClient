package io.github.ponpokoo.mastodonclient.core.security


import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.secondAccount
import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountOrderStoreTest {
    @Test fun reorderPreservesFallbackSelectionWhenOlderStorageHasNoActiveId() = runTest {
        val data = MemoryAuthPreferences(); val store = memoryAuthStore(data)
        store.saveSession(testAccount); store.saveSession(secondAccount)
        data.updateData { preferences -> preferences.toMutablePreferences().apply {
            remove(androidx.datastore.preferences.core.stringPreferencesKey("active_account_session_id"))
        } }
        assertEquals(testAccount, store.getSession())
        store.moveSession(testAccount.sessionId, null)
        assertEquals(listOf(secondAccount, testAccount), store.getSessions())
        assertEquals(testAccount, memoryAuthStore(data).getSession())
    }
    private val third = testAccount.copy(sessionId = "three", instanceUrl = "https://three.example")

    @Test fun movePersistsAcrossStoreRecreationWithoutChangingActiveCredentials() = runTest {
        val data = MemoryAuthPreferences()
        val store = memoryAuthStore(data)
        listOf(testAccount, secondAccount, third).forEach { store.saveSession(it) }
        store.setActiveSession(secondAccount.sessionId)
        store.moveSession(third.sessionId, testAccount.sessionId)
        val restored = memoryAuthStore(data)
        assertEquals(listOf(third, testAccount, secondAccount), restored.getSessions())
        assertEquals(secondAccount, restored.getSession())
        restored.moveSession(third.sessionId, secondAccount.sessionId)
        assertEquals(listOf(testAccount, third, secondAccount), store.getSessions())
        assertEquals(secondAccount, store.getSession())
    }

    @Test fun addAppendReauthorizeReplaceAndDeleteKeepUserOrder() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount); store.saveSession(secondAccount)
        store.moveSession(secondAccount.sessionId, testAccount.sessionId)
        store.saveSession(third)
        val reauthorized = secondAccount.copy(sessionId = "replacement", accessToken = "new-test-token")
        store.saveSession(reauthorized)
        assertEquals(listOf(reauthorized, testAccount, third), store.getSessions())
        store.removeSession(testAccount.sessionId)
        assertEquals(listOf(reauthorized, third), store.getSessions())
        assertEquals(reauthorized, store.getSession())
        store.removeSession(reauthorized.sessionId)
        assertEquals(third, store.getSession())
    }

    @Test fun metadataWritesAndDropPlacementDoNotOverwriteEachOther() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount); store.saveSession(secondAccount)
        val author = testStatus("post").author.copy(id = testAccount.accountId, displayName = "Fresh")
        val firstUpdate = store.updateAccountDisplay(testAccount, author)!!
        store.moveSession(testAccount.sessionId, null)
        val secondUpdate = store.updateAccountDisplay(testAccount, author.copy(displayName = "Newest"))!!
        assertEquals(listOf(secondAccount, secondUpdate), store.getSessions())
        assertEquals(firstUpdate.avatarRevision + 1, secondUpdate.avatarRevision)
        assertEquals(secondAccount, store.getSession())
    }

    @Test fun unchangedPlacementIsNoOpAndMissingTargetsCannotRestoreDeletedAccounts() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount); store.saveSession(secondAccount)
        store.moveSession(testAccount.sessionId, secondAccount.sessionId)
        store.moveSession(secondAccount.sessionId, null)
        store.moveSession(testAccount.sessionId, testAccount.sessionId)
        assertEquals(listOf(testAccount, secondAccount), store.getSessions())
        store.removeSession(testAccount.sessionId)
        assertTrue(runCatching { store.moveSession(testAccount.sessionId, null) }.isFailure)
        assertTrue(runCatching { store.moveSession(secondAccount.sessionId, "removed-target") }.isFailure)
        assertEquals(listOf(secondAccount), store.getSessions())
    }

    @Test fun dropUsesStableDestinationAfterOtherRegistrationsChange() = runTest {
        val store = memoryAuthStore()
        val fourth = testAccount.copy(sessionId = "four", instanceUrl = "https://four.example")
        listOf(testAccount, secondAccount, third, fourth).forEach { store.saveSession(it) }
        // A preceding row disappears after the UI calculated the destination.
        store.removeSession(testAccount.sessionId)
        val updated = store.updateAccountDisplay(secondAccount,
            testStatus().author.copy(id = secondAccount.accountId, displayName = "Fresh"))!!
        store.moveSession(fourth.sessionId, secondAccount.sessionId)
        assertEquals(listOf(fourth, updated, third), store.getSessions())
        assertEquals(fourth, store.getSession())
        store.moveSession(fourth.sessionId, null)
        assertEquals(listOf(updated, third, fourth), store.getSessions())
    }

    @Test fun removedDropDestinationRejectsSaveAndRetainsNewRegistration() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount); store.saveSession(secondAccount)
        store.removeSession(secondAccount.sessionId); store.saveSession(third)
        assertTrue(runCatching { store.moveSession(testAccount.sessionId, secondAccount.sessionId) }.isFailure)
        assertEquals(listOf(testAccount, third), store.getSessions())
        assertEquals(third, store.getSession())
    }
}
