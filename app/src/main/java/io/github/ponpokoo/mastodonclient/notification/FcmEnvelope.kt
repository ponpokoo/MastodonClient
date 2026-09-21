package io.github.ponpokoo.mastodonclient.notification

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The durable queue contains only the encrypted Relay envelope, never FCM or OAuth tokens. */
internal object FcmEnvelope {
    private val allowed = setOf("version", "registrationId", "messageId", "transport", "encoding", "headers", "body")
    fun encode(data: Map<String, String>): String? {
        if (data["version"] != "1" || data["transport"] !in setOf("inline", "fetch")) return null
        if (data["registrationId"]?.matches(Regex("[A-Za-z0-9_-]{22,128}")) != true ||
            data["messageId"]?.matches(Regex("[A-Za-z0-9_-]{43}")) != true) return null
        if (data.keys.any { it !in allowed }) return null
        if (data["transport"] == "inline" && (data["body"].isNullOrEmpty() || data["encoding"].isNullOrEmpty())) return null
        return Json.encodeToString(data).takeIf { it.toByteArray(Charsets.UTF_8).size <= 8_000 }
    }
    fun deadline(sentTime: Long, ttlSeconds: Int, now: Long): Long? {
        if (ttlSeconds <= 0 || sentTime <= 0 || sentTime > now + 60_000) return null
        return (sentTime + ttlSeconds.coerceAtMost(86_400) * 1_000L).takeIf { it > now }
    }
}
