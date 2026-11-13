package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.remote.MastodonStreamingDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineStreamingRepositoryTest {
    @Test fun eofFollowsEveryDecodedEventWhenTheCollectorIsSlow() = runTest {
        MockWebServer().use { server ->
            val ids = List(300) { "post-$it" }
            val body = ids.joinToString("") { id ->
                val status = """{"id":"$id","created_at":"2026-11-12T00:00:00Z","account":{"id":"a","username":"a","acct":"a"}}"""
                "event: update\ndata: $status\n\n"
            }
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            val session = AccountSession("session", server.url("/").toString(), "me", "me", "Me", "", "synthetic")
            val repository = DefaultTimelineRepository(ApiClientFactory())
            var ended = false

            val events = repository.observeUserStream(session)
                .onEach { withContext(Dispatchers.Default) { delay(1) } }
                .catch { error ->
                    if (error !is IOException) throw error
                    ended = true
                }
                .toList()

            assertTrue(ended)
            assertEquals(TimelineStreamEvent.Connected, events.first())
            assertEquals(ids, events.drop(1).map { (it as TimelineStreamEvent.StatusAdded).status.statusId })
        }
    }
    @Test fun orderedEventsUpdateCacheAcrossReconnectAndCancelTheSecondBurst() = runTest {
        MockWebServer().use { server ->
            val author = """{"id":"author","username":"author","acct":"author"}"""
            fun status(id: String, content: String) =
                """{"id":"$id","created_at":"2026-11-12T00:00:00Z","account":$author,"content":"$content"}"""
            fun event(type: String, payload: String) = "event: $type\ndata: $payload\n\n"
            fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(stream(
                event("update", status("post", "initial")) +
                    event("status.update", status("post", "edited")) +
                    event("delete", "\"post\"") +
                    event("notification",
                        """{"id":"notice","type":"follow","created_at":"2026-11-12T00:00:00Z","account":$author}""") +
                    "event: filters_changed\n\n",
            ))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(stream((0 until 300).joinToString("") { event("update", status("new-$it", "new")) }))

            val connections = CopyOnWriteArrayList<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    connections += call
                }
            }).build()
            val repository = DefaultTimelineRepository(ApiClientFactory(), MastodonStreamingDataSource(client))
            val session = AccountSession("session", server.url("/").toString(), "me", "me", "Me", "", "synthetic")

            val received = repository.observeUserStream(session).retry(1).take(8).toList()

            assertEquals(TimelineStreamEvent.Connected, received[0])
            val initial = received[1] as TimelineStreamEvent.StatusAdded
            val edited = received[2] as TimelineStreamEvent.StatusAdded
            assertEquals("initial", initial.status.contentHtml)
            assertEquals(false, initial.isEdit)
            assertEquals("edited", edited.status.contentHtml)
            assertTrue(edited.isEdit)
            assertEquals(TimelineStreamEvent.StatusDeleted("post"), received[3])
            assertEquals("notice", (received[4] as TimelineStreamEvent.NotificationReceived).notification.id)
            assertEquals(TimelineStreamEvent.FiltersChanged, received[5])
            assertEquals(TimelineStreamEvent.Connected, received[6])
            assertEquals("new-0", (received[7] as TimelineStreamEvent.StatusAdded).status.statusId)

            assertEquals(4, server.requestCount)
            assertEquals(2, connections.size)
            assertTrue(connections.last().isCanceled())
            assertNull(repository.getCachedStatus(session, "post"))
            assertEquals("new-0", repository.getCachedStatus(session, "new-0")?.statusId)
            assertNull(repository.getCachedStatus(session.copy(accessToken = "other"), "new-0"))
        }
    }
}
