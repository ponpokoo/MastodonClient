package io.github.ponpokoo.mastodonclient.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

data class StreamingMessage(
    val event: String,
    val payload: String,
)

class MastodonStreamingDataSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        // SSE can be quiet longer than a normal HTTP response. Keep the connection
        // open between heartbeats; awaitClose still cancels it on session/lifecycle changes.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    fun observeUser(
        streamingBaseUrl: String,
        accessToken: String,
    ): Flow<StreamingMessage> = callbackFlow {
        val httpBaseUrl = streamingBaseUrl
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .removeSuffix("/")
        val request = Request.Builder()
            .url("$httpBaseUrl/api/v1/streaming/user")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "text/event-stream")
            .build()
        val call = client.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("ストリーミング接続に失敗しました (${response.code})")
                    val source = response.body?.source() ?: throw IOException("ストリーミング応答が空です")
                    var eventName: String? = null
                    val payload = StringBuilder()
                    fun dispatchPendingEvent() {
                        val event = eventName
                        if (event != null && payload.isNotEmpty()) {
                            trySend(StreamingMessage(event, payload.toString()))
                        }
                        eventName = null
                        payload.clear()
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                            line.startsWith("data:") -> {
                                if (payload.isNotEmpty()) payload.append('\n')
                                payload.append(line.substringAfter(':').trimStart())
                            }
                            line.isEmpty() -> dispatchPendingEvent()
                        }
                    }
                    dispatchPendingEvent()
                }
                // A user stream is expected to stay open. Treat an EOF as a
                // transient failure so the repository's retry policy reconnects.
                throw IOException("ストリーミング接続が切断されました")
            } catch (error: Throwable) {
                if (!call.isCanceled()) close(error) else close()
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }
}
