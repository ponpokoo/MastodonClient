package io.github.ponpokoo.mastodonclient.core.image

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AvatarResponseLimitInterceptorTest {
    private val client = OkHttpClient.Builder().addInterceptor(AvatarResponseLimitInterceptor()).build()

    @Test fun rejectsOversizedDeclaredAndChunkedResponsesButAcceptsTheLimit() {
        MockWebServer().use { server ->
            server.start()
            val limit = AvatarResponseLimitInterceptor.MAX_BYTES.toInt()
            val oversized = Buffer().write(ByteArray(limit + 1))
            server.enqueue(MockResponse().setBody(oversized.clone()))
            server.enqueue(MockResponse().setChunkedBody(oversized, 8192))
            repeat(2) {
                try {
                    client.newCall(Request.Builder().url(server.url("/avatar")).build()).execute().use { it.body!!.bytes() }
                    fail("Oversized avatar was accepted")
                } catch (_: IOException) { }
            }
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(limit))))
            client.newCall(Request.Builder().url(server.url("/avatar")).build()).execute().use {
                assertEquals(limit, it.body!!.bytes().size)
            }
        }
    }
}
