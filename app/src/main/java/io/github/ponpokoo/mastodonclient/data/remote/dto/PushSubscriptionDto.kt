package io.github.ponpokoo.mastodonclient.data.remote.dto

import io.github.ponpokoo.mastodonclient.domain.repository.PushSubscription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class PushSubscriptionDto(
    @Serializable(with = PushSubscriptionIdSerializer::class) val id: String,
    val endpoint: String,
    val alerts: Map<String, Boolean> = emptyMap(),
    @SerialName("server_key") val serverKey: String? = null,
    val standard: Boolean? = null,
) {
    fun toDomain() = PushSubscription(id, endpoint, alerts, serverKey, standard)
}

/** Some servers return a JSON number. Preserve its digits without numeric parsing. */
object PushSubscriptionIdSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val value = element as? JsonPrimitive ?: throw SerializationException("Invalid push subscription ID")
        if (value.isString) return value
        if (!value.content.matches(Regex("[0-9]+"))) throw SerializationException("Invalid push subscription ID")
        return JsonPrimitive(value.content)
    }
}
