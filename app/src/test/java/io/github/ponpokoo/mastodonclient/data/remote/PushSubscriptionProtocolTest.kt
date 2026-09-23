package io.github.ponpokoo.mastodonclient.data.remote

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.repository.DefaultPushSubscriptionRepository
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.PushSubscriptionRequest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import java.net.URLDecoder

class PushSubscriptionProtocolTest {
    @Test fun reactionSupportUsesAdvertisedCapabilityAndStaysIsolatedPerInstance() = runTest {
        MockWebServer().use { fedibird -> MockWebServer().use { standard ->
            fedibird.enqueue(MockResponse().setBody("""{"fedibird_capabilities":["emoji_reaction","future_feature"]}"""))
            standard.enqueue(MockResponse().setBody("""{"version":"4.5.0"}"""))
            val repository = DefaultPushSubscriptionRepository(ApiClientFactory())
            assertEquals(setOf("emoji_reaction"), repository.additionalAlerts(session(fedibird)))
            assertTrue(repository.additionalAlerts(session(standard)).isEmpty())
            assertEquals("/api/v2/instance", fedibird.takeRequest().path)
            assertNull(standard.takeRequest().getHeader("Authorization"))
            fedibird.enqueue(MockResponse().setBody("""{"id":"id","endpoint":"https://relay.example/push/id","alerts":{"emoji_reaction":true}}"""))
            val result = repository.register(session(fedibird), PushSubscriptionRequest(
                "https://relay.example/push/id", "key", "auth", mapOf("emoji_reaction" to true)))
            assertEquals(true, result.alerts["emoji_reaction"])
            assertTrue(URLDecoder.decode(fedibird.takeRequest().body.readUtf8(), "UTF-8")
                .contains("data[alerts][emoji_reaction]=true"))
        } }
    }

    @Test fun legacyInstanceDiscoveryFallsBackOnlyOnNotFound() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultPushSubscriptionRepository(ApiClientFactory())
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"fedibird_capabilities":["emoji_reaction"]}"""))
            assertEquals(setOf("emoji_reaction"), repository.additionalAlerts(session(server)))
            assertEquals("/api/v2/instance", server.takeRequest().path)
            assertEquals("/api/v1/instance", server.takeRequest().path)
            server.enqueue(MockResponse().setResponseCode(503))
            try { repository.additionalAlerts(session(server)); fail() }
            catch (error: HttpException) { assertEquals(503, error.code()) }
            assertEquals(3, server.requestCount)
        }
    }

    private fun session(server: MockWebServer, token: String = "mastodon-token") =
        AccountSession("session", server.url("/").toString(), "account", "name", "Name", "", token)

    private fun response(id: String = "opaque-id") = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"id":"$id","endpoint":"https://relay.example/push/opaque","alerts":{"mention":true},"future_field":42}""")

    @Test fun registrationEncodesKeysAndOmitsVersionDependentFields() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response())
            val repository = DefaultPushSubscriptionRepository(ApiClientFactory())
            val subscription = repository.register(session(server), PushSubscriptionRequest(
                "https://relay.example/push/opaque", "key+/=", "auth+/=", mapOf("mention" to true, "follow" to false),
            ))
            assertEquals("opaque-id", subscription.id)
            assertNull(subscription.standard)
            assertNull(subscription.serverKey)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/push/subscription", request.path)
            assertEquals("Bearer mastodon-token", request.getHeader("Authorization"))
            val fields = request.body.readUtf8().split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals("key+/=", fields["subscription[keys][p256dh]"])
            assertEquals("auth+/=", fields["subscription[keys][auth]"])
            assertEquals("false", fields["data[alerts][follow]"])
            assertFalse(fields.containsKey("subscription[standard]"))
        }
    }

    @Test fun missingSubscriptionIsDistinctFromPermissionFailure() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultPushSubscriptionRepository(ApiClientFactory())
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(repository.get(session(server)))
            server.enqueue(MockResponse().setResponseCode(403))
            try {
                repository.get(session(server))
                fail("Permission failure must propagate")
            } catch (error: HttpException) { assertEquals(403, error.code()) }
        }
    }

    @Test fun instancesAndTokensRemainIsolated() = runTest {
        MockWebServer().use { first -> MockWebServer().use { second ->
            val repository = DefaultPushSubscriptionRepository(ApiClientFactory())
            first.enqueue(response("first"))
            second.enqueue(response("second"))
            assertEquals("first", repository.get(session(first, "first-token"))!!.id)
            assertEquals("second", repository.get(session(second, "second-token"))!!.id)
            assertEquals("Bearer first-token", first.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer second-token", second.takeRequest().getHeader("Authorization"))
        } }
    }

    @Test fun removalCanBeRetriedButServerErrorsPropagate() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultPushSubscriptionRepository(ApiClientFactory())
            for (code in listOf(200, 404)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
                repository.remove(session(server))
                assertEquals("DELETE", server.takeRequest().method)
            }
            server.enqueue(MockResponse().setResponseCode(503))
            try { repository.remove(session(server)); fail("Must retry later") }
            catch (error: HttpException) { assertEquals(503, error.code()) }
        }
    }

    @Test fun insecureEndpointIsRejectedBeforeSendingKeys() = runTest {
        MockWebServer().use { server ->
            try {
                DefaultPushSubscriptionRepository(ApiClientFactory()).register(session(server),
                    PushSubscriptionRequest("http://relay.example/push/id", "key", "auth", emptyMap()))
                fail("Insecure endpoint accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun standardModeIsExplicitAndNumericWireIdRemainsAString() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"id":123456789012345678901234567890,"endpoint":"https://relay.example/push/id","standard":true}"""))
            val result = DefaultPushSubscriptionRepository(ApiClientFactory()).register(session(server),
                PushSubscriptionRequest("https://relay.example/push/id", "key", "auth", emptyMap(), true))
            assertEquals("123456789012345678901234567890", result.id)
            assertEquals(true, result.standard)
            assertTrue(URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
                .contains("subscription[standard]=true"))
        }
    }
}
