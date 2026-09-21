package io.github.ponpokoo.mastodonclient.data.remote.dto

import io.github.ponpokoo.mastodonclient.domain.model.PushNotification
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
class RelayMessageDto(
    val version: String, val registrationId: String, val messageId: String,
    val encoding: String, val headers: String, val body: String,
) {
    fun validate(registration: String, message: String) {
        require(version == "1" && registrationId == registration && messageId == message) { "Mismatched Relay message" }
        require(encoding == "aes128gcm" || encoding == "aesgcm") { "Unsupported encoding" }
        require(headers.length <= 8192 && body.length <= 87384) { "Relay message too large" }
    }
    fun bytes(): ByteArray = Base64.getUrlDecoder().decode(body).also { require(it.size <= 65536) }
    fun cryptoHeaders(): Map<String, String> = json.decodeFromString<Map<String, String>>(headers).also {
        require(it.size <= 2 && it.keys.all { key -> key in setOf("encryption", "crypto-key") } && it.values.all { value -> value.length <= 2048 })
    }
    companion object {
        val json = Json { ignoreUnknownKeys = true }
        fun inline(data: Map<String, String>) = RelayMessageDto(
            data.getValue("version"), data.getValue("registrationId"), data.getValue("messageId"),
            data.getValue("encoding"), data.getValue("headers"), data.getValue("body"),
        )
    }
}

@Serializable
internal class WebPushNotificationDto(
    @SerialName("notification_id") @Serializable(with = PushSubscriptionIdSerializer::class) val id: String,
    @SerialName("notification_type") val type: String? = null,
    val title: String, val body: String = "", val icon: String? = null,
) {
    fun toDomain(): PushNotification {
        require(id.isNotBlank() && id.length <= 256 && title.isNotBlank()) { "Invalid push notification" }
        return PushNotification(id, type, title.take(256), body.take(2048), icon?.takeIf { it.length <= 2048 })
    }
}
