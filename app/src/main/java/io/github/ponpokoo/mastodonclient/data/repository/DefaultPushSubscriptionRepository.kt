package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.PushSubscriptionRepository
import io.github.ponpokoo.mastodonclient.domain.repository.PushSubscriptionRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.HttpException

class DefaultPushSubscriptionRepository(
    private val clients: ApiClientFactory,
    private val capabilities: ServerCapabilitiesRepository = ServerCapabilitiesRepository(clients),
) : PushSubscriptionRepository {
    override suspend fun serverKey(session: AccountSession): String? = try {
        clients.create(session.instanceUrl).getInstance().configuration?.vapid?.publicKey
    } catch (error: HttpException) {
        if (error.code() == 404) null else throw error
    }

    override suspend fun additionalAlerts(session: AccountSession): Set<String> {
        return capabilities.get(session.instanceUrl).additionalPushAlerts
    }

    override suspend fun subscriptionAlerts(session: AccountSession, alerts: Map<String, Boolean>): Map<String, Boolean> {
        if (capabilities.get(session.instanceUrl).reactionApi != io.github.ponpokoo.mastodonclient.domain.model.EmojiReactionApi.Pleroma) return alerts
        // Pleroma maps follow_request to follow and older implementations discard
        // poll. Use the common contract rather than failing response verification.
        return (alerts - "follow_request" - "poll").toMutableMap().apply {
            if ("follow_request" in alerts) put("follow", alerts["follow"] == true || alerts["follow_request"] == true)
        }
    }

    override suspend fun get(session: AccountSession) = try {
        api(session).getPushSubscription().toDomain()
    } catch (error: HttpException) {
        if (error.code() == 404) null else throw error
    }

    override suspend fun register(session: AccountSession, request: PushSubscriptionRequest) = run {
        val endpoint = request.endpoint.toHttpUrl()
        require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null) {
            "Push endpoint must be an HTTPS URL without credentials or fragment"
        }
        require(request.publicKey.isNotBlank() && request.authSecret.isNotBlank()) { "Push keys are required" }
        require(request.alerts.keys.all { it.matches(Regex("[a-z_]+(?:\\.[a-z_]+)?")) || it == "pleroma:emoji_reaction" }) { "Invalid alert type" }
        val fields = buildMap {
            put("subscription[endpoint]", request.endpoint)
            put("subscription[keys][p256dh]", request.publicKey)
            put("subscription[keys][auth]", request.authSecret)
            request.standard?.let { put("subscription[standard]", it.toString()) }
            request.alerts.forEach { (type, enabled) -> put("data[alerts][$type]", enabled.toString()) }
        }
        val extensionKeys = request.alerts.keys.filter { it in REACTION_PUSH_ALERTS }
        val result = try { api(session).registerPushSubscription(fields).toDomain() } catch (error: HttpException) {
            if (extensionKeys.isEmpty() || error.code() !in setOf(400, 422)) throw error
            // Retry once without optional extension alerts; normal Push must still work.
            api(session).registerPushSubscription(fields.filterKeys { field ->
                extensionKeys.none { field == "data[alerts][$it]" }
            }).toDomain()
        }
        if (extensionKeys.any { result.alerts[it] != request.alerts[it] }) capabilities.rejectPushAlerts(session.instanceUrl)
        result
    }

    override suspend fun remove(session: AccountSession) {
        val response = api(session).removePushSubscription()
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
    }

    private fun api(session: AccountSession) = clients.create(session.instanceUrl, session.accessToken)
}

internal val REACTION_PUSH_ALERTS = setOf("emoji_reaction", "pleroma:emoji_reaction")
