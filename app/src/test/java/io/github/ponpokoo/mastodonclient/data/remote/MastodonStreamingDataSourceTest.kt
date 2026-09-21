package io.github.ponpokoo.mastodonclient.data.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class MastodonStreamingDataSourceTest {
    @Test
    fun receivesUpdateAfterQuietConnectionLongerThanDefaultHttpTimeout() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBodyDelay(11, java.util.concurrent.TimeUnit.SECONDS)
                    .setBody("event: update\ndata: {\"id\":\"after-idle\"}\n\n"),
            )
            val message = MastodonStreamingDataSource()
                .observeUser(server.url("/").toString(), "test-token")
                .first()
            assertEquals("update", message.event)
            assertEquals("{\"id\":\"after-idle\"}", message.payload)
            assertEquals(1, server.requestCount)
        }
    }
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

    @Test
    fun reconnectsWhenServerClosesStreamAndKeepsFinalEvent() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: notification\ndata: {\"id\":\"first\"}"),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: notification\ndata: {\"id\":\"second\"}\n\n"),
            )

            val messages = MastodonStreamingDataSource()
                .observeUser(server.url("/").toString(), "secret-token")
                .retry(1)
                .take(2)
                .toList()

            assertEquals(listOf("{\"id\":\"first\"}", "{\"id\":\"second\"}"), messages.map { it.payload })
            assertEquals(2, server.requestCount)
        }
    }
}
