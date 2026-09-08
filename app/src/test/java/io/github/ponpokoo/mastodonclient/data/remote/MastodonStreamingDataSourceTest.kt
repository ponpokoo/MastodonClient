package io.github.ponpokoo.mastodonclient.data.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class MastodonStreamingDataSourceTest {
    @Test
    fun readsServerSentUserEventWithAuthorizationHeader() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: update\ndata: {\"id\":\"42\"}\n\n"),
            )

            val message = MastodonStreamingDataSource()
                .observeUser(server.url("/").toString(), "secret-token")
                .first()

            assertEquals("update", message.event)
            assertEquals("{\"id\":\"42\"}", message.payload)
            val request = server.takeRequest()
            assertEquals("/api/v1/streaming/user", request.path)
            assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        }
    }
}
