package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.data.remote.PushRelayDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PushRegistrationRepositoryTest {
    private val account = AccountSession("one", "https://instance.example", "account", "user", "User", "", "token")
    private val alerts = mapOf("mention" to true)
    private class MemoryStore : PushRegistrationStore {
        val records = mutableMapOf<String, StoredPushRegistration>()
        var failWrite = false
        override suspend fun read(sessionId: String) = records[sessionId]
        override suspend fun write(sessionId: String, record: StoredPushRegistration) {
            if (failWrite) throw IOException("Disk failure")
            records[sessionId] = record
        }
        override suspend fun remove(sessionId: String) { records.remove(sessionId) }
    }
    private class Relay : PushRelayDataSource {
        override val identity = "https://relay.example/"
        val ids = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        var error: Exception? = null
        var removes = 0
        override suspend fun register(id: String, managementToken: String, fcmToken: String): String {
            ids += id; tokens += fcmToken
            error?.let { throw it }
            return "https://relay.example/push/$id"
        }
        override suspend fun unregister(id: String, managementToken: String) { removes++ }
    }
    private class Mastodon : PushSubscriptionRepository {
        var current: PushSubscription? = null
        var registerError: Exception? = null
        var getError: Exception? = null
        var posts = 0
        var deletes = 0
        var ignoreOptions = false
        override suspend fun get(session: AccountSession): PushSubscription? {
            getError?.let { throw it }
            return current
        }
        override suspend fun register(session: AccountSession, request: PushSubscriptionRequest): PushSubscription {
            posts++
            val result = PushSubscription("id", request.endpoint, if (ignoreOptions) emptyMap() else request.alerts, null, request.standard)
            current = result
            registerError?.let { throw it } // Server committed, response was lost.
            return result
        }
        override suspend fun remove(session: AccountSession) { deletes++; current = null }
    }

    @Test fun lostRelayResponseRetainsKeysAndRegistrationAcrossRepositoryRecreation() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        relay.error = IOException("Lost response")
        try { DefaultPushRegistrationRepository(store, relay, remote).enable(account, "fcm", alerts); fail() }
        catch (_: IOException) { }
        val saved = store.records.getValue(account.sessionId)
        assertEquals(PushRegistrationState.REGISTERING, saved.state)
        relay.error = null
        val restored = DefaultPushRegistrationRepository(store, relay, remote)
        restored.enable(account, "fcm", alerts)
        assertEquals(listOf(saved.registrationId, saved.registrationId), relay.ids)
        assertEquals(saved.keys.privateKey, store.records.getValue(account.sessionId).keys.privateKey)
        assertEquals(PushRegistrationState.ACTIVE, restored.state(account))
    }

    @Test fun lostMastodonResponseIsReconciledWithoutReplacingSubscription() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        remote.registerError = IOException("Lost response")
        try { DefaultPushRegistrationRepository(store, relay, remote).enable(account, "fcm", alerts); fail() }
        catch (_: IOException) { }
        remote.registerError = null
        val restored = DefaultPushRegistrationRepository(store, relay, remote)
        restored.enable(account, "rotated-fcm", alerts)
        assertEquals(1, remote.posts)
        assertEquals(listOf("fcm", "rotated-fcm"), relay.tokens)
        assertEquals(PushRegistrationState.ACTIVE, restored.state(account))
    }

    @Test fun persistenceFailurePreventsRemoteMutation() = runTest {
        val store = MemoryStore().apply { failWrite = true }; val relay = Relay()
        try { DefaultPushRegistrationRepository(store, relay, Mastodon()).enable(account, "fcm", alerts); fail() }
        catch (_: IOException) { }
        assertTrue(relay.ids.isEmpty())
    }

    @Test fun cancellationPropagatesAndLeavesRecoverableState() = runTest {
        val store = MemoryStore(); val relay = Relay().apply { error = CancellationException("Stopped") }
        try { DefaultPushRegistrationRepository(store, relay, Mastodon()).enable(account, "fcm", alerts); fail() }
        catch (_: CancellationException) { }
        assertEquals(PushRegistrationState.REGISTERING, store.records.getValue(account.sessionId).state)
    }

    @Test fun failedRemovalBlocksEnableAndRetriesAfterRecreation() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        val repository = DefaultPushRegistrationRepository(store, relay, remote)
        repository.enable(account, "fcm", alerts)
        remote.getError = IOException("Offline")
        try { repository.disable(account); fail() } catch (_: IOException) { }
        assertEquals(1, relay.removes)
        assertEquals(PushRegistrationState.REMOVING, repository.state(account))
        try { repository.enable(account, "fcm", alerts); fail() } catch (_: IllegalStateException) { }
        remote.getError = null
        val restored = DefaultPushRegistrationRepository(store, relay, remote)
        restored.disable(account)
        assertEquals(1, remote.deletes)
        assertEquals(PushRegistrationState.DISABLED, restored.state(account))
    }

    @Test fun foreignSubscriptionIsNeitherReplacedNorRemoved() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        remote.current = PushSubscription("foreign", "https://other.example/push", alerts, null, null)
        val repository = DefaultPushRegistrationRepository(store, relay, remote)
        try { repository.enable(account, "fcm", alerts); fail() } catch (_: IllegalStateException) { }
        repository.disable(account)
        assertEquals(0, remote.posts)
        assertEquals(0, remote.deletes)
        assertEquals("foreign", remote.current!!.id)
    }

    @Test fun changedCredentialsAndRelayAreRejectedBeforeSendingSecrets() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        val repository = DefaultPushRegistrationRepository(store, relay, remote)
        repository.enable(account, "fcm", alerts)
        try { repository.enable(account.copy(accessToken = "new-token"), "fcm", alerts); fail() }
        catch (_: IllegalStateException) { }
        val otherRelay = object : PushRelayDataSource {
            override val identity = "https://other.example/"
            override suspend fun register(id: String, managementToken: String, fcmToken: String): String = error("Must not send")
            override suspend fun unregister(id: String, managementToken: String) = error("Must not send")
        }
        try { DefaultPushRegistrationRepository(store, otherRelay, remote).disable(account); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(1, relay.ids.size)
    }

    @Test fun accountsHaveSeparateKeysAndReenableUsesFreshIdentity() = runTest {
        val store = MemoryStore(); val relay = Relay()
        val first = DefaultPushRegistrationRepository(store, relay, Mastodon())
        val second = DefaultPushRegistrationRepository(store, relay, Mastodon())
        first.enable(account, "fcm", alerts)
        val original = store.records.getValue("one")
        second.enable(account.copy(sessionId = "two", accountId = "second"), "fcm", alerts)
        assertNotEquals(original.keys.privateKey, store.records.getValue("two").keys.privateKey)
        first.disable(account)
        first.enable(account, "fcm", alerts)
        assertNotEquals(original.registrationId, store.records.getValue("one").registrationId)
        assertTrue(store.records.containsKey("two"))
    }

    @Test fun concurrentEnableCallsShareOneRegistration() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        listOf(
            async { DefaultPushRegistrationRepository(store, relay, remote).enable(account, "fcm", alerts) },
            async { DefaultPushRegistrationRepository(store, relay, remote).enable(account, "fcm", alerts) },
        ).awaitAll()
        assertEquals(1, relay.ids.toSet().size)
        assertEquals(1, remote.posts)
    }

    @Test fun ignoredServerOptionsDoNotBecomeActive() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon().apply { ignoreOptions = true }
        val repository = DefaultPushRegistrationRepository(store, relay, remote)
        try { repository.enable(account, "fcm", alerts); fail() } catch (_: IllegalStateException) { }
        assertEquals(PushRegistrationState.REGISTERING, repository.state(account))
    }
}
