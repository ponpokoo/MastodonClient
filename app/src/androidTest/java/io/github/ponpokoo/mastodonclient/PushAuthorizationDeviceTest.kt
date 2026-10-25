package io.github.ponpokoo.mastodonclient

import android.net.Uri
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.*
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAuthRepository
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.PushAuthLifecycle
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PushAuthorizationDeviceTest {
    private val account = AccountSession("existing", "https://instance.example", "123456789012345678901234567890", "test", "Test", "", "old", "read write")
    private class MemoryAuth(account: AccountSession?) : AuthStore {
        private var accounts = listOfNotNull(account)
        private var activeId = account?.sessionId
        var pending: PendingOAuth? = null
        var application: RegisteredApplication? = null
        override suspend fun findApplication(instanceUrl: String) = application
        override suspend fun saveApplication(application: RegisteredApplication) { this.application = application }
        override suspend fun savePending(pending: PendingOAuth) { this.pending = pending }
        override suspend fun getPending() = pending
        override suspend fun clearPending() { pending = null }
        override suspend fun saveSession(session: AccountSession) {
            accounts = accounts.filterNot { it.sessionId == session.sessionId } + session
            activeId = session.sessionId
        }
        override suspend fun replaceSession(expected: AccountSession, session: AccountSession): Boolean {
            if (expected !in accounts) return false
            accounts = accounts.map { if (it.sessionId == expected.sessionId) session else it }
            return true
        }
        override suspend fun getSessions() = accounts
        override suspend fun getSession() = accounts.firstOrNull { it.sessionId == activeId }
        override suspend fun setActiveSession(sessionId: String) = accounts.firstOrNull { it.sessionId == sessionId }?.also { activeId = sessionId }
        override suspend fun removeSession(sessionId: String) { accounts = accounts.filterNot { it.sessionId == sessionId } }
    }
    private inner class Fixture {
        val store = MemoryAuth(account)
        val events = mutableListOf<String>()
        var responseAccountId = account.accountId
        var grantedScopes = "read write push"
        var requests = 0
        var onTokenExchange: () -> Unit = {}
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val body = when (chain.request().url.encodedPath) {
                "/api/v1/apps" -> """{"id":"app","client_id":"client","client_secret":"secret","redirect_uri":"${DefaultAuthRepository.REDIRECT_URI}"}"""
                "/oauth/token" -> {
                    onTokenExchange()
                    """{"access_token":"new","token_type":"Bearer","scope":"$grantedScopes","created_at":1}"""
                }
                "/api/v1/accounts/verify_credentials" -> """{"id":"$responseAccountId","username":"test","acct":"test"}"""
                else -> error("Unexpected endpoint")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = DefaultAuthRepository(ApiClientFactory(client), store, object : PushAuthLifecycle {
            override suspend fun beforeLogout(session: AccountSession) { events += "logout:${session.accessToken}" }
            override suspend fun beforeReauthorization(session: AccountSession) { events += "cleanup:${session.accessToken}" }
            override suspend fun afterAuthorization(session: AccountSession) { events += "register:${session.accessToken}" }
        })
        fun callback(state: String = store.pending!!.state, suffix: String = "code=synthetic") = "${DefaultAuthRepository.REDIRECT_URI}?state=$state&$suffix"
    }
    @Test fun pushReauthorizationRequestsScopePreservesIdentityAndReplacesCredential() = runBlocking {
        val f = Fixture()
        val url = Uri.parse(f.repo.createPushAuthorizationUrl(account.sessionId).getOrThrow())
        assertEquals("read write push", url.getQueryParameter("scope"))
        assertEquals("S256", url.getQueryParameter("code_challenge_method"))
        assertEquals("true", url.getQueryParameter("force_login"))
        assertTrue(f.repo.pendingPushAuthorization())
        val session = f.repo.completeAuthorization(f.callback()).getOrThrow()
        assertEquals(account.sessionId, session.sessionId)
        assertEquals(account.accountId, session.accountId)
        assertEquals("new", session.accessToken)
        assertEquals("read write push", session.scopes)
        assertEquals(listOf("cleanup:old", "register:new"), f.events)
        assertNull(f.store.pending)
    }
    @Test fun wrongAccountAndInsufficientScopeDoNotReplaceExistingSession() = runBlocking {
        val f = Fixture(); f.repo.createPushAuthorizationUrl(account.sessionId).getOrThrow()
        f.responseAccountId = "different"
        assertTrue(f.repo.completeAuthorization(f.callback()).isFailure)
        assertEquals(account, f.store.getSession())
        f.repo.createPushAuthorizationUrl(account.sessionId).getOrThrow()
        f.responseAccountId = account.accountId; f.grantedScopes = "read write"
        assertTrue(f.repo.completeAuthorization(f.callback()).isFailure)
        assertEquals(account, f.store.getSession())
        assertFalse(f.events.any { it.startsWith("register:") })
    }
    @Test fun invalidStateAndDeniedConsentDoNotExchangeTokenOrDestroyOriginalSession() = runBlocking {
        val f = Fixture(); f.repo.createPushAuthorizationUrl(account.sessionId).getOrThrow()
        val requests = f.requests
        assertTrue(f.repo.completeAuthorization(f.callback("wrong")).isFailure)
        assertNotNull(f.store.pending)
        assertTrue(f.repo.completeAuthorization(f.callback(suffix = "error=access_denied")).isFailure)
        assertEquals(requests, f.requests)
        assertEquals(account, f.store.getSession()); assertNull(f.store.pending)
    }
    @Test fun ordinaryLoginKeepsOriginalScopesAndLogoutCleansBeforeRemovingSession() = runBlocking {
        val f = Fixture()
        assertEquals("read write", Uri.parse(f.repo.createAuthorizationUrl(account.instanceUrl).getOrThrow()).getQueryParameter("scope"))
        assertFalse(f.repo.pendingPushAuthorization())
        f.repo.logout()
        assertEquals(listOf("logout:old"), f.events); assertNull(f.store.getSession())
    }

    @Test fun reauthorizingInactiveAccountPreservesSelectedAccount() = runBlocking {
        val f = Fixture()
        val selected = account.copy(sessionId = "selected", accountId = "other")
        f.store.saveSession(selected)
        f.repo.createPushAuthorizationUrl(account.sessionId).getOrThrow()
        val updated = f.repo.completeAuthorization(f.callback()).getOrThrow()
        assertEquals("new", updated.accessToken)
        assertEquals(selected, f.store.getSession())
        assertEquals(listOf(updated, selected), f.store.getSessions())
    }

    @Test fun removalDuringTokenExchangeCannotRestoreAccount() = runBlocking {
        val f = Fixture()
        f.repo.createPushAuthorizationUrl(account.sessionId).getOrThrow()
        f.onTokenExchange = { runBlocking { f.store.removeSession(account.sessionId) } }
        assertTrue(f.repo.completeAuthorization(f.callback()).isFailure)
        assertTrue(f.store.getSessions().isEmpty())
        assertFalse(f.events.any { it.startsWith("register:") })
    }
}
