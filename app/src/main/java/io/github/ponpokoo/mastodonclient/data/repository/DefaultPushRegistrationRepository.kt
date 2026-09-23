package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.security.WebPushKeyGenerator
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
        record = record.copy(state = PushRegistrationState.REGISTERING)
        store.write(session.sessionId, record) // Persist secrets before any remote mutation.
        val endpoint = relay.register(record.registrationId, record.managementToken, fcmToken)
        check(record.endpoint == null || record.endpoint == endpoint) { "Relay changed an existing delivery endpoint" }
        record = record.copy(endpoint = endpoint)
        store.write(session.sessionId, record)
        val current = subscriptions.get(session)
        check(current == null || current.endpoint == endpoint) { "Another push subscription already exists" }
        val requestedAlerts = subscriptions.additionalAlerts(session).associateWith { true } + alerts
        if (current == null || requestedAlerts.any { (type, enabled) -> current.alerts[type] != enabled } ||
            (standard != null && current.standard != standard)) {
            val created = subscriptions.register(session, PushSubscriptionRequest(
                endpoint, record.keys.publicKey, record.keys.authSecret, requestedAlerts, standard,
            ))
            check(created.endpoint == endpoint) { "Server returned a different push endpoint" }
            check(requestedAlerts.all { (type, enabled) -> created.alerts[type] == enabled } &&
                (standard == null || created.standard == standard)) { "Server did not accept the requested push options" }
        }
        store.write(session.sessionId, record.copy(state = PushRegistrationState.ACTIVE))
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
