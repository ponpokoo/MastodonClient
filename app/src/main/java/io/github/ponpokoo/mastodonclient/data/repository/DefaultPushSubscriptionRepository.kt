package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.PushSubscriptionRepository
import io.github.ponpokoo.mastodonclient.domain.repository.PushSubscriptionRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.HttpException

class DefaultPushSubscriptionRepository(
    private val clients: ApiClientFactory,
) : PushSubscriptionRepository {
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
        require(request.alerts.keys.all { it.matches(Regex("[a-z_]+(?:\\.[a-z_]+)?")) }) { "Invalid alert type" }
        val fields = buildMap {
            put("subscription[endpoint]", request.endpoint)
            put("subscription[keys][p256dh]", request.publicKey)
            put("subscription[keys][auth]", request.authSecret)
            request.standard?.let { put("subscription[standard]", it.toString()) }
            request.alerts.forEach { (type, enabled) -> put("data[alerts][$type]", enabled.toString()) }
        }
        api(session).registerPushSubscription(fields).toDomain()
    }

    override suspend fun remove(session: AccountSession) {
        val response = api(session).removePushSubscription()
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
    }

    private fun api(session: AccountSession) = clients.create(session.instanceUrl, session.accessToken)
}
