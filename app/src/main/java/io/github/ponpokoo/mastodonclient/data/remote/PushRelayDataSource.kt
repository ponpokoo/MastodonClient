package io.github.ponpokoo.mastodonclient.data.remote

interface PushRelayDataSource {
    /** Stable configured service identity, persisted to prevent sending secrets to another relay. */
    val identity: String
    suspend fun register(id: String, managementToken: String, fcmToken: String): String
    suspend fun unregister(id: String, managementToken: String)
}

class DefaultPushRelayDataSource(baseUrl: String) : PushRelayDataSource {
    override val identity = baseUrl
    private val client = RelayRegistrationDataSource(baseUrl)
    override suspend fun register(id: String, managementToken: String, fcmToken: String) =
        client.put(id, managementToken, fcmToken).endpoint
    override suspend fun unregister(id: String, managementToken: String) = client.remove(id, managementToken)
}
