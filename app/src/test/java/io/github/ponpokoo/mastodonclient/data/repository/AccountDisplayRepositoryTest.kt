package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.memoryAuthStore
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.secondAccount
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class AccountDisplayRepositoryTest {
    private fun account(id: String = testAccount.accountId, name: String = "Fresh") =
        """{"id":"$id","username":"new-user","acct":"new-user","display_name":"$name","avatar":"https://cdn.example/same-avatar.png"}"""

    @Test fun startupVerificationUpdatesStoredDisplayThroughSelectedInstance() = runTest {
        MockWebServer().use { server ->
            val store = memoryAuthStore()
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            store.saveSession(session)
            server.enqueue(MockResponse().setBody(account()))
            DefaultAuthRepository(ApiClientFactory(), store).refreshAccountDisplay(session).getOrThrow()
            assertEquals("Fresh", store.getSession()?.displayName)
            assertEquals("new-user", store.getSession()?.username)
            assertEquals(session.accessToken, store.getSession()?.accessToken)
            assertEquals("/api/v1/accounts/verify_credentials", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun allOwnProfilePathsReuseResponsesWithoutExtraVerificationRequests() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    if (request.path!!.contains("/statuses")) "[]" else account())
            }
            val store = memoryAuthStore()
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            store.saveSession(session)
            val repository = DefaultTimelineRepository(ApiClientFactory(), accountDisplay = AccountDisplaySynchronizer(store))
            val header = repository.getProfileHeader(session, session.accountId).getOrThrow()
            assertEquals(1L, header.author.avatarRevision)
            val profile = repository.getProfile(session, session.accountId).getOrThrow()
            assertEquals(2L, profile.author.avatarRevision)
            val edited = repository.updateProfile(session, ProfileEditRequest("Fresh", "", false, true)).getOrThrow()
            assertEquals(3L, edited.author.avatarRevision)
            assertEquals("Fresh", store.getSession()?.displayName)
            assertEquals(5, server.requestCount)
            val requests = List(5) { server.takeRequest() }
            assertFalse(requests.any { it.path!!.contains("verify_credentials") })
            assertEquals("PATCH", requests.last().method)
        }
    }

    @Test fun otherProfilesNeverUpdateTheRegisteredOwnAccount() = runTest {
        MockWebServer().use { server ->
            val store = memoryAuthStore()
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            store.saveSession(session)
            server.enqueue(MockResponse().setBody(account("other")))
            DefaultTimelineRepository(ApiClientFactory(), accountDisplay = AccountDisplaySynchronizer(store))
                .getProfileHeader(session, "other").getOrThrow()
            assertEquals(session, store.getSession())
        }
    }

    @Test fun failedVerificationKeepsSavedInformation() = runTest {
        MockWebServer().use { server ->
            val store = memoryAuthStore()
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            store.saveSession(session)
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(DefaultAuthRepository(ApiClientFactory(), store).refreshAccountDisplay(session).isFailure)
            assertEquals(session, store.getSession())
        }
    }

    @Test fun repositorySwitchAndLogoutInvalidateProfileTickets() = runTest {
        val store = memoryAuthStore()
        store.saveSession(secondAccount)
        store.saveSession(testAccount)
        val sync = AccountDisplaySynchronizer(store)
        val auth = DefaultAuthRepository(ApiClientFactory(), store, accountDisplay = sync)
        val author = StatusAuthor(testAccount.accountId, "Late", "late", "avatar")
        val oldA = sync.begin(testAccount)
        auth.switchSession(secondAccount.sessionId)
        auth.switchSession(testAccount.sessionId)
        sync.commit(oldA, author)
        assertEquals(testAccount, store.getSession())
        val logoutTicket = sync.begin(testAccount)
        auth.logout()
        sync.commit(logoutTicket, author)
        assertEquals(listOf(secondAccount), store.getSessions())
    }

    @Test fun metadataPersistenceFailureDoesNotTurnSuccessfulProfileReadIntoAnError() = runTest {
        MockWebServer().use { server ->
            val backing = MemoryAuthPreferences()
            var failWrites = false
            val data = object : DataStore<Preferences> {
                override val data = backing.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    if (failWrites) throw java.io.IOException("disk full")
                    return backing.updateData(transform)
                }
            }
            val store = memoryAuthStore(data)
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            store.saveSession(session)
            failWrites = true
            server.enqueue(MockResponse().setBody(account()))
            val profile = DefaultTimelineRepository(ApiClientFactory(), accountDisplay = AccountDisplaySynchronizer(store))
                .getProfileHeader(session, session.accountId).getOrThrow()
            assertEquals("Fresh", profile.author.displayName)
            assertEquals(session, store.getSession())
        }
    }
}
