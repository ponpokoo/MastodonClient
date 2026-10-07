package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.security.memoryAuthStore
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.secondAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountDisplaySynchronizerTest {
    private val old = StatusAuthor(testAccount.accountId, "Old", "old", "same-url")
    private val fresh = old.copy(displayName = "Fresh", accountName = "fresh")

    @Test fun latestRequestWinsAcrossAuthenticationAndProfilePaths() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount)
        val sync = AccountDisplaySynchronizer(store)
        val background = sync.begin(testAccount)
        val profile = sync.begin(testAccount)
        sync.commit(profile, fresh)
        val superseded = sync.commit(background, old)
        assertEquals("Fresh", store.getSession()?.displayName)
        assertEquals("Fresh", superseded?.displayName)
        assertEquals(1L, store.getSession()?.avatarRevision)
    }

    @Test fun failedNewerRequestDoesNotLetAnOlderResultOverwriteStoredData() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount)
        val sync = AccountDisplaySynchronizer(store)
        val oldRequest = sync.begin(testAccount)
        sync.begin(testAccount) // Newer operation fails before commit.
        sync.commit(oldRequest, old)
        assertEquals(testAccount, store.getSession())
    }

    @Test fun switchingAwayAndBackInvalidatesOldGenerationEvenWithSameCredentials() = runTest {
        val store = memoryAuthStore()
        store.saveSession(secondAccount)
        store.saveSession(testAccount)
        val sync = AccountDisplaySynchronizer(store)
        val request = sync.begin(testAccount)
        sync.invalidate()
        store.setActiveSession(secondAccount.sessionId)
        sync.invalidate()
        store.setActiveSession(testAccount.sessionId)
        sync.commit(request, old)
        assertEquals(testAccount, store.getSession())
        sync.commit(sync.begin(testAccount), fresh)
        assertEquals("Fresh", store.getSession()?.displayName)
    }

    @Test fun cancelledNonCooperativeRequestCannotPersistItsResponse() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount)
        val sync = AccountDisplaySynchronizer(store)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<StatusAuthor>()
        val request = async {
            val ticket = sync.begin(testAccount)
            started.complete(Unit)
            val author = withContext(NonCancellable) { response.await() }
            sync.commit(ticket, author)
        }
        started.await()
        request.cancel()
        response.complete(old)
        request.join()
        assertEquals(testAccount, store.getSession())
    }

    @Test fun authenticationWriteAndGenerationChangeExcludeAnOldResponseEvenIfCredentialsAreReused() = runTest {
        val store = memoryAuthStore()
        store.saveSession(testAccount)
        val sync = AccountDisplaySynchronizer(store)
        val oldRequest = sync.begin(testAccount)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mutation = async {
            sync.changeRegistration {
                writing.complete(Unit)
                release.await()
                store.saveSession(testAccount.copy(displayName = "Reauthorized"))
            }
        }
        writing.await()
        val late = async { sync.commit(oldRequest, old) }
        release.complete(Unit)
        mutation.await()
        assertNull(late.await())
        assertEquals("Reauthorized", store.getSession()?.displayName)
    }
}
