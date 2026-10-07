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
        val bindings = mutableListOf<Pair<String?, Long>>()
        var finalizationError: Exception? = null
        override suspend fun register(id: String, managementToken: String, fcmToken: String): String {
            ids += id; tokens += fcmToken
            error?.let { throw it }
            return "https://relay.example/push/$id"
        }
        override suspend fun registerBound(id: String, managementToken: String, fcmToken: String, serverKey: String?, revision: Long): String {
            bindings += serverKey to revision
            if (serverKey != null) finalizationError?.let { throw it }
            return register(id, managementToken, fcmToken)
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
        var additional = emptySet<String>()
        var signingKey: String? = io.github.ponpokoo.mastodonclient.core.security.WebPushKeyGenerator().generate().publicKey
        var metadataAvailable = true
        override suspend fun serverKey(session: AccountSession) = if (metadataAvailable) signingKey else null
        override suspend fun additionalAlerts(session: AccountSession) = additional
        override suspend fun get(session: AccountSession): PushSubscription? {
            getError?.let { throw it }
            return current
        }
        override suspend fun register(session: AccountSession, request: PushSubscriptionRequest): PushSubscription {
            posts++
            val result = PushSubscription("id", request.endpoint, if (ignoreOptions) emptyMap() else request.alerts, signingKey, request.standard)
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
    @Test fun initializesCheckpointBeforeSubscriptionAndPreservesItOnTokenUpdates() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        var baselineCalls = 0
        val repo = DefaultPushRegistrationRepository(store, relay, remote, syncBaseline = {
            baselineCalls++
            assertEquals(0, remote.posts)
            "opaque-baseline"
        })
        repo.enable(account, "fcm", alerts)
        assertEquals("opaque-baseline", store.records.getValue(account.sessionId).syncSinceId)
        assertTrue(store.records.getValue(account.sessionId).syncInitialized)
        repo.enable(account, "new-fcm", alerts)
        assertEquals(1, baselineCalls)
    }

    @Test fun failedBaselineDoesNotCreateRemoteSubscriptionAndRetriesWithoutNewKeys() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        try { DefaultPushRegistrationRepository(store, relay, remote, syncBaseline = { throw IOException() }).enable(account, "fcm", alerts); fail() }
        catch (_: IOException) { }
        assertEquals(0, remote.posts); assertTrue(relay.ids.isEmpty())
        val saved = store.records.getValue(account.sessionId)
        DefaultPushRegistrationRepository(store, relay, remote, syncBaseline = { null }).enable(account, "fcm", alerts)
        assertEquals(saved.registrationId, store.records.getValue(account.sessionId).registrationId)
        assertTrue(store.records.getValue(account.sessionId).syncInitialized)
    }

    @Test fun subscriptionResponseFinalizesPendingKeyAndLostFinalizationResumesWithoutNewEndpointOrSubscription() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon().apply { metadataAvailable = false }
        relay.finalizationError = IOException("Lost key binding response")
        try { DefaultPushRegistrationRepository(store, relay, remote).enable(account, "fcm", alerts); fail() }
        catch (_: IOException) { }
        val saved = store.records.getValue(account.sessionId)
        assertEquals(PushRegistrationState.REGISTERING, saved.state)
        assertNull(relay.bindings.first().first)
        assertEquals(remote.signingKey, saved.serverKey)
        relay.finalizationError = null
        DefaultPushRegistrationRepository(store, relay, remote).enable(account, "rotated-fcm", alerts)
        assertEquals(1, remote.posts)
        assertEquals(saved.endpoint, store.records.getValue(account.sessionId).endpoint)
        assertEquals(saved.keys, store.records.getValue(account.sessionId).keys)
        assertTrue(relay.bindings.last().second > saved.revision)
        assertEquals(PushRegistrationState.ACTIVE, store.records.getValue(account.sessionId).state)
    }

    @Test fun missingOrInvalidServerKeyCannotBecomeActive() = runTest {
        for (key in listOf(null, "invalid")) {
            val store = MemoryStore(); val relay = Relay(); val remote = Mastodon().apply { signingKey = key }
            try { DefaultPushRegistrationRepository(store, relay, remote).enable(account, "fcm", alerts); fail() }
            catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
            assertEquals(PushRegistrationState.REGISTERING, store.records.getValue(account.sessionId).state)
            assertFalse(relay.bindings.any { it.first == "invalid" })
        }
    }

    @Test fun keyRotationAndExistingV1RecordsKeepEncryptionKeysAndEndpoint() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        DefaultPushRegistrationRepository(store, relay, remote).enable(account, "fcm", alerts)
        val saved = store.records.getValue(account.sessionId)
        store.records[account.sessionId] = saved.copy(serverKey = null, revision = 0)
        remote.signingKey = io.github.ponpokoo.mastodonclient.core.security.WebPushKeyGenerator().generate().publicKey
        remote.current = remote.current!!.copy(serverKey = remote.signingKey)
        DefaultPushRegistrationRepository(store, relay, remote).enable(account, "rotated-fcm", alerts)
        val migrated = store.records.getValue(account.sessionId)
        assertEquals(saved.registrationId, migrated.registrationId)
        assertEquals(saved.endpoint, migrated.endpoint)
        assertEquals(saved.keys, migrated.keys)
        assertEquals(remote.signingKey, migrated.serverKey)
        assertEquals(1, remote.posts)
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
            override suspend fun registerBound(id: String, managementToken: String, fcmToken: String, serverKey: String?, revision: Long): String = error("Must not send")
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

    @Test fun advertisedReactionIsAddedToNewSubscription() = runTest {
        val remote = Mastodon().apply { additional = setOf("emoji_reaction") }
        val repository = DefaultPushRegistrationRepository(MemoryStore(), Relay(), remote)
        repository.enable(account, "fcm", alerts)
        assertEquals(alerts + ("emoji_reaction" to true), remote.current!!.alerts)
        assertEquals(PushRegistrationState.ACTIVE, repository.state(account))
    }

    @Test fun existingSubscriptionGainsReactionOnRestartWithoutChangingKeysOrEndpoint() = runTest {
        val store = MemoryStore(); val relay = Relay(); val remote = Mastodon()
        DefaultPushRegistrationRepository(store, relay, remote).enable(account, "fcm", alerts)
        val saved = store.records.getValue(account.sessionId)
        assertFalse(remote.current!!.alerts.containsKey("emoji_reaction"))
        remote.additional = setOf("emoji_reaction")
        remote.registerError = IOException("Lost upgrade response")
        val restored = DefaultPushRegistrationRepository(store, relay, remote)
        try { restored.enable(account, "fcm", alerts); fail() } catch (_: IOException) { }
        remote.registerError = null
        restored.enable(account, "fcm", alerts)
        assertEquals(true, remote.current!!.alerts["emoji_reaction"])
        assertEquals(2, remote.posts)
        assertEquals(saved.keys, store.records.getValue(account.sessionId).keys)
        assertEquals(saved.endpoint, remote.current!!.endpoint)
        assertEquals(PushRegistrationState.ACTIVE, restored.state(account))
    }
}
