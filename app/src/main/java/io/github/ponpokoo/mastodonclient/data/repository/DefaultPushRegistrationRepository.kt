package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.security.WebPushKeyGenerator
import io.github.ponpokoo.mastodonclient.core.security.VapidPublicKey
import io.github.ponpokoo.mastodonclient.data.local.PushRegistrationStore
import io.github.ponpokoo.mastodonclient.data.local.StoredPushRegistration
import io.github.ponpokoo.mastodonclient.data.remote.PushRelayDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.data.repository.PushRegistrationGuard.binding
import io.github.ponpokoo.mastodonclient.data.repository.PushRegistrationGuard.mutex
import kotlinx.coroutines.sync.withLock

class DefaultPushRegistrationRepository(
    private val store: PushRegistrationStore,
    private val relay: PushRelayDataSource,
    private val subscriptions: PushSubscriptionRepository,
    private val keys: WebPushKeyGenerator = WebPushKeyGenerator(),
    private val syncBaseline: (suspend (AccountSession) -> String?)? = null,
) : PushRegistrationRepository {
    override suspend fun state(session: AccountSession) = mutex.withLock {
        readBound(session)?.state ?: PushRegistrationState.DISABLED
    }

    override suspend fun enable(session: AccountSession, fcmToken: String, alerts: Map<String, Boolean>, standard: Boolean?) = mutex.withLock {
        require(fcmToken.isNotBlank()) { "FCM token is required" }
        var record = readBound(session) ?: StoredPushRegistration(
            binding(session), relay.identity, keys.randomSecret(), keys.randomSecret(), keys.generate(),
        )
        check(record.state != PushRegistrationState.REMOVING) { "Finish pending removal before enabling push" }
        record = record.copy(state = PushRegistrationState.REGISTERING, revision = record.revision + 1)
        store.write(session.sessionId, record) // Persist secrets before any remote mutation.
        // Establish the lower bound before the server can send Push for a new subscription.
        if (!record.syncInitialized && record.endpoint == null && syncBaseline != null) {
            record = record.copy(syncInitialized = true, syncSinceId = syncBaseline.invoke(session))
            store.write(session.sessionId, record)
        }
        val advertisedKey = subscriptions.serverKey(session)?.let(VapidPublicKey::normalize)
        if (record.serverKey == null && advertisedKey != null) {
            record = record.copy(serverKey = advertisedKey)
            store.write(session.sessionId, record)
        }
        val endpoint = relay.registerBound(record.registrationId, record.managementToken, fcmToken, record.serverKey, record.revision)
        check(record.endpoint == null || record.endpoint == endpoint) { "Relay changed an existing delivery endpoint" }
        record = record.copy(endpoint = endpoint)
        store.write(session.sessionId, record)
        val current = subscriptions.get(session)
        check(current == null || current.endpoint == endpoint) { "Another push subscription already exists" }
        val requestedAlerts = subscriptions.additionalAlerts(session).associateWith { true } + alerts
        val confirmed = if (current == null || requestedAlerts.any { (type, enabled) -> current.alerts[type] != enabled } ||
            (standard != null && current.standard != standard)) {
            val created = subscriptions.register(session, PushSubscriptionRequest(
                endpoint, record.keys.publicKey, record.keys.authSecret, requestedAlerts, standard,
            ))
            check(created.endpoint == endpoint) { "Server returned a different push endpoint" }
            check(requestedAlerts.all { (type, enabled) -> created.alerts[type] == enabled } &&
                (standard == null || created.standard == standard)) { "Server did not accept the requested push options" }
            created
        } else current
        // Prefer the authenticated subscription response; metadata is a fallback for servers
        // that omit server_key. Never learn a signing key from an incoming Push.
        val serverKey = confirmed.serverKey?.let(VapidPublicKey::normalize) ?: advertisedKey
        check(serverKey != null) { "Server did not provide a VAPID public key" }
        if (record.serverKey != serverKey) {
            record = record.copy(serverKey = serverKey, revision = record.revision + 1)
            store.write(session.sessionId, record)
            val bound = relay.registerBound(record.registrationId, record.managementToken, fcmToken, serverKey, record.revision)
            check(bound == endpoint) { "Relay changed an existing delivery endpoint" }
        }
        store.write(session.sessionId, record.copy(state = PushRegistrationState.ACTIVE,
            notificationTypes = confirmed.alerts.filterValues { it }.keys.toList()))
    }

    override suspend fun disable(session: AccountSession) = mutex.withLock {
        val record = readBound(session) ?: return@withLock
        store.write(session.sessionId, record.copy(state = PushRegistrationState.REMOVING))
        // Stop delivery even if the Mastodon token has expired or its server is unavailable.
        relay.unregister(record.registrationId, record.managementToken)
        val current = subscriptions.get(session)
        if (record.endpoint != null && current?.endpoint == record.endpoint) subscriptions.remove(session)
        store.remove(session.sessionId)
    }

    private suspend fun readBound(session: AccountSession): StoredPushRegistration? = store.read(session.sessionId)?.also {
        check(it.credentialBinding == binding(session)) { "Push registration belongs to different credentials" }
        check(it.relayIdentity == relay.identity) { "Push registration belongs to a different relay" }
    }

}
