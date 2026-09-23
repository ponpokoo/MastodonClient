package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

/** A server subscription, not proof that delivery to this device works. */
data class PushSubscription(
    val id: String,
    val endpoint: String,
    val alerts: Map<String, Boolean>,
    val serverKey: String?,
    val standard: Boolean?,
)

/** Keys must already be durably stored before registering with Mastodon. */
class PushSubscriptionRequest(
    val endpoint: String,
    val publicKey: String,
    val authSecret: String,
    val alerts: Map<String, Boolean>,
    // null omits the version-dependent parameter for older servers.
    val standard: Boolean? = null,
)

interface PushSubscriptionRepository {
    /** Known optional alert types advertised by this instance, never inferred from its hostname. */
    suspend fun additionalAlerts(session: AccountSession): Set<String> = emptySet()
    suspend fun get(session: AccountSession): PushSubscription?
    suspend fun register(session: AccountSession, request: PushSubscriptionRequest): PushSubscription
    suspend fun remove(session: AccountSession)
}
