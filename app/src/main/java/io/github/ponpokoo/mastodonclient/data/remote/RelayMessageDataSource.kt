package io.github.ponpokoo.mastodonclient.data.remote

import io.github.ponpokoo.mastodonclient.data.remote.dto.RelayMessageDto
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface PushMessageSource {
    suspend fun fetch(relay: String, registrationId: String, messageId: String, managementToken: String): RelayMessageDto?
}

class RelayMessageDataSource internal constructor(
    private val client: OkHttpClient,
    private val allowHttpForTests: Boolean,
) : PushMessageSource {
    constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build(), false)

    override suspend fun fetch(relay: String, registrationId: String, messageId: String, managementToken: String): RelayMessageDto? {
        require(registrationId.matches(Regex("[A-Za-z0-9_-]{22,128}")) && messageId.matches(Regex("[A-Za-z0-9_-]{43}")))
        require(managementToken.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        val base = relay.toHttpUrl()
        require((base.isHttps || allowHttpForTests) && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        val url = base.newBuilder().addPathSegments("v1/registrations").addPathSegment(registrationId)
            .addPathSegment("messages").addPathSegment(messageId).build()
        val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $managementToken").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (it.code == 404 || it.code == 410) return@use null
                            if (!it.isSuccessful) throw IOException("Relay fetch failed: HTTP ${it.code}")
                            val responseBody = it.body ?: throw IOException("Empty Relay response")
                            require(responseBody.contentLength() <= MAX_BYTES) { "Relay response too large" }
                            val source = responseBody.source()
                            source.request(MAX_BYTES + 1)
                            require(source.buffer.size <= MAX_BYTES) { "Relay response too large" }
                            RelayMessageDto.json.decodeFromString<RelayMessageDto>(source.readUtf8())
                        }
                        continuation.resume(value)
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }
            })
        }
    }
    private companion object { const val MAX_BYTES = 100_000L }
}
