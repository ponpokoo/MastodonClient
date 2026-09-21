package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.security.WebPushKeyGenerator
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PushControlRepositoryTest {
    private val account = AccountSession("one", "https://instance.example", "account", "user", "User", "", "oauth", "read write push")
    private class Control : PushControlStore {
        var data = PushControlData()
        override suspend fun read() = data
        override suspend fun write(value: PushControlData) { data = value }
    }
    private class Records : PushRegistrationStore {
        val values = mutableMapOf<String, StoredPushRegistration>()
        override suspend fun read(sessionId: String) = values[sessionId]
        override suspend fun write(sessionId: String, record: StoredPushRegistration) { values[sessionId] = record }
        override suspend fun remove(sessionId: String) { values.remove(sessionId) }
    }
    private inner class Fixture {
        val control = Control()
        val records = Records()
        var accounts = listOf(account)
        var configured = true
        var failure: Exception? = null
        val tokens = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()
        val keys = WebPushKeyGenerator().generate()
        fun repository() = DefaultPushControlRepository({ accounts }, control, records, { configured }) {
            object : PushRegistrationRepository {
                override suspend fun state(session: AccountSession) = records.read(session.sessionId)?.state ?: PushRegistrationState.DISABLED
                override suspend fun enable(session: AccountSession, fcmToken: String, alerts: Map<String, Boolean>, standard: Boolean?) {
                    failure?.let { throw it }
                    tokens += session.sessionId to fcmToken
                    records.write(session.sessionId, StoredPushRegistration(PushRegistrationGuard.binding(session), "https://relay.example/", "id", "secret", keys, state = PushRegistrationState.ACTIVE))
                }
                override suspend fun disable(session: AccountSession) {
                    failure?.let { throw it }
                    removed += session.sessionId
                    records.remove(session.sessionId)
                }
            }
        }
    }
    @Test fun unconfiguredTransportDoesNotPersistOptInOrRegister() = runTest {
        val f = Fixture(); f.configured = false
        val repo = f.repository()
        repo.setEnabled("one", true)
        assertFalse(repo.states.value.getValue("one").enabled)
        assertEquals(PushControlStatus.PREPARING, repo.states.value.getValue("one").status)
        assertTrue(f.control.data.intents.isEmpty()); assertTrue(f.tokens.isEmpty())
    }
    @Test fun legacyAccountRequiresExplicitPushAuthorization() = runTest {
        val f = Fixture(); f.accounts = listOf(account.copy(scopes = ""))
        val repo = f.repository(); repo.tokenChanged("fcm"); repo.setEnabled("one", true)
        assertEquals(PushControlStatus.NEEDS_AUTH, repo.states.value.getValue("one").status)
        assertTrue(f.tokens.isEmpty())
    }
    @Test fun tokenRotationUpdatesAllOptedInAccountsAndRefreshDoesNotRepeatActiveRegistration() = runTest {
        val f = Fixture(); f.accounts += account.copy(sessionId = "two", accountId = "second")
        val repo = f.repository(); repo.tokenChanged("old")
        repo.setEnabled("one", true); repo.setEnabled("two", true); repo.refresh()
        assertEquals(2, f.tokens.size)
        repo.tokenChanged("new")
        assertEquals(listOf("one" to "old", "two" to "old", "one" to "new", "two" to "new"), f.tokens)
    }
    @Test fun offlineLogoutSurvivesRecreationAndFinishesWithoutLoginSession() = runTest {
        val f = Fixture(); val repo = f.repository()
        repo.tokenChanged("fcm"); repo.setEnabled("one", true)
        f.failure = IOException("offline")
        repo.beforeLogout(account); f.accounts = emptyList()
        assertEquals(account, f.control.data.intents.getValue("one").cleanup)
        assertEquals(PushRegistrationState.REMOVING, f.records.values.getValue("one").state)
        assertEquals(PushControlStatus.REMOVING, repo.states.value.getValue("one").status)
        f.failure = null; f.repository().refresh()
        assertTrue(f.control.data.intents.isEmpty()); assertTrue(f.records.values.isEmpty())
        assertEquals(listOf("one"), f.removed)
    }
    @Test fun disableRetriesWithoutTurningNotificationsBackOn() = runTest {
        val f = Fixture(); val repo = f.repository()
        repo.tokenChanged("fcm"); repo.setEnabled("one", true)
        f.failure = IOException(); repo.setEnabled("one", false)
        f.failure = null; repo.refresh()
        assertEquals(PushControlStatus.OFF, repo.states.value.getValue("one").status)
        assertEquals(1, f.tokens.size); assertTrue(f.records.values.isEmpty())
    }
    @Test fun reauthorizationNeverReregistersOldCredentialsWhileBrowserIsPending() = runTest {
        val f = Fixture(); val repo = f.repository()
        repo.tokenChanged("fcm"); repo.setEnabled("one", true)
        repo.beforeReauthorization(account)
        val restored = f.repository(); restored.refresh()
        assertEquals(PushControlStatus.NEEDS_AUTH, restored.states.value.getValue("one").status)
        assertEquals(1, f.tokens.size)
        val renewed = account.copy(accessToken = "renewed")
        f.accounts = listOf(renewed); restored.afterAuthorization(renewed)
        assertEquals(PushRegistrationGuard.binding(renewed), f.records.values.getValue("one").credentialBinding)
        assertEquals(PushControlStatus.ACTIVE, restored.states.value.getValue("one").status)
    }
    @Test fun failedReauthorizationCleanupStaysPendingAndDoesNotReactivateAfterRetry() = runTest {
        val f = Fixture(); val repo = f.repository()
        repo.tokenChanged("fcm"); repo.setEnabled("one", true); f.failure = IOException()
        try { repo.beforeReauthorization(account); fail() } catch (_: IllegalStateException) { }
        f.failure = null; repo.refresh()
        assertEquals(PushControlStatus.NEEDS_AUTH, repo.states.value.getValue("one").status)
        assertEquals(1, f.tokens.size)
    }
    @Test fun cancellationPropagatesAndRegistrationCanResume() = runTest {
        val f = Fixture(); val repo = f.repository(); repo.tokenChanged("fcm")
        f.failure = CancellationException()
        try { repo.setEnabled("one", true); fail() } catch (_: CancellationException) { }
        assertTrue(f.control.data.intents.getValue("one").enabled)
        f.failure = null; repo.refresh()
        assertEquals(PushControlStatus.ACTIVE, repo.states.value.getValue("one").status)
    }
}
